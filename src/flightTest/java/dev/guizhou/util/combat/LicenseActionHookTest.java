package dev.guizhou.util.combat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks denied/allowed control-flow paths without initializing Minecraft or loading real credentials. */
public final class LicenseActionHookTest {
    private static final String BASE = "dev/guizhou/features/modules/";
    private static final String CRYSTAL = BASE + "combat/GuangxiCrystalModule";
    private static final String MINE = BASE + "world/GuangxiMineModule";
    private static final String AUTO = BASE + "combat/AutoMineModule";
    private static final String HEARTBEAT = "dev/guizhou/manager/HeartbeatManager";
    private static int checks;

    public static void main(String[] args) throws IOException {
        crystalActions();
        miningActionsAndCleanup();
        autoMineCallbacks();
        System.out.println("LicenseActionHookTest passed: " + checks + " authorization/action/cleanup checks");
    }

    private static void crystalActions() throws IOException {
        guarded(CRYSTAL, "onCrystalSpawn", null, "sendCrystalAttack", "sendGhostAttacksAfter");
        guarded(CRYSTAL, "onBlockUpdate", null, "schedule");
        guarded(CRYSTAL, "lambda$onBlockUpdate$", null, "scanBlockUpdate");
        guarded(CRYSTAL, "scanBlockUpdate", null, "findBestCrystal", "findBestPlace", "breakCrystal", "doPlace");
        guarded(CRYSTAL, "preplaceCrystal", null, "doPlace");
        guarded(CRYSTAL, "breakCrystal", null, "submit", "sendCrystalAttack");
        guarded(CRYSTAL, "sendCrystalAttack", null, "send", "attack", "markSent");
        guarded(CRYSTAL, "sendGhostAttack", null, "send");
        guarded(CRYSTAL, "doPlace", ";Z)Z", "send", "placeCrystalShared", "recordPlace");
        guarded(CRYSTAL, "placeCrystalShared", "$CrystalOptions;", "placeCrystalDirect", "placeCrystal");
        Code delegate = method(CRYSTAL, "placeCrystalShared", ";Z)Z");
        check(delegate.calls("placeCrystalShared").size() == 1,
                "Two-argument shared placement must delegate to the guarded placement implementation");
    }

    private static void miningActionsAndCleanup() throws IOException {
        // This cancellation is a user input boundary: denying the module must leave normal mining untouched.
        guarded(MINE, "onAttackBlock", null, "cancel", "startMine");
        guarded(MINE, "requestBreak", null, "startMine");
        guarded(MINE, "requestBreakOnce", null, "requestBreak");
        Code singleCleanup = method(MINE, "cancelBreakOnce", null);
        check(!singleCleanup.hasAuthorization() && !singleCleanup.calls("clearMine").isEmpty()
                && !singleCleanup.calls("abortBreak").isEmpty(), "Owned power mining cleanup must survive revocation");
        guarded(MINE, "startMine", null, "demote", "begin");
        guarded(MINE, "begin", null, "rotateFor", "sendAction");
        guarded(MINE, "stopBreak", "(IZ)Z", "rotateFor", "withSwap", "latch");
        guarded(MINE, "fireFinish", null, "onMineFinish");
        guarded(MINE, "fireSubmitted", null, "onMineFinish");

        Code action = method(MINE, "sendAction", null);
        check(action.hasAuthorization(), "Mining packets need a final authorization boundary");
        for (String startOrStop : List.of("START_DESTROY_BLOCK", "STOP_DESTROY_BLOCK")) {
            for (int send : action.calls("send")) {
                check(!action.reachable(send, false, startOrStop), "Denied " + startOrStop + " reached the packet sender");
                check(action.reachable(send, true, startOrStop), "Authorized " + startOrStop + " was disabled");
            }
        }
        check(!action.calls("send").isEmpty(), "Mining action sender must still exist");
        for (int send : action.calls("send")) {
            check(action.reachable(send, false, "ABORT_DESTROY_BLOCK"), "Revocation must not block ABORT cleanup");
        }

        Code tick = guarded(MINE, "onTick", null, "tickSecondary", "begin", "stopBreak");
        for (int cleanup : tick.calls("stopUnauthorizedMining"))
            check(tick.reachable(cleanup, false, null), "Denied mining must reach cleanup instead of stranding a held tool");
        check(!tick.calls("stopUnauthorizedMining").isEmpty(), "Denied tick must explicitly clean up active work");
        Code cleanup = method(MINE, "stopUnauthorizedMining", null);
        check(!cleanup.hasAuthorization(), "Cleanup must not require an active license");
        for (String operation : List.of("abortBreak", "clearSecondary", "clearMine")) {
            check(!cleanup.calls(operation).isEmpty(), "Missing denied cleanup " + operation);
            for (int call : cleanup.calls(operation)) check(cleanup.reachable(call, false, null), "Cleanup unreachable: " + operation);
        }
        for (String clear : List.of("clearSecondary", "clearMine")) {
            Code slots = method(MINE, clear, null);
            check(!slots.hasAuthorization() && !slots.calls("release").isEmpty(), "Tool restoration must remain ungated: " + clear);
        }
        check(!method(MINE, "onDisable", null).hasAuthorization(), "Disabling the miner must always remain able to clean up");
    }

    private static void autoMineCallbacks() throws IOException {
        Code tick = guarded(AUTO, "onPreTick", null, "update");
        check(tick.calls("resetGlass").stream().anyMatch(call -> tick.reachable(call, false, null)),
                "Denied AutoMine must release its rebreak hold");
        guarded(AUTO, "onMineFinish", null, "preplaceCrystal");
        guarded(AUTO, "onPacket", null, "execute");
        guarded(AUTO, "lambda$onPacket$", null, "update");
        guarded(AUTO, "placeGlass", null, "placeDirect");
        check(!method(AUTO, "resetGlass", null).hasAuthorization(), "Rebreak-hold cleanup must remain ungated");
    }

    private static Code guarded(String owner, String name, String descriptor, String... operations) throws IOException {
        Code code = method(owner, name, descriptor);
        check(code.hasAuthorization(), owner + "." + name + " has no direct authorization check");
        for (String operation : operations) {
            List<Integer> calls = code.calls(operation);
            check(!calls.isEmpty(), name + " no longer exercises expected action " + operation);
            for (int call : calls) {
                check(!code.reachable(call, false, null), "Denied path reaches " + name + " -> " + operation);
                check(code.reachable(call, true, null), "Authorized path cannot reach " + name + " -> " + operation);
            }
        }
        return code;
    }

    private static Code method(String owner, String wanted, String descriptor) throws IOException {
        try (InputStream input = LicenseActionHookTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            if (input == null) throw new AssertionError("Missing compiled " + owner);
            ClassNode klass = new ClassNode();
            new ClassReader(input).accept(klass, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var found = klass.methods.stream().filter(method -> (wanted.endsWith("$")
                    ? method.name.startsWith(wanted) : method.name.equals(wanted))
                    && (descriptor == null || method.desc.contains(descriptor))).toList();
            check(found.size() == 1, "Expected one method: " + owner + "." + wanted + ", got " + found.size());
            return new Code(found.getFirst());
        }
    }

    private record Code(MethodNode method) {
        boolean hasAuthorization() {
            for (var instruction : method.instructions) {
                if (isAuthorization(instruction)) return true;
            }
            return false;
        }
        List<Integer> calls(String name) {
            var result = new java.util.ArrayList<Integer>();
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals(name))
                    result.add(method.instructions.indexOf(instruction));
            }
            return result;
        }
        boolean reachable(int goal, boolean authorized, String action) {
            var todo = new ArrayDeque<Integer>();
            Set<Integer> seen = new HashSet<>();
            todo.add(0);
            while (!todo.isEmpty()) {
                int index = todo.removeFirst();
                if (index < 0 || index >= method.instructions.size() || !seen.add(index)) continue;
                if (index == goal) return true;
                AbstractInsnNode instruction = method.instructions.get(index);
                int opcode = instruction.getOpcode();
                if (instruction instanceof JumpInsnNode jump) {
                    int target = method.instructions.indexOf(jump.label);
                    AbstractInsnNode previous = previousReal(instruction);
                    if (isAuthorization(previous)) {
                        check(opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE, "Authorization result must gate a boolean branch");
                        boolean taken = opcode == Opcodes.IFEQ ? !authorized : authorized;
                        todo.add(taken ? target : index + 1);
                        continue;
                    }
                    if (action != null && previous instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                            && field.owner.equals("net/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action")) {
                        check(opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE, "Mining action must compare enum identity");
                        boolean equal = action.equals(field.name);
                        boolean taken = opcode == Opcodes.IF_ACMPEQ ? equal : !equal;
                        todo.add(taken ? target : index + 1);
                        continue;
                    }
                    todo.add(target);
                    if (opcode == Opcodes.GOTO) continue;
                } else if (instruction instanceof TableSwitchInsnNode table) {
                    todo.add(method.instructions.indexOf(table.dflt));
                    for (var label : table.labels) todo.add(method.instructions.indexOf(label));
                    continue;
                } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                    todo.add(method.instructions.indexOf(lookup.dflt));
                    for (var label : lookup.labels) todo.add(method.instructions.indexOf(label));
                    continue;
                }
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) continue;
                todo.add(index + 1);
            }
            return false;
        }
        private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
            var previous = instruction.getPrevious();
            while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
            return previous;
        }
        private static boolean isAuthorization(AbstractInsnNode instruction) {
            return instruction instanceof MethodInsnNode call && call.owner.equals(HEARTBEAT)
                    && call.name.equals("authorized") && call.desc.equals("()Z");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
