package dev.guizhou.util.license;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;

/** Production gate behavior and denied action reachability, without Minecraft initialization. */
public final class BusinessAuthorizationTest {
    private static final String HEARTBEAT = "dev/guizhou/manager/HeartbeatManager";
    public static void main(String[] args) throws Exception {
        for (boolean present : new boolean[]{false, true}) {
            URL[] urls = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                    .map(value -> { try { return Path.of(value).toUri().toURL(); } catch (Exception e) { throw new RuntimeException(e); } })
                    .toArray(URL[]::new);
            try (URLClassLoader isolated = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (!name.equals("d1.G")) return super.findClass(name);
                    if (!present) throw new ClassNotFoundException(name);
                    byte[] fixture = bridgeFixture();
                    return defineClass(name, fixture, 0, fixture.length);
                }
            }) {
                Class<?> guard = isolated.loadClass("dev.guizhou.util.license.GuardStatus");
                var authorization = guard.getMethod("authorized");
                var startup = guard.getMethod("requireAuthorized");
                if ((boolean) authorization.invoke(null)) throw new AssertionError("Missing/ungranted bridge authorized production");
                try { startup.invoke(null); throw new AssertionError("Direct business entry accepted without a grant"); }
                catch (java.lang.reflect.InvocationTargetException rejected) {
                    if (!(rejected.getCause() instanceof IllegalStateException)) throw rejected;
                }
                if (present) {
                    var allowed = isolated.loadClass("d1.G").getField("allowed");
                    allowed.setBoolean(null, true);
                    startup.invoke(null);
                    if (!(boolean) authorization.invoke(null)) throw new AssertionError("Legitimate bridge grant rejected");
                    allowed.setBoolean(null, false);
                    if ((boolean) authorization.invoke(null)) throw new AssertionError("Cached bridge result ignored revocation");
                }
            }
        }
        for (String name : new String[]{"onInitialize", "onInitializeClient"}) {
            MethodNode entry = method("dev/guizhou/GuizhouWare", name);
            AbstractInsnNode first = entry.instructions.getFirst();
            while (first.getOpcode() < 0) first = first.getNext();
            if (!(first instanceof MethodInsnNode call) || !call.owner.equals("dev/guizhou/util/license/GuardStatus")
                    || !call.name.equals("requireAuthorized")) throw new AssertionError("Wrapper bypass reaches business effects: " + name);
        }
        guarded("dev/guizhou/features/modules/Module", "enable", "setValue", "register", "onEnable");
        guarded("dev/guizhou/manager/ModuleManager", "onTick", "onTick");
        guarded("dev/guizhou/manager/ModuleManager", "onRender2D", "onRender2D");
        guarded("dev/guizhou/manager/ModuleManager", "onRender3D", "onRender3D");
        if (!hasGate(method("dev/guizhou/features/modules/Module", "isEnabled"))) throw new AssertionError("Mixin state queries need an action gate");
        if (hasGate(method("dev/guizhou/features/modules/Module", "disable"))) throw new AssertionError("Revocation cannot block cleanup");
        if (!hasGate(method("dev/guizhou/event/system/EventBus", "post"))) throw new AssertionError("Packet/input callbacks need authorization before module dispatch");
        System.out.println("BusinessAuthorizationTest passed: missing guard, direct entry, valid grant, revocation and module dispatch gates");
    }
    private static byte[] bridgeFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "d1/G", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "allowed", "Z", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isAuthorized", "()Z", null, null);
        method.visitCode(); method.visitFieldInsn(Opcodes.GETSTATIC, "d1/G", "allowed", "Z");
        method.visitInsn(Opcodes.IRETURN); method.visitMaxs(1,0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    private static MethodNode method(String owner, String name) throws Exception {
        ClassNode cls = new ClassNode();
        try (var in = BusinessAuthorizationTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            if (in == null) throw new AssertionError("Missing production class " + owner);
            new ClassReader(in).accept(cls, 0);
        }
        return cls.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }
    private static boolean gate(AbstractInsnNode node) {
        return node instanceof MethodInsnNode call && call.owner.equals(HEARTBEAT) && call.name.equals("authorized");
    }
    private static boolean hasGate(MethodNode method) {
        for (var node : method.instructions) if (gate(node)) return true;
        return false;
    }
    private static void guarded(String owner, String name, String... effects) throws Exception {
        MethodNode method = method(owner, name);
        if (!hasGate(method)) throw new AssertionError("Missing gate " + owner + "." + name);
        for (String effect : effects) {
            boolean found = false;
            for (int i=0; i<method.instructions.size(); i++) {
                if (!(method.instructions.get(i) instanceof MethodInsnNode call) || !call.name.equals(effect)) continue;
                found = true;
                if (reachable(method, i, false)) throw new AssertionError("Denied path reaches " + owner + "." + name + " -> " + effect);
                if (!reachable(method, i, true)) throw new AssertionError("Authorized path cannot reach " + effect);
            }
            if (!found) throw new AssertionError("Lost expected functional action " + effect);
        }
    }
    private static boolean reachable(MethodNode method, int target, boolean allowed) {
        var todo = new ArrayDeque<Integer>(); var seen = new HashSet<Integer>(); todo.add(0);
        while (!todo.isEmpty()) {
            int index=todo.removeFirst(); if (index<0 || index>=method.instructions.size() || !seen.add(index)) continue;
            if (index==target) return true;
            AbstractInsnNode node=method.instructions.get(index); int opcode=node.getOpcode();
            if (node instanceof JumpInsnNode jump) {
                AbstractInsnNode previous=node.getPrevious(); while(previous!=null && previous.getOpcode()<0) previous=previous.getPrevious();
                if (opcode==Opcodes.GOTO) { todo.add(method.instructions.indexOf(jump.label)); continue; }
                if (gate(previous) && (opcode==Opcodes.IFEQ || opcode==Opcodes.IFNE)) {
                    todo.add((opcode==Opcodes.IFEQ ? !allowed : allowed) ? method.instructions.indexOf(jump.label) : index+1);
                } else { todo.add(method.instructions.indexOf(jump.label)); todo.add(index+1); }
                continue;
            }
            if ((opcode>=Opcodes.IRETURN && opcode<=Opcodes.RETURN) || opcode==Opcodes.ATHROW) continue;
            todo.add(index+1);
        }
        return false;
    }
}
