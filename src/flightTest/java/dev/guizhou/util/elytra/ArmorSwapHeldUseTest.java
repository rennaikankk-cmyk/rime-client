package dev.guizhou.util.elytra;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Chest-only swaps must preserve a held spear, without widening other inventory permissions. */
public final class ArmorSwapHeldUseTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Map<String, List<String>> calls = new HashMap<>();
        Map<String, List<String>> fields = new HashMap<>();
        try (var in = ArmorSwapHeldUseTest.class.getClassLoader().getResourceAsStream(
                "dev/guizhou/util/elytra/ArmorSwapController.class")) {
            if (in == null) throw new AssertionError("Missing real armor controller");
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                          String signature, String[] exceptions) {
                    var methodCalls = calls.computeIfAbsent(name, key -> new ArrayList<>());
                    var methodFields = fields.computeIfAbsent(name, key -> new ArrayList<>());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                              String descriptor, boolean isInterface) {
                            methodCalls.add(name);
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String name,
                                                             String descriptor) {
                            methodFields.add(name);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        var safe = calls.get("safeInventory");
        check(safe.contains("canSwapWhileUsing") && !safe.contains("isUsingItem"),
                "Chest swapping must allow an intact charged spear instead of rejecting all item use");
        for (String guard : List.of("isSameThread", "sameSession", "getConnection", "isDeadOrDying",
                "isSpectator", "active", "selected", "getCarried", "isEmpty", "isLatched")) {
            check(safe.contains(guard), "Existing inventory guard must remain: " + guard);
        }
        for (String guard : List.of("screen", "gameMode", "armorSlot", "containerMenu", "inventoryMenu")) {
            check(fields.get("safeInventory").contains(guard), "Existing inventory field guard: " + guard);
        }
        var adapter = calls.get("canSwapWhileUsing");
        for (String read : List.of("isUsingItem", "getUseItem", "has", "getUsedItemHand", "getItemInHand",
                "preservesHeldUse")) {
            check(adapter.contains(read), "Use eligibility must read live hand/stack state: " + read);
        }
        check(fields.get("canSwapWhileUsing").contains("KINETIC_WEAPON"),
                "Only native kinetic weapons qualify for the held-use exception");
        check(calls.get("swapPair").containsAll(List.of("safeInventory", "ownsPair", "mayPickup", "mayPlace")),
                "Every chest click still requires the intact pair and slot permissions");
        check(calls.get("acquire").contains("selected"), "Acquisition must still exclude the held hotbar slot");

        // Invoke the real policy, using identity tokens so no game registry bootstrap is needed.
        var policy = ArmorSwapController.class.getDeclaredMethod("preservesHeldUse",
                boolean.class, boolean.class, Object.class, Object.class);
        policy.setAccessible(true);
        Object spear = new Object();
        Object replacement = new Object();
        for (boolean using : new boolean[]{false, true}) {
            for (boolean kinetic : new boolean[]{false, true}) {
                for (Object used : new Object[]{null, spear, replacement}) {
                    for (Object held : new Object[]{null, spear, replacement}) {
                        boolean allowed = (boolean) policy.invoke(null, using, kinetic, used, held);
                        boolean expected = !using || kinetic && used != null && used == held;
                        check(allowed == expected,
                                "Idle use is safe; charged use requires the exact held kinetic stack");
                    }
                }
            }
        }
        System.out.println("ArmorSwapHeldUseTest passed: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
