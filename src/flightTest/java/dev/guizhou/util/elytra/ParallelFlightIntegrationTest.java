package dev.guizhou.util.elytra;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** Bytecode checks prevent accidentally introducing live game access into worker inputs or math. */
public final class ParallelFlightIntegrationTest {
    private static final String UTIL = "dev/guizhou/util/elytra/";
    private static final String CHASE = "dev/guizhou/features/modules/combat/GuangxiElytraChaseModule";
    private static final String FLIGHT = "dev/guizhou/features/modules/movement/GuangxiElytraModule";
    private static int checks;

    public static void main(String[] args) throws IOException {
        immutable(CHASE + "$DuelRequest");
        immutable(UTIL + "AerialDuelPredictor$Input");
        immutable(UTIL + "AerialDuelPredictor$Projectile");
        immutable(UTIL + "TargetMotionTracker$Snapshot");
        immutable(UTIL + "FlightCollisionSnapshot");
        immutable(UTIL + "FlightRecoveryPlan");
        pure(UTIL + "AerialDuelPredictor");
        pure(UTIL + "TargetMotionTracker$Snapshot");
        pure(UTIL + "FlightTerrainSafety");
        pure(UTIL + "FlightRoutePlanner");
        pure(UTIL + "FlightRecoveryPlan");
        noBlocking(CHASE);
        noBlocking(FLIGHT);
        noBlocking(UTIL + "AsyncFlightRoute");
        Set<String> chase = calls(CHASE);
        check(chase.contains(UTIL + "FlightPlanningTask.submit"), "Chase submits background predictions");
        check(chase.contains(UTIL + "AsyncFlightRoute.routeTo"), "Chase uses asynchronous routing");
        check(chase.contains(UTIL + "FlightPlanningTask.invalidate"), "Chase can cancel stale work");
        targetMotionWiring();
        completionCounting();
        Set<String> flight = calls(FLIGHT);
        check(flight.contains(UTIL + "FlightPlanningTask.submit"), "Recovery submits background trajectories");
        check(flight.contains(UTIL + "AsyncFlightRoute.routeTo"), "Standalone flight uses asynchronous routing");
        check(flight.contains(UTIL + "FlightTerrainSafety.trajectoryClear"), "Live trajectory safety remains active");
        check(flight.contains(UTIL + "FlightPlanningTask.invalidate"), "Flight can cancel stale work");
        System.out.println("ParallelFlightIntegrationTest passed: " + checks + " checks");
    }

    private static void targetMotionWiring() throws IOException {
        Set<String> planner = new HashSet<>();
        Set<String> phase = new HashSet<>();
        Set<String> synchronous = new HashSet<>();
        Set<String> classify = new HashSet<>();
        read(CHASE).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                Set<String> into = switch (name) {
                    case "updateDuelPlanning" -> planner;
                    case "planningPhaseToken" -> phase;
                    case "predictedTarget" -> synchronous;
                    case "classifyTarget" -> classify;
                    default -> null;
                };
                if (into == null) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String called, String descriptor, boolean itf) {
                        into.add(owner + "." + called);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(planner.contains(CHASE + "$DuelRequest.targetMotionRevision")
                        && planner.contains(UTIL + "TargetMotionTracker$Snapshot.revision"),
                "Background acceptance must compare captured target motion revision with the current observation");
        check(phase.contains(UTIL + "TargetMotionTracker.revision"),
                "Target landing, equipment changes and discontinuities must invalidate route phase tokens");
        check(synchronous.contains(UTIL + "TargetMotionTracker$Snapshot.displacement"),
                "Synchronous fallback must use the same bounded observed model as the worker");
        check(!classify.contains(UTIL + "SpearApproach$CommitWindow.reset"),
                "Repeated target equipment changes must not renew the bounded spear commit window");
        check(planner.contains(CHASE + ".countPrediction")
                        && !planner.contains("dev/guizhou/util/diagnostics/ClientDiagnostics.count"),
                "Plan telemetry must use the completion identity guard instead of counting every poll");
    }

    private static void completionCounting() throws IOException {
        ClassNode klass = new ClassNode();
        read(CHASE).accept(klass, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var method = klass.methods.stream().filter(m -> m.name.equals("countPrediction")).findFirst().orElseThrow();
        JumpInsnNode identityGuard = null;
        int countIndex = -1;
        for (var instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IF_ACMPNE
                    && jump.getPrevious() instanceof FieldInsnNode field && field.name.equals("lastCountedPrediction")) {
                identityGuard = jump;
            }
            if (instruction instanceof MethodInsnNode call && call.owner.equals("dev/guizhou/util/diagnostics/ClientDiagnostics")
                    && call.name.equals("count")) countIndex = method.instructions.indexOf(call);
        }
        check(identityGuard != null && countIndex >= 0, "Completion counting needs an identity comparison before diagnostics");
        check(identityGuard.getNext().getOpcode() == Opcodes.RETURN,
                "Polling the same completion again must return without adding another accept/stale count");
        check(method.instructions.indexOf(identityGuard) < method.instructions.indexOf(identityGuard.label)
                        && method.instructions.indexOf(identityGuard.label) < countIndex,
                "Only a different completion may reach diagnostics counting");
    }

    private static void immutable(String name) throws IOException {
        read(name).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String field, String descriptor, String signature, Object value) {
                if ((access & Opcodes.ACC_STATIC) == 0) {
                    check((access & Opcodes.ACC_FINAL) != 0, name + "." + field + " must be final");
                    check(!liveType(descriptor), name + "." + field + " cannot retain a live game object");
                }
                return null;
            }
        }, ClassReader.SKIP_CODE);
    }

    private static void pure(String name) throws IOException {
        read(name).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean itf) {
                        check(!liveType(owner), name + "." + method + " must not call live game APIs: " + owner);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        check(!liveType(owner) && !liveType(desc), name + "." + method + " must not access live game state");
                    }
                };
            }
        }, 0);
    }

    private static void noBlocking(String name) throws IOException {
        for (String call : calls(name)) {
            check(!call.equals("java/lang/Thread.sleep") && !call.equals("java/lang/Thread.join")
                            && !call.equals("java/lang/Object.wait")
                            && !call.equals("java/util/concurrent/Future.get")
                            && !call.equals("java/util/concurrent/CompletableFuture.get")
                            && !call.equals("java/util/concurrent/CompletableFuture.join")
                            && !call.equals("java/util/concurrent/CountDownLatch.await"),
                    name + " must not wait for a background result: " + call);
        }
    }

    private static Set<String> calls(String name) throws IOException {
        Set<String> result = new HashSet<>();
        read(name).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean itf) {
                        result.add(owner + "." + called);
                    }
                };
            }
        }, 0);
        return result;
    }

    private static boolean liveType(String name) {
        return name.contains("net/minecraft/client/") || name.contains("net/minecraft/world/entity/")
                && !name.equals("Lnet/minecraft/world/entity/Pose;")
                || name.contains("net/minecraft/world/level/")
                || name.contains("net/minecraft/world/item/")
                || name.contains("dev/guizhou/GuizhouWare");
    }

    private static ClassReader read(String name) throws IOException {
        try (InputStream input = ParallelFlightIntegrationTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new IOException("Missing class: " + name);
            return new ClassReader(input);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
