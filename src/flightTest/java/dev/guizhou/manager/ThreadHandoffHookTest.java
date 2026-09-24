package dev.guizhou.manager;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Netty-thread receive paths must hand world work to the client thread: AutoLog only queues,
 * and packet-handler HEAD hooks return before touching the world on the first (Netty) pass.
 */
public final class ThreadHandoffHookTest {
    private static int checks;

    public static void main(String[] args) throws IOException {
        MethodNode receive = method("dev/guizhou/features/modules/combat/AutoLogModule", "onPacketReceive");
        for (String forbidden : List.of("disconnect", "disable", "logOut", "getEntity", "sendMessage")) {
            check(calls(receive, forbidden) == 0, "AutoLog receive callback must not call " + forbidden + " on Netty");
        }
        check(calls(receive, "execute") >= 2, "AutoLog must queue both packet kinds onto the client thread");

        String mixin = "dev/guizhou/mixin/network/MixinClientPlayNetworkHandler";
        for (String hook : List.of("cancelKnockback", "saveExplosionVelocity", "silentSwapFix")) {
            MethodNode method = method(mixin, hook);
            AbstractInsnNode first = firstCall(method);
            check(first instanceof MethodInsnNode call && call.name.equals("getInstance"), hook + " starts from Minecraft");
            MethodInsnNode thread = nextCall(first);
            check(thread != null && thread.name.equals("isSameThread"), hook + " must check the client thread first");
            AbstractInsnNode branch = next(thread);
            check(branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ,
                    hook + " must return when not on the client thread");
        }
        check(calls(method("dev/guizhou/mixin/network/MixinClientConnection", "channelRead0"), "matches") == 0,
                "The held-slot filter no longer runs on the Netty read path");
        System.out.println("ThreadHandoffHookTest passed: " + checks + " checks");
    }

    private static AbstractInsnNode firstCall(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) return insn;
        return null;
    }

    private static MethodInsnNode nextCall(AbstractInsnNode from) {
        for (AbstractInsnNode insn = from == null ? null : from.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        return null;
    }

    private static AbstractInsnNode next(AbstractInsnNode from) {
        AbstractInsnNode insn = from.getNext();
        while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
        return insn;
    }

    private static int calls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals(name)) count++;
        }
        return count;
    }

    private static MethodNode method(String owner, String name) throws IOException {
        try (InputStream input = ThreadHandoffHookTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            if (input == null) throw new AssertionError("Missing compiled " + owner);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var found = node.methods.stream().filter(m -> m.name.equals(name)).toList();
            check(found.size() == 1, "Expected one method " + owner + "." + name + ", got " + found.size());
            return found.getFirst();
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
