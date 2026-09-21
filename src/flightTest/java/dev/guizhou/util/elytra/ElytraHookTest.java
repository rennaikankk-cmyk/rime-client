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
    private static final String TRAVEL = "travel(" + VECTOR + ")V";
    private static final String MOVE_DESCRIPTOR =
            "(Lnet/minecraft/world/entity/MoverType;" + VECTOR + ")V";
    private static final String MOVE_TARGET = "Lnet/minecraft/world/entity/LivingEntity;move" + MOVE_DESCRIPTOR;
    private static final String GLIDE_GATE = "L" + ENTITY + ";isFallFlying()Z";
    private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
    private static final String SLICE = "Lorg/spongepowered/asm/mixin/injection/Slice;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";

    private ElytraHookTest() {
    }

    public static void main(String[] args) throws IOException {
        verifyGlideCallOrder();
        verifyCompiledMixin();
        verifyIncomingVelocityDataflow();
        verifySteeringDelegation();
        verifyRocketAttachmentAccessor();
        verifyChaseHandoff();
        System.out.println("Elytra hook checks passed: inline glide branch, mixin target, steering, rocket attachment and chase handoff.");
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
        int gate = singleIndex(calls, "isFallFlying", "()Z");
        check(calls.get(gate).owner().equals(ENTITY), "Glide gate invocation owner changed");

        // 1.21.1: glide physics is inline. The glide branch ends with
        // setDeltaMovement(v) -> getDeltaMovement() -> move(SELF, v). ModifyArg
        // matches ordinal 0 in the slice, i.e. the FIRST move after the gate, so
        // that call must carry the glide shape — no uniqueness claim is made about
        // later move calls in the method.
        int gateMove = -1;
        int lastGet = -1;
        int lastSet = -1;
        for (int i = gate + 1; i < calls.size(); i++) {
            Invocation call = calls.get(i);
            if (call.name().equals("move") && call.descriptor().equals(MOVE_DESCRIPTOR)) {
                if (gateMove == -1) {
                    gateMove = i;
                    break;
                }
            }
            if (call.name().equals("setDeltaMovement") && call.descriptor().equals("(" + VECTOR + ")V")) {
                lastSet = i;
            }
            if (call.name().equals("getDeltaMovement") && call.descriptor().equals("()" + VECTOR)) {
                lastGet = i;
            }
        }
        check(gateMove != -1, "Glide branch move call missing after isFallFlying");
        check(calls.get(gateMove).owner().equals(ENTITY),
                "Vanilla glide move owner must be LivingEntity, was " + calls.get(gateMove).owner());
        check(MOVE_TARGET.equals("L" + calls.get(gateMove).owner() + ";move" + MOVE_DESCRIPTOR),
                "Annotation MOVE_TARGET owner must match the actual vanilla move owner");
        check(lastSet != -1 && lastSet < gateMove, "Glide velocity must be assigned before the slice move");
        check(lastGet == gateMove - 1, "The ordinal-0 slice move argument must be read from the assigned velocity");
    }


    /**
     * Dataflow checks over the compiled mixin (code included):
     *  1. travel HEAD captures getDeltaMovement() into guizhouware$incomingVelocity
     *     BEFORE any early return, so the glide modifier sees the pre-travel velocity.
     *  2. guizhouware$flightVelocity passes that saved field as the SECOND argument
     *     of applyFlightVelocity (after the vanilla glide velocity, before the look).
     *  3. The transformed result local is the same Vec3 written through
     *     setDeltaMovement and returned by the modifier.
     */
    private static void verifyIncomingVelocityDataflow() throws IOException {
        String mixin = "dev/guizhou/mixin/entity/MixinLivingEntityTravel";
        String incomingField = "guizhouware$incomingVelocity";

        List<int[]> headSequence = new ArrayList<>();  // opcode, kind
        List<String[]> headOps = new ArrayList<>();    // owner, name for invokes/fields
        readMethod(mixin, "guizhouware$travelHead", headSequence, headOps);
        int captureCall = -1;
        int captureStore = -1;
        int earlyReturn = -1;
        for (int i = 0; i < headSequence.size(); i++) {
            int opcode = headSequence.get(i)[0];
            String[] op = headOps.get(i);
            if (opcode == Opcodes.INVOKEVIRTUAL && "getDeltaMovement".equals(op[1])
                    && op[2].equals("()" + VECTOR)) {
                captureCall = i;
            }
            if (opcode == Opcodes.PUTFIELD && incomingField.equals(op[1])) {
                captureStore = i;
            }
            if (opcode == Opcodes.RETURN && earlyReturn == -1 && captureStore == -1) {
                earlyReturn = i;
            }
        }
        check(captureCall != -1 && captureStore == captureCall + 1,
                "travel HEAD must store getDeltaMovement() directly into " + incomingField);
        check(captureStore != -1 && (earlyReturn == -1 || captureStore < earlyReturn),
                "The incoming-velocity capture must precede every early return in travel HEAD");

        List<int[]> flightSequence = new ArrayList<>();
        List<String[]> flightOps = new ArrayList<>();
        readMethod(mixin, "guizhouware$flightVelocity", flightSequence, flightOps);
        int apply = -1;
        for (int i = 0; i < flightSequence.size(); i++) {
            if (flightSequence.get(i)[0] == Opcodes.INVOKEVIRTUAL
                    && "applyFlightVelocity".equals(flightOps.get(i)[1])) {
                apply = i;
                break;
            }
        }
        check(apply != -1, "Flight modifier must delegate through applyFlightVelocity");

        // Walk back over the three Vec3 producers feeding the call: nearest is the
        // look (arg3), then the saved incoming field (arg2), then the modifier
        // parameter (arg1).
        int vecsSeen = 0;
        int incomingArg = -1;
        for (int i = apply - 1; i >= 0 && vecsSeen < 3; i--) {
            int opcode = flightSequence.get(i)[0];
            String[] op = flightOps.get(i);
            if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                if (op[2].endsWith(VECTOR)) vecsSeen++;
                if (vecsSeen == 3) break;  // looked past arg1
                continue;
            }
            if (opcode == Opcodes.GETFIELD && op[2].equals(VECTOR)) {
                vecsSeen++;
                if (vecsSeen == 2) check(incomingField.equals(op[1]),
                        "applyFlightVelocity arg2 must read " + incomingField + ", was " + op[1]);
                incomingArg = i;
                continue;
            }
            if (opcode == Opcodes.ALOAD && flightSequence.get(i)[1] == 1) {
                // the modifier parameter may only be arg1 (outermost producer)
                vecsSeen++;
            }
        }
        check(incomingArg != -1, "Flight modifier must read " + incomingField + " for the second argument");

        // The call result local is both written back through setDeltaMovement and
        // returned unchanged.
        int callStore = -1;
        for (int i = apply + 1; i < flightSequence.size() && callStore == -1; i++) {
            if (flightSequence.get(i)[0] == Opcodes.ASTORE) {
                callStore = flightSequence.get(i)[1];
            }
        }
        check(callStore != -1, "applyFlightVelocity result must be stored to a local");
        boolean writesBack = false;
        for (int i = apply + 1; i + 1 < flightSequence.size(); i++) {
            if (flightSequence.get(i)[0] == Opcodes.ALOAD && flightSequence.get(i)[1] == callStore
                    && flightSequence.get(i + 1)[0] == Opcodes.INVOKEVIRTUAL
                    && "setDeltaMovement".equals(flightOps.get(i + 1)[1])) {
                writesBack = true;
            }
        }
        check(writesBack, "The transformed velocity must be written back via setDeltaMovement");
        boolean returnsSame = false;
        for (int i = apply + 1; i < flightSequence.size() - 1; i++) {
            if (flightSequence.get(i)[0] == Opcodes.ALOAD && flightSequence.get(i)[1] == callStore
                    && flightSequence.get(i + 1)[0] == Opcodes.ARETURN) {
                returnsSame = true;
            }
        }
        check(returnsSame, "The modifier must return the same transformed Vec3 instance");
    }

    /** Records per-instruction (opcode, extra) and (owner, name, descriptor) triples. */
    private static void readMethod(String owner, String methodName,
                                   List<int[]> sequence, List<String[]> ops) throws IOException {
        read(owner).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals(methodName)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        sequence.add(new int[]{opcode, var});
                        ops.add(new String[]{"", "", ""});
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        sequence.add(new int[]{opcode, -1});
                        ops.add(new String[]{owner, name, descriptor});
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        sequence.add(new int[]{opcode, -1});
                        ops.add(new String[]{owner, name, descriptor});
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        sequence.add(new int[]{opcode, -1});
                        ops.add(new String[]{"", "", ""});
                    }

                    @Override
                    public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        sequence.add(new int[]{opcode, -1});
                        ops.add(new String[]{"", "", ""});
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(!sequence.isEmpty(), "Missing method body: " + owner + "." + methodName);
    }

    private static void verifyCompiledMixin() throws IOException {
        List<String> methods = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        List<String> sliceFrom = new ArrayList<>();
        int[] index = {-1};
        int[] require = {-1};
        int[] annotations = {0};
        read("dev/guizhou/mixin/entity/MixinLivingEntityTravel").accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("guizhouware$flightVelocity")) return null;
                check(descriptor.equals("(" + VECTOR + ")" + VECTOR), "Flight modifier signature changed");
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!descriptor.equals(MODIFY_ARG)) return null;
                        annotations[0]++;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String name, Object value) {
                                if (name.equals("require")) require[0] = (Integer) value;
                                if (name.equals("index")) index[0] = (Integer) value;
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
                                            return descriptor.equals(AT)
                                                    ? atVisitor(targets, new ArrayList<String>()) : null;
                                        }
                                    };
                                }
                                return null;
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                if (name.equals("at") && descriptor.equals(AT)) {
                                    return atVisitor(targets, new ArrayList<String>());
                                }
                                if (name.equals("slice") && descriptor.equals(SLICE)) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public AnnotationVisitor visitAnnotation(String ignored, String inner) {
                                            if (!inner.equals(AT)) return null;
                                            return new AnnotationVisitor(Opcodes.ASM9) {
                                                @Override
                                                public void visit(String key, Object value) {
                                                    if (key.equals("target")) sliceFrom.add((String) value);
                                                }
                                            };
                                        }
                                    };
                                }
                                return null;
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        check(annotations[0] == 1, "Expected exactly one flight ModifyArg annotation");
        check(methods.equals(List.of("travel")), "Flight modifier method target changed: " + methods);
        check(targets.equals(List.of(MOVE_TARGET)), "Flight modifier invocation target changed: " + targets);
        check(sliceFrom.equals(List.of(GLIDE_GATE)),
                "Flight modifier must be sliced to the glide branch: " + sliceFrom);
        check(index[0] == 1, "Flight modifier must capture the movement vector argument");
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
