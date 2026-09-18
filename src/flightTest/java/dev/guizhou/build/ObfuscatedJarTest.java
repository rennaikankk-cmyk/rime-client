package dev.guizhou.build;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the production artifact without loading Minecraft or licence managers. */
public final class ObfuscatedJarTest {
    private static final String HEARTBEAT = "dev/guizhou/manager/HeartbeatManager";
    private static final String RESULT_TYPE = "dev/guizhou/util/inventory/ResultType";
    private static final String ENTRYPOINT = "dev/guizhou/GuizhouWare";
    private static int checks;

    private ObfuscatedJarTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected inputJar outputJar mappingFile");
        }
        Path outputPath = Path.of(args[1]);
        try (JarFile input = new JarFile(args[0]); JarFile output = new JarFile(args[1])) {
            Map<String, ClassInfo> before = classes(input);
            Map<String, ClassInfo> after = classes(output);
            // Run this first so a previously broken artifact fails on the actual defect.
            int enumCount = verifyEnumStructure(after);
            Mapping mapping = Mapping.read(Path.of(args[2]));
            int removedEnums = verifyEnumMapping(before, after, mapping);
            int reflected = verifyPureEnums(outputPath, before, after, mapping);
            verifyResources(input, output);
            verifyEntrypointAndMixins(before, after, mapping);
            verifyGuiOverrides(before, after, mapping);
            verifyHeartbeat(before, after, mapping);
            System.out.println("ObfuscatedJarTest passed: " + checks + " checks; "
                    + enumCount + " enums audited, " + reflected + " isolated reflection checks, "
                    + (enumCount - reflected) + " complex enums ASM-only, "
                    + removedEnums + " unused input enums removed");
        }
    }

    private static int verifyEnumStructure(Map<String, ClassInfo> classes) {
        int count = 0;
        for (ClassInfo info : classes.values()) {
            if ((info.access & Opcodes.ACC_ENUM) == 0) continue;
            count++;
            check("java/lang/Enum".equals(info.superName), info.name + " lost Enum superclass");
            MethodInfo values = info.methods.get("values()[L" + info.name + ";");
            check(values != null, info.name + " missing public static values(): enum reflection will fail");
            check(publicStatic(values.access), info.name + ".values() lost public/static access");
            MethodInfo valueOf = info.methods.get("valueOf(Ljava/lang/String;)L" + info.name + ";");
            check(valueOf != null, info.name + " missing public static valueOf(String)");
            check(publicStatic(valueOf.access), info.name + ".valueOf(String) lost public/static access");
        }
        check(count > 0, "No output enums found");
        return count;
    }

    private static int verifyEnumMapping(Map<String, ClassInfo> before,
                                         Map<String, ClassInfo> after, Mapping mapping) {
        int removed = 0;
        for (ClassInfo info : before.values()) {
            if ((info.access & Opcodes.ACC_ENUM) == 0) continue;
            ClassInfo result = after.get(mapping.className(info.name));
            if (result == null) {
                removed++;
                continue;
            }
            check((result.access & Opcodes.ACC_ENUM) != 0, info.name + " lost ACC_ENUM");
            check("java/lang/Enum".equals(result.superName), info.name + " lost Enum superclass");
            long originalConstants = info.fields.values().stream()
                    .filter(field -> (field.access & Opcodes.ACC_ENUM) != 0).count();
            long resultConstants = result.fields.values().stream()
                    .filter(field -> (field.access & Opcodes.ACC_ENUM) != 0).count();
            check(originalConstants == resultConstants, info.name + " lost enum constants");
        }
        return removed;
    }

    private static int verifyPureEnums(Path output, Map<String, ClassInfo> before,
                                       Map<String, ClassInfo> after, Mapping mapping) throws Exception {
        String resultType = mapping.requiredClass(RESULT_TYPE);
        check(before.containsKey(RESULT_TYPE), "Input ResultType is missing");
        check(after.containsKey(resultType), "Output ResultType is missing");
        check(isPureEnum(after.get(resultType)), "ResultType requires reviewed reflection isolation");
        int count = 0;
        // A null parent prevents source/test classes from masking a broken production class.
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, null)) {
            for (ClassInfo info : after.values()) {
                if ((info.access & Opcodes.ACC_ENUM) == 0 || !isPureEnum(info)) continue;
                Class<?> type = Class.forName(info.name.replace('/', '.'), true, loader);
                check(type.getClassLoader() == loader, "Reflection used wrong loader: " + info.name);
                check(type.isEnum(), "Reflection does not recognize enum: " + info.name);
                Object[] constants = type.getEnumConstants();
                check(constants != null, "getEnumConstants returned null: " + info.name);
                long fields = info.fields.values().stream()
                        .filter(field -> (field.access & Opcodes.ACC_ENUM) != 0).count();
                check(constants.length == fields, "Reflection lost constants: " + info.name);
                var valuesMethod = type.getMethod("values");
                var valueOfMethod = type.getMethod("valueOf", String.class);
                // JDK enum reflection also opens public methods of package-private enums.
                check(valuesMethod.trySetAccessible() && valueOfMethod.trySetAccessible(),
                        "Cannot access enum reflection methods: " + info.name);
                Object values = valuesMethod.invoke(null);
                Object nextValues = valuesMethod.invoke(null);
                check(values != nextValues, "values() must return a fresh array: " + info.name);
                check(Array.getLength(values) == constants.length, "values() length changed: " + info.name);
                for (int index = 0; index < constants.length; index++) {
                    Enum<?> constant = (Enum<?>) constants[index];
                    check(Array.get(values, index) == constant, "values() order changed: " + info.name);
                    check(valueOfMethod.invoke(null, constant.name()) == constant,
                            "valueOf failed: " + info.name + "." + constant.name());
                    verifyEnumSet(constant);
                }
                count++;
            }
        }
        check(count > 0, "No enums received isolated runtime verification");
        return count;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void verifyEnumSet(Enum<?> constant) {
        EnumSet set = EnumSet.of((Enum) constant);
        check(set.size() == 1 && set.contains(constant), "EnumSet.of failed: " + constant);
        check(EnumSet.allOf((Class) constant.getDeclaringClass()).contains(constant),
                "EnumSet.allOf failed: " + constant);
    }

    private static boolean isPureEnum(ClassInfo info) {
        if ((info.access & Opcodes.ACC_ENUM) == 0) return false;
        Set<String> descriptors = Set.of("(Ljava/lang/String;I)V", "()V", "()[L" + info.name + ";",
                "(Ljava/lang/String;)L" + info.name + ";");
        for (FieldInfo field : info.fields.values()) {
            if (!field.descriptor.equals("L" + info.name + ";")
                    && !field.descriptor.equals("[L" + info.name + ";")) return false;
        }
        for (MethodInfo method : info.methods.values()) {
            if (!descriptors.contains(method.descriptor)) return false;
            for (Step step : method.steps) {
                if (step.opcode == Opcodes.INVOKEDYNAMIC) return false;
                if (step.owner != null) {
                    boolean own = step.owner.equals(info.name);
                    boolean enumBase = step.owner.equals("java/lang/Enum")
                            && ((step.name.equals("<init>") && step.descriptor.equals("(Ljava/lang/String;I)V"))
                            || (step.name.equals("valueOf") && step.descriptor.equals(
                                    "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;")));
                    boolean arrayClone = step.owner.equals("[L" + info.name + ";")
                            && step.name.equals("clone") && step.descriptor.equals("()Ljava/lang/Object;");
                    if (!own && !enumBase && !arrayClone) return false;
                }
                if (step.value instanceof Type type && !type.getInternalName().equals(info.name)
                        && !type.getInternalName().equals("[L" + info.name + ";")) return false;
            }
        }
        return true;
    }

    private static void verifyResources(JarFile input, JarFile output) throws IOException {
        for (String name : List.of("AI_NOTICE.md", "CLAUDE.md", "AGENTS.md", "LLM.txt", "README.md",
                "fabric.mod.json", "guizhouware.mixins.json", "guizhouware.accesswidener")) {
            byte[] original = entry(input, name);
            check(original.length > 0, "Empty input resource: " + name);
            check(Arrays.equals(original, entry(output, name)), "Resource changed/lost: " + name);
        }
        check(input.getManifest() != null && output.getManifest() != null, "Manifest missing");
        String notice = input.getManifest().getMainAttributes().getValue("X-AI-Notice");
        check(notice != null && !notice.isBlank(), "Input manifest protection notice missing");
        check(notice.equals(output.getManifest().getMainAttributes().getValue("X-AI-Notice")),
                "Manifest protection notice changed/lost");
        List<String> nested = input.stream().filter(e -> !e.isDirectory() && e.getName().endsWith(".jar"))
                .map(java.util.jar.JarEntry::getName).toList();
        check(!nested.isEmpty(), "Input has no nested runtime libraries");
        for (String name : nested) {
            check(Arrays.equals(entry(input, name), entry(output, name)), "Nested library changed/lost: " + name);
        }
    }

    private static void verifyEntrypointAndMixins(Map<String, ClassInfo> before,
                                                 Map<String, ClassInfo> after, Mapping mapping) {
        check(ENTRYPOINT.equals(mapping.requiredClass(ENTRYPOINT)), "Entrypoint class was renamed");
        ClassInfo entrypoint = required(after, ENTRYPOINT);
        check(entrypoint.interfaces.contains("net/fabricmc/api/ModInitializer"),
                "Entrypoint lost ModInitializer");
        check(entrypoint.interfaces.contains("net/fabricmc/api/ClientModInitializer"),
                "Entrypoint lost ClientModInitializer");
        check(entrypoint.methods.containsKey("onInitialize()V"), "onInitialize was removed/renamed");
        check(entrypoint.methods.containsKey("onInitializeClient()V"), "onInitializeClient was removed/renamed");
        int mixins = 0;
        for (ClassInfo info : before.values()) {
            if (!info.name.startsWith("dev/guizhou/mixin/")) continue;
            mixins++;
            check(info.name.equals(mapping.requiredClass(info.name)), "Mixin renamed: " + info.name);
            ClassInfo result = required(after, info.name);
            for (MethodInfo method : info.methods.values()) {
                check(result.methods.containsKey(method.name + mapping.descriptor(method.descriptor)),
                        "Mixin method changed/lost: " + info.name + "." + method.name);
            }
            for (var field : info.fields.entrySet()) {
                String name = field.getKey().substring(0,
                        field.getKey().length() - field.getValue().descriptor.length());
                check(result.fields.containsKey(name + mapping.descriptor(field.getValue().descriptor)),
                        "Mixin field changed/lost: " + info.name + "." + name);
            }
        }
        check(mixins > 0, "No mixins checked");
    }

    private static void verifyGuiOverrides(Map<String, ClassInfo> before,
                                           Map<String, ClassInfo> after, Mapping mapping) {
        String gui = "dev/guizhou/features/gui/GuizhouWareGui";
        ClassInfo source = required(before, gui);
        ClassInfo result = required(after, mapping.requiredClass(gui));
        check(source.superName.equals(result.superName), "ClickGUI lost its Minecraft Screen superclass");
        int overrides = 0;
        for (MethodInfo method : source.methods.values()) {
            if (!method.name.startsWith("method_")) continue;
            overrides++;
            check(result.methods.containsKey(method.name + method.descriptor),
                    "ClickGUI override removed/renamed: " + method.name + method.descriptor);
        }
        check(overrides >= 8, "ClickGUI override coverage unexpectedly small");
        check(result.methods.keySet().stream().anyMatch(key -> key.startsWith("method_25394(")),
                "ClickGUI render override missing");
    }

    private static void verifyHeartbeat(Map<String, ClassInfo> before,
                                         Map<String, ClassInfo> after, Mapping mapping) {
        ClassInfo original = required(before, HEARTBEAT);
        String owner = mapping.requiredClass(HEARTBEAT);
        ClassInfo heartbeat = required(after, owner);
        String notice = mapping.requiredMember(HEARTBEAT, "AI_GUARD_NOTICE");
        FieldInfo guard = heartbeat.fields.get(notice + "[Ljava/lang/String;");
        check(guard != null && Modifier.isStatic(guard.access), "Heartbeat guard array was removed");
        verifyGuardReference(requiredMethod(original, "status()Ljava/lang/String;"),
                HEARTBEAT, "AI_GUARD_NOTICE");
        String retainedStatus = mapping.requiredMember(HEARTBEAT, "status()");
        verifyGuardReference(requiredMethod(heartbeat, retainedStatus + "()Ljava/lang/String;"), owner, notice);
        MethodInfo initial = requiredMethod(original, "<clinit>()V");
        MethodInfo resultInitial = requiredMethod(heartbeat, "<clinit>()V");
        Set<String> sourceConstants = stringConstants(initial);
        check(sourceConstants.size() >= 5, "Input heartbeat guard notices missing");
        check(stringConstants(resultInitial).containsAll(sourceConstants),
                "Heartbeat guard notices changed/lost");
        String authorized = mapping.requiredMember(HEARTBEAT, "authorized()");
        String denied = mapping.requiredMember(HEARTBEAT, "notifyDeniedOnce()");
        requiredMethod(heartbeat, authorized + "()Z");
        requiredMethod(heartbeat, denied + "()V");
        for (String simpleName : List.of("GuangxiCrystalModule", "GuangxiElytraChaseModule",
                "GuangxiBedModule", "GuangxiElytraModule", "GuangxiMineModule", "AutoUseModule")) {
            List<ClassInfo> matches = before.values().stream()
                    .filter(info -> info.name.endsWith("/" + simpleName)).toList();
            check(matches.size() == 1, "Expected one protected module: " + simpleName);
            ClassInfo source = matches.getFirst();
            ClassInfo result = required(after, mapping.requiredClass(source.name));
            List<MethodInfo> sourceTicks = source.methods.values().stream()
                    .filter(method -> method.descriptor.startsWith("(Ldev/guizhou/event/")
                            && method.descriptor.endsWith(";)V"))
                    .filter(method -> method.steps.stream().anyMatch(step ->
                            member(step, Opcodes.INVOKESTATIC, HEARTBEAT, "authorized", "()Z"))).toList();
            int expectedCheckpoints = simpleName.equals("GuangxiElytraChaseModule") ? 2 : 1;
            check(sourceTicks.size() == expectedCheckpoints, "Input authorization checkpoint count: " + simpleName);
            for (MethodInfo sourceTick : sourceTicks) {
                String tickName = sourceTick.name;
                List<MethodInfo> tickMethods = result.methods.values().stream()
                        .filter(method -> method.name.equals(tickName)).toList();
                check(tickMethods.size() == 1, "Protected tick method changed/lost: " + simpleName);
                List<Step> steps = executable(tickMethods.getFirst());
                check(steps.size() >= 5, "Authorization prefix missing: " + simpleName);
                check(member(steps.get(0), Opcodes.INVOKESTATIC, owner, authorized, "()Z")
                                && steps.get(1).opcode == Opcodes.IFNE
                                && member(steps.get(2), Opcodes.INVOKESTATIC, owner, denied, "()V")
                                && steps.get(3).opcode == Opcodes.RETURN,
                        "Authorization must precede module work: " + simpleName);
            }
        }
    }

    private static void verifyGuardReference(MethodInfo status, String owner, String notice) {
        List<Step> statusSteps = executable(status);
        check(statusSteps.size() >= 3, "Heartbeat status body missing");
        check(member(statusSteps.get(0), Opcodes.GETSTATIC, owner, notice, "[Ljava/lang/String;")
                        && statusSteps.get(1).opcode == Opcodes.ARRAYLENGTH
                        && statusSteps.get(2).opcode == Opcodes.IFNE,
                "Heartbeat status lost intentional guard-array reference");
    }

    private static Set<String> stringConstants(MethodInfo method) {
        java.util.HashSet<String> strings = new java.util.HashSet<>();
        for (Step step : method.steps) {
            if (step.opcode == Opcodes.LDC && step.value instanceof String value) strings.add(value);
        }
        return strings;
    }

    private static List<Step> executable(MethodInfo method) {
        return method.steps.stream().filter(step -> step.opcode >= 0).toList();
    }

    private static boolean member(Step step, int opcode, String owner, String name, String descriptor) {
        return step.opcode == opcode && owner.equals(step.owner) && name.equals(step.name)
                && descriptor.equals(step.descriptor);
    }

    private static boolean publicStatic(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
    }

    private static ClassInfo required(Map<String, ClassInfo> classes, String name) {
        ClassInfo info = classes.get(name);
        check(info != null, "Class missing: " + name);
        return info;
    }

    private static MethodInfo requiredMethod(ClassInfo info, String nameAndDescriptor) {
        MethodInfo method = info.methods.get(nameAndDescriptor);
        check(method != null, "Method missing: " + info.name + "." + nameAndDescriptor);
        return method;
    }

    private static byte[] entry(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        check(entry != null, "JAR entry missing: " + name);
        try (var stream = jar.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    private static Map<String, ClassInfo> classes(JarFile jar) throws IOException {
        Map<String, ClassInfo> classes = new LinkedHashMap<>();
        for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
            ClassInfo info = new ClassInfo();
            new ClassReader(entry(jar, entry.getName())).accept(info,
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            check(classes.put(info.name, info) == null, "Duplicate class: " + info.name);
        }
        return classes;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class ClassInfo extends ClassVisitor {
        String name;
        String superName;
        int access;
        List<String> interfaces;
        final Map<String, FieldInfo> fields = new LinkedHashMap<>();
        final Map<String, MethodInfo> methods = new LinkedHashMap<>();

        ClassInfo() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            this.name = name;
            this.superName = superName;
            this.access = access;
            this.interfaces = Arrays.asList(interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            fields.put(name + descriptor, new FieldInfo(access, descriptor));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            MethodInfo method = new MethodInfo(access, name, descriptor);
            methods.put(name + descriptor, method);
            return method;
        }
    }

    private record FieldInfo(int access, String descriptor) {
    }

    private record Step(int opcode, String owner, String name, String descriptor, Object value) {
    }

    private static final class MethodInfo extends MethodVisitor {
        final int access;
        final String name;
        final String descriptor;
        final List<Step> steps = new ArrayList<>();

        MethodInfo(int access, String name, String descriptor) {
            super(Opcodes.ASM9);
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }

        private void add(int opcode, Object value) {
            steps.add(new Step(opcode, null, null, null, value));
        }

        @Override public void visitInsn(int opcode) { add(opcode, null); }
        @Override public void visitIntInsn(int opcode, int operand) { add(opcode, operand); }
        @Override public void visitVarInsn(int opcode, int varIndex) { add(opcode, varIndex); }
        @Override public void visitTypeInsn(int opcode, String type) { add(opcode, Type.getObjectType(type)); }
        @Override public void visitLdcInsn(Object value) { add(Opcodes.LDC, value); }
        @Override public void visitJumpInsn(int opcode, Label label) { add(opcode, label); }
        @Override public void visitLabel(Label label) { add(-1, label); }
        @Override public void visitIincInsn(int varIndex, int increment) { add(Opcodes.IINC, varIndex); }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            add(Opcodes.TABLESWITCH, null);
        }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            add(Opcodes.LOOKUPSWITCH, null);
        }
        @Override public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            add(Opcodes.MULTIANEWARRAY, Type.getType(descriptor));
        }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            steps.add(new Step(opcode, owner, name, descriptor, null));
        }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                              boolean isInterface) {
            steps.add(new Step(opcode, owner, name, descriptor, null));
        }
        @Override public void visitInvokeDynamicInsn(String name, String descriptor,
                                                      org.objectweb.asm.Handle handle, Object... args) {
            add(Opcodes.INVOKEDYNAMIC, null);
        }
    }

    private static final class Mapping {
        private static final Pattern MEMBER = Pattern.compile(
                "^(?:\\d+:\\d+:)?\\S+ ([^ (]+)(\\([^)]*\\))?(?::\\d+(?::\\d+)?)? -> (\\S+)$");
        final Map<String, String> classes = new HashMap<>();
        final Map<String, String> members = new HashMap<>();

        static Mapping read(Path path) throws IOException {
            Mapping mapping = new Mapping();
            String owner = null;
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                if (!Character.isWhitespace(line.charAt(0)) && line.endsWith(":")) {
                    int separator = line.indexOf(" -> ");
                    check(separator > 0, "Malformed mapping class: " + line);
                    owner = line.substring(0, separator).replace('.', '/');
                    mapping.classes.put(owner,
                            line.substring(separator + 4, line.length() - 1).replace('.', '/'));
                } else {
                    Matcher matcher = MEMBER.matcher(line.trim());
                    check(owner != null && matcher.matches(), "Malformed mapping member: " + line);
                    String member = matcher.group(1) + (matcher.group(2) == null ? "" : matcher.group(2));
                    mapping.members.put(owner + "." + member, matcher.group(3));
                }
            }
            check(!mapping.classes.isEmpty(), "Empty ProGuard mapping");
            return mapping;
        }

        String className(String name) {
            return classes.getOrDefault(name, name);
        }

        String requiredClass(String name) {
            check(classes.containsKey(name), "Class absent from mapping: " + name);
            return classes.get(name);
        }

        String requiredMember(String owner, String name) {
            String result = members.get(owner + "." + name);
            check(result != null, "Member absent from mapping: " + owner + "." + name);
            return result;
        }

        String descriptor(String descriptor) {
            Type type = Type.getType(descriptor);
            return switch (type.getSort()) {
                case Type.OBJECT -> "L" + className(type.getInternalName()) + ";";
                case Type.ARRAY -> "[".repeat(type.getDimensions()) + descriptor(type.getElementType().getDescriptor());
                case Type.METHOD -> {
                    StringBuilder result = new StringBuilder("(");
                    for (Type argument : type.getArgumentTypes()) result.append(descriptor(argument.getDescriptor()));
                    yield result.append(')').append(descriptor(type.getReturnType().getDescriptor())).toString();
                }
                default -> descriptor;
            };
        }
    }
}
