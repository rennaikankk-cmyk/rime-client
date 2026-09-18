package dev.guizhou.util.elytra;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ElytraHookTest {
    private static final String ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String VECTOR = "Lnet/minecraft/world/phys/Vec3;";
    private static final String TRAVEL = "travelFallFlying(" + VECTOR + ")V";
    private static final String UPDATE_DESCRIPTOR = "(" + VECTOR + ")" + VECTOR;
    private static final String TARGET = "L" + ENTITY + ";updateFallFlyingMovement" + UPDATE_DESCRIPTOR;
    private static final String MODIFY_EXPRESSION =
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";

    private ElytraHookTest() {
    }

    public static void main(String[] args) throws IOException {
        verifyGlideCallOrder();
        verifyCompiledMixin();
        verifySteeringDelegation();
        verifyRocketAttachmentAccessor();
        verifyChaseHandoff();
        System.out.println("Elytra hook checks passed: glide phase, mixin target, steering, rocket attachment and chase handoff.");
    }

    private static void verifyGlideCallOrder() throws IOException {
        List<Invocation> calls = new ArrayList<>();
        int[] methods = {0};
        read(ENTITY).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!(name + descriptor).equals(TRAVEL)) return null;
                methods[0]++;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        calls.add(new Invocation(owner, name, descriptor));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        check(methods[0] == 1, "Expected exactly one named LivingEntity." + TRAVEL);
        int update = singleIndex(calls, "updateFallFlyingMovement", UPDATE_DESCRIPTOR);
        int assign = singleIndex(calls, "setDeltaMovement", "(" + VECTOR + ")V");
        int move = singleIndex(calls, "move", "(Lnet/minecraft/world/entity/MoverType;" + VECTOR + ")V");
        check(calls.get(update).owner().equals(ENTITY), "Glide invocation owner changed");
        check(update + 1 == assign, "Glide result must be assigned immediately after calculation");
        check(assign + 2 == move, "Movement must follow assignment and its velocity getter");
        check(calls.get(assign + 1).name().equals("getDeltaMovement")
                        && calls.get(assign + 1).descriptor().equals("()" + VECTOR),
                "Movement no longer reads the corrected delta movement");
    }

    private static void verifyCompiledMixin() throws IOException {
        List<String> methods = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        List<String> points = new ArrayList<>();
        int[] require = {-1};
        int[] annotations = {0};
        read("dev/guizhou/mixin/entity/MixinLivingEntityTravel").accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("guizhouware$flightVelocity")) return null;
                check(descriptor.equals(UPDATE_DESCRIPTOR), "Flight modifier signature changed");
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!descriptor.equals(MODIFY_EXPRESSION)) return null;
                        annotations[0]++;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String name, Object value) {
                                if (name.equals("require")) require[0] = (Integer) value;
                            }

                            @Override
                            public AnnotationVisitor visitArray(String name) {
                                if (name.equals("method")) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visit(String ignored, Object value) {
                                            methods.add((String) value);
                                        }
                                    };
                                }
                                if (name.equals("at")) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
                                            return descriptor.equals(AT) ? atVisitor(targets, points) : null;
                                        }
                                    };
                                }
                                return null;
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                return name.equals("at") && descriptor.equals(AT)
                                        ? atVisitor(targets, points) : null;
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        check(annotations[0] == 1, "Expected exactly one flight ModifyExpressionValue annotation");
        check(methods.equals(List.of(TRAVEL)), "Flight modifier method target changed: " + methods);
        check(targets.equals(List.of(TARGET)), "Flight modifier invocation target changed: " + targets);
        check(points.equals(List.of("INVOKE")), "Flight modifier must replace the glide invocation result");
        check(require[0] == 1, "Missing flight hook must fail mixin application");
    }

    private static AnnotationVisitor atVisitor(List<String> targets, List<String> points) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (name.equals("target")) targets.add((String) value);
                if (name.equals("value")) points.add((String) value);
            }
        };
    }

    private static void verifySteeringDelegation() throws IOException {
        String module = "dev/guizhou/features/modules/movement/GuangxiElytraModule";
        String request = "dev/guizhou/manager/RotationRequest";
        List<String> steeringCallers = new ArrayList<>();
        boolean[] constructsRequest = {false};
        boolean[] submitsRequest = {false};
        read(module).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        check(!(owner.startsWith("net/minecraft/") && descriptor.equals("(F)V")
                                        && (name.equals("setYRot") || name.equals("setXRot"))),
                                "Flight steering must not overwrite camera rotation in " + method);
                        if (owner.equals(module) && name.equals("submitFlightRotation")
                                && descriptor.equals("(FF)V")) steeringCallers.add(method);
                        if (!method.equals("submitFlightRotation")) return;
                        if (owner.equals(request) && name.equals("<init>")) constructsRequest[0] = true;
                        if (owner.equals("dev/guizhou/manager/RotationManager") && name.equals("submit")
                                && descriptor.equals("(L" + request + ";)V")) submitsRequest[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(constructsRequest[0] && submitsRequest[0], "Steering must submit a RotationRequest");
        check(steeringCallers.containsAll(List.of("alignLook", "idleHover", "tickGroundHop")),
                "Flight, hover and ground hop must all delegate steering: " + steeringCallers);
    }

    private static void verifyRocketAttachmentAccessor() throws IOException {
        String fieldName = "DATA_ATTACHED_TO_TARGET";
        String dataAccessor = "Lnet/minecraft/network/syncher/EntityDataAccessor;";
        int[] fields = {0};
        read("net/minecraft/world/entity/projectile/FireworkRocketEntity").accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor,
                                                   String signature, Object value) {
                        if (!name.equals(fieldName)) return null;
                        fields[0]++;
                        check((access & Opcodes.ACC_STATIC) != 0, "Rocket attachment field must be static");
                        check(descriptor.equals(dataAccessor), "Rocket attachment field type changed");
                        check(signature != null && signature.contains("<Ljava/util/OptionalInt;>"),
                                "Rocket attachment data must contain an optional entity ID");
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(fields[0] == 1, "Missing unique named rocket attachment field");

        List<String> targets = new ArrayList<>();
        read("dev/guizhou/mixin/entity/FireworkRocketEntityAccessor").accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("guizhouware$getAttachedTargetData")) return null;
                check((access & Opcodes.ACC_STATIC) != 0, "Rocket attachment accessor must be static");
                check(descriptor.equals("()" + dataAccessor), "Rocket attachment accessor return type changed");
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!descriptor.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String name, Object value) {
                                if (name.equals("value")) targets.add((String) value);
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(targets.equals(List.of(fieldName)), "Rocket attachment accessor target changed: " + targets);
    }

    private static int singleIndex(List<Invocation> calls, String name, String descriptor) {
        int index = -1;
        for (int i = 0; i < calls.size(); i++) {
            Invocation call = calls.get(i);
            if (!call.name().equals(name) || !call.descriptor().equals(descriptor)) continue;
            check(index == -1, "Duplicate invocation: " + name + descriptor);
            index = i;
        }
        check(index != -1, "Missing invocation: " + name + descriptor);
        return index;
    }

    private static void verifyChaseHandoff() throws IOException {
        String flight = "dev/guizhou/features/modules/movement/GuangxiElytraModule";
        FlowTrace landing = trace(flight, "onPreTick");
        int wants = landing.callIndex("chaseWantsFlight");
        FlowStep guard = landing.steps.get(wants + 1);
        int disable = landing.callIndex("disable");
        check(guard.opcode() == Opcodes.IFNE && landing.labels.get(guard.target()) > disable
                        && wants < disable,
                "Active chase takeoff must bypass landing auto-disable");

        FlowTrace hop = trace(flight, "tickGroundHop");
        int hopWants = hop.callIndex("chaseWantsFlight");
        int active = hop.callIndex("chaseActive");
        FlowStep wantsBranch = hop.steps.get(hopWants + 1);
        FlowStep activeBranch = hop.steps.get(active + 1);
        int falseReturn = hop.labels.get(wantsBranch.target());
        check(wantsBranch.opcode() == Opcodes.IFNE && hopWants < active,
                "A grounded chase takeoff request must suppress ground hop");
        check(activeBranch.opcode() == Opcodes.IFEQ && active + 2 == falseReturn
                        && hop.labels.get(activeBranch.target()) > falseReturn + 1,
                "An actual chase heading must suppress ground hop even without auto-control");
        check(hop.steps.get(falseReturn).opcode() == Opcodes.ICONST_0
                        && hop.steps.get(falseReturn + 1).opcode() == Opcodes.IRETURN,
                "Chase handoff must return false so normal flight steering continues");
    }

    private static FlowTrace trace(String owner, String method) throws IOException {
        FlowTrace trace = new FlowTrace();
        read(owner).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals(method)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        trace.steps.add(new FlowStep(opcode, name, null));
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        trace.steps.add(new FlowStep(opcode, null, label));
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        trace.steps.add(new FlowStep(opcode, null, null));
                    }

                    @Override
                    public void visitLabel(Label label) {
                        trace.labels.put(label, trace.steps.size());
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return trace;
    }

    private static final class FlowTrace {
        final List<FlowStep> steps = new ArrayList<>();
        final Map<Label, Integer> labels = new IdentityHashMap<>();

        int callIndex(String name) {
            for (int i = 0; i < steps.size(); i++) {
                if (name.equals(steps.get(i).call())) return i;
            }
            throw new AssertionError("Missing control-flow call: " + name);
        }
    }

    private record FlowStep(int opcode, String call, Label target) {
    }

    private static ClassReader read(String internalName) throws IOException {
        try (InputStream input = ElytraHookTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            check(input != null, "Missing named class resource: " + internalName);
            return new ClassReader(input);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Invocation(String owner, String name, String descriptor) {
    }
}
