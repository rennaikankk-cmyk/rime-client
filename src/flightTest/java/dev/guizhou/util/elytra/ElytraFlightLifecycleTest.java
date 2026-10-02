package dev.guizhou.util.elytra;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ElytraFlightLifecycleTest {
    private static final String FLIGHT = "dev/guizhou/features/modules/movement/GuangxiElytraModule";
    private static final String VIEW_ANCHOR = "dev/guizhou/util/elytra/FlightViewAnchor";
    private static int checks;

    public static void main(String[] args) throws IOException {
        Map<String, Trace> methods = readFlight();
        verifyChaseOwnsBoostDecision(methods);
        verifyMaceDivePause(methods);
        verifyDurabilityBoundary(methods.get("usableElytra"));
        verifyWingReplacement(methods);
        verifyRocketInventorySafety(methods);
        verifySafeRocketPredicate(methods.get("safeRocket"));
        verifyDeferredDiveRestoration(methods);
        verifyDiveRecoveryPrerequisite(methods);
        verifyArmorSwapWiring(methods);
        verifyIndependentArmorPreferences(methods);
        verifyGlideRestart(methods);
        verifyLandingRecovery(methods);
        verifyLowSpeedSteering(methods.get("alignLook"));
        verifyCorrectionCooldownSafety(methods.get("onPreTick"));
        verifyFreeLookWiring(methods);
        verifyCameraRestoration();
        verifySafetyBoostView(methods);
        System.out.println("ElytraFlightLifecycleTest passed: " + checks + " checks");
    }

    private static void verifyChaseOwnsBoostDecision(Map<String, Trace> methods) {
        Trace gate = methods.get("useStandaloneGrimBoost");
        check(gate != null, "Standalone boost needs one shared ownership decision");
        for (String owns : List.of("chaseActive", "chaseWantsFlight")) {
            int call = gate.call(owns);
            Step branch = gate.steps.get(call + 1);
            check(branch.opcode == Opcodes.IFNE, owns + " must reject standalone boost");
            assertFalseReturn(gate, gate.target(branch), owns);
        }

        for (String name : List.of("onPreTick", "onPacketSend", "onPacketReceive", "needsSpearBoostRefresh")) {
            check(methods.get(name).hasCall("useStandaloneGrimBoost"),
                    name + " must use the same Chase/boost ownership decision");
        }

        Trace tick = methods.get("onPreTick");
        int boostGate = tick.call("useStandaloneGrimBoost");
        Step branch = tick.steps.get(boostGate + 1);
        check(branch.opcode == Opcodes.IFEQ, "Rejected standalone boost must enter normal flight intent");
        int normal = tick.target(branch);
        int chase = tick.call("onElytra");
        int cleanup = tick.callAfter("endFreeze", normal - 1);
        int accelerating = tick.fieldAfter(Opcodes.PUTFIELD, "accelerating", normal - 1);
        check(normal < cleanup && cleanup < chase && accelerating < chase,
                "Normal intent must clear both acceleration and packet freeze before Chase steering");
        check(tick.steps.get(accelerating - 1).opcode == Opcodes.ICONST_0,
                "Switching to Chase must clear the acceleration flag");

        Trace send = methods.get("onPacketSend");
        int decision = send.call("useStandaloneGrimBoost");
        int active = send.fieldAfter(Opcodes.PUTFIELD, "boostTickActive", decision);
        check(send.steps.get(active - 1).opcode == Opcodes.ICONST_0,
                "An inactive outbound boost path must clear its cached per-tick flag");
        check(active < send.call("cancel"), "Cached boost ownership must be cleared before any freeze cancellation");
    }

    private static void verifyArmorSwapWiring(Map<String, Trace> methods) {
        check(methods.get("onEnable").hasCall("monitor"), "Enabled flight must register armor-cycle ticks");
        Trace disable = methods.get("onDisable");
        check(disable.call("stop") < disable.call("swapBackChest"),
                "Disabling flight must recover the owned cycle before returning original equipment");
        for (String name : List.of("equipElytraIfNeeded", "swapBackChest", "useStandaloneGrimBoost")) {
            Trace trace = methods.get(name);
            int active = trace.call("active");
            check("dev/guizhou/util/elytra/ArmorSwapController".equals(trace.steps.get(active).owner),
                    name + " must respect the armor cycle's ownership");
        }
        Trace rocket = methods.get("canUseRocket");
        check(rocket.hasCall("active") && rocket.hasCall("usableElytra"),
                "Armor-cycle rockets must wait for a worn-wing restart window");
        Trace use = methods.get("tryRocket");
        check(use.call("rocketWindowOpen") < use.call("withSwap"),
                "Actual rocket use must wait for a fresh restart before switching or consuming the item");
        check(!rocket.hasCall("rocketWindowOpen") && !methods.get("needsSpearBoostRefresh").hasCall("rocketWindowOpen"),
                "Spear must request hand release before a held-use item permits the armor restart window");
        Trace requested = methods.get("wantsArmorSwap");
        check(requested.hasCall("armorFlyRequested")
                        && requested.fieldAfter(Opcodes.GETFIELD, "correctionPending", -1) >= 0
                        && requested.fieldAfter(Opcodes.GETFIELD, "boostRetryTick", -1) >= 0,
                "Armor restarts need the independent owner and must respect correction recovery");
        Trace owner = methods.get("armorFlyRequested");
        check(owner.hasCall("requested") && !owner.hasCall("isEnabled"),
                "Only the independently authorized ArmorFly module may request armor flight");
        check(disable.hasCall("requested") && methods.get("releaseArmorFly").hasCall("armorFlyRequested"),
                "Closing either flight owner must preserve the other owner");
        int descent = requested.call("preservesMaceDescent");
        Step descending = requested.steps.get(descent + 1);
        check(descending.opcode == Opcodes.IFNE,
                "Armor cycling must yield to natural mace descent before attempting a glide restart");
        assertFalseReturn(requested, requested.target(descending), "Owned mace descent");
    }

    private static void verifyIndependentArmorPreferences(Map<String, Trace> methods) throws IOException {
        String armorClass = "dev/guizhou/features/modules/movement/ArmorFlyModule";
        var armor = readClass(armorClass);
        Trace service = methods.get("serviceIndependentArmorFly");
        check(service.hasCall(armorClass, "autoEquip"), "Independent equipment must use its own authorized preference");
        Trace equip = methods.get("equipElytraIfNeeded");
        check(equip.hasCall(armorClass, "autoEquip") && equip.hasCall(armorClass, "requested"),
                "Both armor owners must share the safe inventory scope without borrowing a disabled controller's setting");
        for (String preference : List.of("autoEquip", "minimumDurability", "serverPaced")) {
            check(armor.get(preference).hasCall("dev/guizhou/features/modules/movement/SLMArmorFlyModule", "selected"),
                    "The explicitly selected SLM module retains its own preferences: " + preference);
        }
        check(armor.get("autoEquip").hasCall("requested") && armor.get("serverPaced").hasCall("requested"),
                "Equipment and server windows still require an authorized armor request");
        check(armor.get("onFlightWindow").hasCall("resumeRequested") && armor.get("onPreTick").hasCall("observe")
                        && armor.get("ensureMetadataSession").hasCall("reset"),
                "Original armor must recover an observed foreign flight, discarding stale sessions");
        check(armor.get("metadataEligible").fieldAfter(Opcodes.GETFIELD, "serverSync", -1) >= 0,
                "Experimental glide metadata cannot delay the server-synchronized cycle");
        Trace constructor = armor.get("<init>");
        for (String preference : List.of("AutoEquip", "ServerSync")) {
            int setting = -1;
            for (int i = 0; i < constructor.steps.size(); i++) if (preference.equals(constructor.steps.get(i).operand)) setting = i;
            check(setting >= 0 && constructor.steps.get(setting + 1).opcode == Opcodes.ICONST_1,
                    "A saved preset missing the new setting receives the compatible default: " + preference);
        }
        Trace monitor = methods.get("monitorArmorFly");
        int expiry = monitor.fieldAfter(Opcodes.PUTFIELD, "rocketSentThisTick", -1);
        check(expiry >= 0 && monitor.steps.get(expiry - 1).opcode == Opcodes.ICONST_0 && expiry < monitor.call("monitor"),
                "Independent armor must expire its previous rocket result before equipment preparation");
        var controller = readClass("dev/guizhou/util/elytra/ArmorSwapController");
        check(controller.get("onPreTick").hasCall(armorClass, "serverPaced"),
                "The actual equipment adapter must use the selected mode's synchronization policy");
        check(controller.get("acquire").hasCall(armorClass, "minimumDurability")
                        && controller.get("usableWings").hasCall(armorClass, "minimumDurability"),
                "Armor and elytra both respect the active owner's durability reserve");
        check(methods.get("maybeTakeoff").hasCall("dev/guizhou/util/elytra/ArmorTakeoff", "ready"),
                "The real takeoff path must open owned wings during the descending half of a held jump");
    }

    private static void verifyDurabilityBoundary(Trace usable) {
        check(usable != null, "Missing shared usable-wing predicate");
        for (int damage : new int[]{0, 100, 430, 431, 432, 433}) {
            check(evaluateUsable(usable, true, true, damage, 432) == (damage < 431),
                    "Wings must retain at least two durability points: damage=" + damage);
        }
        check(!evaluateUsable(usable, false, true, 0, 432), "An ordinary undamaged item is not usable wings");
        check(evaluateUsable(usable, true, false, 432, 432), "Unbreakable wings ignore damage counters");
        check(!evaluateUsable(usable, false, false, 0, 0), "Unbreakable non-wings are still rejected");
        for (int reserve : new int[]{5, 100}) for (int remaining : new int[]{1, 4, 5, 99, 100, 432})
            check(evaluateUsable(usable, true, true, 432 - remaining, 432, reserve) == (remaining >= reserve),
                    "SLM durability reserve=" + reserve + ", remaining=" + remaining);
    }

    private static void verifyMaceDivePause(Map<String, Trace> methods) {
        Trace tick = methods.get("onPreTick");
        int dive = tick.call("isMaceDiveActive");
        Step branch = tick.steps.get(dive + 1);
        check(branch.opcode == Opcodes.IFEQ, "An active mace dive must enter the flight pause branch");
        int resumed = tick.target(branch);
        check(dive < tick.call("maybeTakeoff") && dive < tick.call("useStandaloneGrimBoost"),
                "Mace dive ownership must be checked before takeoff and automatic boost");
        int gravity = tick.callAfter("restoreNoGravity", dive);
        int freeze = tick.callAfter("endFreeze", dive);
        int acceleration = tick.fieldAfter(Opcodes.PUTFIELD, "accelerating", dive);
        int previous = tick.fieldAfter(Opcodes.PUTFIELD, "lastApplied", dive);
        check(gravity < resumed && freeze < resumed && acceleration < resumed && previous < resumed,
                "Pausing for a mace dive must release flight gravity, freeze and motion state");
        check(tick.steps.get(acceleration - 1).opcode == Opcodes.ICONST_0
                        && tick.steps.get(previous - 1).opcode == Opcodes.ACONST_NULL,
                "A mace dive must clear acceleration and previous applied motion");
        check(tick.steps.get(resumed - 1).opcode == Opcodes.RETURN,
                "The mace dive pause must return before any automatic flight action");
        for (String name : List.of("useStandaloneGrimBoost", "equipElytraIfNeeded", "canUseRocket")) {
            check(methods.get(name).hasCall("isMaceDiveActive"),
                    name + " must respect dive ownership outside the regular movement tick");
        }
    }

    private static void verifyRocketInventorySafety(Map<String, Trace> methods) {
        Trace safe = methods.get("canUseRocket");
        for (String field : List.of("screen", "containerMenu", "inventoryMenu")) {
            check(safe.steps.stream().anyMatch(step -> step.opcode == Opcodes.GETFIELD && field.equals(step.name)),
                    "Automatic rocket use must check " + field);
        }
        check(safe.hasCall("getCarried") && safe.hasCall("isEmpty"),
                "Automatic rocket use must not operate with a carried inventory stack");
        check(!safe.hasCall("isUsingItem"), "The shared request gate must let a held spear request a rocket refresh");
        Trace refresh = methods.get("needsSpearBoostRefresh");
        check(refresh.hasCall("canUseRocket") && !refresh.hasCall("isUsingItem"),
                "Spear refresh must check inventory safety without requiring the spear to be released already");
        Trace use = methods.get("tryRocket");
        int safeCall = use.call("canUseRocket");
        int using = use.call("isUsingItem");
        int swap = use.call("withSwap");
        check(safeCall < using && using < swap, "Actual firework use must check inventory and item-use safety before swapping");
        Step refused = use.steps.get(safeCall + 1);
        int refusal = -1;
        if (refused.opcode == Opcodes.IFEQ) refusal = use.target(refused);
        else if (refused.opcode == Opcodes.IFNE) refusal = safeCall + 2;
        check(refusal >= 0 && returnsAtMostWithRocketMarker(use, refusal),
                "An unsafe inventory context must return before a firework swap");
        List<Trace> actions = methods.values().stream().filter(trace -> trace.hasCall("useItem")).toList();
        check(actions.size() == 1, "Expected one automatic firework use action");
        Trace action = actions.getFirst();
        int held = action.call("getItemInHand");
        int matches = action.call("safeRocket");
        int useItem = action.call("useItem");
        check(held < matches && matches < useItem && action.hasCall("consumesAction"),
                "Refire accounting requires a safe held firework and a locally consumed use action");
        check(FLIGHT.equals(action.steps.get(matches).owner),
                "The held stack must use the same explosive-rocket exclusion as inventory search");
        Step mismatch = action.steps.get(matches + 1);
        check(mismatch.opcode == Opcodes.IFEQ && action.target(mismatch) > useItem,
                "A swap that leaves another item or an explosive rocket in hand must never use it");
        for (Trace search : List.of(use, refresh)) {
            check(search.handles.stream().anyMatch(handle -> handle.getOwner().equals(FLIGHT)
                            && handle.getName().equals("safeRocket")),
                    "Rocket selection and spear-refresh requests must exclude explosive rockets");
        }
        int trajectory = use.call("rocketTrajectoryClear");
        int usefulBoost = use.call("hasUsefulBoost");
        check(use.fieldAfter(Opcodes.GETFIELD, "refireTicks", -1) < trajectory
                        && usefulBoost < trajectory && use.call("found") < trajectory && trajectory < swap,
                "Rocket trajectory sweeps belong after cheap readiness checks and before the actual inventory swap");
        Step blockedTrajectory = use.steps.get(trajectory + 1);
        check(blockedTrajectory.opcode == Opcodes.IFNE
                        && returnsAtMostWithRocketMarker(use, trajectory + 2),
                "Moving the expensive trajectory check must preserve its collision rejection");
        check(use.call("rocketLaunchDirection") < trajectory,
                "The final rocket trajectory check must still inspect the actual outgoing flight direction");
        int refreshTrajectory = refresh.call("rocketTrajectoryClear");
        check(refresh.hasCall("preservesMaceDescent") && refresh.call("found") < refreshTrajectory
                        && refresh.call("rocketLaunchDirection") < refreshTrajectory,
                "A spear may release charging only for a present rocket with a safe actual launch direction");
        Step refusedRefresh = refresh.steps.get(refreshTrajectory + 1);
        check(refusedRefresh.opcode == Opcodes.IFEQ,
                "A blocked rocket trajectory must refuse the spear boost-refresh request");
        assertFalseReturn(refresh, refresh.target(refusedRefresh), "Blocked spear boost refresh");
        Trace trajectoryCheck = methods.get("rocketTrajectoryClear");
        check(trajectoryCheck.hasCall("rocketLaunchClear") && !trajectoryCheck.hasCall("getLookAngle")
                        && trajectoryCheck.fieldAfter(Opcodes.GETFIELD, "terrainGuard", -1) >= 0
                        && trajectoryCheck.fieldAfter(Opcodes.GETFIELD, "safeLanding", -1) >= 0,
                "Both boost request and launch must share the same terrain and landing trajectory predicate");
        check(trajectoryCheck.handles.stream().anyMatch(handle -> handle.getOwner().equals(FLIGHT)
                        && handle.getName().equals("clearRelativePath")),
                "The shared launch predicate must retain the live swept-body collision check");
        Trace direction = methods.get("rocketLaunchDirection");
        check(direction.hasCall("isRotating") && direction.hasCall("isBypassUseSpoof")
                        && direction.hasCall("getRotationPitch") && direction.hasCall("getRotationYaw")
                        && direction.hasCall("getLookAngle"),
                "Launch safety must match the use-packet rotation while retaining the manual-view fallback");
    }

    private static void verifySafeRocketPredicate(Trace safe) {
        check(safe != null, "Automatic boost needs one shared non-explosive rocket predicate");
        for (boolean rocket : new boolean[]{false, true}) {
            for (int explosions : new int[]{-1, 0, 1, 4}) {
                check(evaluateSafeRocket(safe, rocket, explosions) == (rocket && explosions <= 0),
                        "Rocket safety mismatch: rocket=" + rocket + ", explosions=" + explosions);
            }
        }
    }

    // Interpret only the compiled stack/component predicate, without loading game registries.
    private static boolean evaluateSafeRocket(Trace trace, boolean rocket, int explosions) {
        List<Integer> stack = new ArrayList<>();
        Map<Integer, Integer> locals = new HashMap<>();
        locals.put(0, 1);
        for (int pc = 0, visited = 0; pc < trace.steps.size() && visited++ < 100; pc++) {
            Step step = trace.steps.get(pc);
            switch (step.opcode) {
                case Opcodes.ALOAD -> stack.add(locals.get((Integer) step.operand));
                case Opcodes.ASTORE -> locals.put((Integer) step.operand, pop(stack));
                case Opcodes.CHECKCAST -> { }
                case Opcodes.GETSTATIC -> {
                    check("FIREWORKS".equals(step.name) || "FIREWORK_ROCKET".equals(step.name),
                            "Rocket safety may inspect only the rocket item and its fireworks component");
                    stack.add(1);
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> {
                    pop(stack);
                    switch (step.name) {
                        case "get" -> {
                            pop(stack);
                            stack.add(explosions < 0 ? 0 : 1);
                        }
                        case "is" -> {
                            pop(stack);
                            stack.add(rocket ? 1 : 0);
                        }
                        case "explosions" -> stack.add(1);
                        case "isEmpty" -> stack.add(explosions == 0 ? 1 : 0);
                        default -> throw new AssertionError("Unreviewed rocket predicate call: " + step.name);
                    }
                }
                case Opcodes.ICONST_0 -> stack.add(0);
                case Opcodes.ICONST_1 -> stack.add(1);
                case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                    int value = pop(stack);
                    boolean zero = step.opcode == Opcodes.IFEQ || step.opcode == Opcodes.IFNULL;
                    if (zero ? value == 0 : value != 0) pc = trace.target(step) - 1;
                }
                case Opcodes.GOTO -> pc = trace.target(step) - 1;
                case Opcodes.IRETURN -> { return pop(stack) != 0; }
                default -> throw new AssertionError("Unreviewed rocket predicate instruction: " + step.opcode);
            }
        }
        throw new AssertionError("Rocket predicate did not return");
    }

    // Evaluate the compiled predicate against stack facts without bootstrapping Minecraft registries.
    private static boolean evaluateUsable(Trace trace, boolean wings, boolean damageable, int damage, int maxDamage) {
        return evaluateUsable(trace, wings, damageable, damage, maxDamage, 2);
    }
    private static boolean evaluateUsable(Trace trace, boolean wings, boolean damageable, int damage, int maxDamage, int reserve) {
        List<Integer> stack = new ArrayList<>();
        for (int pc = 0, visited = 0; pc < trace.steps.size() && visited++ < 100; pc++) {
            Step step = trace.steps.get(pc);
            switch (step.opcode) {
                case Opcodes.ALOAD -> stack.add(0);
                case Opcodes.INVOKESTATIC -> {
                    check("dev/guizhou/features/modules/movement/ArmorFlyModule".equals(step.owner)
                            && "minimumDurability".equals(step.name), "Only the configured durability reserve is sampled");
                    stack.add(reserve);
                }
                case Opcodes.GETSTATIC -> {
                    check("net/minecraft/world/item/Items".equals(step.owner) && "ELYTRA".equals(step.name),
                            "The wing predicate must test the actual elytra item");
                    stack.add(0);
                }
                case Opcodes.INVOKEVIRTUAL -> {
                    check("net/minecraft/world/item/ItemStack".equals(step.owner),
                            "Only ItemStack facts belong in the usable-wing predicate");
                    pop(stack);
                    switch (step.name) {
                        case "is" -> {
                            pop(stack);
                            stack.add(wings ? 1 : 0);
                        }
                        case "isDamageableItem" -> stack.add(damageable ? 1 : 0);
                        case "getDamageValue" -> stack.add(damage);
                        case "getMaxDamage" -> stack.add(maxDamage);
                        default -> throw new AssertionError("Unreviewed ItemStack predicate: " + step.name);
                    }
                }
                case Opcodes.ICONST_0 -> stack.add(0);
                case Opcodes.ICONST_1 -> stack.add(1);
                case Opcodes.ISUB -> {
                    int right = pop(stack);
                    stack.add(pop(stack) - right);
                }
                case Opcodes.IFEQ, Opcodes.IFNE -> {
                    int value = pop(stack);
                    if (step.opcode == Opcodes.IFEQ ? value == 0 : value != 0) pc = trace.target(step) - 1;
                }
                case Opcodes.IF_ICMPGE, Opcodes.IF_ICMPLT -> {
                    int right = pop(stack);
                    int left = pop(stack);
                    if (step.opcode == Opcodes.IF_ICMPGE ? left >= right : left < right) pc = trace.target(step) - 1;
                }
                case Opcodes.GOTO -> pc = trace.target(step) - 1;
                case Opcodes.IRETURN -> { return pop(stack) != 0; }
                default -> throw new AssertionError("Unreviewed usable-wing instruction: " + step.opcode);
            }
        }
        throw new AssertionError("Usable-wing predicate did not return");
    }

    private static void verifyWingReplacement(Map<String, Trace> methods) {
        for (String name : List.of("maybeTakeoff", "canStartGliding", "tickGroundHop", "equipElytraIfNeeded")) {
            check(methods.get(name).hasCall("usableElytra"), name + " must reject unusable worn wings");
        }
        for (String name : List.of("maybeTakeoff", "tickGroundHop")) {
            Trace method = methods.get(name);
            check(method.call("usableElytra") < method.call("equipElytraIfNeeded"),
                    name + " must attempt replacement after checking worn-wing durability");
        }
        Trace equip = methods.get("equipElytraIfNeeded");
        check(equip.handles.stream().anyMatch(handle -> handle.getOwner().equals(FLIGHT)
                        && handle.getName().equals("usableElytra")),
                "The inventory search must use the same usable-wing predicate");
        int swapped = equip.call("swapChestArmorWith");
        int owned = equip.fieldAfter(Opcodes.GETFIELD, "weEquipped", swapped);
        Step branch = equip.steps.get(owned + 1);
        int slot = equip.fieldAfter(Opcodes.PUTFIELD, "stowedChestSlot", owned);
        int original = equip.fieldAfter(Opcodes.PUTFIELD, "stowedChest", owned);
        check(branch.opcode == Opcodes.IFNE && equip.target(branch) > slot && equip.target(branch) > original,
                "Replacing owned broken wings must preserve the original chest item and its source slot");
        check(equip.fieldAfter(Opcodes.PUTFIELD, "weEquipped", original) >= equip.target(branch),
                "Both initial equip and replacement must keep restoration ownership");
    }

    private static void verifyDeferredDiveRestoration(Map<String, Trace> methods) {
        Trace restore = methods.get("swapBackChest");
        int dive = restore.call("isMaceDiveActive");
        Step active = restore.steps.get(dive + 1);
        check(active.opcode == Opcodes.IFEQ && restore.steps.get(dive + 2).opcode == Opcodes.RETURN,
                "Active dive restoration must defer Flight cleanup without clearing its owned chest record");
        check(dive < restore.call("swapChestArmorWith")
                        && dive < restore.fieldAfter(Opcodes.PUTFIELD, "weEquipped", -1),
                "Dive ownership must be checked before swapping or discarding original armor metadata");

        Trace finished = methods.get("finishDiveRecovery");
        check(finished != null, "The persistent dive controller needs a Flight cleanup callback");
        for (String field : List.of("weEquipped", "statePlayer", "stateLevel")) {
            check(finished.fieldAfter(Opcodes.GETFIELD, field, -1) < finished.call("getSlot"),
                    "Recovery callback must verify " + field + " before inventory access");
        }
        long damageFields = finished.steps.stream().filter(step -> step.opcode == Opcodes.GETSTATIC
                && "net/minecraft/core/component/DataComponents".equals(step.owner)
                && "DAMAGE".equals(step.name)).count();
        check(damageFields == 2 && finished.hasCall("copy") && finished.hasCall("matches"),
                "Returned original armor may differ only in natural damage, compared on copies");
        int snapshot = finished.fieldAfter(Opcodes.PUTFIELD, "stowedChest", -1);
        int enabled = finished.call("isEnabled");
        int swap = finished.call("swapBackChest");
        Step keptEnabled = finished.steps.get(enabled + 1);
        check(snapshot < enabled, "Refresh returned armor durability even while Flight remains enabled");
        check(keptEnabled.opcode == Opcodes.IFNE && finished.target(keptEnabled) > swap,
                "The recovery callback restores the original chest item only after Flight has been disabled");
    }

    private static void verifyDiveRecoveryPrerequisite(Map<String, Trace> methods) {
        Trace prerequisite = methods.get("canRecoverDiveFlight");
        check(prerequisite != null, "Dive preparation needs an explicit flight recovery capability");
        int enabled = prerequisite.call("isEnabled");
        int takeoff = prerequisite.fieldAfter(Opcodes.GETFIELD, "takeoff", enabled);
        int setting = prerequisite.callAfter("getValue", takeoff);
        int value = prerequisite.callAfter("booleanValue", setting);
        for (int decision : new int[]{enabled, value}) {
            Step branch = prerequisite.steps.get(decision + 1);
            check(branch.opcode == Opcodes.IFEQ,
                    "Disabled flight or disabled takeoff must reject dive recovery capability");
            assertFalseReturn(prerequisite, prerequisite.target(branch), "Unavailable dive recovery");
        }
    }

    private static void verifyGlideRestart(Map<String, Trace> methods) {
        Trace restart = methods.get("resumeGliding");
        check(restart != null, "Recovery needs one real glide restart path");
        int canStart = restart.call("canStartGliding");
        int start = restart.call("tryToStartFallFlying");
        int command = restart.fieldAfter(Opcodes.GETSTATIC, "START_FALL_FLYING", start);
        int send = restart.call("send");
        check(canStart < start && start < command && command < send && !restart.hasCall("startFallFlying"),
                "Recovery must validate and actually start gliding before sending START_FALL_FLYING");
        Step succeeded = restart.steps.get(start + 1);
        check(succeeded.opcode == Opcodes.IFNE && restart.target(succeeded) <= command,
                "Only a successful local glide restart may reach the server command");
        assertFalseReturn(restart, start + 2, "Failed glide restart");

        Trace finished = methods.get("finishDiveRecovery");
        int enabled = finished.call("isEnabled");
        int resumed = finished.call("resumeGliding");
        int owned = finished.fieldAfter(Opcodes.GETFIELD, "weEquipped", -1);
        Step skipMetadata = finished.steps.get(owned + 1);
        check(skipMetadata.opcode == Opcodes.IFEQ && finished.target(skipMetadata) <= enabled,
                "Manually equipped wings must still reach glide recovery without auto-equip ownership");
        check(enabled < resumed, "Restored wings must resume gliding while Flight remains enabled");
        check(methods.get("onPreTick").hasCall("resumeGliding"),
                "Unexpected glide loss must reach the same guarded restart path");
    }

    private static void verifyLandingRecovery(Map<String, Trace> methods) throws IOException {
        Trace restore = methods.get("swapBackChest");
        int register = restore.call("register");
        int canSwap = restore.call("canSwapChest");
        int swap = restore.call("swapChestArmorWith");
        int deferredReturn = -1;
        for (int index = register + 1; index < canSwap; index++) {
            if (restore.steps.get(index).opcode == Opcodes.RETURN) {
                deferredReturn = index;
                break;
            }
        }
        check(register < deferredReturn && deferredReturn < canSwap && canSwap < swap,
                "Airborne disable must register recovery and return before touching chest equipment");
        for (String safePlace : List.of("onGround", "isInWater", "isInLava")) {
            int decision = restore.call(safePlace);
            Step grounded = restore.steps.get(decision + 1);
            check(decision < register && grounded.opcode == Opcodes.IFNE
                            && restore.target(grounded) > deferredReturn && restore.target(grounded) <= canSwap,
                    "Chest restoration may pass the airborne guard only when " + safePlace);
        }
        int pending = restore.fieldAfter(Opcodes.PUTFIELD, "awaitingLanding", -1);
        check(pending < register && restore.steps.get(pending - 1).opcode == Opcodes.ICONST_1,
                "Deferred restoration must preserve an explicit pending-recovery subscription");

        Trace cancel = methods.get("cancelLandingRecovery");
        check(cancel.hasCall("unregister") && cancel.hasCall("clearFlightRotation"),
                "Cancelling deferred recovery must release its subscription and sticky rotation");
        Trace clear = methods.get("clearFlightRotation");
        check(clear.hasCall("cancel") && clear.steps.stream().anyMatch(step ->
                        step.opcode == Opcodes.LDC && "GuangxiElytra_safety".equals(step.operand)),
                "Rotation cleanup must include the safety rotation owner");
        check(methods.get("onEnable").call("cancelLandingRecovery") < methods.get("onEnable").call("resetFlightState"),
                "Re-enabling flight must cancel the disabled-module recovery before rebuilding state");
        Map<String, Trace> recovery = readClass(FLIGHT + "$LandingRecovery");
        Trace tick = recovery.get("tick");
        check(tick.hasCall("cancelLandingRecovery") && tick.hasCall("resumeGliding") && tick.hasCall("swapBackChest"),
                "Deferred recovery must handle cancellation, airborne glide recovery and eventual chest restoration");
    }

    private static void verifyLowSpeedSteering(Trace align) {
        int length = align.call("lengthSqr");
        Step threshold = align.steps.get(length + 1);
        Step compare = align.steps.get(length + 2);
        int decision = length + 3;
        check(threshold.opcode == Opcodes.LDC && threshold.operand instanceof Double
                        && (compare.opcode == Opcodes.DCMPL || compare.opcode == Opcodes.DCMPG),
                "Steering must use a finite-vector magnitude guard before normalizing");
        int submit = align.call("submitFlightRotation");
        for (double speed : new double[]{0.0, 0.05, 0.07, 0.25}) {
            int comparison = Double.compare(speed * speed, (Double) threshold.operand);
            boolean jump = unaryBranch(align.steps.get(decision).opcode, comparison);
            Set<Integer> reachable = reachable(align, 0, Map.of(), Map.of(decision, jump));
            check(reachable.contains(submit) == (speed > 0.0),
                    "Every nonzero configured flight speed must still submit its heading: " + speed);
        }
    }

    private static void verifyCorrectionCooldownSafety(Trace tick) {
        int retry = tick.fieldAfter(Opcodes.GETFIELD, "boostRetryTick", -1);
        check(tick.steps.get(retry + 1).opcode == Opcodes.LCMP,
                "Cooldown must compare the current tick against the retry deadline");
        int decision = retry + 2;
        int pc = unaryBranch(tick.steps.get(decision).opcode, -1)
                ? tick.target(tick.steps.get(decision)) : decision + 1;
        Integer cooling = null;
        int variable = -1;
        for (int visited = 0; visited < 8; visited++) {
            Step step = tick.steps.get(pc);
            if (step.opcode == Opcodes.ICONST_0 || step.opcode == Opcodes.ICONST_1) {
                cooling = step.opcode - Opcodes.ICONST_0;
                pc++;
            } else if (step.opcode == Opcodes.GOTO) {
                pc = tick.target(step);
            } else if (step.opcode == Opcodes.ISTORE && cooling != null) {
                variable = (Integer) step.operand;
                pc++;
                break;
            } else {
                throw new AssertionError("Cooldown must retain its decision and continue into flight safety");
            }
        }
        check(variable >= 0 && cooling != null && cooling == 1,
                "A tick before the retry deadline must enter the cooling state");
        Map<Integer, Integer> known = Map.of(variable, cooling);
        Set<Integer> reachable = reachable(tick, pc, known, Map.of());
        for (String safetyCall : List.of("resumeGliding", "applyTerrainSafety", "alignLook")) {
            check(reachable.stream().map(tick.steps::get).anyMatch(step ->
                            step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE
                                    && safetyCall.equals(step.name)),
                    "Correction cooldown must retain a path to " + safetyCall);
        }
        int coolingVariable = variable;
        check(reachable.stream().map(tick.steps::get).noneMatch(step ->
                        step.opcode == Opcodes.ISTORE && Integer.valueOf(coolingVariable).equals(step.operand)),
                "Cooldown ownership must stay stable through this movement tick");
        for (String blockedCall : List.of("tryRocket", "onElytra", "maybeTakeoff", "tickGroundHop")) {
            check(reachable.stream().map(tick.steps::get).noneMatch(step ->
                            step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE
                                    && blockedCall.equals(step.name)),
                    "Correction cooldown must not run active flight propulsion or pursuit: " + blockedCall);
        }
        Set<Integer> normal = reachable(tick, pc, Map.of(variable, 0), Map.of());
        check(normal.contains(tick.call("tryRocket")) && normal.contains(tick.call("onElytra")),
                "The cooldown gate must release propulsion and pursuit again after its deadline");
    }

    private static void verifyFreeLookWiring(Map<String, Trace> methods) {
        Trace constructor = methods.get("<init>");
        int setting = -1;
        for (int index = 0; index < constructor.steps.size(); index++) {
            Step step = constructor.steps.get(index);
            if (step.opcode == Opcodes.LDC && "FreeLook".equals(step.operand)) {
                setting = index;
                break;
            }
        }
        check(setting >= 0 && constructor.steps.get(setting + 1).opcode == Opcodes.ICONST_1,
                "Independent camera movement must be enabled by default");

        Trace update = methods.get("updateViewAnchor");
        int yaw = update.call("getYRot");
        int pitch = update.call("getXRot");
        int captured = update.call("update");
        check(yaw < pitch && pitch < captured && VIEW_ANCHOR.equals(update.steps.get(captured).owner)
                        && !update.hasCall("getLookAngle") && !update.hasCall("getRotationYaw")
                        && !update.hasCall("getRotationPitch"),
                "The anchor must receive raw camera angles, never spoofed flight or server look");
        for (String method : List.of("buildInputDirection", "idleHover", "tickGroundHop", "brakingLook")) {
            Trace trace = methods.get(method);
            check(trace.hasCall(VIEW_ANCHOR, "yaw") || trace.hasCall(VIEW_ANCHOR, "direction"),
                    method + " must read the flight anchor");
            check(!trace.hasCall("getYRot") && !trace.hasCall("getXRot") && !trace.hasCall("getLookAngle"),
                    method + " must not drift with the camera or another rotation owner");
        }
        Trace input = methods.get("buildInputDirection");
        check(input.hasCall(VIEW_ANCHOR, "yaw") && input.hasCall(VIEW_ANCHOR, "direction"),
                "Both CONTROL and ROTATION flight modes must use the independent anchor");

        Trace enable = methods.get("onEnable");
        check(enable.hasCall(VIEW_ANCHOR, "reset") && enable.call("reset") < enable.call("updateViewAnchor"),
                "Enabling flight must capture a new anchor after clearing its previous session");
        check(methods.get("checkFlightSession").hasCall(VIEW_ANCHOR, "reset"),
                "Changing player or world must discard the previous flight anchor");
        for (Map.Entry<String, Trace> entry : methods.entrySet()) {
            if (entry.getValue().hasCall(VIEW_ANCHOR, "reset")) {
                check(List.of("onEnable", "checkFlightSession").contains(entry.getKey()),
                        "Ordinary flight cleanup must not recapture the camera: " + entry.getKey());
            }
            if (entry.getValue().hasCall(VIEW_ANCHOR, "update")) {
                check("updateViewAnchor".equals(entry.getKey()),
                        "All camera sampling must pass through the anchor's lock policy");
            }
        }
        Trace tick = methods.get("onPreTick");
        check(tick.call("checkFlightSession") < tick.call("updateViewAnchor")
                        && tick.call("updateViewAnchor") < tick.call("buildInputDirection"),
                "Tick steering must update the correct session's anchor before reading controls");
        int autoFly = tick.fieldAfter(Opcodes.GETFIELD, "autoFly", -1);
        int autoDirection = tick.callAfter("direction", autoFly);
        check(VIEW_ANCHOR.equals(tick.steps.get(autoDirection).owner)
                        && autoDirection < tick.call("onElytra"),
                "AutoFly must start from the anchored direction before optional target steering");
        int boost = tick.call("useStandaloneGrimBoost");
        int boostDirection = tick.callAfter("direction", boost);
        int boostTurn = tick.callAfter("alignLook", boostDirection);
        check(VIEW_ANCHOR.equals(tick.steps.get(boostDirection).owner) && boostTurn < tick.call("tryRocket"),
                "Standalone boost must retain the anchored heading before firing a rocket");
        check(!methods.get("resetFlightState").hasCall("updateViewAnchor")
                        && !methods.get("tickGroundHop").hasCall("updateViewAnchor"),
                "Correction cleanup and ground hopping must not sample a new camera heading");
    }

    private static void verifyCameraRestoration() throws IOException {
        verifyRotationScope("dev/guizhou/mixin/entity/MixinLivingEntityTravel", "travel",
                "guizhouware$travelHead", "guizhouware$travelReturn", "RETURN",
                "guizhouware$origYaw", "guizhouware$origPitch");
        verifyRotationScope("dev/guizhou/mixin/entity/MixinLocalPlayerRotation", "sendPosition",
                "guizhouware$spoofRotationHead", "guizhouware$spoofRotationTail", "TAIL",
                "guizhouware$savedYaw", "guizhouware$savedPitch");
        verifyRotationScope("dev/guizhou/mixin/network/MixinMultiPlayerGameMode", "useItem",
                "guizhouware$useItemRotateHead", "guizhouware$useItemRotateReturn", "RETURN",
                "guizhouware$origUseYaw", "guizhouware$origUsePitch");
    }

    private static void verifySafetyBoostView(Map<String, Trace> methods) {
        Trace safety = methods.get("applyTerrainSafety");
        check(safety.hasCall("safetyRocketAligned") && !safety.hasCall("getLookAngle"),
                "Pre-steering safety boost readiness must never depend on a freely moved camera");
        Trace look = methods.get("safetyFlightLook");
        check(look.hasCall("finite") && look.hasCall("lengthSqr") && look.hasCall(VIEW_ANCHOR, "direction")
                        && look.fieldAfter(Opcodes.GETFIELD, "lastLook", -1) >= 0,
                "Safety boost must prefer valid actual flight look, with the locked anchor as fallback");
        check(look.steps.stream().noneMatch(step -> step.owner != null
                        && (step.owner.startsWith("net/minecraft/client/")
                        || step.owner.equals("dev/guizhou/manager/RotationManager"))),
                "Looking left, right or behind cannot change the safety boost's selected flight view");
        Trace velocity = methods.get("applyFlightVelocity");
        int saved = velocity.fieldAfter(Opcodes.PUTFIELD, "lastLook", -1);
        Step actual = velocity.steps.get(saved - 1);
        check(actual.opcode == Opcodes.ALOAD && Integer.valueOf(3).equals(actual.operand),
                "Safety alignment must use the look received by the actual glide-physics hook");

        Trace aligned = methods.get("safetyRocketAligned");
        int source = aligned.call("safetyFlightLook");
        int dot = aligned.call("dot");
        check(source < dot && aligned.steps.stream().filter(step -> "normalize".equals(step.name)).count() == 2,
                "Safety alignment must compare normalized actual-flight and escape headings");
        Step threshold = aligned.steps.get(dot + 1);
        check(threshold.opcode == Opcodes.LDC && threshold.operand instanceof Double,
                "Safety alignment must keep its explicit angular tolerance");
        for (double turn : new double[]{0.0, 5.0, 15.0, -90.0, 90.0, 180.0}) {
            double agreement = Math.cos(Math.toRadians(turn));
            int decision = dot + 3;
            int pc = unaryBranch(aligned.steps.get(decision).opcode,
                    Double.compare(agreement, (Double) threshold.operand))
                    ? aligned.target(aligned.steps.get(decision)) : decision + 1;
            check(booleanResult(aligned, pc) == (Math.abs(turn) <= 5.0),
                    "Safety boost still requires the flight itself, not the camera, to align: " + turn);
        }
    }

    private static boolean booleanResult(Trace trace, int pc) {
        Boolean value = null;
        for (int visited = 0; visited < 8; visited++) {
            Step step = trace.steps.get(pc);
            if (step.opcode == Opcodes.ICONST_0 || step.opcode == Opcodes.ICONST_1) {
                value = step.opcode == Opcodes.ICONST_1;
                pc++;
            } else if (step.opcode == Opcodes.GOTO) {
                pc = trace.target(step);
            } else if (step.opcode == Opcodes.IRETURN && value != null) {
                return value;
            } else {
                throw new AssertionError("Expected a compiled boolean decision result");
            }
        }
        throw new AssertionError("Boolean decision did not return");
    }

    private static void verifyRotationScope(String className, String target, String entryName,
                                            String exitName, String exitPoint, String savedYaw, String savedPitch)
            throws IOException {
        Map<String, Trace> hooks = readClass(className);
        Trace entry = hooks.get(entryName);
        Trace exit = hooks.get(exitName);
        check(entry.injectedMethods.contains(target) && entry.injectionPoints.contains("HEAD")
                        && exit.injectedMethods.contains(target) && exit.injectionPoints.contains(exitPoint),
                target + " camera restoration must remain paired with its actual mixin injection");
        for (String[] axis : List.of(new String[]{"getYRot", "setYRot", savedYaw},
                new String[]{"getXRot", "setXRot", savedPitch})) {
            int read = entry.call(axis[0]);
            int save = entry.fieldAfter(Opcodes.PUTFIELD, axis[2], read);
            int spoof = entry.call(axis[1]);
            int restore = exit.call(axis[1]);
            Step source = exit.steps.get(restore - 1);
            check(read < save && save < spoof,
                    target + " must save the camera's " + axis[0] + " before temporary rotation");
            check(source.opcode == Opcodes.GETFIELD && className.equals(source.owner)
                            && axis[2].equals(source.name),
                    target + " must restore " + axis[1] + " from the original camera snapshot");
        }
    }

    // Explore compiled control flow, fixing only the decision under test and leaving game state unconstrained.
    private static Set<Integer> reachable(Trace trace, int start, Map<Integer, Integer> locals,
                                          Map<Integer, Boolean> branches) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int pc = queue.removeFirst();
            if (pc < 0 || pc >= trace.steps.size() || !visited.add(pc)) continue;
            Step step = trace.steps.get(pc);
            if (step.opcode >= Opcodes.IRETURN && step.opcode <= Opcodes.RETURN || step.opcode == Opcodes.ATHROW) continue;
            if (step.opcode == Opcodes.GOTO) {
                queue.add(trace.target(step));
                continue;
            }
            if (step.target != null) {
                Boolean jump = branches.get(pc);
                if (jump == null && pc > 0) {
                    Step previous = trace.steps.get(pc - 1);
                    if (previous.opcode == Opcodes.ILOAD && locals.containsKey((Integer) previous.operand)) {
                        jump = unaryBranch(step.opcode, locals.get((Integer) previous.operand));
                    }
                }
                if (jump == null || jump) queue.add(trace.target(step));
                if (jump == null || !jump) queue.add(pc + 1);
            } else {
                queue.add(pc + 1);
            }
        }
        return visited;
    }

    private static boolean unaryBranch(int opcode, int value) {
        return switch (opcode) {
            case Opcodes.IFEQ -> value == 0;
            case Opcodes.IFNE -> value != 0;
            case Opcodes.IFLT -> value < 0;
            case Opcodes.IFGE -> value >= 0;
            case Opcodes.IFGT -> value > 0;
            case Opcodes.IFLE -> value <= 0;
            default -> throw new AssertionError("Expected one-value control-flow branch: " + opcode);
        };
    }

    private static void assertFalseReturn(Trace trace, int start, String context) {
        check(trace.steps.get(start).opcode == Opcodes.ICONST_0, context + " must produce false");
        int next = start + 1;
        if (trace.steps.get(next).opcode == Opcodes.GOTO) next = trace.target(trace.steps.get(next));
        check(trace.steps.get(next).opcode == Opcodes.IRETURN, context + " must return without enabling standalone boost");
    }

    /** A rocket refusal path may return directly or after only the passive diagnostic write. */
    private static boolean returnsAtMostWithRocketMarker(Trace trace, int index) {
        if (index < 0 || index >= trace.steps.size()) return false;
        Step step = trace.steps.get(index);
        if (step.opcode == Opcodes.RETURN) return true;
        if (step.opcode == Opcodes.ALOAD && Integer.valueOf(0).equals(step.operand())
                && index + 2 < trace.steps.size()) {
            Step constant = trace.steps.get(index + 1);
            boolean load = constant.opcode >= Opcodes.ICONST_M1 && constant.opcode <= Opcodes.ICONST_5
                    || constant.opcode == Opcodes.LDC || constant.opcode == Opcodes.BIPUSH
                    || constant.opcode == Opcodes.SIPUSH;
            Step write = trace.steps.get(index + 2);
            return load && write.opcode == Opcodes.PUTFIELD && "lastRocketBlock".equals(write.name)
                    && index + 3 < trace.steps.size()
                    && trace.steps.get(index + 3).opcode == Opcodes.RETURN;
        }
        return false;
    }

    private static Map<String, Trace> readFlight() throws IOException {
        return readClass(FLIGHT);
    }

    private static Map<String, Trace> readClass(String className) throws IOException {
        Map<String, Trace> methods = new HashMap<>();
        try (InputStream stream = ElytraFlightLifecycleTest.class.getClassLoader().getResourceAsStream(className + ".class")) {
            if (stream == null) throw new IOException("Missing compiled class: " + className);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    Trace trace = new Trace();
                    methods.put(name, trace);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!descriptor.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public AnnotationVisitor visitArray(String name) {
                                    if (name.equals("method")) {
                                        return new AnnotationVisitor(Opcodes.ASM9) {
                                            @Override
                                            public void visit(String name, Object value) {
                                                trace.injectedMethods.add((String) value);
                                            }
                                        };
                                    }
                                    if (!name.equals("at")) return null;
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                            return new AnnotationVisitor(Opcodes.ASM9) {
                                                @Override
                                                public void visit(String name, Object value) {
                                                    if (name.equals("value")) trace.injectionPoints.add((String) value);
                                                }
                                            };
                                        }
                                    };
                                }
                            };
                        }
                        private void add(int opcode, String owner, String name, Label target) {
                            trace.steps.add(new Step(opcode, owner, name, target, null));
                        }
                        @Override
                        public void visitInsn(int opcode) { add(opcode, null, null, null); }
                        @Override
                        public void visitVarInsn(int opcode, int variable) {
                            trace.steps.add(new Step(opcode, null, null, null, variable));
                        }
                        @Override
                        public void visitIntInsn(int opcode, int value) { add(opcode, null, null, null); }
                        @Override
                        public void visitLdcInsn(Object value) {
                            trace.steps.add(new Step(Opcodes.LDC, null, null, null, value));
                        }
                        @Override
                        public void visitTypeInsn(int opcode, String type) { add(opcode, type, null, null); }
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            add(opcode, owner, name, null);
                        }
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            add(opcode, owner, name, null);
                        }
                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                                           Object... arguments) {
                            add(Opcodes.INVOKEDYNAMIC, null, name, null);
                            for (Object argument : arguments) {
                                if (argument instanceof Handle handle) trace.handles.add(handle);
                            }
                        }
                        @Override
                        public void visitJumpInsn(int opcode, Label target) { add(opcode, null, null, target); }
                        @Override
                        public void visitLabel(Label label) { trace.labels.put(label, trace.steps.size()); }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return methods;
    }

    private static int pop(List<Integer> stack) { return stack.removeLast(); }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Trace {
        final List<Step> steps = new ArrayList<>();
        final Map<Label, Integer> labels = new IdentityHashMap<>();
        final List<Handle> handles = new ArrayList<>();
        final Set<String> injectedMethods = new HashSet<>();
        final Set<String> injectionPoints = new HashSet<>();

        int target(Step step) { return labels.get(step.target); }
        boolean hasCall(String name) {
            return steps.stream().anyMatch(step -> name.equals(step.name)
                    && step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE);
        }
        boolean hasCall(String owner, String name) {
            return steps.stream().anyMatch(step -> owner.equals(step.owner) && name.equals(step.name)
                    && step.opcode >= Opcodes.INVOKEVIRTUAL && step.opcode <= Opcodes.INVOKEINTERFACE);
        }
        int call(String name) { return callAfter(name, -1); }
        int callAfter(String name, int after) {
            for (int index = after + 1; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (name.equals(step.name) && step.opcode >= Opcodes.INVOKEVIRTUAL
                        && step.opcode <= Opcodes.INVOKEINTERFACE) return index;
            }
            throw new AssertionError("Missing call: " + name);
        }
        int fieldAfter(int opcode, String name, int after) {
            for (int index = after + 1; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (step.opcode == opcode && name.equals(step.name)) return index;
            }
            throw new AssertionError("Missing field access: " + name);
        }
    }

    private record Step(int opcode, String owner, String name, Label target, Object operand) {
    }
}
