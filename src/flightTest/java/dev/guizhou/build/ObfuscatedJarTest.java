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
            verifyReleaseLicense(before, after, mapping);
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
        for (String gui : List.of("dev/guizhou/features/gui/GuizhouWareGui", "dev/guizhou/features/gui/HudEditorScreen")) {
        ClassInfo source = required(before, gui);
        ClassInfo result = required(after, mapping.requiredClass(gui));
        check(source.superName.equals(result.superName), gui + " lost its Minecraft Screen superclass");
        int overrides = 0;
        for (MethodInfo method : source.methods.values()) {
            if (!method.name.startsWith("method_")) continue;
            overrides++;
            check(result.methods.containsKey(method.name + method.descriptor),
                    gui + " override removed/renamed: " + method.name + method.descriptor);
        }
        check(overrides >= 8, gui + " override coverage unexpectedly small");
        check(result.methods.keySet().stream().anyMatch(key -> key.startsWith("method_25394(")),
                gui + " render override missing");
        }
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
        Map<String, List<String>> checkpoints = Map.ofEntries(
                Map.entry("GuangxiCrystalModule", List.of("onPreTick", "onCrystalSpawn", "onBlockUpdate",
                        "lambda$onBlockUpdate$", "scanBlockUpdate", "preplaceCrystal", "breakCrystal",
                        "sendCrystalAttack", "sendGhostAttack", "doPlace", "placeCrystalShared")),
                Map.entry("GuangxiMineModule", List.of("onTick", "onAttackBlock", "requestBreak", "startMine",
                        "begin", "stopBreak", "sendAction", "isAvailable", "fireFinish", "fireSubmitted")),
                Map.entry("AutoMineModule", List.of("onPreTick", "onMineFinish", "onPacket", "lambda$onPacket$", "placeGlass")),
                Map.entry("GuangxiElytraChaseModule", List.of("onPreTick", "onPostMovement")),
                Map.entry("GuangxiBedModule", List.of("onPreTick")),
                Map.entry("GuangxiElytraModule", List.of("onPreTick")),
                Map.entry("AutoUseModule", List.of("onPreTick", "shouldKeepUsing")),
                Map.entry("ArmorFlyModule", List.of("onPreTick", "onFlightWindow", "requested")),
                Map.entry("AutoAnchorModule", List.of("onPreTick")),
                Map.entry("GuangxiPistonModule", List.of("onTick")),
                Map.entry("NoFallModule", List.of("onPreTick", "protects")),
                Map.entry("NoSlowModule", List.of("onTick", "onPacketSend", "shouldCancelConsumeSlow", "isActive")),
                Map.entry("AntiHungerModule", List.of("onPreTick", "onPacketSend")));
        for (var checkpoint : checkpoints.entrySet()) {
            String simpleName = checkpoint.getKey();
            List<ClassInfo> matches = before.values().stream()
                    .filter(info -> info.name.endsWith("/" + simpleName)).toList();
            check(matches.size() == 1, "Expected one protected module: " + simpleName);
            ClassInfo source = matches.getFirst();
            ClassInfo result = required(after, mapping.requiredClass(source.name));
            List<MethodInfo> protectedMethods = source.methods.values().stream()
                    .filter(method -> method.steps.stream().anyMatch(step ->
                            member(step, Opcodes.INVOKESTATIC, HEARTBEAT, "authorized", "()Z"))).toList();
            for (String expected : checkpoint.getValue()) {
                check(protectedMethods.stream().anyMatch(method -> expected.endsWith("$")
                                ? method.name.startsWith(expected) : method.name.equals(expected)),
                        "Input authorization missing from " + simpleName + "." + expected);
            }
            for (MethodInfo sourceMethod : protectedMethods) {
                String mappedName = mapping.requiredMember(source.name, methodMappingKey(sourceMethod));
                MethodInfo outputMethod = requiredMethod(result, mappedName + mapping.descriptor(sourceMethod.descriptor));
                verifyAuthorizationFlow(sourceMethod, outputMethod, owner, authorized, simpleName);
                if (Set.of("onPreTick", "onTick", "onPostMovement", "onFlightWindow").contains(sourceMethod.name)) {
                    verifyDeniedTickPrefix(source, sourceMethod);
                }
            }
        }
    }

    private static String methodMappingKey(MethodInfo method) {
        return method.name + "(" + String.join(",", Arrays.stream(Type.getArgumentTypes(method.descriptor))
                .map(Type::getClassName).toList()) + ")";
    }

    /** Production uses -dontoptimize: preserve the complete guarded control flow, including cleanup paths. */
    private static void verifyAuthorizationFlow(MethodInfo source, MethodInfo output, String heartbeat,
                                                 String authorized, String module) {
        List<Step> original = executable(source), retained = executable(output);
        String label = module + "." + source.name;
        check(original.size() == retained.size(), "Protected action control flow changed: " + label);
        int guards = 0;
        for (int index = 0; index < original.size(); index++) {
            Step expected = original.get(index), actual = retained.get(index);
            check(expected.opcode == actual.opcode, "Protected action instruction changed: " + label + " @" + index);
            if (expected.value instanceof Label sourceTarget) {
                check(actual.value instanceof Label, "Protected action branch lost: " + label);
                check(labelOrdinal(source, sourceTarget) == labelOrdinal(output, (Label) actual.value),
                        "Protected action branch target changed: " + label);
            }
            boolean sourceGuard = member(expected, Opcodes.INVOKESTATIC, HEARTBEAT, "authorized", "()Z");
            boolean outputGuard = member(actual, Opcodes.INVOKESTATIC, heartbeat, authorized, "()Z");
            check(sourceGuard == outputGuard, "Authorization call removed or moved: " + label);
            if (sourceGuard) guards++;
        }
        check(guards > 0, "Protected action has no retained authorization guard: " + label);
    }

    private static int labelOrdinal(MethodInfo method, Label label) {
        int ordinal = 0;
        for (Step step : method.steps) {
            if (step.opcode < 0 && step.value == label) return ordinal;
            if (step.opcode >= 0) ordinal++;
        }
        throw new AssertionError("Missing branch label in " + method.name);
    }

    /** Revocation may release state and restore user controls, but must return before normal tick work. */
    private static void verifyDeniedTickPrefix(ClassInfo owner, MethodInfo method) {
        List<Step> steps = executable(method);
        String label = owner.name + "." + method.name;
        check(steps.size() >= 4 && member(steps.getFirst(), Opcodes.INVOKESTATIC, HEARTBEAT, "authorized", "()Z"),
                "Authorization must precede tick work: " + label);
        Step branch = steps.get(1);
        check(branch.opcode == Opcodes.IFNE, "Denied tick must take a terminating cleanup path: " + label);
        int allowedWork = labelOrdinal(method, (Label) branch.value);
        boolean returned = false;
        Set<String> cleanup = Set.of("stopUnauthorizedMining", "resetGlass", "resetState", "restoreSprint");
        for (int index = 2; index < allowedWork; index++) {
            Step step = steps.get(index);
            if (step.opcode == Opcodes.RETURN) { returned = true; break; }
            check(!(step.value instanceof Label) && step.opcode != Opcodes.INVOKEDYNAMIC,
                    "Denied tick can branch or schedule new business: " + label);
            if (step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE) {
                boolean notification = member(step, Opcodes.INVOKESTATIC, HEARTBEAT, "notifyDeniedOnce", "()V");
                boolean ownCleanup = owner.name.equals(step.owner) && cleanup.contains(step.name) && step.descriptor.equals("()V");
                boolean clear = step.name.equals("clear") && step.descriptor.equals("()V")
                        && (step.owner.startsWith("java/util/") || step.owner.equals("dev/guizhou/features/modules/combat/MiningBreakTracker")
                        || step.owner.equals("dev/guizhou/util/render/placement/PlacementVisuals"));
                check(notification || ownCleanup || clear, "Denied tick calls normal business: " + label + " -> " + step.name);
            }
        }
        check(returned, "Denied tick reaches normal module work: " + label);
    }

    private static void verifyGuardReference(MethodInfo status, String owner, String notice) {
        List<Step> statusSteps = executable(status);
        check(statusSteps.size() >= 3, "Heartbeat status body missing");
        check(member(statusSteps.get(0), Opcodes.GETSTATIC, owner, notice, "[Ljava/lang/String;")
                        && statusSteps.get(1).opcode == Opcodes.ARRAYLENGTH
                        && statusSteps.get(2).opcode == Opcodes.IFNE,
                "Heartbeat status lost intentional guard-array reference");
    }

    private static void verifyReleaseLicense(Map<String, ClassInfo> before,
                                             Map<String, ClassInfo> after, Mapping mapping) {
        String buildConfig = "dev/guizhou/util/BuildConfig";
        FieldInfo development = required(before, buildConfig).fields.get("DEVELOPMENT_BUILDZ");
        check(development != null && (development.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                        == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL) && Integer.valueOf(0).equals(development.value),
                "Production input must compile DEVELOPMENT_BUILD as the constant false");

        String lease = "dev/guizhou/util/license/LicenseLease";
        String signed = "dev/guizhou/util/license/SignedHeartbeat";
        String verdict = signed + "$Verdict";
        String limited = signed + "$LimitedBody";
        for (boolean obfuscated : List.of(false, true)) {
            Map<String, ClassInfo> classes = obfuscated ? after : before;
            String heartbeatOwner = obfuscated ? mapping.requiredClass(HEARTBEAT) : HEARTBEAT;
            String leaseOwner = obfuscated ? mapping.requiredClass(lease) : lease;
            String signedOwner = obfuscated ? mapping.requiredClass(signed) : signed;
            String verdictOwner = obfuscated ? mapping.requiredClass(verdict) : verdict;
            String limitedOwner = obfuscated ? mapping.requiredClass(limited) : limited;
            ClassInfo heartbeat = required(classes, heartbeatOwner);
            MethodInfo initial = requiredMethod(heartbeat, "<clinit>()V");
            check(initial.steps.stream().noneMatch(step -> "net/fabricmc/loader/api/FabricLoader".equals(step.owner)
                            && "isDevelopmentEnvironment".equals(step.name)),
                    "Production heartbeat retained a runtime development exemption");

            MethodInfo authorized = retainedMethod(before, classes, mapping, HEARTBEAT, "authorized()Z", obfuscated);
            String leaseField = obfuscated ? mapping.requiredMember(HEARTBEAT, "LEASE") : "LEASE";
            String leaseAuthorized = obfuscated ? mapping.requiredMember(lease, "authorized()") : "authorized";
            List<Step> gate = executable(authorized);
            check(gate.size() == 3 && member(gate.get(0), Opcodes.GETSTATIC, heartbeatOwner, leaseField, "L" + leaseOwner + ";")
                            && member(gate.get(1), Opcodes.INVOKEVIRTUAL, leaseOwner, leaseAuthorized, "()Z")
                            && gate.get(2).opcode == Opcodes.IRETURN,
                    "Production authorization must directly return the expiring lease decision");

            MethodInfo beat = retainedMethod(before, classes, mapping, HEARTBEAT, "beat()V", obfuscated);
            String verifyName = obfuscated ? mapping.requiredMember(signed, "verify(byte[],java.security.PublicKey)") : "verify";
            String allowedName = obfuscated ? mapping.requiredMember(verdict, "allowed()") : "allowed";
            String successName = obfuscated ? mapping.requiredMember(lease, "verifiedSuccess()") : "verifiedSuccess";
            int verify = uniqueCall(beat, signedOwner, verifyName, "([BLjava/security/PublicKey;)L" + verdictOwner + ";");
            int allowed = uniqueCall(beat, verdictOwner, allowedName, "()Z");
            int success = uniqueCall(beat, leaseOwner, successName, "()V");
            check(verify < allowed && allowed < success, "Heartbeat lease renewal must follow verified allowed verdict");
            check(!flowReaches(beat, 0, success, verify) && !flowReaches(beat, 0, success, allowed),
                    "Heartbeat lease renewal bypasses signature verification or the allowed verdict");
            check(!flowReaches(beat, rejectedBooleanPath(beat, allowed), success, -1),
                    "A signed denial can renew the production lease");
            for (ExceptionRegion region : beat.exceptions) {
                if (labelIndex(beat, region.start) <= verify && verify < labelIndex(beat, region.end)) {
                    check(!flowReaches(beat, labelIndex(beat, region.handler), success, -1),
                            "A signature-verification exception can renew the production lease");
                }
            }

            MethodInfo verifier = retainedMethod(before, classes, mapping, signed,
                    "verify([BLjava/security/PublicKey;)L" + verdict + ";", obfuscated);
            int signature = uniqueCall(verifier, "java/security/Signature", "verify", "([B)Z");
            int createVerdict = uniqueCall(verifier, verdictOwner, "<init>", "(ZLjava/lang/String;)V");
            check(!flowReaches(verifier, 0, createVerdict, signature)
                            && !flowReaches(verifier, rejectedBooleanPath(verifier, signature), createVerdict, -1),
                    "SignedHeartbeat can construct a verdict after a failed or skipped cryptographic verification");

            ClassInfo subscriber = required(classes, limitedOwner);
            check(subscriber.interfaces.contains("java/net/http/HttpResponse$BodySubscriber"),
                    "Bounded heartbeat subscriber lost its HTTP BodySubscriber interface");
            for (String callback : List.of("getBody()Ljava/util/concurrent/CompletionStage;",
                    "onSubscribe(Ljava/util/concurrent/Flow$Subscription;)V",
                    "onNext(Ljava/lang/Object;)V", "onError(Ljava/lang/Throwable;)V", "onComplete()V")) {
                MethodInfo retained = requiredMethod(subscriber, callback);
                check((retained.access & Opcodes.ACC_PUBLIC) != 0, "HTTP subscriber callback lost public access: " + callback);
            }
            // Flow.Subscriber dispatches the erased Object signature. Its typed target may legally be renamed.
            MethodInfo typedNext = retainedMethod(before, classes, mapping, limited,
                    "onNext(Ljava/util/List;)V", obfuscated);
            check((typedNext.access & Opcodes.ACC_PUBLIC) != 0, "Typed HTTP subscriber callback lost public access");
            MethodInfo bridgeNext = requiredMethod(subscriber, "onNext(Ljava/lang/Object;)V");
            // ProGuard may discard ACC_BRIDGE metadata; the erased dispatch implementation must remain exact.
            int bridgeFlags = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | (obfuscated ? 0 : Opcodes.ACC_BRIDGE);
            check((bridgeNext.access & bridgeFlags) == bridgeFlags,
                    "HTTP subscriber erased onNext lost its synthetic bridge contract");
            List<Step> bridge = executable(bridgeNext);
            check(bridge.size() == 5 && bridge.get(0).opcode == Opcodes.ALOAD && Integer.valueOf(0).equals(bridge.get(0).value)
                            && bridge.get(1).opcode == Opcodes.ALOAD && Integer.valueOf(1).equals(bridge.get(1).value)
                            && bridge.get(2).opcode == Opcodes.CHECKCAST && Type.getObjectType("java/util/List").equals(bridge.get(2).value)
                            && member(bridge.get(3), Opcodes.INVOKEVIRTUAL, limitedOwner, typedNext.name, typedNext.descriptor)
                            && bridge.get(4).opcode == Opcodes.RETURN,
                    "HTTP subscriber erased onNext no longer delegates its List argument to the mapped typed callback");
            MethodInfo factory = retainedMethod(before, classes, mapping, signed,
                    "limitedBody()Ljava/net/http/HttpResponse$BodySubscriber;", obfuscated);
            uniqueCall(factory, limitedOwner, "<init>", "()V");
            String factoryName = obfuscated ? mapping.requiredMember(signed, "limitedBody()") : "limitedBody";
            check(heartbeat.methods.values().stream().anyMatch(method -> method.steps.stream().anyMatch(step ->
                            member(step, Opcodes.INVOKESTATIC, signedOwner, factoryName, "()Ljava/net/http/HttpResponse$BodySubscriber;"))),
                    "Heartbeat response handling no longer uses the bounded subscriber factory");
        }
    }

    private static MethodInfo retainedMethod(Map<String, ClassInfo> before, Map<String, ClassInfo> current,
                                             Mapping mapping, String owner, String signature, boolean obfuscated) {
        MethodInfo original = requiredMethod(required(before, owner), signature);
        return obfuscated ? requiredMethod(required(current, mapping.requiredClass(owner)),
                mapping.requiredMember(owner, methodMappingKey(original)) + mapping.descriptor(original.descriptor)) : original;
    }

    private static int uniqueCall(MethodInfo method, String owner, String name, String descriptor) {
        int found = -1;
        for (int index = 0; index < method.steps.size(); index++) {
            Step step = method.steps.get(index);
            if (step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE
                    && owner.equals(step.owner) && name.equals(step.name) && descriptor.equals(step.descriptor)) {
                check(found < 0, "Expected one call to " + owner + "." + name + " in " + method.name);
                found = index;
            }
        }
        check(found >= 0, "Missing call to " + owner + "." + name + " in " + method.name);
        return found;
    }

    private static int labelIndex(MethodInfo method, Label label) {
        for (int index = 0; index < method.steps.size(); index++) {
            Step step = method.steps.get(index);
            if (step.opcode < 0 && step.value == label) return index;
        }
        throw new AssertionError("Missing control-flow label in " + method.name);
    }

    private static int rejectedBooleanPath(MethodInfo method, int predicate) {
        int branchIndex = predicate + 1;
        while (method.steps.get(branchIndex).opcode < 0) branchIndex++;
        Step branch = method.steps.get(branchIndex);
        check(branch.opcode == Opcodes.IFEQ || branch.opcode == Opcodes.IFNE,
                "Security predicate no longer controls a boolean branch: " + method.name);
        return branch.opcode == Opcodes.IFEQ ? labelIndex(method, (Label) branch.value) : branchIndex + 1;
    }

    /** Conservative CFG includes exception handlers: failure paths must never appear to be unreachable. */
    private static boolean flowReaches(MethodInfo method, int start, int goal, int excluded) {
        var todo = new java.util.ArrayDeque<Integer>();
        var seen = new java.util.HashSet<Integer>();
        todo.add(start);
        while (!todo.isEmpty()) {
            int index = todo.removeFirst();
            if (index == excluded || index < 0 || index >= method.steps.size() || !seen.add(index)) continue;
            if (index == goal) return true;
            Step step = method.steps.get(index);
            for (ExceptionRegion region : method.exceptions) {
                if (step.opcode >= 0 && labelIndex(method, region.start) <= index && index < labelIndex(method, region.end))
                    todo.add(labelIndex(method, region.handler));
            }
            if (step.value instanceof Label target && step.opcode >= 0) {
                todo.add(labelIndex(method, target));
                if (step.opcode == Opcodes.GOTO) continue;
            }
            check(step.opcode != Opcodes.TABLESWITCH && step.opcode != Opcodes.LOOKUPSWITCH,
                    "Security CFG switch needs explicit review in " + method.name);
            if (step.opcode >= Opcodes.IRETURN && step.opcode <= Opcodes.RETURN || step.opcode == Opcodes.ATHROW) continue;
            todo.add(index + 1);
        }
        return false;
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
            fields.put(name + descriptor, new FieldInfo(access, descriptor, value));
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

    private record FieldInfo(int access, String descriptor, Object value) {
    }

    private record Step(int opcode, String owner, String name, String descriptor, Object value) {
    }

    private record ExceptionRegion(Label start, Label end, Label handler) {}

    private static final class MethodInfo extends MethodVisitor {
        final int access;
        final String name;
        final String descriptor;
        final List<Step> steps = new ArrayList<>();
        final List<ExceptionRegion> exceptions = new ArrayList<>();

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
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            exceptions.add(new ExceptionRegion(start, end, handler));
        }
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
