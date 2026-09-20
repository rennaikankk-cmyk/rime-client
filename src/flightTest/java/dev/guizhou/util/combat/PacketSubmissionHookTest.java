package dev.guizhou.util.combat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Verifies each complete event-dispatch path reports exactly one local disposition. */
public final class PacketSubmissionHookTest {
    public static void main(String[] args) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = PacketSubmissionHookTest.class.getClassLoader().getResourceAsStream(
                "dev/guizhou/mixin/network/MixinClientConnection.class")) {
            if (in == null) throw new AssertionError("Missing connection mixin");
            new ClassReader(in).accept(node, 0);
        }
        MethodNode method = node.methods.stream().filter(m -> m.name.equals("sendImmediately")).findFirst().orElseThrow();
        List<List<String>> paths = new ArrayList<>();
        walk(method, 0, new ArrayList<>(), paths, 0);
        int accepted = 0, cancelled = 0, ignored = 0;
        for (List<String> calls : paths) {
            boolean posted = calls.contains("post"), submitted = calls.contains("onSubmitted");
            boolean rejected = calls.contains("onCancelled"), cancelHook = calls.contains("cancel");
            if (!posted) {
                check(!submitted && !rejected, "non-client flow must not enter counters");
                ignored++;
            } else if (submitted) {
                check(!rejected && !cancelHook, "accepted path cannot count a cancelled original");
                check(calls.indexOf("post") < calls.indexOf("onSubmitted"), "submission must follow the entire event dispatch");
                accepted++;
            } else {
                check(rejected && cancelHook, "rejected path must cancel and only count cancellation");
                check(calls.indexOf("post") < calls.indexOf("onCancelled"), "cancellation must follow the entire dispatch");
                cancelled++;
            }
        }
        check(accepted == 1 && cancelled == 1 && ignored == 1, "must cover all three normal connection paths");
        System.out.println("PacketSubmissionHookTest passed: accepted, cancelled and non-client paths after full dispatch");
    }

    private static void walk(MethodNode method, int index, List<String> calls, List<List<String>> out, int steps) {
        if (steps > 200 || index >= method.instructions.size()) throw new AssertionError("Unexpected hook control flow");
        AbstractInsnNode insn = method.instructions.get(index);
        var next = new ArrayList<>(calls);
        if (insn instanceof MethodInsnNode call && (call.name.equals("post") || call.name.equals("cancel")
                || call.name.equals("onSubmitted") || call.name.equals("onCancelled"))) next.add(call.name);
        if (insn.getOpcode() == Opcodes.RETURN) { out.add(next); return; }
        if (insn instanceof JumpInsnNode jump) {
            walk(method, method.instructions.indexOf(jump.label), next, out, steps + 1);
            if (jump.getOpcode() == Opcodes.GOTO) return;
        }
        walk(method, index + 1, next, out, steps + 1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
