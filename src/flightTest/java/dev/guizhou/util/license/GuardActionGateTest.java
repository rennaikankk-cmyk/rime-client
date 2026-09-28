package dev.guizhou.util.license;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.ArrayDeque;

/** Executes the actual action-gate boolean control flow without loading Minecraft. */
public final class GuardActionGateTest {
    public static void main(String[] args) throws Exception {
        for (boolean current : new boolean[]{false, true}) for (boolean legacy : new boolean[]{false, true}) {
            var metadata = (net.fabricmc.loader.api.metadata.ModMetadata) java.lang.reflect.Proxy.newProxyInstance(
                    GuardActionGateTest.class.getClassLoader(),
                    new Class<?>[]{net.fabricmc.loader.api.metadata.ModMetadata.class},
                    (proxy, method, values) -> {
                        if (!method.getName().equals("containsCustomValue")) throw new AssertionError("Unexpected metadata access");
                        return values[0].equals("rimeguard") ? current : values[0].equals("dickguard") && legacy;
                    });
            if (GuardStatus.requiresGuard(metadata) != (current || legacy))
                throw new AssertionError("Current and legacy guard metadata must both require authorization");
        }
        ClassNode cls = new ClassNode();
        try (var in = GuardActionGateTest.class.getClassLoader().getResourceAsStream(
                "dev/guizhou/manager/HeartbeatManager.class")) {
            if (in == null) throw new AssertionError("Missing heartbeat class");
            new ClassReader(in).accept(cls, 0);
        }
        MethodNode gate = cls.methods.stream().filter(m -> m.name.equals("authorized") && m.desc.equals("()Z"))
                .findFirst().orElseThrow();
        for (boolean lease : new boolean[]{false,true}) for (boolean guard : new boolean[]{false,true}) {
            var stack = new ArrayDeque<Boolean>();
            boolean returned = false;
            for (AbstractInsnNode node = gate.instructions.getFirst(); node != null; node = node.getNext()) {
                int op = node.getOpcode();
                if (node instanceof MethodInsnNode call) {
                    if (!call.name.equals("authorized")) throw new AssertionError("Unexpected gate call");
                    if (call.owner.equals("dev/guizhou/util/license/LicenseLease")) stack.push(lease);
                    else if (call.owner.equals("dev/guizhou/util/license/GuardStatus")) stack.push(guard);
                    else throw new AssertionError("Unexpected authorization provider");
                } else if (node instanceof JumpInsnNode jump) {
                    boolean take = op == Opcodes.GOTO || (op == Opcodes.IFEQ ? !stack.pop() : stack.pop());
                    if (take) node = jump.label;
                } else if (op == Opcodes.ICONST_0 || op == Opcodes.ICONST_1) stack.push(op == Opcodes.ICONST_1);
                else if (op == Opcodes.IRETURN) {
                    if (stack.pop() != (lease && guard)) throw new AssertionError("Both licence and guard must authorize actions");
                    returned = true; break;
                } else if (op >= 0 && op != Opcodes.GETSTATIC) throw new AssertionError("Unexpected opcode " + op);
            }
            if (!returned) throw new AssertionError("Gate did not return");
        }
        System.out.println("GuardActionGateTest: four metadata aliases and four lease/guard authorization combinations passed");
    }
}
