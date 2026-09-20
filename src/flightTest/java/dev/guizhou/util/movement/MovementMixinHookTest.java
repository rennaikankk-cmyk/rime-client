package dev.guizhou.util.movement;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;

/** Detect mapping/signature drift before shipping packet mutations or movement mixins. */
public final class MovementMixinHookTest {
    public static void main(String[] args) throws IOException {
        String entity = "net/minecraft/world/entity/Entity";
        String local = "net/minecraft/client/player/LocalPlayer";
        assertCalls(local, "modifyInput", "(Lnet/minecraft/world/phys/Vec2;)Lnet/minecraft/world/phys/Vec2;",
                local, "itemUseSpeedMultiplier", "()F", 1);
        assertCalls(entity, "makeStuckInBlock", "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/Vec3;)V",
                entity, "resetFallDistance", "()V", 1);
        assertCalls("net/minecraft/world/level/block/SlimeBlock", "stepOn",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/Entity;)V",
                entity, "setDeltaMovement", "(Lnet/minecraft/world/phys/Vec3;)V", 1);
        int[] fields = {0};
        read("net/minecraft/network/protocol/game/ServerboundMovePlayerPacket").accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if (name.equals("onGround") && desc.equals("Z") && (access & Opcodes.ACC_STATIC) == 0) fields[0]++;
                return null;
            }
        }, ClassReader.SKIP_CODE);
        check(fields[0] == 1, "Movement packet onGround field changed; recheck mutable accessor");
        System.out.println("Movement mixin hook checks passed against the Minecraft runtime classes.");
    }

    private static void assertCalls(String owner, String name, String descriptor,
                                    String targetOwner, String targetName, String targetDescriptor, int expected) throws IOException {
        int[] methods = {0}, calls = {0};
        read(owner).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                if (!name.equals(method) || !descriptor.equals(desc)) return null;
                methods[0]++;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String calledOwner, String calledName, String calledDesc, boolean iface) {
                        if (targetOwner.equals(calledOwner) && targetName.equals(calledName)
                                && targetDescriptor.equals(calledDesc)) calls[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(methods[0] == 1 && calls[0] == expected,
                owner + "." + name + " hook changed: methods=" + methods[0] + ", calls=" + calls[0]);
    }

    private static ClassReader read(String owner) throws IOException {
        try (InputStream stream = MovementMixinHookTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            if (stream == null) throw new IOException("Missing class: " + owner);
            return new ClassReader(stream);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
