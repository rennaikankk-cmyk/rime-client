package dev.guizhou.util.elytra;

import com.google.gson.JsonParser;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ElytraWeaponControllerTest {
    private static final String GAME_MODE = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
    private static final String RELEASE_DESCRIPTOR = "(Lnet/minecraft/world/entity/player/Player;)V";
    private static int checks;

    public static void main(String[] args) throws IOException {
        hotbarSelection();
        ownershipRestoration();
        manualSelectionWins();
        manualWeaponMode();
        verifySpearUseHook();
        System.out.println("ElytraWeaponControllerTest passed: " + checks + " checks");
    }

    private static void hotbarSelection() {
        check(ElytraWeaponController.chooseHotbar(7, slot -> slot == 1 || slot == 7) == 7,
                "Keep a matching manually selected weapon");
        check(ElytraWeaponController.chooseHotbar(4, slot -> slot == 1 || slot == 7) == 1,
                "Use a deterministic hotbar fallback");
        check(ElytraWeaponController.chooseHotbar(4, slot -> slot == 9) == -1,
                "Never move items from the main inventory");
        check(ElytraWeaponController.chooseHotbar(-1, slot -> slot == 8) == 8,
                "An unknown selected slot still finds the final hotbar slot");
    }

    private static void ownershipRestoration() {
        var slots = new ElytraWeaponController.SlotOwnership();
        slots.select(2, 6);
        check(slots.observe(6), "An owned slot remains under control");
        slots.select(6, 8);
        check(slots.release(8, true) == 2, "Mace/spear changes preserve the original selection");
        check(slots.release(8, true) == -1, "Cleanup is idempotent");
        slots.select(6, 6);
        check(slots.release(6, true) == -1, "An initially held weapon requires no restoration");
        slots.select(2, 6);
        check(slots.release(6, false) == -1, "Inventory, latch or item use prevents restoration");
        slots.select(2, 6);
        check(slots.release(8, true) == -1, "Cleanup never overwrites a newly selected slot");
    }

    private static void manualSelectionWins() {
        var slots = new ElytraWeaponController.SlotOwnership();
        slots.select(2, 6);
        check(!slots.observe(3), "Manual selection relinquishes control immediately");
        check(!slots.observe(3), "The next update does not take the slot back");
        check(!slots.observe(6), "Switching back does not erase the manual override");
        check(slots.release(6, true) == -1, "Manual takeover discards the old restore slot");
        check(slots.observe(4), "A new control lifecycle can acquire a weapon again");
    }

    private static void manualWeaponMode() {
        var slots = new ElytraWeaponController.SlotOwnership();
        slots.followManual(2);
        check(slots.observe(2), "Manual mace selection does not block weapon use");
        slots.followManual(6);
        check(slots.observe(6), "Manually selecting a spear allows automatic use in MXS");
        check(slots.release(6, true) == -1, "Manual weapon mode never restores another slot");

        slots.select(2, 6);
        check(!slots.observe(3), "Auto-switch mode still respects manual takeover");
        slots.followManual(7);
        check(slots.observe(7), "Switching to manual weapon mode clears only automatic slot ownership");
        check(slots.release(7, true) == -1, "Disabling auto-switch relinquishes its old restore slot");

        slots.followManual(6);
        check(!slots.observe(2), "Re-enabling auto-switch does not overwrite a new manual selection");
        check(!slots.observe(2), "Automatic mode does not erase the manual takeover on later ticks");
    }

    private static void verifySpearUseHook() throws IOException {
        int[] releaseCalls = {0};
        read("net/minecraft/client/Minecraft").accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("handleKeybinds") || !descriptor.equals("()V")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        if (owner.equals(GAME_MODE) && name.equals("releaseUsingItem")
                                && descriptor.equals(RELEASE_DESCRIPTOR)) releaseCalls[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(releaseCalls[0] == 1, "Minecraft key handling must contain exactly one release operation");

        List<String> methods = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        int[] required = {-1};
        int[] ownershipChecks = {0};
        int[] originalCalls = {0};
        read("dev/guizhou/mixin/client/MixinMinecraftSpearUse").accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("guizhouware$keepOwnedSpearUse")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!descriptor.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String name, Object value) {
                                if (name.equals("require")) required[0] = (Integer) value;
                            }

                            @Override
                            public AnnotationVisitor visitArray(String name) {
                                if (name.equals("at")) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
                                            return atVisitor(targets);
                                        }
                                    };
                                }
                                if (!name.equals("method")) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        methods.add((String) value);
                                    }
                                };
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                return name.equals("at") ? atVisitor(targets) : null;
                            }
                        };
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        if (owner.equals("dev/guizhou/features/modules/combat/GuangxiElytraChaseModule")
                                && name.equals("shouldKeepUsingSpear")) ownershipChecks[0]++;
                        if (owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")
                                && name.equals("call")) originalCalls[0]++;
                        check(!(owner.equals("net/minecraft/client/KeyMapping") && name.equals("setDown")),
                                "Spear control must not modify global key state");
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(methods.equals(List.of("handleKeybinds")), "Only the key-handling release may be wrapped");
        check(targets.equals(List.of("L" + GAME_MODE + ";releaseUsingItem" + RELEASE_DESCRIPTOR)),
                "The mixin must wrap the verified Minecraft release signature");
        check(required[0] == 1 && ownershipChecks[0] == 1 && originalCalls[0] == 1,
                "The required hook must preserve unowned vanilla item release");
        try (InputStream stream = ElytraWeaponControllerTest.class.getClassLoader()
                .getResourceAsStream("guizhouware.mixins.json")) {
            if (stream == null) throw new IOException("Missing mixin configuration");
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            long registrations = config.getAsJsonArray("client").asList().stream()
                    .filter(entry -> entry.getAsString().equals("client.MixinMinecraftSpearUse")).count();
            check(registrations == 1, "Spear use mixin must be registered exactly once");
        }
    }

    private static AnnotationVisitor atVisitor(List<String> targets) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (name.equals("target")) targets.add((String) value);
            }
        };
    }

    private static ClassReader read(String name) throws IOException {
        try (InputStream stream = ElytraWeaponControllerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) throw new IOException("Missing class: " + name);
            return new ClassReader(stream);
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
