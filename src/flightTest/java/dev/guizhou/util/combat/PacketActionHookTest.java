package dev.guizhou.util.combat;

import dev.guizhou.event.Event;
import dev.guizhou.event.system.EventBus;
import dev.guizhou.event.system.Subscribe;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** Verifies actual action wiring without constructing Minecraft's client singleton. */
public final class PacketActionHookTest {
    private static final String PISTON = "dev/guizhou/features/modules/combat/GuangxiPistonModule";
    private static final String ROTATION = "dev/guizhou/manager/RotationManager";
    private static final String SEND = "dev/guizhou/util/PacketSendUtil";
    private static final String PLACEMENT = "dev/guizhou/manager/PlacementManager";

    public static void main(String[] args) throws IOException {
        pistonAttackGates();
        rotationAcceptance();
        placementAcceptance();
        packetDispatchAcceptance();
        swordGapLatchOwnership();
        retainedEventSeesLaterCancellation();
        System.out.println("PacketActionHookTest passed: piston safety/ray, accepted rotation, placement and cancellation");
    }

    private static void pistonAttackGates() throws IOException {
        Code code = method(PISTON, "breakCrystal", null);
        int safety = code.call(PISTON, "selfSafe");
        int rotate = code.call(ROTATION, "trySubmit");
        int ray = code.lastCall(PISTON, "canHitCrystal");
        int send = code.call(SEND, "send");
        int mark = code.call("dev/guizhou/util/combat/CrystalRetryTracker", "markSent");
        check(safety < rotate && rotate < ray && ray < send && send < mark,
                "Piston must check safety, submitted rotation and final ray before attack bookkeeping");
        for (int gate : new int[]{safety, rotate, ray, send}) {
            code.dominates(gate, mark, "A rejected piston action can reach sent bookkeeping");
            code.rejectedCannotReach(gate, false, mark);
        }
        code.dominates(code.call(ROTATION, "getServerYaw"), ray, "Final ray lost submitted yaw");
        code.dominates(code.call(ROTATION, "getServerPitch"), ray, "Final ray lost submitted pitch");
        Code safetyCode = method(PISTON, "selfSafe", null);
        check(safetyCode.call(PISTON, "calcDamage") < safetyCode.call("java/lang/Float", "isFinite"),
                "Self safety must evaluate actual current damage and reject nonfinite results");
        check(safetyCode.field("maxSelfDamage", Opcodes.GETFIELD) >= 0
                && safetyCode.field("reserve", Opcodes.GETFIELD) >= 0,
                "Piston lost maximum self-damage or reserved-health guard");
    }

    private static void rotationAcceptance() throws IOException {
        Code submit = method(ROTATION, "trySubmit", null);
        int arbitration = submit.call(ROTATION + "$SilentArbitration", "blocks");
        int send = submit.call(ROTATION, "performSilent");
        int accept = submit.call(ROTATION + "$SilentArbitration", "accepted");
        submit.dominates(arbitration, accept, "Silent arbitration no longer gates acceptance");
        submit.dominates(send, accept, "Acceptance can precede actual rotation submission");
        submit.rejectedCannotReach(arbitration, true, accept);
        submit.rejectedCannotReach(send, false, accept);

        Code silent = method(ROTATION, "performSilent", null);
        int dispatch = silent.call(SEND, "send");
        int yaw = silent.call(null, "getYRot");
        int pitch = silent.call(null, "getXRot");
        int setYaw = silent.call(ROTATION, "setServerYaw");
        int setPitch = silent.call(ROTATION, "setServerPitch");
        silent.dominates(dispatch, setYaw, "Server rotation changed without packet submission");
        silent.dominates(yaw, setYaw, "Server yaw must come from the final packet");
        silent.dominates(pitch, setPitch, "Server pitch must come from the final packet");
        check(dispatch < yaw && dispatch < pitch, "Packet angles must be read after event policies mutate the packet");
        silent.rejectedCannotReach(dispatch, false, setYaw);
        silent.rejectedCannotReach(dispatch, false, setPitch);
    }

    private static void placementAcceptance() throws IOException {
        Code place = method(PLACEMENT, "placeDirect", "Vec3;)Z");
        int swap = place.call("dev/guizhou/manager/SwapManager", "withSwap");
        int mark = place.call(PLACEMENT, "notePlacement");
        place.dominates(swap, mark, "Placement can be recorded without trying its swap");
        place.rejectedCannotReach(swap, false, mark);
        int callbackResult = place.opcode(Opcodes.BALOAD);
        place.dominates(callbackResult, mark, "Callback result does not gate placement bookkeeping");
        place.rejectedCannotReach(callbackResult, false, mark);

        Code callback = method(PLACEMENT, "lambda$placeDirect$", null);
        int send = callback.call(PLACEMENT, "sendPlace");
        check(callback.steps.get(send + 1).opcode == Opcodes.BASTORE,
                "Direct placement callback must preserve sendPlace's boolean result, not report unconditional success");
    }

    private static void packetDispatchAcceptance() throws IOException {
        Code send = method(SEND, "send", null);
        int thread = send.call(null, "isSameThread");
        int dispatch = send.call("net/minecraft/client/multiplayer/ClientPacketListener", "send");
        int cancelled = send.call("dev/guizhou/event/impl/network/PacketEvent$Send", "isCancelled");
        send.dominates(thread, dispatch, "Packet submission no longer requires client thread");
        send.rejectedCannotReach(thread, false, dispatch);
        check(dispatch < cancelled, "Cancellation must be checked after full synchronous event dispatch");
        for (int success : send.opcodes(Opcodes.ICONST_1)) {
            send.dominates(cancelled, success, "A send can return success without reading final cancellation");
        }
        Code observer = method(SEND + "$Observer", "onSend", null);
        check(observer.priority == Integer.MAX_VALUE, "PacketSend must retain the event before cancellable policies run");

        Code forward = method(SEND, "forward", null);
        int replacement = forward.call(SEND, "send");
        int stored = forward.field("replacementAccepted", Opcodes.PUTFIELD);
        check(forward.steps.get(replacement + 1).opcode == Opcodes.ISTORE,
                "Replacement acceptance must retain the nested send result");
        int resultLocal = forward.steps.get(replacement + 1).variable;
        Step supplied = forward.steps.get(stored - 1);
        check(supplied.opcode == Opcodes.ILOAD && supplied.variable == resultLocal,
                "A cancelled replacement must not mark the original packet accepted");
    }

    private static void retainedEventSeesLaterCancellation() {
        for (boolean observerFirst : new boolean[]{false, true}) {
            EventBus bus = new EventBus();
            Observer observer = new Observer();
            if (observerFirst) bus.register(observer);
            bus.register(new Canceller());
            if (!observerFirst) bus.register(observer);
            Probe rejected = new Probe(true);
            check(bus.post(rejected), "Cancellation probe must be rejected");
            check(observer.seen == rejected && observer.seen.isCancelled(),
                    "Retained high-priority event must expose a later cancellation");
            Probe accepted = new Probe(false);
            check(!bus.post(accepted) && observer.seen == accepted && !observer.seen.isCancelled(),
                    "Accepted send must not inherit another attempt's cancellation");
        }
    }

    private static void swordGapLatchOwnership() throws IOException {
        String module = "dev/guizhou/features/modules/combat/AutoSwordModule";
        int[] calls = {0};
        try (InputStream input = PacketActionHookTest.class.getClassLoader().getResourceAsStream(module + ".class")) {
            check(input != null, "Missing compiled SwordGap");
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("tickSwordGap") && !name.equals("releaseSwordGap")) {
                        return new MethodVisitor(Opcodes.ASM9) {};
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            if (!owner.equals("dev/guizhou/manager/SwapManager")
                                    || !Set.of("latch", "isLatched", "release").contains(name)) return;
                            check(descriptor.startsWith("(Ljava/lang/Object;"),
                                    "SwordGap must not query or release another module's latch after interruption");
                            calls[0]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        check(calls[0] == 3, "Recheck SwordGap ownership: expected acquire, inspect and release operations");
        Code release = method("dev/guizhou/manager/SwapManager", "release", "Ljava/lang/Object;");
        int owned = release.field("owner", Opcodes.GETFIELD);
        int globalRelease = release.call("dev/guizhou/manager/SwapManager", "release");
        release.dominates(owned, globalRelease, "Owner release lost its identity check");
        int compare = release.opcode(Opcodes.IF_ACMPNE);
        check(!release.reachable(release.labels.get(release.steps.get(compare).target), globalRelease, -1),
                "Mismatched SwordGap owner can release miner's tool latch");
    }

    private static final class Probe extends Event {
        final boolean reject;
        Probe(boolean reject) { this.reject = reject; }
    }
    private static final class Observer {
        Probe seen;
        @Subscribe(priority = Integer.MAX_VALUE) private void on(Probe event) { seen = event; }
    }
    private static final class Canceller {
        @Subscribe(priority = Integer.MIN_VALUE) private void on(Probe event) { if (event.reject) event.cancel(); }
    }

    private static Code method(String owner, String wanted, String descriptorContains) throws IOException {
        Code result = new Code();
        int[] matches = {0};
        try (InputStream input = PacketActionHookTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            check(input != null, "Missing compiled class " + owner);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    boolean lambda = wanted.endsWith("$");
                    if (!(lambda ? name.startsWith(wanted) : name.equals(wanted))
                            || descriptorContains != null && !descriptor.contains(descriptorContains)) return null;
                    matches[0]++;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                            result.steps.add(new Step(op, owner, name, -1, null));
                        }
                        @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
                            result.steps.add(new Step(op, owner, name, -1, null));
                        }
                        @Override public void visitVarInsn(int op, int variable) { result.steps.add(new Step(op, "", "", variable, null)); }
                        @Override public void visitJumpInsn(int op, Label label) { result.steps.add(new Step(op, "", "", -1, label)); }
                        @Override public void visitLabel(Label label) { result.labels.put(label, result.steps.size()); }
                        @Override public void visitInsn(int op) { result.steps.add(new Step(op, "", "", -1, null)); }
                        @Override public void visitIntInsn(int op, int operand) { result.steps.add(new Step(op, "", "", -1, null)); }
                        @Override public void visitTypeInsn(int op, String type) { result.steps.add(new Step(op, "", "", -1, null)); }
                        @Override public void visitLdcInsn(Object value) { result.steps.add(new Step(Opcodes.LDC, "", "", -1, null)); }
                        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... args) {
                            result.steps.add(new Step(Opcodes.INVOKEDYNAMIC, "", name, -1, null));
                        }
                        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!descriptor.equals("Ldev/guizhou/event/system/Subscribe;")) return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public void visit(String name, Object value) {
                                    if (name.equals("priority")) result.priority = (Integer) value;
                                }
                            };
                        }
                        @Override public void visitTableSwitchInsn(int min, int max, Label def, Label... targets) {
                            throw new AssertionError("Recheck regression CFG after switch added to " + owner + "." + wanted);
                        }
                        @Override public void visitLookupSwitchInsn(Label def, int[] keys, Label[] targets) {
                            throw new AssertionError("Recheck regression CFG after switch added to " + owner + "." + wanted);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        check(matches[0] == 1, "Expected one method " + owner + "." + wanted + ", found " + matches[0]);
        return result;
    }

    private record Step(int opcode, String owner, String name, int variable, Label target) {}

    private static final class Code {
        final List<Step> steps = new ArrayList<>();
        final Map<Label, Integer> labels = new IdentityHashMap<>();
        int priority;

        int call(String owner, String name) {
            for (int i = 0; i < steps.size(); i++) if (isCall(steps.get(i), owner, name)) return i;
            throw new AssertionError("Missing call " + owner + "." + name);
        }
        int lastCall(String owner, String name) {
            for (int i = steps.size() - 1; i >= 0; i--) if (isCall(steps.get(i), owner, name)) return i;
            throw new AssertionError("Missing call " + owner + "." + name);
        }
        boolean isCall(Step s, String owner, String name) {
            return s.opcode >= Opcodes.INVOKEVIRTUAL && s.opcode <= Opcodes.INVOKEINTERFACE
                    && s.name.equals(name) && (owner == null || s.owner.equals(owner));
        }
        int field(String name, int opcode) {
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).opcode == opcode && steps.get(i).name.equals(name)) return i;
            throw new AssertionError("Missing field " + name);
        }
        int opcode(int opcode) {
            List<Integer> found = opcodes(opcode);
            check(!found.isEmpty(), "Missing opcode " + opcode);
            return found.getFirst();
        }
        List<Integer> opcodes(int opcode) {
            List<Integer> found = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).opcode == opcode) found.add(i);
            return found;
        }
        void dominates(int gate, int operation, String message) { check(!reachable(0, operation, gate), message); }

        void rejectedCannotReach(int predicate, boolean rejectedValue, int operation) {
            int jumpIndex = predicate + 1;
            // javac stores boolean results before if(sent) / if(swapped).
            if (steps.get(jumpIndex).opcode == Opcodes.ISTORE) {
                int local = steps.get(jumpIndex).variable;
                jumpIndex++;
                check(steps.get(jumpIndex).opcode == Opcodes.ILOAD && steps.get(jumpIndex).variable == local,
                        "Stored predicate no longer immediately controls its guard");
                jumpIndex++;
            }
            Step jump = steps.get(jumpIndex);
            check(jump.opcode == Opcodes.IFEQ || jump.opcode == Opcodes.IFNE, "Missing boolean rejection branch");
            boolean taken = jump.opcode == Opcodes.IFNE ? rejectedValue : !rejectedValue;
            int rejected = taken ? labels.get(jump.target) : jumpIndex + 1;
            check(!reachable(rejected, operation, -1), "Rejected predicate can reach protected action");
        }

        boolean reachable(int start, int target, int excluded) {
            ArrayDeque<Integer> todo = new ArrayDeque<>();
            Set<Integer> seen = new HashSet<>();
            todo.add(start);
            while (!todo.isEmpty()) {
                int i = todo.removeFirst();
                if (i == excluded || i >= steps.size() || !seen.add(i)) continue;
                if (i == target) return true;
                Step step = steps.get(i);
                if (step.target != null) {
                    todo.add(labels.get(step.target));
                    if (step.opcode == Opcodes.GOTO) continue;
                }
                if (step.opcode >= Opcodes.IRETURN && step.opcode <= Opcodes.RETURN || step.opcode == Opcodes.ATHROW) continue;
                todo.add(i + 1);
            }
            return false;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
