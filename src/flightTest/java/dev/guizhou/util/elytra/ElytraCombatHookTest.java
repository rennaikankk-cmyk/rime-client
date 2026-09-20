package dev.guizhou.util.elytra;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.EventBus;
import dev.guizhou.event.system.Subscribe;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ElytraCombatHookTest {
    private static final String CHASE = "dev/guizhou/features/modules/combat/GuangxiElytraChaseModule";
    private static final String MACE = "net/minecraft/world/item/MaceItem";
    private static final String ROTATION = "dev/guizhou/manager/RotationManager";
    private static final String SILENT_WINDOW = ROTATION + "$SilentArbitration";
    private static final String PRE_TICK_DESC = "(Ldev/guizhou/event/impl/entity/player/PreTickEvent;)V";
    private static final String DAMAGE = "net/minecraft/network/protocol/game/ClientboundDamageEventPacket";
    private static final String FLIGHT = "dev/guizhou/features/modules/movement/GuangxiElytraModule";
    private static int checks;

    private ElytraCombatHookTest() {
    }

    public static void main(String[] args) throws IOException, ReflectiveOperationException {
        verifyVanillaSmashGate();
        verifyChaseSmashGate();
        verifyAttackRotation();
        verifyAcceptedAttack();
        verifySilentWindowBoundary();
        verifySilentWindowBehavior();
        verifyEventDispatchOrder();
        verifyDamageFeedback();
        verifyImmediateAttack();
        verifyDefenseIntegration();
        verifySpearChargeContinuity();
        verifyDiveIntegration();
        verifyMaceDescentOwnership();
        verifyCombatRegressionWiring();
        verifyPlanningSpeedWiring();
        System.out.println("ElytraCombatHookTest passed: " + checks + " checks");
    }

    private static void verifyPlanningSpeedWiring() throws IOException {
        // Reachability must be evaluated against the speed the flight controller is
        // about to request, not the envelope-clamped instantaneous speed (measured
        // applied 1.4-2.4 b/t vs the ~2.5 reachable threshold -> climb never commits).
        Map<String, Trace> mace = read(CHASE + "$MaceBehavior");
        Trace planning = method(mace, "planningSpeed");
        check(planning.hasCall("dev/guizhou/features/modules/movement/GuangxiElytraModule", "lastRequestedBpt"),
                "planningSpeed must consider the requested cruise speed, not just the clamped instantaneous one");
        check(planning.hasCall("java/lang/Math", "max"),
                "planningSpeed must take the larger of requested and instantaneous speed");
    }

    private static void verifyCombatRegressionWiring() throws IOException {
        Map<String, Trace> chase = read(CHASE);
        Map<String, Trace> mace = read(CHASE + "$MaceBehavior");
        Map<String, Trace> spear = read(CHASE + "$SpearBehavior");
        check(method(mace, "inject").hasCall("dev/guizhou/util/elytra/MaceApproach", "pullUpIntent"),
                "Custom climb pitch must use the magnitude-preserving intent transformation");
        for (String name : List.of("tickFollow", "tickDive")) {
            check(method(mace, name).hasCall(CHASE + "$MaceBehavior", "aimDiveExit"),
                    "Glide exit must retain dive steering from entry through flag acknowledgement");
        }
        Trace exit = method(mace, "aimDiveExit");
        check(exit.hasCall("net/minecraft/client/player/LocalPlayer", "isFallFlying")
                        && exit.hasCall(ROTATION, "submit") && exit.hasCall(ROTATION, "cancel"),
                "Dive steering ends after the real glide flag ends");
        for (String name : List.of("onPreTick", "resetRuntime")) {
            check(method(chase, name).hasCall(CHASE, "clearDiveAim"),
                    "Paused, disabled, or changed-target ticks must not retain a dive rotation");
        }
        check(method(spear, "inject").hasCall("dev/guizhou/util/elytra/FlightVelocity", "preserveDirection"),
                "Spear steering must not independently expand the climb axis");
        check(method(spear, "toTargetAtLeast").hasCall(CHASE + "$SpearBehavior", "approachHeading"),
                "Ground approaches must pass through the shallow-lane planner");
        for (String name : List.of("targetPos", "tooClose")) {
            check(method(spear, name).hasCall(CHASE, "targetPacketOffset"),
                    "Spear steering and retreat must not use a stale rendered target position");
        }
        check(method(chase, "aimSpear").hasCall(CHASE, "targetPacketOffset"),
                "Spear aim must include the same packet position as navigation");
        check(method(chase, "aimSpear").hasCall("dev/guizhou/util/elytra/SpearCombat", "attackEye"),
                "Spear aim must account for the server eye origin during owned armor flight");
        check(method(chase, "aimSpear").hasCall(FLIGHT, "isArmorSwapActive"),
                "Standing-eye compensation must depend on an active owned armor cycle");
        for (String name : List.of("tickFollow", "strikePlan", "tryAttack", "shouldStartDive", "tickDive")) {
            check(method(mace, name).hasCall(CHASE, "observedTargetBox"),
                    "Mace approach, attack and recovery must agree on the latest received target box: " + name);
        }
        check(method(chase, "attack").hasCall(CHASE, "observedTargetBox"),
                "Final reach and accepted-rotation ray must test the observed box used by the approach");
        check(method(mace, "shouldStartDive").hasCall("dev/guizhou/util/elytra/AerialIntercept", "dropApproachRange"),
                "Fast opposing passes need a speed-aware glide-exit approach gate");
        Trace standalone = method(read(FLIGHT), "serviceIndependentArmorFly");
        for (String forbidden : List.of("enable", "alignLook", "applyFlightVelocity", "buildSetbackTrigger")) {
            check(!standalone.hasCall(FLIGHT, forbidden), "Independent armor must not take direction or exploit ownership");
        }
        check(standalone.hasCall(FLIGHT, "tryRocket"), "Independent armor must service rockets in its wing window");
    }

    private static void verifyVanillaSmashGate() throws IOException {
        Trace smash = method(read(MACE), "canSmashAttack");
        for (double distance : new double[]{0.0, 1.0, 1.5, Math.nextUp(1.5), 2.0, 20.0}) {
            for (boolean flying : new boolean[]{false, true}) {
                check(evaluateSmash(smash, distance, flying) == (distance > 1.5 && !flying),
                        "Vanilla smash eligibility changed for fall=" + distance + ", flying=" + flying);
            }
        }
    }

    // Interpret this small vanilla predicate without loading or initializing Minecraft classes.
    private static boolean evaluateSmash(Trace trace, double distance, boolean flying) {
        List<Object> stack = new ArrayList<>();
        for (int pc = 0, visited = 0; pc < trace.steps.size() && visited++ < 100; pc++) {
            Step step = trace.steps.get(pc);
            switch (step.opcode) {
                case Opcodes.ALOAD -> stack.add("entity");
                case Opcodes.GETFIELD -> {
                    requireMember(step, "net/minecraft/world/entity/LivingEntity", "fallDistance");
                    pop(stack);
                    stack.add(distance);
                }
                case Opcodes.INVOKEVIRTUAL -> {
                    requireMember(step, "net/minecraft/world/entity/LivingEntity", "isFallFlying");
                    pop(stack);
                    stack.add(flying ? 1 : 0);
                }
                case Opcodes.LDC -> stack.add(step.constant);
                case Opcodes.DCONST_0 -> stack.add(0.0);
                case Opcodes.DCONST_1 -> stack.add(1.0);
                case Opcodes.ICONST_0 -> stack.add(0);
                case Opcodes.ICONST_1 -> stack.add(1);
                case Opcodes.DCMPL, Opcodes.DCMPG -> {
                    double right = ((Number) pop(stack)).doubleValue();
                    double left = ((Number) pop(stack)).doubleValue();
                    stack.add(Double.compare(left, right));
                }
                case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLE, Opcodes.IFLT,
                     Opcodes.IFGE, Opcodes.IFGT -> {
                    int value = ((Number) pop(stack)).intValue();
                    boolean jump = switch (step.opcode) {
                        case Opcodes.IFEQ -> value == 0;
                        case Opcodes.IFNE -> value != 0;
                        case Opcodes.IFLE -> value <= 0;
                        case Opcodes.IFLT -> value < 0;
                        case Opcodes.IFGE -> value >= 0;
                        default -> value > 0;
                    };
                    if (jump) pc = trace.target(step) - 1;
                }
                case Opcodes.GOTO -> pc = trace.target(step) - 1;
                case Opcodes.IRETURN -> {
                    return ((Number) pop(stack)).intValue() != 0;
                }
                default -> throw new AssertionError("Unreviewed vanilla smash instruction: " + step.opcode);
            }
        }
        throw new AssertionError("Vanilla smash predicate did not return");
    }

    private static void verifyChaseSmashGate() throws IOException {
        Trace chase = method(read(CHASE), "willUseMaceAttack");
        int gate = chase.call(MACE, "canSmashAttack");
        Step branch = chase.steps.get(gate + 1);
        check(branch.opcode == Opcodes.IFEQ, "A failed vanilla smash gate must branch away from success");
        assertFalseReturn(chase, chase.target(branch), "Failed vanilla smash gate");
        check(chase.steps.stream().anyMatch(step -> step.opcode == Opcodes.GETFIELD
                        && "fallDistance".equals(step.name)),
                "The configurable extra fall threshold must still be checked");
    }

    private static void verifyAttackRotation() throws IOException {
        Trace attack = method(read(CHASE), "attack");
        int submit = attack.call(ROTATION, "submit");
        int accepted = attack.call(CHASE, "acceptPendingRotation");
        int pitch = attack.field(CHASE, "sentPitch", Opcodes.GETFIELD);
        int yaw = attack.field(CHASE, "sentYaw", Opcodes.GETFIELD);
        int look = attack.call("net/minecraft/world/phys/Vec3", "directionFromRotation");
        int clip = attack.call("net/minecraft/world/phys/AABB", "clip");
        int empty = attack.call("java/util/Optional", "isEmpty");
        int send = attack.call("net/minecraft/client/multiplayer/MultiPlayerGameMode", "attack");
        check(submit < accepted && accepted < pitch && accepted < yaw && pitch < look && yaw < look
                        && look < clip && clip < empty && empty < send,
                "Attack must submit rotation and verify the resulting server ray before sending");
        for (int required : new int[]{submit, accepted, pitch, yaw, look}) {
            check(!canReach(attack, 0, send, required, new HashSet<>()),
                    "A path to attack bypasses rotation submission or actual server angles");
        }
        Step miss = attack.steps.get(empty + 1);
        check(miss.opcode == Opcodes.IFEQ, "Only a nonempty target-box intersection may continue");
        assertFalseReturn(attack, empty + 2, "A ray missing the target box");
        int contains = attack.call("net/minecraft/world/phys/AABB", "contains");
        check(look < contains && contains < clip,
                "The only geometric shortcut must check whether the eye is inside the target box");
    }

    private static void verifyAcceptedAttack() throws IOException {
        Map<String, Trace> methods = read(CHASE);
        Trace attack = method(methods, "attack");
        int send = attack.call("net/minecraft/client/multiplayer/MultiPlayerGameMode", "attack");
        int cancelled = attack.call("dev/guizhou/event/impl/network/PacketEvent$Send", "isCancelled");
        int arm = attack.call("dev/guizhou/util/elytra/CombatHitWindow", "arm");
        int tick = attack.field(CHASE, "lastAttackTick", Opcodes.PUTFIELD);
        check(send < cancelled && cancelled < tick && tick < arm,
                "Only an accepted attack may start the hit confirmation window");
        check(!canReach(attack, 0, arm, cancelled, new HashSet<>()),
                "Cancelled dispatch must not arm a pending hit");
        Step branch = attack.steps.get(cancelled + 1);
        check(branch.opcode == Opcodes.IFEQ, "Only a noncancelled attack may continue");
        assertFalseReturn(attack, cancelled + 2, "Cancelled attack");
        check(method(methods, "onPacketSend").priority == Integer.MIN_VALUE,
                "Outgoing attack and rotation observation must follow packet cancellation policies");
        Trace rotation = method(methods, "acceptPendingRotation");
        int rotateCancelled = rotation.call("dev/guizhou/event/impl/network/PacketEvent$Send", "isCancelled");
        int acceptedYaw = rotation.field(CHASE, "sentYaw", Opcodes.PUTFIELD);
        check(!canReach(rotation, 0, acceptedYaw, rotateCancelled, new HashSet<>()),
                "Unaccepted rotation must not become the attack ray");
    }

    /** The silent arbitration window must not outlive its tick: the post-movement attack
     * submission is the closing tick's last opportunity, never the next tick's gate. */
    private static void verifySilentWindowBoundary() throws IOException {
        Map<String, Trace> rotation = read(ROTATION);
        Trace submit = method(rotation, "submit");
        int blocks = submit.call(SILENT_WINDOW, "blocks");
        int send = submit.call(ROTATION, "performSilent");
        int accept = submit.call(SILENT_WINDOW, "accepted");
        int sync = submit.field(ROTATION, "silentSyncRequired", Opcodes.PUTFIELD);
        check(blocks < send && send < accept && accept < sync,
                "Silent submit must arbitrate on the window before sending and still mark the sync after");

        Trace boundary = null;
        Trace updater = null;
        for (Map.Entry<String, Trace> entry : rotation.entrySet()) {
            if (!entry.getKey().endsWith(PRE_TICK_DESC)) continue;
            if (entry.getValue().priority == Integer.MAX_VALUE) boundary = entry.getValue();
            if (entry.getValue().priority == Integer.MIN_VALUE) updater = entry.getValue();
        }
        check(boundary != null,
                "A MAX_VALUE tick-boundary listener must close the silent window before combat modules run");
        int closes = 0;
        for (Step step : boundary.steps) {
            if (step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE) closes++;
            check(step.opcode != Opcodes.PUTFIELD || !"silentSyncRequired".equals(step.name),
                    "The tick boundary must not write the orientation sync marker");
        }
        check(closes == 1 && boundary.hasCall(SILENT_WINDOW, "close"),
                "The tick boundary does nothing but close the arbitration window");
        check(updater != null, "The MIN_VALUE rotation updater stays the last pre-tick listener");

        Map<String, Trace> mixinRotation = read("dev/guizhou/mixin/entity/MixinLocalPlayerRotation");
        Trace tail = method(mixinRotation, "guizhouware$spoofRotationTail");
        check(tail.hasCall(ROTATION, "setSilentSyncRequired") && tail.hasCall(ROTATION, "resetSilentTick"),
                "The sendPosition tail still consumes the sync marker and closes the window");

        Map<String, Trace> clientPlayer = read("dev/guizhou/mixin/entity/MixinClientPlayerEntity");
        Trace postHook = method(clientPlayer, "tickHook3");
        check(postHook.hasCall("dev/guizhou/event/system/EventBus", "post"),
                "The post-movement hook posts the movement event");
        // The ordering evidence lives in the @Inject metadata, not in instructions.
        String[] postTarget = new String[1];
        String[] postShift = new String[1];
        readClass("dev/guizhou/mixin/entity/MixinClientPlayerEntity").accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("tickHook3")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String member) {
                                // @Inject#at is an At[] array member.
                                if (!member.equals("at")) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public AnnotationVisitor visitAnnotation(String nested, String descriptor) {
                                        return new AnnotationVisitor(Opcodes.ASM9) {
                                            @Override
                                            public void visit(String member, Object value) {
                                                if (member.equals("target")) postTarget[0] = String.valueOf(value);
                                            }

                                            @Override
                                            public void visitEnum(String member, String descriptor, String value) {
                                                if (member.equals("shift")) postShift[0] = value;
                                            }
                                        };
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(postTarget[0] != null && postTarget[0].contains("sendPosition") && "AFTER".equals(postShift[0]),
                "The post-movement event fires only after the sendPosition invoke returns");
    }

    /** Drives the real arbitration window through the reported cross-tick sequence. */
    private static void verifySilentWindowBehavior() throws ReflectiveOperationException {
        Class<?> window;
        try {
            window = Class.forName(SILENT_WINDOW.replace('/', '.'));
        } catch (ClassNotFoundException missing) {
            check(false, "The pure silent-arbitration window is missing: the cross-tick gate is unfixed");
            return;
        }
        Object gate = window.getDeclaredConstructor().newInstance();
        var blocks = window.getMethod("blocks", int.class);
        var accept = window.getMethod("accepted", int.class);
        var close = window.getMethod("close");
        check(!(Boolean) blocks.invoke(gate, 70), "A fresh window blocks nothing");
        accept.invoke(gate, 70);
        check((Boolean) blocks.invoke(gate, 70) && (Boolean) blocks.invoke(gate, 60),
                "Same window: equal and lower priorities are rejected");
        check(!(Boolean) blocks.invoke(gate, 80), "Same window: a higher priority is accepted");
        accept.invoke(gate, 80);
        check((Boolean) blocks.invoke(gate, 75), "After a higher accept, medium priorities stay gated");
        close.invoke(gate);
        accept.invoke(gate, 70);
        check((Boolean) blocks.invoke(gate, 70),
                "Inside the post-movement window the same priority is still arbitrated");
        close.invoke(gate);
        check(!(Boolean) blocks.invoke(gate, 70),
                "The next tick's pre-movement silent must not be blocked by the old tick's gate");
        for (Field field : window.getDeclaredFields()) {
            check(!field.getName().contains("sync"),
                    "The arbitration window must not own the orientation sync marker");
        }
    }

    /** Premise check with the real dispatcher: the boundary outranks combat listeners. */
    private static void verifyEventDispatchOrder() {
        EventBus bus = new EventBus();
        List<String> order = new ArrayList<>();
        Object combat = new Object() {
            @Subscribe(priority = 100)
            private void onPreTick(PreTickEvent event) { order.add("combat"); }
        };
        Object armor = new Object() {
            @Subscribe(priority = 150)
            private void onPreTick(PreTickEvent event) { order.add("armor"); }
        };
        Object boundary = new Object() {
            @Subscribe(priority = Integer.MAX_VALUE)
            private void onPreTick(PreTickEvent event) { order.add("boundary"); }
        };
        bus.register(combat);
        bus.register(armor);
        bus.register(boundary);
        bus.post(new PreTickEvent());
        check(order.equals(List.of("boundary", "armor", "combat")),
                "EventBus must dispatch the MAX_VALUE tick boundary before armor and combat listeners");
    }

    private static void verifyDamageFeedback() throws IOException {
        Map<String, Trace> methods = read(CHASE);
        List<Trace> handlers = methods.values().stream()
                .filter(trace -> trace.hasCall(DAMAGE, "sourceType")).toList();
        check(handlers.size() == 1, "Expected one client-thread typed damage handler");
        Trace feedback = handlers.getFirst();
        Set<String> sources = new HashSet<>();
        for (Step step : feedback.steps) {
            if (step.opcode == Opcodes.GETSTATIC
                    && "net/minecraft/world/damagesource/DamageTypes".equals(step.owner)) {
                sources.add(step.name);
            }
        }
        check(sources.containsAll(Set.of("MACE_SMASH", "PLAYER_ATTACK", "SPEAR")),
                "Feedback must recognize actual smash, ordinary melee and spear damage types");
        int direct = feedback.call(DAMAGE, "sourceDirectId");
        int typed = feedback.call(DAMAGE, "sourceType");
        int consume = feedback.call("dev/guizhou/util/elytra/CombatHitWindow", "consume");
        check(direct < typed && typed < consume, "Attribution must precede mace confirmation consumption");
        int hit = feedback.callAfter(CHASE + "$Behavior", "onHit", consume);
        Step consumed = feedback.steps.get(consume + 1);
        check(consumed.opcode == Opcodes.IFEQ && feedback.target(consumed) > hit,
                "An unmatched or consumed mace confirmation must skip the mace hit callback");
        check(feedback.hasCall(CHASE, "spearPhase"), "Spear damage feedback must require an active spear phase");
        Trace outgoing = method(methods, "onPacketSend");
        check(outgoing.steps.stream().noneMatch(step -> step.opcode == Opcodes.INSTANCEOF
                        && "net/minecraft/network/protocol/game/ServerboundSetCarriedItemPacket".equals(step.owner)),
                "Temporary firework and restored-hotbar selection must not erase an already-sent mace confirmation");
        check(outgoing.steps.stream().filter(step -> step.opcode == Opcodes.INSTANCEOF
                        && "net/minecraft/network/protocol/game/ServerboundInteractPacket".equals(step.owner)).count() == 2,
                "The accepted owned attack must remain distinct from intervening entity interactions");
    }

    private static void verifyImmediateAttack() throws IOException {
        for (String owner : List.of(CHASE, CHASE + "$MaceBehavior", CHASE + "$MxsBehavior")) {
            readClass(owner).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    check(!name.equals("attackFlag"), "A stale cross-tick attackFlag must not return in " + owner);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        Trace follow = method(read(CHASE + "$MaceBehavior"), "tickFollow");
        Trace attempt = method(read(CHASE + "$MaceBehavior"), "tryAttack");
        int attack = attempt.call(CHASE, "attack");
        int wait = attempt.callAfter(CHASE + "$MaceBehavior", "tickWaitAttack", attack);
        check(attack < wait, "Mace follow must attempt attack in the current tick before waiting");
        check(attempt.steps.stream().anyMatch(step -> step.opcode == Opcodes.PUTFIELD
                        && "lastAttackSentTick".equals(step.name)),
                "The confirmation timeout must begin at the actual attack attempt");
        int resumed = attempt.callAfter(CHASE + "$MaceBehavior", "resumeAfterPass", attack);
        int waitState = attempt.field(CHASE + "$MaceBehavior", "state", Opcodes.PUTFIELD);
        check(attack < waitState && waitState < resumed && resumed < wait,
                "A natural fall hit must release FOLLOW ownership and resume flight before computing its next climb");
        int missedPass = follow.call("dev/guizhou/util/elytra/MacePassTracker", "shouldPullUp");
        int tryAttack = follow.call(CHASE + "$MaceBehavior", "tryAttack");
        check(tryAttack < missedPass && !canReach(follow, tryAttack + 2, missedPass, -1, new HashSet<>()),
                "A current successful attack must win over exhausted-pass recovery");
        Trace post = method(read(CHASE), "onPostMovement");
        check(post.hasCall(CHASE + "$MaceBehavior", "tryAttack")
                        && post.hasCall("dev/guizhou/event/impl/entity/player/UpdateWalkingPlayerEvent", "getStage")
                        && post.hasCall("dev/guizhou/manager/HeartbeatManager", "authorized")
                        && post.hasCall(CHASE, "paused"),
                "Post-movement reach checks share the guarded attack transaction");
        Trace restart = method(read(CHASE + "$MaceBehavior"), "restartPass");
        int pullUpState = restart.field(CHASE + "$MaceBehavior", "state", Opcodes.PUTFIELD);
        int resumedMiss = restart.call(CHASE + "$MaceBehavior", "resumeAfterPass");
        int climbedMiss = restart.call(CHASE + "$MaceBehavior", "tickPullUp");
        check(pullUpState < resumedMiss && resumedMiss < climbedMiss,
                "A missed pass must release descent ownership before same-tick glide recovery and climb");
        Trace resume = method(read(CHASE + "$MaceBehavior"), "resumeAfterPass");
        int ownsDive = resume.call("dev/guizhou/util/elytra/MaceDiveController", "isActive");
        int restartFlight = resume.call(FLIGHT, "finishDiveRecovery");
        Step owned = resume.steps.get(ownsDive + 1);
        check(owned.opcode == Opcodes.IFNE
                        && !canReach(resume, resume.target(owned), restartFlight, -1, new HashSet<>()),
                "Natural-pass flight recovery must not double-restart an owned equipment dive");
    }

    private static void verifySpearChargeContinuity() throws IOException {
        String weapon = "dev/guizhou/util/elytra/ElytraWeaponController";
        String spear = CHASE + "$SpearBehavior";
        Map<String, Trace> methods = read(CHASE);
        Trace tick = method(read(spear), "tick");
        long preparations = tick.steps.stream().filter(step -> CHASE.equals(step.owner)
                && "prepareWeapon".equals(step.name)).count();
        check(preparations == 1, "Pull-over must not perform a second preparation that releases spear charge");
        int prepare = tick.call(CHASE, "prepareWeapon");
        check(tick.call(weapon, "isHoldingUse") < prepare,
                "An existing owned charge must survive crossing the charge-start distance");
        check(tick.field(spear, "state", Opcodes.GETFIELD) > prepare,
                "Movement state must not disqualify an otherwise valid spear charge");
        check(tick.call(FLIGHT, "needsSpearBoostRefresh") < prepare,
                "Required firework refresh must retain its charge-release opportunity");

        Trace reset = method(methods, "resetDefenseBehavior");
        int phase = reset.call(CHASE, "spearPhase");
        int release = reset.call(weapon, "release");
        Step branch = reset.steps.get(phase + 1);
        check(branch.opcode == Opcodes.IFEQ, "Defense reset must branch on the active spear phase");
        check(!canReach(reset, phase + 2, release, -1, new HashSet<>()),
                "Defense movement changes must preserve the current spear use and visible slot");
        check(canReach(reset, reset.target(branch), release, -1, new HashSet<>()),
                "Non-spear defense changes must still release weapon ownership");
        check(reset.hasCall(CHASE + "$Behavior", "resetHeading") && reset.hasCall(spear, "enter"),
                "Preserving MXS spear phase must still reset movement state and heading");
        Trace upkeep = method(methods, "maintainDefensiveSpearUse");
        check(upkeep.hasCall(CHASE, "spearPhase") && upkeep.hasCall(weapon, "isHoldingUse")
                        && upkeep.hasCall(FLIGHT, "needsSpearBoostRefresh")
                        && upkeep.hasCall(CHASE, "prepareWeapon"),
                "Defense may maintain only an owned spear, respecting expiry and firework refresh");
        check(!upkeep.hasCall(CHASE, "aimSpear") && !upkeep.hasCall(CHASE, "attack"),
                "Defensive use upkeep must not add an attack or stale aim");
        Trace defense = method(methods, "updateDefense");
        check(defense.hasCall(CHASE, "resetDefenseBehavior")
                        && defense.hasCall(CHASE, "maintainDefensiveSpearUse")
                        && defense.hasCall(CHASE, "clearSpearAim"),
                "The real defense path must preserve use while discarding offensive rotation");
    }

    private static void verifyDefenseIntegration() throws IOException {
        Map<String, Trace> methods = read(CHASE);
        Trace tick = method(methods, "onPreTick");
        int defense = tick.call(CHASE, "updateDefense");
        int attackBehavior = tick.call(CHASE + "$Behavior", "tick");
        check(defense < attackBehavior, "Defense decisions must precede any attack behavior");
        check(!canReach(tick, 0, attackBehavior, defense, new HashSet<>()),
                "No attack path may skip the defense decision");
        check(tick.steps.get(defense + 1).opcode == Opcodes.IFEQ
                        && tick.steps.get(defense + 2).opcode == Opcodes.RETURN,
                "An active defense must end the tick before attack behavior");
        Trace gate = method(methods, "blocksCrystalCombat");
        check(gate.hasCall("dev/guizhou/util/elytra/CombatHitWindow", "pending"),
                "Crystal actions must protect the pending mace confirmation");
        check(gate.hasCall("dev/guizhou/util/elytra/ElytraWeaponController", "isHoldingUse")
                        && gate.hasCall(CHASE, "isUsingSpear"),
                "Crystal actions must protect both owned and manually charged spear use");
        check(!gate.hasCall(CHASE, "isWeaponControlActive"),
                "Far pursuit must not inherit the broad hotbar ownership lock");
        Trace flight = method(methods, "onElytra");
        int injection = flight.call(CHASE + "$Behavior", "inject");
        int path = flight.call(CHASE, "safeFlightVector");
        int apply = flight.callAfter("dev/guizhou/util/elytra/FlightVelocity", "set", path);
        check(injection < path && path < apply, "Route filtering must cover the final flight request");
        check(flight.hasCall("dev/guizhou/util/elytra/FlightVelocity", "requestBraking"),
                "A fully blocked path must explicitly request braking");
        Trace route = method(methods, "safeFlightVector");
        check(route.hasCall(CHASE, "clearSpearAim"), "Obstacle avoidance must release the spear aim priority");
        String planner = "dev/guizhou/util/elytra/FlightRoutePlanner";
        String result = planner + "$Result";
        int lookahead = route.call(FLIGHT, "navigationLookahead");
        int planned = route.call(planner, "routeTo");
        int distance = route.call(result, "distance");
        int boundedSpeed = route.callAfter("java/lang/Math", "min", distance);
        check(lookahead < planned && planned < distance && distance < boundedSpeed,
                "Chase must plan toward its destination and cap speed before overshooting a route waypoint");
        check(route.handles.stream().anyMatch(handle -> FLIGHT.equals(handle.getOwner())
                        && "clearNavigationPath".equals(handle.getName())),
                "Route planning must use the flight module's full-body collision sweep");
        check(!route.hasCall("dev/guizhou/util/elytra/ElytraPhysics", "simulateGlide"),
                "Current momentum must not reject every geometrically valid detour before trajectory recovery");
        check(flight.hasCall("dev/guizhou/util/elytra/FlightVelocity", "requestAvoidance"),
                "A detour must retain its safety intent through the flight handoff");
        Map<String, Trace> movementMethods = read(FLIGHT);
        Trace movement = method(movementMethods, "onPreTick");
        int chaseRequest = movement.call(CHASE, "onElytra");
        int terrainSafety = movement.call(FLIGHT, "applyTerrainSafety");
        int brake = movement.call("dev/guizhou/util/elytra/FlightVelocity", "isBraking");
        int turn = movement.callAfter(FLIGHT, "alignLook", brake);
        int rocket = movement.callAfter(FLIGHT, "tryRocket", brake);
        check(chaseRequest < terrainSafety && terrainSafety < brake
                        && !canReach(movement, chaseRequest + 1, rocket, terrainSafety, new HashSet<>()),
                "Every chase movement request must pass terrain safety before motion or a new rocket");
        check(brake < turn, "Braking must steer against momentum");
        check(movement.steps.get(brake + 1).opcode == Opcodes.IFEQ
                        && !canReach(movement, brake + 2, rocket, -1, new HashSet<>()),
                "A blocked braking request must not fire another rocket");
        Trace safety = method(movementMethods, "applyTerrainSafety");
        String terrain = "dev/guizhou/util/elytra/FlightTerrainSafety";
        int trajectory = safety.call(terrain, "trajectoryClear");
        int queued = safety.call(FLIGHT, "queueRecovery");
        int recovery = safety.call(FLIGHT, "recoveryLook");
        check(trajectory < recovery && safety.handles.stream().anyMatch(handle -> FLIGHT.equals(handle.getOwner())
                        && "clearRelativePath".equals(handle.getName())),
                "Physical recovery must check the actual curved glide through body-swept terrain");
        check(trajectory < queued && queued < recovery
                        && safety.steps.subList(queued + 1, recovery).stream().noneMatch(step ->
                        "dev/guizhou/util/elytra/FlightVelocity".equals(step.owner) && "isAvoiding".equals(step.name)),
                "A planned detour must not exempt its still-colliding momentum from recovery");
        Trace recoverySelection = method(movementMethods, "recoveryLook");
        check(recoverySelection.hasCall("dev/guizhou/util/elytra/FlightRecoveryPlan", "matches")
                        && recoverySelection.hasCall(terrain, "trajectoryClear")
                        && recoverySelection.hasCall(terrain, "recoveryLook"),
                "Background recovery needs freshness and live physics validation with an emergency fallback");
        Trace relative = method(movementMethods, "clearRelativePath");
        check(relative.hasCall(FLIGHT, "clearNavigationPath"),
                "Glide prediction and route planning must share the same full-body collision policy");
        Map<String, Trace> terrainMethods = read(terrain);
        List<Trace> trajectoryOverloads = terrainMethods.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("trajectoryClear(")).map(Map.Entry::getValue).toList();
        check(trajectoryOverloads.stream().anyMatch(trace -> trace.hasCall(terrain, "sweepTrajectory")),
                "Trajectory validation must reach the shared glide and rocket sweep");
        Trace trajectoryCheck = method(terrainMethods, "sweepTrajectory");
        check(trajectoryCheck.hasCall("dev/guizhou/util/elytra/ElytraPhysics", "simulateGlide")
                        && trajectoryCheck.hasCall(terrain + "$Clearance", "clear"),
                "Trajectory validation must simulate glide and query terrain along the movement");
        Trace velocity = method(movementMethods, "applyFlightVelocity");
        int safetyFlag = velocity.field(FLIGHT, "pendingSafetySteering", Opcodes.GETFIELD);
        int boostEnvelope = velocity.call("dev/guizhou/util/elytra/ElytraPhysics", "clampToGrimEnvelope");
        Step guardedBoost = velocity.steps.get(safetyFlag + 1);
        check(safetyFlag < boostEnvelope && guardedBoost.opcode == Opcodes.IFNE
                        && !canReach(velocity, velocity.target(guardedBoost), boostEnvelope, -1, new HashSet<>()),
                "Safety steering must not be expanded back into boosted collision speed by the velocity hook");
        int finalSweep = velocity.callAfter(FLIGHT, "clearRelativePath", boostEnvelope);
        int finalTrajectory = velocity.callAfter(terrain, "trajectoryClear", finalSweep);
        int heldSafety = velocity.field(FLIGHT, "safetyHoldTicks", Opcodes.PUTFIELD);
        int applied = velocity.field(FLIGHT, "lastApplied", Opcodes.PUTFIELD);
        check(boostEnvelope < finalSweep && finalSweep < finalTrajectory && finalTrajectory < heldSafety && heldSafety < applied,
                "The final expanded velocity must pass body and trajectory collision checks before it is applied");
        check(intConstant(velocity.steps.get(heldSafety - 1)) == 6
                        && velocity.steps.get(heldSafety - 4).opcode == Opcodes.ALOAD
                        && Integer.valueOf(1).equals(velocity.steps.get(heldSafety - 4).constant)
                        && velocity.steps.get(heldSafety - 3).opcode == Opcodes.ASTORE
                        && velocity.steps.get(applied - 1).opcode == Opcodes.ALOAD
                        && velocity.steps.get(heldSafety - 3).constant.equals(velocity.steps.get(applied - 1).constant),
                "A rejected boost must restore the incoming vanilla velocity and hold safety for six ticks");
    }

    private static void verifyDiveIntegration() throws IOException {
        String controller = "dev/guizhou/util/elytra/MaceDiveController";
        Map<String, Trace> mace = read(CHASE + "$MaceBehavior");
        Trace begin = method(mace, "shouldStartDive");
        int recovery = begin.call(FLIGHT, "canRecoverDiveFlight");
        int plan = begin.call("dev/guizhou/util/elytra/AerialIntercept", "canStartDrop");
        check(!canReach(begin, 0, plan, recovery, new HashSet<>()),
                "A dive cannot be planned without automatic flight recovery");
        check(begin.hasCall("dev/guizhou/util/elytra/TargetMotionTracker", "hasTwoVelocities")
                        && begin.hasCall("dev/guizhou/util/elytra/AerialCombatPolicy", "coherent"),
                "Dive planning must check motion reliability");
        Trace dive = method(mace, "tickDive");
        int eligible = dive.call(controller, "canAttack");
        int vanilla = dive.call(CHASE, "willUseMaceAttack");
        int send = dive.call(CHASE, "attack");
        check(eligible < vanilla && vanilla < send, "Real dive and vanilla smash eligibility must precede the hit");
        check(!canReach(dive, 0, send, eligible, new HashSet<>())
                        && !canReach(dive, 0, send, vanilla, new HashSet<>()),
                "A dive cannot send an ordinary gliding attack through an alternate path");
        check(dive.hasCall(CHASE + "$MaceBehavior", "dropPathClear"),
                "Glide-exit waiting and actual diving both need collision recovery");
        int equipmentRecovery = dive.callAfter(controller, "requestRecovery", send);
        int immediateClimb = dive.callAfter(CHASE + "$MaceBehavior", "tickWaitAttack", equipmentRecovery);
        int successReturn = nextOpcode(dive, immediateClimb, Opcodes.RETURN);
        Step accepted = dive.steps.get(send + 1);
        check(accepted.opcode == Opcodes.IFEQ && dive.target(accepted) > successReturn
                        && send < equipmentRecovery && equipmentRecovery < immediateClimb,
                "An accepted dive hit must restore wings and compute its upward heading in that same tick");
        check(!canReach(dive, send + 2, successReturn, equipmentRecovery, new HashSet<>())
                        && !canReach(dive, send + 2, successReturn, immediateClimb, new HashSet<>()),
                "The successful-hit branch cannot return while equipment recovery or pull-up remains deferred");
        check(dive.steps.stream().filter(step -> (CHASE + "$MaceBehavior").equals(step.owner)
                        && "recoverMissedDive".equals(step.name)).count() == 2,
                "Both an interrupted dive and a missed low/blocked pass must compute immediate recovery");
        Trace missedDive = method(mace, "recoverMissedDive");
        int releasedDescent = missedDive.field(CHASE + "$MaceBehavior", "state", Opcodes.PUTFIELD);
        int restoreWings = missedDive.call(controller, "requestRecovery");
        int nextClimb = missedDive.call(CHASE + "$MaceBehavior", "tickPullUp");
        check(releasedDescent < restoreWings && restoreWings < nextClimb
                        && !canReach(missedDive, 0, restoreWings, releasedDescent, new HashSet<>()),
                "A missed dive must release FOLLOW before synchronous wing recovery and create a climb in the same tick");
        Trace waiting = method(mace, "tickWaitAttack");
        Trace next = method(mace, "aimNextClimb");
        Trace climb = method(mace, "climbPlan");
        check(waiting.hasCall(CHASE + "$MaceBehavior", "aimNextClimb")
                        && next.call(CHASE + "$MaceBehavior", "climbPlan")
                        < next.field(CHASE + "$MaceBehavior", "heading", Opcodes.PUTFIELD)
                        && next.hasCall("java/lang/Math", "max")
                        && climb.field(CHASE, "maceHeight", Opcodes.GETFIELD) >= 0
                        && climb.hasCall("dev/guizhou/util/elytra/MaceApproach", "climb"),
                "Waiting must plan the configured next climb immediately and never start a premature descent");
        Trace state = method(read(CHASE), "isMaceDiveActive");
        check(!state.hasCall(CHASE, "isEnabled"), "Equipment restoration must survive disabling Chase");
        Trace controllerTick = method(read(controller), "onPreTick");
        check(controllerTick.priority > 100, "Dive recovery must run before chase and flight ticks");
        for (String owner : List.of(controller, CHASE, CHASE + "$MaceBehavior")) {
            for (Trace trace : read(owner).values()) {
                check(trace.steps.stream().noneMatch(step -> step.opcode == Opcodes.PUTFIELD
                                && "fallDistance".equals(step.name)),
                        "Mace automation must not fabricate fall distance");
            }
        }
    }

    private static void verifyMaceDescentOwnership() throws IOException {
        Map<String, Trace> chase = read(CHASE);
        Trace ownership = method(chase, "preservesMaceDescent");
        check(ownership.steps.stream().anyMatch(step -> "isFallFlying".equals(step.name)),
                "A powered mace approach must not suppress armor cycling and rocket refresh before glide ends");
        check(ownership.hasCall(CHASE, "hasMaceDescentIntent"),
                "Natural-fall ownership must also retain every mace intent gate");
        ownership = method(chase, "hasMaceDescentIntent");
        for (String gate : List.of("isEnabled", "paused", "isValid")) {
            check(ownership.hasCall(CHASE, gate), "Mace descent ownership must check " + gate);
        }
        check(ownership.steps.stream().anyMatch(step -> step.opcode == Opcodes.GETSTATIC
                        && "net/minecraft/world/item/Items".equals(step.owner) && "MACE".equals(step.name))
                        && ownership.steps.stream().anyMatch(step -> step.opcode == Opcodes.INSTANCEOF
                        && (CHASE + "$MaceBehavior").equals(step.owner))
                        && ownership.steps.stream().anyMatch(step -> step.opcode == Opcodes.GETFIELD
                        && (CHASE + "$MaceBehavior").equals(step.owner) && "state".equals(step.name)),
                "Only a real mace behavior in its attack phase may retain descent ownership");
        check(ownership.steps.stream().anyMatch(step -> "onGround".equals(step.name))
                        && ownership.steps.stream().filter(step -> "getY".equals(step.name)).count() >= 2,
                "Grounded or already-past-target movement cannot retain descent ownership");

        Map<String, Trace> movement = read(FLIGHT);
        for (String name : List.of("resumeGliding", "maybeTakeoff")) {
            Trace launch = method(movement, name);
            int preserve = launch.call(CHASE, "preservesMaceDescent");
            int restart = launch.steps.stream().filter(step -> "tryToStartFallFlying".equals(step.name))
                    .mapToInt(launch.steps::indexOf).findFirst().orElseThrow();
            Step protectedDescent = launch.steps.get(preserve + 1);
            check(preserve < restart && protectedDescent.opcode == Opcodes.IFEQ
                            && launch.steps.get(preserve + 2).opcode == (name.equals("resumeGliding") ? Opcodes.ICONST_0 : Opcodes.RETURN),
                    name + " must return before restarting glide during the authorized mace descent");
        }
        Trace rocket = method(movement, "tryRocket");
        int preserveRocket = rocket.call(CHASE, "preservesMaceDescent");
        check(rocket.steps.get(preserveRocket + 1).opcode == Opcodes.IFEQ
                        && rocketRefusalOnlyPassiveMarker(rocket, preserveRocket + 2),
                "Automatic rocket use cannot interrupt the natural mace descent");

        Trace safety = method(movement, "applyTerrainSafety");
        int preserve = safety.call(CHASE, "hasMaceDescentIntent");
        Step savedDescent = safety.steps.get(preserve + 1);
        check(savedDescent.opcode == Opcodes.ISTORE, "Terrain safety must retain the current attack-descent decision");
        int landing = safety.call(FLIGHT, "needsLandingRecovery");
        int load = nextLocalLoad(safety, preserve + 1, savedDescent.constant);
        Step landingGate = safety.steps.get(load + 1);
        check(load < landing && landingGate.opcode == Opcodes.IFNE
                        && !canReach(safety, safety.target(landingGate), landing, -1, new HashSet<>()),
                "Generic landing recovery must defer to a valid attack descent without disabling combat");
        int trajectory = safety.call("dev/guizhou/util/elytra/FlightTerrainSafety", "trajectoryClear");
        int horizonLoad = nextLocalLoad(safety, landing, savedDescent.constant);
        Step horizonGate = safety.steps.get(horizonLoad + 1);
        check(horizonLoad < trajectory && horizonGate.opcode == Opcodes.IFEQ
                        && intConstant(safety.steps.get(horizonLoad + 2)) == 1
                        && intConstant(safety.steps.get(safety.target(horizonGate))) == 8,
                "Attack descent must retain the next-tick body sweep while shortening early ground prediction");
        check(canReach(safety, horizonLoad + 2, trajectory, -1, new HashSet<>()),
                "Attack descent cannot bypass physical collision validation altogether");
    }

    private static int nextOpcode(Trace trace, int after, int opcode) {
        for (int index = after + 1; index < trace.steps.size(); index++) {
            if (trace.steps.get(index).opcode == opcode) return index;
        }
        throw new AssertionError("Missing instruction: " + opcode);
    }

    private static int nextLocalLoad(Trace trace, int after, Object local) {
        for (int index = after + 1; index < trace.steps.size(); index++) {
            Step step = trace.steps.get(index);
            if (step.opcode == Opcodes.ILOAD && local.equals(step.constant)) return index;
        }
        throw new AssertionError("Missing local boolean load: " + local);
    }

    private static int intConstant(Step step) {
        if (step.opcode >= Opcodes.ICONST_M1 && step.opcode <= Opcodes.ICONST_5) return step.opcode - Opcodes.ICONST_0;
        if ((step.opcode == Opcodes.BIPUSH || step.opcode == Opcodes.SIPUSH || step.opcode == Opcodes.LDC)
                && step.constant instanceof Integer value) return value;
        throw new AssertionError("Expected an integer constant, got opcode " + step.opcode);
    }

    private static void assertFalseReturn(Trace trace, int start, String context) {
        Step first = trace.steps.get(start);
        if (first.opcode == Opcodes.GOTO) {
            assertFalseReturn(trace, trace.target(first), context);
            return;
        }
        check(first.opcode == Opcodes.ICONST_0, context + " must produce false");
        int next = start + 1;
        if (trace.steps.get(next).opcode == Opcodes.GOTO) next = trace.target(trace.steps.get(next));
        check(trace.steps.get(next).opcode == Opcodes.IRETURN, context + " must return without attacking");
    }

    private static boolean canReach(Trace trace, int index, int target, int forbidden, Set<Integer> visited) {
        if (index == forbidden || index < 0 || index >= trace.steps.size() || !visited.add(index)) return false;
        if (index == target) return true;
        Step step = trace.steps.get(index);
        if (step.opcode >= Opcodes.IRETURN && step.opcode <= Opcodes.RETURN || step.opcode == Opcodes.ATHROW) {
            return false;
        }
        if (step.target != null) {
            if (canReach(trace, trace.target(step), target, forbidden, visited)) return true;
            if (step.opcode == Opcodes.GOTO) return false;
        }
        return canReach(trace, index + 1, target, forbidden, visited);
    }

    private static Map<String, Trace> read(String owner) throws IOException {
        Map<String, Trace> methods = new HashMap<>();
        readClass(owner).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                Trace trace = new Trace();
                methods.put(name + descriptor, trace);
                return new MethodVisitor(Opcodes.ASM9) {
                    private void add(int opcode, String owner, String name, Object value, Label target) {
                        trace.steps.add(new Step(opcode, owner, name, value, target));
                    }

                    @Override
                    public void visitInsn(int opcode) { add(opcode, null, null, null, null); }
                    @Override
                    public void visitVarInsn(int opcode, int variable) { add(opcode, null, null, variable, null); }
                    @Override
                    public void visitIntInsn(int opcode, int value) { add(opcode, null, null, value, null); }
                    @Override
                    public void visitTypeInsn(int opcode, String type) { add(opcode, type, null, null, null); }
                    @Override
                    public void visitLdcInsn(Object value) { add(Opcodes.LDC, null, null, value, null); }
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        add(opcode, owner, name, null, null);
                    }
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        add(opcode, owner, name, null, null);
                    }
                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                                       Object... arguments) {
                        add(Opcodes.INVOKEDYNAMIC, null, name, null, null);
                        for (Object argument : arguments) {
                            if (argument instanceof Handle handle) trace.handles.add(handle);
                        }
                    }
                    @Override
                    public void visitJumpInsn(int opcode, Label target) { add(opcode, null, null, null, target); }
                    @Override
                    public void visitLabel(Label label) { trace.labels.put(label, trace.steps.size()); }
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!descriptor.equals("Ldev/guizhou/event/system/Subscribe;")) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String name, Object value) {
                                if (name.equals("priority")) trace.priority = (Integer) value;
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private static Trace method(Map<String, Trace> methods, String name) {
        List<Trace> matches = methods.entrySet().stream().filter(entry -> entry.getKey().startsWith(name + "("))
                .map(Map.Entry::getValue).toList();
        check(matches.size() == 1, "Expected one method named " + name);
        return matches.getFirst();
    }

    private static ClassReader readClass(String name) throws IOException {
        try (InputStream stream = ElytraCombatHookTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) throw new IOException("Missing named class resource: " + name);
            return new ClassReader(stream);
        }
    }

    private static Object pop(List<Object> stack) {
        return stack.removeLast();
    }

    private static void requireMember(Step step, String owner, String name) {
        check(owner.equals(step.owner) && name.equals(step.name), "Unreviewed smash predicate member");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    /** A refusal path may return directly or after only the passive rocket-block write. */
    private static boolean rocketRefusalOnlyPassiveMarker(Trace trace, int index) {
        if (index < 0 || index >= trace.steps.size()) return false;
        Step step = trace.steps.get(index);
        if (step.opcode == Opcodes.RETURN) return true;
        if (step.opcode != Opcodes.ALOAD || !Integer.valueOf(0).equals(step.constant())
                || index + 3 >= trace.steps.size()) return false;
        boolean load = trace.steps.get(index + 1).opcode >= Opcodes.ICONST_M1
                && trace.steps.get(index + 1).opcode <= Opcodes.ICONST_5
                || trace.steps.get(index + 1).opcode == Opcodes.LDC;
        Step write = trace.steps.get(index + 2);
        return load && write.opcode == Opcodes.PUTFIELD && "lastRocketBlock".equals(write.name)
                && trace.steps.get(index + 3).opcode == Opcodes.RETURN;
    }

    private static final class Trace {
        final List<Step> steps = new ArrayList<>();
        final Map<Label, Integer> labels = new IdentityHashMap<>();
        final List<Handle> handles = new ArrayList<>();
        int priority;

        int target(Step step) { return labels.get(step.target); }

        boolean hasCall(String owner, String name) {
            return steps.stream().anyMatch(step -> owner.equals(step.owner) && name.equals(step.name)
                    && step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE);
        }

        int call(String owner, String name) { return callAfter(owner, name, -1); }

        int field(String owner, String name, int opcode) {
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (owner.equals(step.owner) && name.equals(step.name) && opcode == step.opcode) return index;
            }
            throw new AssertionError("Missing field access: " + owner + "." + name);
        }

        int callAfter(String owner, String name, int after) {
            for (int index = after + 1; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (owner.equals(step.owner) && name.equals(step.name)
                        && step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE) return index;
            }
            throw new AssertionError("Missing call: " + owner + "." + name);
        }
    }

    private record Step(int opcode, String owner, String name, Object constant, Label target) {
    }
}
