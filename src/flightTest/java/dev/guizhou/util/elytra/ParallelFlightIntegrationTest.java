package dev.guizhou.util.elytra;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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
        immutable(UTIL + "FlightCollisionSnapshot");
        immutable(UTIL + "FlightRecoveryPlan");
        pure(UTIL + "AerialDuelPredictor");
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
        Set<String> flight = calls(FLIGHT);
        check(flight.contains(UTIL + "FlightPlanningTask.submit"), "Recovery submits background trajectories");
        check(flight.contains(UTIL + "AsyncFlightRoute.routeTo"), "Standalone flight uses asynchronous routing");
        check(flight.contains(UTIL + "FlightTerrainSafety.trajectoryClear"), "Live trajectory safety remains active");
        check(flight.contains(UTIL + "FlightPlanningTask.invalidate"), "Flight can cancel stale work");
        System.out.println("ParallelFlightIntegrationTest passed: " + checks + " checks");
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
