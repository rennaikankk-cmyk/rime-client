package dev.guizhou.util.combat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Safety boundaries verified without bootstrapping Minecraft or opening a world. */
public final class CombatPlanningWiringTest {
    private static final String UTIL = "dev/guizhou/util/combat/";
    private static final String MODULE = "dev/guizhou/features/modules/combat/";
    private static int checks;

    public static void main(String[] args) throws IOException {
        for (String name : List.of("AnchorPlanner", "AnchorPlanner$Input", "AnchorPlanner$Candidate", "AnchorPlanner$Result",
                "CombatCollisionSnapshot", "CombatCollisionSnapshot$Box", "CombatantSnapshot",
                "CombatSnapshotValidation", "CombatPlanningTask", "CombatPlanningTask$Pending", "CombatPlanningTask$Completed"))
            checkPure(UTIL + name);
        Trace apply = method(MODULE + "AutoAnchorModule", "selectCandidate");
        int publish = apply.find("<init>", MODULE + "AutoAnchorModule$Candidate");
        // Structural checks complement the pure compatibility tests. The combined validity boolean
        // is stored in a local, so a control-flow-only graph cannot prove those short-circuit paths.
        for (String check : List.of("compatible", "compatibleTargets", "stillValid"))
            check(apply.find(check, null) < publish, "Snapshot " + check + " is evaluated before publishing a worker winner");
        for (String check : List.of("usableCandidate", "score", "selfDamage")) {
            int guard = apply.find(check, null);
            check(!apply.reachable(publish, guard), "Live " + check + " must dominate publication of a worker winner");
        }
        check(apply.find("findBest", null) >= 0, "Main-thread synchronous fallback remains available");
        check(method(UTIL + "CombatTerrainCapture", "capture").find("requireClientThread", null) >= 0,
                "Terrain capture enforces client-thread access");
        check(method(UTIL + "CombatTerrainCapture$Captured", "stillValid").find("requireClientThread", null) >= 0,
                "Terrain revalidation enforces client-thread access");
        check(method(UTIL + "CombatSnapshotService", "get").find("isSameThread", null) >= 0,
                "Equipment capture enforces client-thread access");
        for (String[] call : List.of(new String[]{"GuangxiCrystalModule", "collectTargets"},
                new String[]{"GuangxiCrystalModule", "ensureSelfCache"}, new String[]{"GuangxiPistonModule", "profileOf"})) {
            check(method(MODULE + call[0], call[1]).find("get", UTIL + "CombatSnapshotService") >= 0,
                    call[0] + " uses the shared immutable profile boundary");
        }
        check(method("dev/guizhou/util/AnchorDamageUtil$Target", "of").find("get", UTIL + "CombatSnapshotService") >= 0,
                "Anchor and bed share the same equipment cache");
        Trace updates = method(MODULE + "GuangxiCrystalModule", "onBlockUpdate");
        check(updates.find("tell", null) >= 0 && !updates.has("scanBlockUpdate", null),
                "Reactive block updates queue one drain instead of scanning in each callback");
        check(method(MODULE + "GuangxiCrystalModule", "onCrystalSpawn").find("sendCrystalAttack", null) >= 0,
                "Crystal spawn retains the immediate attack path");
        Trace anchorUpdates = method(MODULE + "AutoAnchorModule", "onBlockUpdate");
        check(anchorUpdates.find("tell", null) >= 0 && !anchorUpdates.has("runCycle", null),
                "Anchor updates coalesce through a scheduled main-thread drain");
        check(method(MODULE + "AutoAnchorModule", "perform").find("miningBlocked", null) >= 0,
                "Anchor final action rechecks mining reservations");
        System.out.println("CombatPlanningWiringTest passed: " + checks + " checks");
    }

    private static void checkPure(String name) throws IOException {
        read(name, new ClassVisitor(Opcodes.ASM9) {
            private void pure(String value) {
                if (value != null && value.contains("net/minecraft/")) throw new AssertionError(name + " leaks a Minecraft type: " + value);
            }
            @Override public FieldVisitor visitField(int access, String field, String descriptor, String signature, Object value) {
                pure(descriptor); pure(signature); return null;
            }
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                pure(descriptor); pure(signature);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitTypeInsn(int opcode, String type) { pure(type); }
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) { pure(owner); pure(desc); }
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) { pure(owner); pure(desc); }
                };
            }
        });
        checks++;
    }

    private static Trace method(String owner, String name) throws IOException {
        Trace trace = new Trace();
        read(owner, new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                if (!method.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String calledOwner, String calledName, String descriptor, boolean itf) {
                        trace.steps.add(new Step(opcode, calledName, calledOwner, null));
                    }
                    @Override public void visitJumpInsn(int opcode, Label label) { trace.steps.add(new Step(opcode, "", "", label)); }
                    @Override public void visitInsn(int opcode) { trace.steps.add(new Step(opcode, "", "", null)); }
                    @Override public void visitLabel(Label label) { trace.labels.put(label, trace.steps.size()); }
                };
            }
        });
        return trace;
    }
    private static void read(String name, ClassVisitor visitor) throws IOException {
        try (InputStream stream = CombatPlanningWiringTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) throw new AssertionError("Missing compiled " + name);
            new ClassReader(stream).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }
    private record Step(int opcode, String name, String owner, Label target) {}
    private static final class Trace {
        final List<Step> steps = new ArrayList<>();
        final Map<Label, Integer> labels = new IdentityHashMap<>();
        boolean has(String name, String owner) {
            return steps.stream().anyMatch(s -> s.name.equals(name) && (owner == null || s.owner.equals(owner)));
        }
        int find(String name, String owner) {
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).name.equals(name) && (owner == null || steps.get(i).owner.equals(owner))) return i;
            throw new AssertionError("Missing call " + owner + "." + name);
        }
        boolean reachable(int goal, int excluded) {
            var todo = new ArrayDeque<Integer>();
            var seen = new HashSet<Integer>(); todo.add(0);
            while (!todo.isEmpty()) {
                int index = todo.removeFirst();
                if (index == excluded || index >= steps.size() || !seen.add(index)) continue;
                if (index == goal) return true;
                Step step = steps.get(index);
                if (step.target != null) { todo.add(labels.get(step.target)); if (step.opcode == Opcodes.GOTO) continue; }
                if (step.opcode >= Opcodes.IRETURN && step.opcode <= Opcodes.RETURN || step.opcode == Opcodes.ATHROW) continue;
                todo.add(index + 1);
            }
            return false;
        }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
