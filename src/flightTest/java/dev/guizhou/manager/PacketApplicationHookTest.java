package dev.guizhou.manager;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class PacketApplicationHookTest {
    private static final String PACKET = "Lnet/minecraft/network/protocol/game/ClientboundBlockUpdatePacket;";
    private static final String CORRECTION = "Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;";
    private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String CLIENT = "net/minecraft/client/Minecraft";
    private static final String MANAGER = "dev/guizhou/manager/PlacementManager";
    private static final String MIXIN = "dev/guizhou/mixin/network/MixinClientPlayNetworkHandler";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";

    public static void main(String[] args) throws IOException {
        verifyVanillaApplicationOrder();
        verifyBlockUpdateMixin();
        verifyManagerCallback();
        verifyCorrectionHook();
        System.out.println("Packet application hook checks passed: world update, TAIL delegation and thread guards.");
    }

    private static void verifyVanillaApplicationOrder() throws IOException {
        Code code = method("net/minecraft/client/multiplayer/ClientPacketListener", "handleBlockUpdate", "(" + PACKET + ")V");
        int thread = code.call("net/minecraft/network/protocol/PacketUtils", "ensureRunningOnSameThread",
                "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V");
        int update = code.call("net/minecraft/client/multiplayer/ClientLevel", "setServerVerifiedBlockState",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V");
        List<Integer> returns = code.indices("return");
        check(returns.size() == 1, "Block update handler now has multiple returns; recheck TAIL applicability");
        check(thread < update && update < returns.getFirst(), "World update must follow the thread handoff and precede RETURN");
        check(code.indices("jump").isEmpty(), "Block update now branches; verify the update dominates the hooked return");
    }

    private static void verifyBlockUpdateMixin() throws IOException {
        Code code = method(MIXIN, "onBlockUpdateApplied", "(" + PACKET + CALLBACK + ")V");
        check(code.injects == 1, "Expected one block update injection");
        check(code.targets.equals(List.of("handleBlockUpdate")), "Block update injection target changed: " + code.targets);
        check(code.points.equals(List.of("TAIL")), "Reactive placement must run at TAIL, after vanilla changes the world");
        check(code.require == 1, "Missing block update hook must fail mixin application");
        int delegate = code.call(MANAGER, "onBlockUpdateApplied", "(" + PACKET + ")V");
        verifyThreadGuard(code, delegate);
    }

    private static void verifyManagerCallback() throws IOException {
        Code code = method(MANAGER, "onBlockUpdateApplied", "(" + PACKET + ")V");
        check((code.access & Opcodes.ACC_PUBLIC) != 0, "Applied block update callback must be public");
        int listener = code.call(MANAGER + "$PlacementListener", "onBlockUpdate", "(Lnet/minecraft/core/BlockPos;Z)V");
        int flush = code.call(MANAGER, "flushQueue", "()V");
        check(listener < flush, "Reactive listeners must inspect the applied update before the placement queue flushes");
        verifyThreadGuard(code, listener);
        for (Step step : code.steps) {
            check(!(step.kind.equals("call") && step.name.equals("execute")),
                    "Applied update callback must not enqueue another deferred world scan");
        }
        read(MANAGER).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                check(!name.equals("onPacketReceive"), "PlacementManager still has its pre-application packet callback");
                if (!descriptor.contains("PacketEvent$Receive;")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        check(!descriptor.equals("Ldev/guizhou/event/system/Subscribe;"),
                                "PlacementManager must not subscribe to raw receive packets");
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static void verifyCorrectionHook() throws IOException {
        method("net/minecraft/client/multiplayer/ClientPacketListener", "handleMovePlayer", "(" + CORRECTION + ")V");
        Code code = method(MIXIN, "onHandleMovePlayerPre", "(" + CORRECTION + CALLBACK + ")V");
        check(code.injects == 1 && code.targets.equals(List.of("handleMovePlayer")), "Correction hook target changed");
        check(code.points.equals(List.of("HEAD")), "Correction delta must be measured before vanilla applies the position");
        int correction = code.call("dev/guizhou/manager/TPSCounterService", "onPlayerCorrection", "(" + CORRECTION + ")D");
        verifyThreadGuard(code, correction);
    }

    private static void verifyThreadGuard(Code code, int protectedCall) {
        int guard = code.call(CLIENT, "isSameThread", "()Z");
        check(guard < protectedCall, "Main-thread check must precede world-dependent callbacks");
        check(guard + 1 < code.steps.size(), "Missing branch after thread check");
        Step branch = code.steps.get(guard + 1);
        check(branch.kind.equals("jump") && (branch.opcode == Opcodes.IFEQ || branch.opcode == Opcodes.IFNE),
                "Thread check must immediately guard an early return");
        int falsePath = branch.opcode == Opcodes.IFEQ ? code.labels.get(branch.target) : guard + 2;
        while (falsePath < code.steps.size() && code.steps.get(falsePath).kind.equals("label")) falsePath++;
        check(falsePath < code.steps.size() && code.steps.get(falsePath).kind.equals("return"),
                "A non-client thread must return before touching the world or notifying listeners");
    }

    private static Code method(String owner, String method, String descriptor) throws IOException {
        Code code = new Code();
        int[] found = {0};
        read(owner).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(method) || !desc.equals(descriptor)) return null;
                found[0]++;
                code.access = access;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        code.steps.add(new Step("call", opcode, owner, name, descriptor, null));
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        code.steps.add(new Step("jump", opcode, null, null, null, label));
                    }

                    @Override
                    public void visitLabel(Label label) {
                        code.labels.put(label, code.steps.size());
                        code.steps.add(new Step("label", -1, null, null, null, label));
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) code.steps.add(new Step("return", opcode, null, null, null, null));
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!descriptor.equals(INJECT)) return null;
                        code.injects++;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String name, Object value) {
                                if (name.equals("require")) code.require = (Integer) value;
                            }

                            @Override
                            public AnnotationVisitor visitArray(String name) {
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        if (name.equals("method")) code.targets.add((String) value);
                                    }

                                    @Override
                                    public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
                                        return name.equals("at") && descriptor.equals(AT) ? at(code) : null;
                                    }
                                };
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                return name.equals("at") && descriptor.equals(AT) ? at(code) : null;
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(found[0] == 1, "Expected one named method: " + owner + "." + method + descriptor);
        return code;
    }

    private static AnnotationVisitor at(Code code) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (name.equals("value")) code.points.add((String) value);
            }
        };
    }

    private static ClassReader read(String owner) throws IOException {
        try (InputStream input = PacketApplicationHookTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            check(input != null, "Missing named class: " + owner);
            return new ClassReader(input);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Step(String kind, int opcode, String owner, String name, String descriptor, Label target) {}

    private static final class Code {
        private final List<Step> steps = new ArrayList<>();
        private final Map<Label, Integer> labels = new IdentityHashMap<>();
        private final List<String> targets = new ArrayList<>();
        private final List<String> points = new ArrayList<>();
        private int access;
        private int injects;
        private int require = -1;

        private List<Integer> indices(String kind) {
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).kind.equals(kind)) indices.add(i);
            return indices;
        }

        private int call(String owner, String name, String descriptor) {
            int found = -1;
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (!step.kind.equals("call") || !owner.equals(step.owner)
                        || !name.equals(step.name) || !descriptor.equals(step.descriptor)) continue;
                check(found == -1, "Ambiguous invocation: " + owner + "." + name + descriptor);
                found = i;
            }
            check(found >= 0, "Missing invocation: " + owner + "." + name + descriptor);
            return found;
        }
    }
}
