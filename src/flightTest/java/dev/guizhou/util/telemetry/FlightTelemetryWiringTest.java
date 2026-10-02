package dev.guizhou.util.telemetry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Bytecode wiring pins for the flight diagnostics lifecycle, in the project's
 * refusal-path style: they verify the real TelemetryManager / flight module
 * structure that the rc.4 review probes exercised behaviourally. Behaviour of
 * the pure accumulator itself is covered by FlightSummaryTest.
 */
public final class FlightTelemetryWiringTest {
    private static final String TELEMETRY = "dev/guizhou/manager/TelemetryManager";
    private static final String SUMMARY = "dev/guizhou/util/telemetry/FlightSummary";
    private static final String BUFFER = "dev/guizhou/util/telemetry/TelemetryBuffer";
    private static final String FLIGHT = "dev/guizhou/features/modules/movement/GuangxiElytraModule";
    private static int checks;

    public static void main(String[] args) throws IOException {
        Map<String, Trace> telemetry = read(TELEMETRY);
        inactivePause(telemetry);
        identityBoundary(telemetry);
        configGuard(telemetry);
        blockedSettlement(telemetry);
        enqueueGuard(telemetry);
        fourHundredHandling(telemetry);
        tickScopedRocketBlock();
        invalidatedFlightObservations(telemetry);
        coupledSnapshot(telemetry);
        coupledMovementBoundaries();
        System.out.println("FlightTelemetryWiringTest passed: " + checks + " checks");
    }

    /** Inactivity must cut the sampling interval even when shorter than the gap threshold. */
    private static void inactivePause(Map<String, Trace> telemetry) {
        Trace tick = method(telemetry, "tickFlight");
        check(tick.hasCall(SUMMARY, "pause"),
                "An inactive tick must pause the window instead of bridging unobserved time");
    }

    /** Same-connection player or world replacement must discard the old window. */
    private static void identityBoundary(Map<String, Trace> telemetry) {
        Trace tick = method(telemetry, "tickFlight");
        check(tick.field("flightPlayer", Opcodes.GETFIELD, -1) >= 0
                        && tick.field("flightLevel", Opcodes.GETFIELD, -1) >= 0,
                "tickFlight must compare the observing player and world identities");
        Trace open = method(telemetry, "openFlightWindow");
        check(open.field("flightPlayer", Opcodes.PUTFIELD, -1) >= 0
                        && open.field("flightLevel", Opcodes.PUTFIELD, -1) >= 0,
                "The window must bind the player and world it was opened with");
    }

    /** Configuration is checked while sampling, not only at settlement. */
    private static void configGuard(Map<String, Trace> telemetry) {
        Trace tick = method(telemetry, "tickFlight");
        int config = tick.call(TELEMETRY, "flightConfig");
        check(config >= 0, "tickFlight must re-evaluate the configuration snapshot during sampling");
        check(tick.field("flightWindowTag", Opcodes.GETFIELD, config) >= 0,
                "The freshly built tag must be compared against the tag the window opened with");
    }

    /** A blocked receiver must stop settlement uploads of already-open windows. */
    private static void blockedSettlement(Map<String, Trace> telemetry) {
        Trace settle = method(telemetry, "settleFlight");
        check(settle.field("flightSummaryBlocked", Opcodes.GETFIELD, -1) >= 0,
                "settleFlight must refuse to upload flight summaries while degraded");
    }

    /** The enqueue path is a second line of defence behind settlement. */
    private static void enqueueGuard(Map<String, Trace> telemetry) {
        Trace enqueue = null;
        for (Map.Entry<String, Trace> entry : telemetry.entrySet()) {
            if (entry.getKey().startsWith("enqueue(") && entry.getKey().endsWith("J)V")) enqueue = entry.getValue();
        }
        check(enqueue != null, "expected the base enqueue(String, JsonObject, long) overload");
        boolean guard = false;
        for (int index = 0; index < enqueue.steps.size(); index++) {
            Step step = enqueue.steps.get(index);
            if (step.opcode == Opcodes.LDC && "flight_summary".equals(step.constant)) {
                guard = enqueue.field("flightSummaryBlocked", Opcodes.GETFIELD, index) >= 0;
            }
        }
        check(guard, "enqueue must drop flight_summary events while degraded");
    }

    /** A 400 marshals to the game thread, matches the sending session, purges and discards. */
    private static void fourHundredHandling(Map<String, Trace> telemetry) {
        Trace flush = method(telemetry, "flush");
        int send = flush.call("java/net/http/HttpClient", "sendAsync");
        check(send >= 0, "flush must send through the async client");
        boolean sessionGuard = false;
        for (int index = 0; index < flush.steps.size(); index++) {
            Step step = flush.steps.get(index);
            if (step.opcode == Opcodes.GETFIELD && "sessionId".equals(step.name) && index < send) {
                sessionGuard = true;
            }
        }
        check(sessionGuard, "The request must capture its session before the 400 can degrade it");
        boolean marshalled = false;
        boolean batchTypeGuard = false;
        boolean examinesTypes = false;
        Trace degradation = null;
        for (Map.Entry<String, Trace> entry : telemetry.entrySet()) {
            if (!entry.getKey().startsWith("lambda$flush")) continue;
            Trace trace = entry.getValue();
            if (trace.steps.stream().anyMatch(step -> "flight_summary".equals(step.constant))) examinesTypes = true;
            int execute = trace.call("net/minecraft/client/Minecraft", "execute");
            if (execute >= 0) {
                marshalled = true;
                boolean capturesBatchType = entry.getKey().substring(0, entry.getKey().indexOf(')')).contains("Z");
                int response = trace.call("java/net/http/HttpResponse", "statusCode");
                batchTypeGuard = capturesBatchType && trace.steps.subList(Math.max(0, response), execute).stream()
                        .anyMatch(step -> step.opcode == Opcodes.IFEQ && trace.target(step) > execute);
            }
            if (entry.getValue().hasCall(TELEMETRY, "discardFlightWindow")) degradation = entry.getValue();
        }
        check(marshalled, "The degradation handler must run on the game thread");
        check(examinesTypes && batchTypeGuard && flush.hasCall("java/util/stream/Stream", "anyMatch"),
                "A rejected batch without flight_summary must not disable later flight summaries");
        check(degradation != null && degradation.field("sessionId", Opcodes.GETFIELD, -1) >= 0
                        && degradation.hasCall(BUFFER, "removeType"),
                "The 400 handler must check its session, discard the open window and purge queued summaries");
    }

    /** The rocket-block reason lives for exactly one module tick. */
    private static void tickScopedRocketBlock() throws IOException {
        Map<String, Trace> flight = read(FLIGHT);
        Trace preTick = method(flight, "onPreTick");
        int clear = -1;
        for (int index = 0; index < preTick.steps.size(); index++) {
            Step step = preTick.steps.get(index);
            if (step.opcode == Opcodes.PUTFIELD && "lastRocketBlock".equals(step.name)) {
                clear = index;
                break;
            }
        }
        check(clear > 0 && preTick.steps.get(clear - 1).opcode == Opcodes.ICONST_0,
                "onPreTick must clear the rocket block observation at the start of the tick");
        int firstTickWork = preTick.call(FLIGHT, "tryRocket");
        if (firstTickWork < 0) firstTickWork = preTick.call(FLIGHT, "buildInputDirection");
        check(firstTickWork < 0 || clear < firstTickWork,
                "The per-tick clear must happen before any rocket or flight work of the tick");
    }

    /** Denied flight and module/session resets cannot keep reporting previous observations. */
    private static void invalidatedFlightObservations(Map<String, Trace> telemetry) throws IOException {
        Trace tick = method(telemetry, "tickFlight");
        int authorized = tick.call("dev/guizhou/manager/HeartbeatManager", "authorized");
        check(authorized >= 0, "Flight observations must stop when the flight authorization gate closes");
        Step branch = tick.steps.get(authorized + 1);
        check(branch.opcode == Opcodes.IFNE,
                "Authorized flight must branch past the observation discard path");
        int permitted = tick.target(branch);
        int discard = tick.callAfter(TELEMETRY, "discardFlightWindow", authorized);
        check(discard > authorized && discard < permitted
                        && tick.steps.get(discard + 1).opcode == Opcodes.RETURN,
                "Denied flight must discard its window and return before sampling");

        Trace reset = method(read(FLIGHT), "resetFlightState");
        int block = reset.field("lastRocketBlock", Opcodes.PUTFIELD, -1);
        check(block > 0 && reset.steps.get(block - 1).opcode == Opcodes.ICONST_0,
                "Module/session reset must clear old rocket-gate observations");
        int stamp = reset.field("lastAppliedTick", Opcodes.PUTFIELD, -1);
        check(stamp > 0 && Integer.valueOf(Integer.MIN_VALUE).equals(reset.steps.get(stamp - 1).constant),
                "Module/session reset must invalidate old velocity sample stamps");
    }

    private static void coupledSnapshot(Map<String, Trace> telemetry) {
        Trace tick = method(telemetry, "tickFlight");
        check(tick.hasCall(FLIGHT, "isCruiseActive"), "Standalone cruise participates in flight observation windows");
        Trace config = method(telemetry, "flightConfig");
        java.util.Set<Object> keys = new java.util.HashSet<>();
        for (Step step : config.steps) if (step.opcode == Opcodes.LDC) keys.add(step.constant);
        check(keys.containsAll(java.util.Set.of("CoupledCruise", "coupled", "auto_fly", "pitch", "climb_pitch",
                "descent_pitch", "smart_climb", "descent_floor", "porpoise", "height_window", "speed_cap", "box_cap", "inset")),
                "The actual snapshot includes the complete bounded cruise whitelist");
    }

    private static void coupledMovementBoundaries() throws IOException {
        Map<String, Trace> methods = read(FLIGHT);
        Trace constructor = method(methods, "<init>");
        boolean off = false;
        for (int i = 0; i + 1 < constructor.steps.size(); i++) {
            if ("CoupledCruise".equals(constructor.steps.get(i).constant)
                    && constructor.steps.get(i + 1).opcode == Opcodes.ICONST_0) off = true;
        }
        check(off, "Cruise is opt-in; existing flight and combat defaults remain unchanged");
        Trace eligible = method(methods, "cruiseEligible");
        String chase = "dev/guizhou/features/modules/combat/GuangxiElytraChaseModule";
        check(eligible.hasCall(chase, "isMaceDiveActive") && eligible.hasCall(chase, "preservesMaceDescent")
                        && eligible.hasCall(FLIGHT, "chaseWantsFlight") && eligible.hasCall(FLIGHT, "chaseActive")
                        && eligible.hasCall(FLIGHT, "needsLandingRecovery"),
                "Cruise gate excludes combat ownership, natural mace falls and landing recovery");
        Trace apply = method(methods, "applyFlightVelocity");
        int consumption = apply.field("pendingCruise", Opcodes.PUTFIELD, -1);
        int project = apply.call("dev/guizhou/util/elytra/CoupledCruise", "apply");
        check(consumption > 0 && apply.steps.get(consumption - 1).opcode == Opcodes.ACONST_NULL
                        && project > consumption, "Pending cruise is consumed once by the movement hook");
        check(apply.field("attachedBoost", Opcodes.GETFIELD, -1) < project
                        && apply.hasCall(FLIGHT, "cruiseEligible"), "Cruise rechecks rocket and ownership before applying");
        check(method(methods, "resetFlightState").hasCall(FLIGHT, "resetCruise"),
                "Shared player/world/correction reset clears cruise state");
    }

    // --- minimal ASM reader in the house style ---

    private static Map<String, Trace> read(String owner) throws IOException {
        Map<String, Trace> methods = new java.util.HashMap<>();
        readClass(owner).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                Trace trace = new Trace();
                methods.put(name + descriptor, trace);
                return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(int opcode) {
                        trace.steps.add(new Step(opcode, null, null, null, null));
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label target) {
                        trace.steps.add(new Step(opcode, null, null, null, target));
                    }

                    @Override
                    public void visitLabel(Label label) {
                        trace.labels.put(label, trace.steps.size());
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        trace.steps.add(new Step(Opcodes.LDC, null, null, value, null));
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        trace.steps.add(new Step(opcode, owner, name, null, null));
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        trace.steps.add(new Step(opcode, owner, name, null, null));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private static Trace method(Map<String, Trace> methods, String name) {
        Trace found = null;
        for (Map.Entry<String, Trace> entry : methods.entrySet()) {
            if (!entry.getKey().startsWith(name + "(")) continue;
            if (found != null) throw new AssertionError("ambiguous method " + name);
            found = entry.getValue();
        }
        if (found == null) throw new AssertionError("missing method " + name);
        return found;
    }

    private static ClassReader readClass(String name) throws IOException {
        try (InputStream stream = FlightTelemetryWiringTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) throw new IOException("Missing named class resource: " + name);
            return new ClassReader(stream);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Trace {
        final java.util.List<Step> steps = new java.util.ArrayList<>();
        final Map<Label, Integer> labels = new IdentityHashMap<>();

        int target(Step step) {
            return labels.get(step.target);
        }

        boolean hasCall(String owner, String name) {
            return callAfter(owner, name, -1) >= 0;
        }

        int call(String owner, String name) {
            return callAfter(owner, name, -1);
        }

        int callAfter(String owner, String name, int after) {
            for (int index = after + 1; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (owner.equals(step.owner) && name.equals(step.name)
                        && step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE) return index;
            }
            return -1;
        }

        int field(String name, int opcode, int after) {
            for (int index = after + 1; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (name.equals(step.name) && opcode == step.opcode) return index;
            }
            return -1;
        }
    }

    private record Step(int opcode, String owner, String name, Object constant, Label target) {}
}
