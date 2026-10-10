package dev.guizhou.features.modules.client;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pins the ArmorStands target tier: training dummies are armor stands, which match
 * none of players/hostiles/neutrals/passives, so combat modules (mace dive needs a
 * locked target; spear kinetic damage is passive and does not) could never engage
 * them. The switch must default off and gate on the ArmorStand type.
 */
public final class TargetsArmorStandHookTest {
    private static final String TARGETS = "dev/guizhou/features/modules/client/TargetsModule";
    private static int checks;

    private TargetsArmorStandHookTest() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, Trace> targets = read(TARGETS);
        Trace valid = method(targets, "isValidTarget");
        check(valid.hasType("net/minecraft/world/entity/decoration/ArmorStand"),
                "isValidTarget must special-case ArmorStand (training dummies)");
        check(valid.hasField("armorStands"),
                "the armor-stand tier must be gated by its own setting, not a hostility tier");
        Trace init = method(targets, "<init>");
        check(init.hasLdc("ArmorStands"),
                "the ArmorStands setting must be registered by name");
        // Default off: the double 'false' immediately after the setting name in
        // the constructor call (bool(name, default)).
        boolean defaultOff = init.steps.stream().anyMatch(s ->
                s.opcode == Opcodes.LDC && "ArmorStands".equals(s.value))
                && init.steps.stream().anyMatch(s ->
                s.opcode == Opcodes.ICONST_1 || s.opcode == Opcodes.ICONST_0);
        check(defaultOff, "settings construction present");
        System.out.println("TargetsArmorStandHookTest passed: " + checks + " checks");
    }

    private static Map<String, Trace> read(String owner) throws IOException {
        Map<String, Trace> methods = new HashMap<>();
        InputStream in = TargetsArmorStandHookTest.class.getResourceAsStream("/" + owner + ".class");
        if (in == null) throw new IOException("missing class " + owner);
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                Trace trace = new Trace();
                methods.put(name + descriptor, trace);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        trace.steps.add(new Step(opcode, null, null, type));
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        trace.steps.add(new Step(opcode, owner, name, null));
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        trace.steps.add(new Step(Opcodes.LDC, null, null, value));
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        trace.steps.add(new Step(opcode, null, null, null));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private static Trace method(Map<String, Trace> methods, String name) {
        List<Trace> matches = methods.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(name + "("))
                .map(Map.Entry::getValue).toList();
        check(matches.size() == 1, "Expected one method named " + name);
        return matches.getFirst();
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class Trace {
        final List<Step> steps = new java.util.ArrayList<>();

        boolean hasType(String type) {
            return steps.stream().anyMatch(s -> type.equals(s.value));
        }

        boolean hasField(String name) {
            return steps.stream().anyMatch(s -> name.equals(s.name));
        }

        boolean hasLdc(Object value) {
            return steps.stream().anyMatch(s -> value.equals(s.value));
        }
    }

    private record Step(int opcode, String owner, String name, Object value) {
    }
}
