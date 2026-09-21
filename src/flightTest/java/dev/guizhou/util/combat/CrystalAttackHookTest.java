package dev.guizhou.util.combat;

import dev.guizhou.event.Event;
import dev.guizhou.event.system.EventBus;
import dev.guizhou.event.system.Subscribe;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
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
import java.util.Set;

public final class CrystalAttackHookTest {
    private static final String MODULE = "dev/guizhou/features/modules/combat/GuangxiCrystalModule";

    public static void main(String[] args) throws IOException {
        Trace breaker = method("breakCrystal");
        int submit = breaker.first("call", "tryAction");
        int accept = breaker.first("call", "acceptPendingRotation");
        int finalRay = breaker.last("call", "canBreakCrystal");
        int send = breaker.first("call", "sendCrystalAttack");
        check(submit < accept && accept < breaker.first("field", "sentYaw")
                && accept < breaker.first("field", "sentPitch") && finalRay < send,
                "Break must validate accepted outbound rotation after the rotation request");
        check(!breaker.reachable(0, send, finalRay), "Final rotation check must dominate every normal break send");
        breaker.checkBooleanFalseReturn(finalRay, false);

        Trace reach = method("canBreakCrystal");
        int emptyRay = reach.first("call", "isEmpty");
        int wall = reach.first("field", "wallPlace");
        check(reach.first("call", "clip") < emptyRay && emptyRay < wall,
                "Wall setting must be considered only after ray intersection");
        reach.checkBooleanFalseReturn(emptyRay, true);

        Trace attack = method("sendCrystalAttack");
        int dispatch = attack.first("call", "attack");
        int cancelled = attack.first("call", "isCancelled");
        int mark = attack.first("call", "markSent");
        int cooldown = attack.first("call", "reset");
        check(dispatch < cancelled && cancelled < mark && mark < cooldown,
                "Only an accepted dispatch may advance attack deduplication and cooldown");
        check(!attack.reachable(0, mark, cancelled), "Cancellation check must dominate sent bookkeeping");
        attack.checkBooleanFalseReturn(cancelled, true);
        check(method("onCrystalSpawn").first("call", "sendCrystalAttack") >= 0,
                "Spawn attacks must share the normal send bookkeeping");

        Trace listener = method("onPacketSendDiag");
        check(listener.priority == Integer.MIN_VALUE, "Send observer must run after cancellable packet policies");
        verifyCancellationOrder();
        System.out.println("CrystalAttackHookTest: rotation, wall-ray and cancelled-send checks passed");
    }

    private static void verifyCancellationOrder() {
        for (boolean registerObserverFirst : new boolean[]{false, true}) {
            EventBus bus = new EventBus();
            Observer observer = new Observer();
            if (registerObserverFirst) bus.register(observer);
            bus.register(new Blocker());
            if (!registerObserverFirst) bus.register(observer);
            check(bus.post(new SendProbe(true)), "Probe should be cancelled");
            check(observer.observed == null, "A cancelled send must not reach the lowest-priority observer");
            SendProbe allowed = new SendProbe(false);
            check(!bus.post(allowed) && observer.observed == allowed, "Allowed send must reach the observer");
            allowed.cancel();
            check(observer.observed.isCancelled(), "Retained event must expose a subsequent cancellation");
        }
    }

    private static final class SendProbe extends Event {
        final boolean shouldCancel;
        SendProbe(boolean shouldCancel) { this.shouldCancel = shouldCancel; }
    }

    private static final class Blocker {
        @Subscribe
        private void onSend(SendProbe event) { if (event.shouldCancel) event.cancel(); }
    }

    private static final class Observer {
        SendProbe observed;
        @Subscribe(priority = Integer.MIN_VALUE)
        private void onSend(SendProbe event) { observed = event; }
    }

    private static Trace method(String wanted) throws IOException {
        Trace trace = new Trace();
        int[] count = {0};
        try (InputStream input = CrystalAttackHookTest.class.getClassLoader().getResourceAsStream(MODULE + ".class")) {
            if (input == null) throw new AssertionError("Missing compiled " + MODULE);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals(wanted)) return null;
                    count[0]++;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            trace.steps.add(new Step("call", name, opcode, null));
                        }
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            trace.steps.add(new Step("field", name, opcode, null));
                        }
                        @Override
                        public void visitJumpInsn(int opcode, Label label) {
                            trace.steps.add(new Step("jump", "", opcode, label));
                        }
                        @Override
                        public void visitLabel(Label label) { trace.labels.put(label, trace.steps.size()); }
                        @Override
                        public void visitInsn(int opcode) { trace.steps.add(new Step("insn", "", opcode, null)); }
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!descriptor.equals("Ldev/guizhou/event/system/Subscribe;")) return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String name, Object value) {
                                    if (name.equals("priority")) trace.priority = (Integer) value;
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        check(count[0] == 1, "Expected one method named " + wanted);
        return trace;
    }

    private record Step(String kind, String name, int opcode, Label target) {}

    private static final class Trace {
        final List<Step> steps = new ArrayList<>();
        final Map<Label, Integer> labels = new IdentityHashMap<>();
        int priority;

        int first(String kind, String name) {
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).kind.equals(kind) && steps.get(i).name.equals(name)) return i;
            }
            throw new AssertionError("Missing " + kind + " " + name);
        }

        int last(String kind, String name) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                if (steps.get(i).kind.equals(kind) && steps.get(i).name.equals(name)) return i;
            }
            throw new AssertionError("Missing " + kind + " " + name);
        }

        void checkBooleanFalseReturn(int predicate, boolean rejectedValue) {
            Step jump = steps.get(predicate + 1);
            check(jump.opcode == Opcodes.IFEQ || jump.opcode == Opcodes.IFNE, "Expected boolean branch after predicate");
            boolean branchTaken = jump.opcode == Opcodes.IFNE ? rejectedValue : !rejectedValue;
            int rejected = branchTaken ? labels.get(jump.target) : predicate + 2;
            check(steps.get(rejected).opcode == Opcodes.ICONST_0
                    && steps.get(rejected + 1).opcode == Opcodes.IRETURN,
                    "Rejected rotation or missing intersection must return false immediately");
        }

        boolean reachable(int start, int goal, int excluded) {
            ArrayDeque<Integer> todo = new ArrayDeque<>();
            Set<Integer> seen = new HashSet<>();
            todo.add(start);
            while (!todo.isEmpty()) {
                int index = todo.removeFirst();
                if (index == excluded || index >= steps.size() || !seen.add(index)) continue;
                if (index == goal) return true;
                Step step = steps.get(index);
                if (step.kind.equals("jump")) {
                    todo.add(labels.get(step.target));
                    if (step.opcode == Opcodes.GOTO) continue;
                }
                if (step.opcode >= Opcodes.IRETURN && step.opcode <= Opcodes.RETURN
                        || step.opcode == Opcodes.ATHROW) continue;
                todo.add(index + 1);
            }
            return false;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
