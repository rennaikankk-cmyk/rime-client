package dev.guizhou.util.diagnostics;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Inspects compiled hooks without loading Minecraft or constructing any upload/credential manager. */
public final class DiagnosticsWiringTest {
    private static final String ROOT = "dev/guizhou/";
    private static final String DIAG = ROOT + "util/diagnostics/ClientDiagnostics";
    private static final String TELEMETRY = ROOT + "manager/TelemetryManager";
    private static int checks;

    public static void main(String[] args) throws Exception {
        moduleFailureIsolation();
        localSubmissionObservation();
        telemetryCountSemantics();
        automaticTelemetryBoundary();
        localCommandBoundary();
        System.out.println("DiagnosticsWiringTest passed: " + checks + " checks");
    }

    private static void moduleFailureIsolation() throws IOException {
        Parsed manager = read(ROOT + "manager/ModuleManager");
        for (String name : List.of("onTick", "onRender2D", "onRender3D")) {
            Trace method = manager.methods.get(name);
            int callback = method.call(ROOT + "features/modules/Module", name);
            boolean protectedCallback = method.handlers.stream().anyMatch(handler ->
                    "java/lang/RuntimeException".equals(handler.type)
                            && method.labels.get(handler.start) <= callback && callback < method.labels.get(handler.end));
            check(callback >= 0 && protectedCallback, "Each " + name + " callback has its own recoverable-exception boundary");
            check(method.call(ROOT + "manager/ModuleManager", "reportCallbackFailure") >= 0,
                    "Callback failures remain observable: " + name);
            check(method.call(DIAG, "startTimer") >= 0 && method.call(DIAG, "endTimer") > callback,
                    "Ordinary module callbacks participate in local timings: " + name);
            check(method.handlers.stream().noneMatch(handler -> "java/lang/Throwable".equals(handler.type)
                            || "java/lang/Error".equals(handler.type)), "Module loop does not suppress fatal Errors");
        }
        Trace failure = manager.methods.get("reportCallbackFailure");
        check(failure.call(DIAG, "count") >= 0 && failure.call("org/slf4j/Logger", "error") >= 0,
                "Failure handling counts and logs instead of silently disabling the module");
        check(failure.call(ROOT + "features/modules/Module", "disable") < 0,
                "Diagnostic failure reporting does not alter enabled configuration");
    }

    private static void localSubmissionObservation() throws IOException {
        Parsed debug = read(ROOT + "features/debug/MoveLimitDebug");
        check(debug.methods.get("<init>").call(ROOT + "util/PacketSendUtil", "addSubmissionListener") >= 0,
                "Move HUD consumes final post-dispatch submission callback");
        check(!debug.methods.containsKey("onPacketSend"), "Move HUD has no premature cancellable send-event counter");
        check(debug.methods.get("onPacketSubmitted").call(ROOT + "util/diagnostics/SubmissionRate", "submitted") >= 0,
                "Move HUD uses normalized local submission rate");
    }

    private static void telemetryCountSemantics() throws IOException {
        Parsed telemetry = read(TELEMETRY);
        Trace attack = null;
        for (String inner : telemetry.innerClasses) {
            if (!inner.startsWith(TELEMETRY + "$")) continue;
            Trace candidate = read(inner).methods.get("onAttack");
            if (candidate != null && candidate.fieldWrite(TELEMETRY, "cwAttacks") >= 0) attack = candidate;
        }
        check(attack != null, "Accepted attack callback increments the engagement attack counter");
        int increment = attack.fieldWrite(TELEMETRY, "cwAttacks");
        check(increment < attack.call(TELEMETRY, "allow") && attack.call(TELEMETRY, "markCombat") < attack.call(TELEMETRY, "allow"),
                "Detailed-payload rate limit cannot suppress attack totals or attack-only engagement windows");
        Trace damage = telemetry.methods.get("handleDamage");
        check(damage.fieldWrite(TELEMETRY, "cwDamageDealt") < damage.call(TELEMETRY, "allow")
                        && damage.fieldWrite(TELEMETRY, "cwDamageTaken") < damage.call(TELEMETRY, "allow"),
                "Both directions count damage events before detail throttling");
        Trace summary = telemetry.methods.get("flushCombatSummary");
        check(summary.constants.contains("damage_dealt") && summary.constants.contains("damage_taken")
                        && summary.constants.stream().noneMatch(value -> value instanceof String key && key.endsWith(".events")),
                "Remote combat_summary retains its fixed legacy field schema");
        Trace tick = telemetry.methods.get("onTick");
        check(tick.call(TELEMETRY, "drainLocalDamage") >= 0
                        && tick.call(TELEMETRY, "drainLocalDamage") < tick.call(TELEMETRY, "canUpload"),
                "Local damage observation runs independently of remote upload availability");
        Trace local = telemetry.methods.get("drainLocalDamage");
        check(local.constants.contains("combat.damage_dealt.events") && local.constants.contains("combat.damage_taken.events")
                        && local.call(TELEMETRY, "canUpload") < 0 && local.call(TELEMETRY, "enqueue") < 0,
                "Clear damage-event units stay in local capture, not remote payloads");
        check(local.call(DIAG, "recordingId") >= 0 && local.call(ROOT + "event/impl/network/PacketEvent$Receive", "isCancelled") >= 0,
                "Queued local damage checks the active capture and final cancellation state");
        check(local.call(TELEMETRY + "$LocalDamage", "level") >= 0 && local.call(TELEMETRY + "$LocalDamage", "player") >= 0,
                "World/player identity fences prevent old damage packets contaminating a respawn or new world");
    }

    private static void localCommandBoundary() throws IOException {
        Parsed command = read(ROOT + "features/commands/impl/DiagnosticsCommand");
        check(command.methods.get("start").call(DIAG, "start") >= 0
                        && command.methods.get("exportLocal").call(DIAG, "export") >= 0,
                "Local command starts and exports the bounded profiler");
        check(command.methods.values().stream().flatMap(method -> method.steps.stream())
                        .noneMatch(step -> TELEMETRY.equals(step.owner) || (step.owner != null && step.owner.startsWith("java/net/"))),
                "Local command cannot enable telemetry or perform network operations");
    }

    private static void automaticTelemetryBoundary() throws IOException {
        Parsed telemetry = read(TELEMETRY);
        Trace gate = telemetry.methods.get("canUpload");
        check(gate.call(ROOT + "util/telemetry/TelemetryCapability", "validAt") >= 0,
                "Automatic uploads still require a non-expired embedded capability");
        check(telemetry.methods.values().stream().flatMap(method -> method.steps.stream())
                        .noneMatch(step -> TELEMETRY.equals(step.owner) && "active".equals(step.name))
                        && !telemetry.methods.containsKey("setActive"),
                "The retired on/off preference cannot silently prevent automatic upload");
        Trace state = telemetry.methods.get("loadState");
        check(state.constants.contains("install") && !state.constants.contains("enabled")
                        && state.call(TELEMETRY, "saveState") >= 0,
                "State migration preserves installation identity and ignores old opt-out fields");
        Trace save = telemetry.methods.get("saveState");
        check(save.constants.contains("install") && !save.constants.contains("enabled"),
                "Persisted state no longer claims to save an upload toggle");
        String status = ROOT + "util/telemetry/TelemetryUploadStatus";
        Trace flush = telemetry.methods.get("flush");
        check(flush.call(status, "started") >= 0 && flush.call(status, "completed") >= 0
                        && telemetry.methods.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith("lambda$flush")
                                && entry.getValue().call(status, "completed") >= 0),
                "Synchronous setup errors and asynchronous HTTP outcomes both update visible status");
        check(flush.call("java/net/http/HttpResponse$BodyHandlers", "discarding") >= 0,
                "Receipt visibility does not retain server bodies or add fields to upload payloads");
        Parsed command = read(ROOT + "features/commands/impl/TelemetryCommand");
        Trace builder = command.methods.get("createArgumentBuilder");
        check(builder.constants.contains("status") && !builder.constants.contains("on") && !builder.constants.contains("off"),
                "Telemetry command exposes status only; old off cannot report a false stopped state");
        Trace display = command.methods.get("status");
        check(display.call(TELEMETRY, "uploadStatus") >= 0 && display.call(TELEMETRY, "capabilityExpired") >= 0,
                "Users can inspect receiver outcomes and unavailable capability states");
    }

    private record Step(String owner, String name, int opcode) {}
    private record Handler(Label start, Label end, String type) {}
    private static final class Trace extends MethodVisitor {
        final List<Step> steps = new ArrayList<>();
        final Map<Label, Integer> labels = new IdentityHashMap<>();
        final List<Handler> handlers = new ArrayList<>();
        final List<Object> constants = new ArrayList<>();
        Trace() { super(Opcodes.ASM9); }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            steps.add(new Step(owner, name, opcode));
        }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            steps.add(new Step(owner, name, opcode));
        }
        @Override public void visitLabel(Label label) { labels.put(label, steps.size()); }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            handlers.add(new Handler(start, end, type));
        }
        @Override public void visitLdcInsn(Object value) { constants.add(value); }
        int call(String owner, String name) {
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (owner.equals(step.owner) && name.equals(step.name)
                        && step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE) return i;
            }
            return -1;
        }
        int fieldWrite(String owner, String name) {
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (owner.equals(step.owner) && name.equals(step.name) && step.opcode == Opcodes.PUTFIELD) return i;
            }
            return -1;
        }
    }

    private static final class Parsed extends ClassVisitor {
        final Map<String, Trace> methods = new HashMap<>();
        final List<String> innerClasses = new ArrayList<>();
        Parsed() { super(Opcodes.ASM9); }
        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            Trace trace = new Trace();
            methods.put(name, trace);
            return trace;
        }
        @Override public void visitInnerClass(String name, String outerName, String innerName, int access) { innerClasses.add(name); }
    }

    private static Parsed read(String name) throws IOException {
        try (InputStream input = DiagnosticsWiringTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new IOException("Missing compiled class: " + name);
            Parsed parsed = new Parsed();
            new ClassReader(input).accept(parsed, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            return parsed;
        }
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
