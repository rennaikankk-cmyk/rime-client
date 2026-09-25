package dev.guizhou.features.modules.combat;

import com.google.gson.JsonElement;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.*;
import dev.guizhou.mixin.entity.LivingEntityAttackAccessor;
import dev.guizhou.util.*;
import dev.guizhou.util.combat.*;
import dev.guizhou.util.combat.AuraPolicy.*;
import dev.guizhou.util.inventory.*;
import dev.guizhou.util.player.EatUtil;
import dev.guizhou.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.function.Consumer;

/** Stable configuration ID remains AutoSword; the UI and commands use Aura. */
public class AutoSwordModule extends Module {
    public enum TpsMode { NONE, LATEST, AVERAGE }
    public enum CritMode { OFF, SMART, ONLY }
    private final Setting<TargetMode> targetMode = mode("Mode", TargetMode.SINGLE);
    private final Setting<Integer> maxTargets = num("MaxTargets", 3, 1, 16).setVisibility(v -> targetMode.getValue() == TargetMode.MULTI);
    private final Setting<SortPriority> sort = mode("SortPriority", SortPriority.DISTANCE);
    private final Setting<Double> range = num("Range", 3.0, 1.0, 6.0);
    private final Setting<Double> wallsRange = num("WallsRange", 0.0, 0.0, 6.0);
    private final Setting<Double> fov = num("FOV", 360.0, 1.0, 360.0);
    private final Setting<Boolean> rotate = bool("Rotate", true);
    private final RotationOptions rotation = new RotationOptions(this, rotate);
    private final Setting<Boolean> swing = bool("Swing", true);
    private final Setting<Boolean> render = bool("Render", true);
    private final Setting<TpsMode> tpsMode = mode("TPS", TpsMode.LATEST);
    private final Setting<Double> attackCD = num("AttackCD", 1.0, 0.1, 1.0);
    private final Setting<Boolean> customDelay = bool("CustomDelay", false);
    private final Setting<Double> hitDelay = num("HitDelay", 10.0, 1.0, 40.0).setVisibility(v -> customDelay.getValue());
    private final Setting<Integer> switchDelay = num("SwitchDelay", 0, 0, 10);
    private final Setting<CritMode> critMode = mode("CritMode", CritMode.OFF);
    private final Setting<Boolean> pauseEating = bool("PauseEating", true);
    private final Setting<Boolean> multitask = bool("Multitask", false);
    private final Setting<Boolean> pauseScreen = bool("PauseScreen", true);
    private final Setting<Boolean> crystalPriority = bool("CrystalPriority", true);
    private final Setting<WeaponMode> weaponMode = mode("WeaponMode", WeaponMode.SWAP).setPage("Weapon");
    private final Setting<Weapon> weapon = mode("Weapon", Weapon.ALL).setPage("Weapon");
    private final Setting<SwapAction> swapAction = mode("SwapAction", SwapAction.SILENT).setPage("Weapon");
    private final Setting<MaceMode> maceMode = mode("MaceMode", MaceMode.SMART).setPage("Weapon");
    private final Setting<ShieldMode> shieldMode = mode("ShieldMode", ShieldMode.BREAK).setPage("Weapon");
    private final Setting<Boolean> players = bool("Players", true).setPage("Targets");
    private final Setting<Boolean> animals = bool("Animals", false).setPage("Targets");
    private final Setting<Boolean> hostiles = bool("Hostiles", false).setPage("Targets");
    private final Setting<Boolean> neutrals = bool("Neutrals", false).setPage("Targets");
    private final Setting<Boolean> ignoreNakeds = bool("IgnoreNakeds", false).setPage("Targets");
    private final Setting<Boolean> ignoreNamed = bool("IgnoreNamed", false).setPage("Targets");
    private final Setting<Boolean> ignoreTamed = bool("IgnoreTamed", true).setPage("Targets");
    private final Setting<MobAge> mobAge = mode("MobAge", MobAge.BOTH).setPage("Targets");
    private final Setting<Boolean> tp = bool("TP", false).setPage("TP");
    private final Setting<Double> tpRange = num("TPRange", 12.0, 4.0, 32.0).setPage("TP").setVisibility(v -> tp.getValue());
    private final Setting<Double> tpStep = num("TPStep", 3.0, 1.0, 5.0).setPage("TP").setVisibility(v -> tp.getValue());
    private final Setting<Boolean> stopOnSetback = bool("StopOnSetback", true).setPage("TP").setVisibility(v -> tp.getValue());
    private Entity currentTarget;
    private final AuraPolicy.Clock clock = new AuraPolicy.Clock();
    private final Map<UUID,Long> lastHit = new HashMap<>();
    private final Map<UUID,Integer> pathRetry = new HashMap<>();
    private long hitSequence;
    private int desiredSlot = -1, changedAt, submittedSlot = -1, recoverSlot = -1;
    private int sendingTarget = -1, lastTeleportTick = Integer.MIN_VALUE, servicedTick = Integer.MIN_VALUE;
    private boolean servicedOk;
    private boolean attackSubmitted, tpBlocked;
    private Vec3 submittedPosition, pendingReturn;
    private Object sessionPlayer, sessionLevel, sessionConnection;
    private final Consumer<Packet<?>> submissions = this::onSubmitted;

    public AutoSwordModule() {
        super("AutoSword", "Aura with weapon selection, multiple targets and optional return teleport.", Category.COMBAT);
    }
    @Override public void onEnable() {
        resetState();
        PacketSendUtil.addSubmissionListener(submissions);
    }
    @Override public void onDisable() {
        resetState();
        PacketSendUtil.removeSubmissionListener(submissions);
    }
    private void resetState() {
        restorePosition();
        if (!nullCheck() && sameSession() && recoverSlot >= 0) ensureSlot(recoverSlot);
        currentTarget = null;
        desiredSlot = -1;
        lastHit.clear();
        pathRetry.clear();
        hitSequence = 0;
        if (GuizhouWare.swapManager != null) releaseWeapon();
        sessionPlayer = sessionLevel = sessionConnection = null;
        pendingReturn = submittedPosition = null;
        submittedSlot = recoverSlot = -1;
        tpBlocked = false;
        lastTeleportTick = Integer.MIN_VALUE;
        if (GuizhouWare.rotationManager != null) GuizhouWare.rotationManager.cancel("Aura");
    }
    private boolean sameSession() {
        return mc.player == sessionPlayer && mc.level == sessionLevel && mc.getConnection() == sessionConnection;
    }
    private void session() {
        if (sameSession()) return;
        resetState();
        sessionPlayer = mc.player; sessionLevel = mc.level; sessionConnection = mc.getConnection();
        submittedSlot = mc.player.getInventory().selected;
        submittedPosition = mc.player.position();
        clock.reset(mc.player.tickCount, ((LivingEntityAttackAccessor)mc.player).guizhouware$attackStrengthTicks());
    }
    private void onSubmitted(Packet<?> packet) {
        if (!mc.isSameThread() || nullCheck() || !sameSession()) return;
        if (packet instanceof ServerboundSetCarriedItemPacket slot) submittedSlot = slot.getSlot();
        if (packet instanceof ServerboundMovePlayerPacket move && move.hasPosition())
            submittedPosition = new Vec3(move.getX(mc.player.getX()),move.getY(mc.player.getY()),move.getZ(mc.player.getZ()));
        if (packet instanceof ServerboundInteractPacket interact) interact.dispatch(new ServerboundInteractPacket.Handler() {
            public void onInteraction(InteractionHand hand) {}
            public void onInteraction(InteractionHand hand, Vec3 location) {}
            public void onAttack() {
                clock.submitted();
                if (interact.entityId == sendingTarget) attackSubmitted = true;
            }
        });
    }
    @Subscribe private void onReceive(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundPlayerPositionPacket)) return;
        Object connection = mc.getConnection();
        mc.execute(() -> {
            if (!isEnabled() || nullCheck() || connection != mc.getConnection() || !sameSession()
                    || (long)mc.player.tickCount - lastTeleportTick > 40) return;
            tpBlocked = stopOnSetback.getValue();
            pendingReturn = null; // A server correction supersedes the old origin.
            currentTarget = null;
            GuizhouWare.rotationManager.cancel("Aura");
        });
    }
    @Subscribe private void onPreTick(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) { resetState(); return; }
        if (!serviceTick()) return;
        KillAuraModule killAura = GuizhouWare.moduleManager.getModuleByClass(KillAuraModule.class);
        if (killAura != null && killAura.isEnabled()) return;
        engage(findTarget(tp.getValue() && !tpBlocked), rotate.getValue());
    }

    /** Clock, session and position recovery. Idempotent within one player tick. */
    public boolean serviceTick() {
        if (!nullCheck() && servicedTick == mc.player.tickCount && sameSession()) return servicedOk;
        servicedOk = false;
        if (!HeartbeatManager.authorized()) { resetState(); return false; }
        if (nullCheck() || mc.player.isDeadOrDying() || mc.getConnection() == null || mc.gameMode == null) { resetState(); return false; }
        session();
        servicedTick = mc.player.tickCount;
        clock.tick(mc.player.tickCount, switch (tpsMode.getValue()) {
            case LATEST -> GuizhouWare.tpsCounterService.getLatestTPS();
            case AVERAGE -> GuizhouWare.tpsCounterService.getAverageTPS();
            default -> 20;
        });
        if (!restorePosition()) return false;
        if (recoverSlot >= 0) {
            if (!ensureSlot(recoverSlot)) return false;
            recoverSlot = -1;
        }
        if (weaponMode.getValue() != WeaponMode.SWAP || swapAction.getValue() != SwapAction.SWAPBACK) releaseWeapon();
        if (paused() || GuizhouWare.tpsCounterService.isRubberbandRecent()) { clearTarget(); return false; }
        servicedOk = true;
        return true;
    }

    public boolean coolingDown() {
        return nullCheck() || !ready(mc.player.getMainHandItem());
    }

    /** Shared attack used by KillAura. AutoSword's own tick does not also swing while KillAura is on. */
    public boolean tryAttack(Entity target, boolean doRotate) {
        if (!HeartbeatManager.authorized()) return false;
        return engage(target, doRotate) == Engage.HIT;
    }

    private enum Engage { SKIP, STOP, HIT }

    private Engage engage(Entity next, boolean doRotate) {
        if (!HeartbeatManager.authorized()) return Engage.STOP;
        if (next != currentTarget) { changedAt = mc.player.tickCount; desiredSlot = -1; }
        currentTarget = next;
        if (next == null) { clearTarget(); return Engage.STOP; }
        int slot = getWeapon(next);
        if (slot < 0) return Engage.SKIP;
        if (desiredSlot != slot) { desiredSlot = slot; changedAt = mc.player.tickCount; }
        ItemStack stack = mc.player.getInventory().getItem(slot);
        if (mc.player.tickCount - changedAt < switchDelay.getValue() || !ready(stack) || !critReady()) return Engage.STOP;
        GuangxiCrystalModule crystal = GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
        if (crystalPriority.getValue() && crystal != null && crystal.isEnabled() && crystal.getLastBestDamage() > 0 && !isMaceAttackReady()) {
            GuizhouWare.rotationManager.cancel("Aura");
            releaseWeapon();
            return Engage.STOP;
        }
        Vec3 origin = mc.player.position();
        boolean distant = !inReach(next, mc.player.getEyePosition());
        List<Vec3> path = distant ? teleportPath(next) : List.of();
        if (distant && path.isEmpty()) {
            pathRetry.put(next.getUUID(), mc.player.tickCount + 10);
            clearTarget();
            return Engage.STOP;
        }
        Vec3 actionFeet = distant ? path.getLast() : origin;
        Vec3 eye = actionFeet.add(0, mc.player.getEyeHeight(), 0);
        Vec3 aim = aimPoint(next, eye);
        if (aim == null) return Engage.STOP;
        float[] angles = MathUtil.calcAngle(eye, aim);
        if (doRotate && !GuizhouWare.rotationManager.tryAction(rotation.request("Aura", 6, angles[0], angles[1], RotationRequest.Mode.SILENT))) return Engage.STOP;
        float yaw = GuizhouWare.rotationManager.getServerYaw(), pitch = GuizhouWare.rotationManager.getServerPitch();
        boolean[] struck = {false};
        Runnable action = () -> {
            if (!ensureSlot(slot)) return;
            if (!distant) struck[0] = attack(next, eye, yaw, pitch, doRotate);
            else {
                pendingReturn = origin;
                AuraTeleport.Result result = AuraTeleport.roundTrip(origin, path,
                        point -> sendTeleport(point, yaw, pitch),
                        () -> {
                            boolean hit = attack(next, eye, yaw, pitch, doRotate);
                            struck[0] = hit;
                            return hit;
                        },
                        point -> moveTo(point, yaw, pitch));
                if (result.restored()) pendingReturn = null;
            }
        };
        if (weaponMode.getValue() != WeaponMode.SWAP) action.run();
        else if (swapAction.getValue() == SwapAction.SILENT) {
            try { GuizhouWare.swapManager.withSwap(new Result(slot, stack, ResultType.HOTBAR), SwapMode.SILENT, SwapPriority.WEAPON, action); }
            finally {
                if (!nullCheck() && sameSession()) {
                    int restore = mc.player.getInventory().selected;
                    if (submittedSlot != restore && !ensureSlot(restore)) recoverSlot = restore;
                }
            }
        } else {
            if (swapAction.getValue() == SwapAction.SWAPBACK) {
                if (GuizhouWare.swapManager.isLatched(this) && GuizhouWare.swapManager.latchedSlot() != slot) releaseWeapon();
                if (!GuizhouWare.swapManager.latch(this, new Result(slot, stack, ResultType.HOTBAR))) return Engage.STOP;
            } else {
                if (GuizhouWare.swapManager.isLatched()) return Engage.STOP;
                InventoryUtil.swap(slot);
            }
            action.run();
        }
        return struck[0] ? Engage.HIT : Engage.SKIP;
    }

    public List<Entity> selectTargets(SortPriority order, int limit) {
        if (nullCheck()) return List.of();
        Vec3 eye = mc.player.getEyePosition();
        boolean allowTp = tp.getValue() && !tpBlocked;
        double search = allowTp ? Math.max(reach(), tpRange.getValue()) : reach();
        Comparator<Entity> cmp = switch (order) {
            case DISTANCE -> Comparator.comparingDouble(e -> eye.distanceToSqr(clampToBox(eye, e.getBoundingBox())));
            case CROSSHAIR -> Comparator.comparingDouble(this::crosshair);
            case HEALTH -> Comparator.comparingDouble(e -> ((LivingEntity) e).getHealth() + ((LivingEntity) e).getAbsorptionAmount());
        };
        return AuraPolicy.candidates(mc.level.getEntities(null, mc.player.getBoundingBox().inflate(search + 1)),
                e -> eligible(e) && pathRetry.getOrDefault(e.getUUID(), 0) <= mc.player.tickCount
                        && (inReach(e, eye) || (allowTp && eye.distanceTo(clampToBox(eye, e.getBoundingBox())) <= search)),
                cmp.thenComparingInt(Entity::getId), limit);
    }
    private void releaseWeapon() {
        boolean owned = GuizhouWare.swapManager.isLatched(this);
        GuizhouWare.swapManager.release(this);
        if (owned && !nullCheck() && sameSession()) {
            int restore = mc.player.getInventory().selected;
            if (submittedSlot != restore && !ensureSlot(restore)) recoverSlot = restore;
        }
    }
    private void clearTarget() {
        currentTarget = null;
        desiredSlot = -1;
        GuizhouWare.rotationManager.cancel("Aura");
        releaseWeapon();
    }
    private boolean paused() {
        return GuangxiElytraChaseModule.isWeaponControlActive()
                || (pauseScreen.getValue() && mc.screen != null)
                || (pauseEating.getValue() && (EatUtil.shouldDefer() || mc.player.isUsingItem()))
                || (!multitask.getValue() && (mc.player.isUsingItem() || mc.gameMode.isDestroying()));
    }
    private boolean ready(ItemStack stack) {
        double required = customDelay.getValue() ? hitDelay.getValue()
                : AuraPolicy.cooldown(AuraWeaponAttributes.value(mc.player,stack,Attributes.ATTACK_SPEED),attackCD.getValue());
        return clock.ready(required);
    }
    private boolean critReady() {
        if (critMode.getValue() == CritMode.OFF || mc.player.isInWater() || mc.player.isInLava()
                || mc.player.onClimbable() || mc.player.isPassenger() || mc.player.isFallFlying()) return true;
        boolean falling = !mc.player.onGround() && mc.player.fallDistance > 0;
        return falling || (critMode.getValue() == CritMode.SMART && mc.player.onGround());
    }
    private boolean ensureSlot(int slot) {
        if (slot == submittedSlot) return true;
        return PacketSendUtil.send(new ServerboundSetCarriedItemPacket(slot)) && submittedSlot == slot;
    }
    private boolean attack(Entity target, Vec3 eye, float yaw, float pitch, boolean doRotate) {
        if (!HeartbeatManager.authorized()) return false;
        if (!sameSession() || paused() || !eligible(target) || !inReach(target,eye)
                || submittedSlot != desiredSlot || !ready(mc.player.getInventory().getItem(desiredSlot))) return false;
        Vec3 end = eye.add(getLookVector(yaw,pitch).scale(reach()));
        Vec3 hit = target.getBoundingBox().contains(eye) ? eye : target.getBoundingBox().clip(eye,end).orElse(null);
        if (hit == null) return false;
        if (pendingReturn != null && (submittedPosition == null || submittedPosition.add(0,mc.player.getEyeHeight(),0).distanceToSqr(eye) > 1e-8)) return false;
        HitResult block = mc.level.clip(new ClipContext(eye,hit,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
        if (block.getType() != HitResult.Type.MISS && eye.distanceTo(hit) > Math.min(reach(),wallsRange.getValue())) return false;
        if (doRotate && !GuizhouWare.rotationManager.isSynchronized(yaw,pitch)) return false;
        attackSubmitted = false;
        sendingTarget = target.getId();
        try { PacketSendUtil.send(ServerboundInteractPacket.createAttackPacket(target,mc.player.isShiftKeyDown())); }
        finally { sendingTarget = -1; }
        if (!attackSubmitted) return false;
        mc.player.resetAttackStrengthTicker();
        lastHit.put(target.getUUID(),++hitSequence);
        if (swing.getValue()) mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }
    private double reach() { return Math.min(range.getValue(),mc.player.entityInteractionRange()); }
    private boolean inReach(Entity target, Vec3 eye) { return aimPoint(target,eye) != null; }
    private Vec3 aimPoint(Entity target, Vec3 eye) {
        AABB box = target.getBoundingBox();
        Vec3 closest = getClosestPointToEye(eye,box), center = box.getCenter();
        for (Vec3 point : List.of(closest,center,new Vec3(center.x,box.maxY-0.1,center.z),new Vec3(center.x,box.minY+0.1,center.z))) {
            if (eye.distanceTo(point) > reach()) continue;
            HitResult block = mc.level.clip(new ClipContext(eye,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
            if (block.getType() == HitResult.Type.MISS) return point;
        }
        return eye.distanceTo(closest) <= Math.min(reach(),wallsRange.getValue()) ? closest : null;
    }
    private boolean eligible(Entity entity) {
        if (!(entity instanceof LivingEntity living) || entity == mc.player || !entity.isAlive()
                || entity.isRemoved() || entity.isSpectator() || !entity.isAttackable()) return false;
        if (entity instanceof Player player) {
            if (!players.getValue() || GuizhouWare.friendManager.isFriend(player)) return false;
            if (ignoreNakeds.getValue() && player.getArmorValue() == 0) return false;
        } else {
            boolean allowed = TargetsModule.isHostile(entity) ? hostiles.getValue()
                    : TargetsModule.isNeutral(entity) ? neutrals.getValue()
                    : TargetsModule.isPassive(entity) && animals.getValue();
            if (!allowed || (ignoreNamed.getValue() && entity.hasCustomName())) return false;
            if (ignoreTamed.getValue() && entity instanceof TamableAnimal tame && tame.isTame()) return false;
            if (mobAge.getValue() != MobAge.BOTH && living.isBaby() != (mobAge.getValue() == MobAge.BABY)) return false;
        }
        if (shieldMode.getValue() == ShieldMode.IGNORE && blocking(entity)) return false;
        return crosshair(entity) <= fov.getValue()/2;
    }
    private boolean blocking(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !living.isBlocking()) return false;
        Vec3 toward = mc.player.position().subtract(entity.position()).normalize();
        return living.getLookAngle().multiply(1,0,1).dot(toward) > 0;
    }
    private double crosshair(Entity target) {
        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(),target.getBoundingBox().getCenter());
        return Math.abs(Mth.wrapDegrees(angles[0]-mc.player.getYRot()));
    }
    private Entity findTarget(boolean allowTp) {
        Vec3 eye = mc.player.getEyePosition();
        double search = allowTp ? Math.max(reach(),tpRange.getValue()) : reach();
        Comparator<Entity> order = switch (sort.getValue()) {
            case DISTANCE -> Comparator.comparingDouble(e -> eye.distanceToSqr(clampToBox(eye,e.getBoundingBox())));
            case CROSSHAIR -> Comparator.comparingDouble(this::crosshair);
            case HEALTH -> Comparator.comparingDouble(e -> ((LivingEntity)e).getHealth()+((LivingEntity)e).getAbsorptionAmount());
        };
        List<Entity> candidates = AuraPolicy.candidates(mc.level.getEntities(null,mc.player.getBoundingBox().inflate(search+1)),
                e -> eligible(e) && pathRetry.getOrDefault(e.getUUID(),0) <= mc.player.tickCount && (inReach(e,eye) || (allowTp && eye.distanceTo(clampToBox(eye,e.getBoundingBox())) <= search)),
                order.thenComparingInt(Entity::getId), targetMode.getValue() == TargetMode.MULTI ? maxTargets.getValue() : 16);
        if (lastHit.size() > 128) lastHit.keySet().retainAll(candidates.stream().map(Entity::getUUID).toList());
        return AuraPolicy.choose(candidates,currentTarget,targetMode.getValue(),e -> lastHit.getOrDefault(e.getUUID(),0L));
    }
    public boolean isMaceAttackReady() {
        if (!isEnabled() || nullCheck() || !sameSession() || mc.gameMode == null || mc.getConnection() == null || paused()
                || !MaceItem.canSmashAttack(mc.player) || !critReady()) return false;
        Entity target = currentTarget != null && eligible(currentTarget) && inReach(currentTarget,mc.player.getEyePosition())
                ? currentTarget : findTarget(false);
        if (target == null) return false;
        int slot = getWeapon(target);
        return slot >= 0 && mc.player.getInventory().getItem(slot).getItem() instanceof MaceItem
                && (slot == submittedSlot || !mc.player.isUsingItem())
                && (switchDelay.getValue() == 0 || (currentTarget == target && desiredSlot == slot
                    && mc.player.tickCount - changedAt >= switchDelay.getValue()))
                && (rotate.getValue() || target.getBoundingBox().clip(mc.player.getEyePosition(),mc.player.getEyePosition().add(
                    getLookVector(GuizhouWare.rotationManager.getServerYaw(),GuizhouWare.rotationManager.getServerPitch()).scale(reach()))).isPresent())
                && ready(mc.player.getInventory().getItem(slot));
    }
    private boolean allowedWeapon(ItemStack stack) {
        boolean sword = stack.is(ItemTags.SWORDS), axe = stack.is(ItemTags.AXES);
        boolean mace = stack.getItem() instanceof MaceItem && maceMode.getValue() != MaceMode.NONE;
        boolean trident = stack.getItem() instanceof TridentItem;
        return switch (weapon.getValue()) {
            case ALL -> sword || axe || mace || trident;
            case SWORD -> sword; case AXE -> axe; case MACE -> mace; case TRIDENT -> trident;
        };
    }
    private int getWeapon(Entity target) {
        int selected = mc.player.getInventory().selected;
        if (weaponMode.getValue() == WeaponMode.NONE) return selected;
        if (weaponMode.getValue() == WeaponMode.REQUIRE) return allowedWeapon(mc.player.getMainHandItem()) ? selected : -1;
        int best = -1;
        double bestScore = -Double.MAX_VALUE;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            boolean axe = stack.is(ItemTags.AXES) && shieldMode.getValue() == ShieldMode.BREAK && blocking(target);
            if (!axe && !allowedWeapon(stack)) continue;
            double damage = smashScore(stack,target);
            if (target instanceof LivingEntity living)
                damage = AuraPolicy.afterArmor(damage,living.getArmorValue(),living.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                        stack.getItem() instanceof MaceItem ? EnchantmentUtil.getLevel(Enchantments.BREACH,stack) : 0);
            double score = MaceItem.canSmashAttack(mc.player) ? damage
                    : damage * AuraWeaponAttributes.value(mc.player,stack,Attributes.ATTACK_SPEED);
            if (axe) score += 1000000;
            if (stack.getItem() instanceof MaceItem && MaceItem.canSmashAttack(mc.player)) {
                if (maceMode.getValue() == MaceMode.BREACH) score += 1000 * EnchantmentUtil.getLevel(Enchantments.BREACH,stack);
                if (maceMode.getValue() == MaceMode.DENSITY) score += 1000 * EnchantmentUtil.getLevel(Enchantments.DENSITY,stack);
            }
            if (Double.isFinite(score) && (score > bestScore || (score == bestScore && slot == selected))) { best = slot; bestScore = score; }
        }
        return best;
    }
    private List<Vec3> teleportPath(Entity target) {
        if (!tp.getValue() || tpBlocked || mc.player.isPassenger() || mc.player.isFallFlying()) return List.of();
        Vec3 origin = mc.player.position();
        Vec3 away = origin.subtract(target.position()).multiply(1,0,1).normalize();
        if (away.lengthSqr() < 0.01) away = new Vec3(1,0,0);
        Vec3 goal = target.position().add(away.scale(Math.min(2,reach()*0.65)));
        if (!inReach(target,goal.add(0,mc.player.getEyeHeight(),0))) return List.of();
        return AuraPath.find(point(origin),point(goal),tpStep.getValue(),tpRange.getValue(),384,
                (from,to) -> clearSegment(vec(from),vec(to))).stream().map(AutoSwordModule::vec).toList();
    }
    private boolean clearSegment(Vec3 from, Vec3 to) {
        AABB swept = mc.player.getBoundingBox().move(from.subtract(mc.player.position())).expandTowards(to.subtract(from)).deflate(1e-5);
        if (swept.minY < mc.level.getMinBuildHeight() || swept.maxY > mc.level.getMaxBuildHeight()
                || !mc.level.getWorldBorder().isWithinBounds(swept)) return false;
        for (int x = Mth.floor(swept.minX)>>4; x <= Mth.floor(swept.maxX)>>4; x++)
            for (int z = Mth.floor(swept.minZ)>>4; z <= Mth.floor(swept.maxZ)>>4; z++)
                if (!mc.level.hasChunk(x,z)) return false;
        return mc.level.noCollision(mc.player,swept);
    }
    private boolean sendTeleport(Vec3 point, float yaw, float pitch) {
        if (!HeartbeatManager.authorized()) return false;
        if (nullCheck() || !sameSession() || !tp.getValue() || tpBlocked || submittedPosition == null
                || !clearSegment(submittedPosition,point)) return false;
        lastTeleportTick = mc.player.tickCount;
        return moveTo(point,yaw,pitch);
    }
    private boolean moveTo(Vec3 point, float yaw, float pitch) {
        if (nullCheck() || !sameSession()) return false;
        boolean sent = PacketSendUtil.send(new ServerboundMovePlayerPacket.PosRot(point.x,point.y,point.z,yaw,pitch,false));
        return sent && submittedPosition != null && submittedPosition.distanceToSqr(point) < 1e-10
                && GuizhouWare.rotationManager.isSynchronized(yaw,pitch);
    }
    private boolean restorePosition() {
        if (pendingReturn == null) return true;
        if (!sameSession() || nullCheck()) { pendingReturn = null; return true; }
        if (!moveTo(pendingReturn,GuizhouWare.rotationManager.getServerYaw(),GuizhouWare.rotationManager.getServerPitch())) return false;
        pendingReturn = null;
        return true;
    }
    private static AuraPath.Point point(Vec3 v) { return new AuraPath.Point(v.x,v.y,v.z); }
    private static Vec3 vec(AuraPath.Point p) { return new Vec3(p.x(),p.y(),p.z()); }
    @Override public String getDisplayInfo() {
        return currentTarget == null ? (tpBlocked ? "TP paused" : null) : currentTarget.getName().getString();
    }
    @Override public void fromJson(JsonElement element) {
        super.fromJson(AuraConfigMigration.migrate(element));
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || currentTarget == null || nullCheck() || mc.player.isDeadOrDying()) return;

        GuangxiCrystalModule ac = GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
        if (ac != null && ac.isEnabled() && ac.getLastBestDamage() >= ac.getMinDamage()) return;

        float partialTicks = event.getDelta();
        double interpX = currentTarget.xOld + (currentTarget.getX() - currentTarget.xOld) * partialTicks;
        double interpY = currentTarget.yOld + (currentTarget.getY() - currentTarget.yOld) * partialTicks;
        double interpZ = currentTarget.zOld + (currentTarget.getZ() - currentTarget.zOld) * partialTicks;
        AABB box = currentTarget.getBoundingBox().move(
            interpX - currentTarget.getX(),
            interpY - currentTarget.getY(),
            interpZ - currentTarget.getZ()
        );

        RenderUtil.drawBox(event.getMatrix(), box, GuizhouWare.colorManager.get("ui"), 1.5f);
    }

    private double smashScore(ItemStack weapon, Entity target) {
        double base = AuraWeaponAttributes.value(mc.player, weapon, Attributes.ATTACK_DAMAGE);
        int targetLevels = 0;
        if (target.getType().is(EntityTypeTags.SENSITIVE_TO_SMITE))
            targetLevels += EnchantmentUtil.getLevel(Enchantments.SMITE, weapon);
        if (target.getType().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS))
            targetLevels += EnchantmentUtil.getLevel(Enchantments.BANE_OF_ARTHROPODS, weapon);
        if (target.getType().is(EntityTypeTags.SENSITIVE_TO_IMPALING))
            targetLevels += EnchantmentUtil.getLevel(Enchantments.IMPALING, weapon);
        double enchantments = MaceDamageScore.enchantmentDamage(
                EnchantmentUtil.getLevel(Enchantments.SHARPNESS, weapon), targetLevels);
        double fallBonus = 0.0;
        int density = 0;
        if (weapon.getItem() instanceof MaceItem mace && MaceItem.canSmashAttack(mc.player)) {
            // The client branch provides vanilla fall damage; Density is applied only on ServerLevel.
            fallBonus = mace.getAttackDamageBonus(target, (float) base, mc.player.damageSources().source(
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "mace_smash")),
                        mc.player));
            density = EnchantmentUtil.getLevel(Enchantments.DENSITY, weapon);
        }
        return MaceDamageScore.damage(base, enchantments, fallBonus, mc.player.fallDistance, density);
    }

    private Vec3 getClosestPointToEye(Vec3 eye, AABB box) {
        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);

        final double VEC = 1.0 / 16.0;
        final double EPS = 1e-9;
        if (Math.abs(x - box.minX) < EPS) x = Math.min(box.minX + VEC, box.maxX - EPS);
        else if (Math.abs(x - box.maxX) < EPS) x = Math.max(box.maxX - VEC, box.minX + EPS);
        if (Math.abs(z - box.minZ) < EPS) z = Math.min(box.minZ + VEC, box.maxZ - EPS);
        else if (Math.abs(z - box.maxZ) < EPS) z = Math.max(box.maxZ - VEC, box.minZ + EPS);

        return new Vec3(x, y, z);
    }

    private Vec3 clampToBox(Vec3 point, AABB box) {
        return new Vec3(
            Mth.clamp(point.x, box.minX, box.maxX),
            Mth.clamp(point.y, box.minY, box.maxY),
            Mth.clamp(point.z, box.minZ, box.maxZ)
        );
    }

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }
}
