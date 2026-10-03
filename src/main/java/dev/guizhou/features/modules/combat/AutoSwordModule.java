package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.combat.AuraPath;
import dev.guizhou.util.combat.AuraTeleport;
import dev.guizhou.util.combat.AttackCooldown;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import dev.guizhou.util.EnchantmentUtil;
import dev.guizhou.util.combat.MaceDamageScore;
import dev.guizhou.util.MathUtil;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.player.EatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.ClipContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class AutoSwordModule extends Module {

    public enum TpsMode { NONE, LATEST, AVERAGE }

    private static final double RANGE = 3.0;

    private final Setting<Double> delay = num("Delay", 0.92, 0.0, 1.0);
    private final Setting<Boolean> swing = bool("Swing", true);
    private final Setting<Boolean> render = bool("Render", true);
    private final Setting<TpsMode> tpsMode = mode("TPS", TpsMode.LATEST);

    private final Setting<Boolean> criticals = bool("Criticals", false);
    private final Setting<Boolean> critsWithSword = bool("CritsWithSword", true)
            .setVisibility(v -> criticals.getValue());
    private final Setting<Boolean> strict = bool("Strict", true)
            .setVisibility(v -> criticals.getValue());
    private final Setting<TargetSort> sort = mode("Sort", TargetSort.DISTANCE);
    private final Setting<Integer> targets = num("Targets", 1, 1, 3);
    private final Setting<Boolean> rotate = bool("Rotate", true);
    private final Setting<Boolean> noAc = bool("NoAC", false)
            .described("无反作弊服使用。只检查距离和遮挡，关闭 Rotate 时不等待服务端视角命中。");
    private final Setting<Boolean> swordGap = bool("SwordGap", false);
    private final Setting<Boolean> tp = bool("TP", false).setPage("TP");
    private final Setting<Double> tpRange = num("TPRange", 12.0, 4.0, 32.0).setPage("TP").setVisibility(v -> tp.getValue());
    private final Setting<Double> tpStep = num("TPStep", 3.0, 1.0, 5.0).setPage("TP").setVisibility(v -> tp.getValue());
    private final Setting<Boolean> stopOnSetback = bool("StopOnSetback", true).setPage("TP").setVisibility(v -> tp.getValue());

    private Entity currentTarget = null;
    private final AttackCooldown attackCooldown = new AttackCooldown();
    private boolean auraActive;
    private int projectileTick = Integer.MIN_VALUE;
    private final java.util.Set<Integer> hitProjectiles = new java.util.HashSet<>();
    private int targetTick = Integer.MIN_VALUE;
    private int weaponTick = Integer.MIN_VALUE;
    private int weaponTargetId = Integer.MIN_VALUE;
    private int cachedWeapon = -1;
    private boolean gapLatched;
    private final Object gapOwner = new Object();
    private final Map<UUID, Integer> pathRetry = new java.util.HashMap<>();
    private final Consumer<Packet<?>> submissions = this::onSubmitted;
    private int lastTeleportTick = Integer.MIN_VALUE;
    private boolean tpBlocked;
    private Vec3 submittedPosition, pendingReturn;
    private Object sessionPlayer, sessionLevel, sessionConnection;

    public AutoSwordModule() {
        super("AutoSword", "Attacks nearby targets and can swap to a gapple while you hold use.", Category.COMBAT);
    }

    /** Hold use on a sword to latch an enchanted golden apple, then return when you release. */
    private void tickSwordGap() {
        if (!swordGap.getValue() || nullCheck()) {
            releaseSwordGap();
            return;
        }
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            releaseSwordGap();
            return;
        }
        if (gapLatched && !GuizhouWare.swapManager.isLatched(gapOwner)) gapLatched = false;
        boolean useHeld = mc.options.keyUse.isDown();
        if (useHeld && !gapLatched) {
            if (!mc.player.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS)) return;
            Result apple = InventoryUtil.find(Items.ENCHANTED_GOLDEN_APPLE, java.util.EnumSet.of(ResultType.HOTBAR));
            if (apple.found() && GuizhouWare.swapManager.latch(gapOwner, apple)) gapLatched = true;
        } else if (!useHeld && gapLatched) {
            releaseSwordGap();
        }
    }

    private void releaseSwordGap() {
        if (!gapLatched) return;
        GuizhouWare.swapManager.release(gapOwner);
        gapLatched = false;
    }

    @Override
    public void onEnable() {
        tpBlocked = false;
        pendingReturn = null;
        submittedPosition = null;
        pathRetry.clear();
        lastTeleportTick = Integer.MIN_VALUE;
        sessionPlayer = sessionLevel = sessionConnection = null;
        PacketSendUtil.addSubmissionListener(submissions);
    }

    @Override
    public void onDisable() {
        releaseSwordGap();
        if (GuizhouWare.rotationManager != null) GuizhouWare.rotationManager.cancel("AutoSword");
        if (auraActive) return;
        restorePosition();
        pendingReturn = null;
        tpBlocked = false;
        pathRetry.clear();
        PacketSendUtil.removeSubmissionListener(submissions);
        currentTarget = null;
        attackCooldown.reset();
        targetTick = Integer.MIN_VALUE;
        weaponTick = Integer.MIN_VALUE;
        hitProjectiles.clear();
        projectileTick = Integer.MIN_VALUE;
        if (GuizhouWare.rotationManager != null) GuizhouWare.rotationManager.cancel("AutoSwordProjectiles");
    }

    /** KillAura owns this service even when the standalone AutoSword module is disabled. */
    public void setAuraActive(boolean active) {
        auraActive = active;
        if (active) PacketSendUtil.addSubmissionListener(submissions);
        else if (!isEnabled()) onDisable();
    }

    public boolean prepareAttackTick() {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (!mc.isSameThread() || nullCheck() || mc.player.isDeadOrDying()
                || mc.gameMode == null || mc.getConnection() == null) return false;
        ensureSession();
        if (!restorePosition()) return false;
        attackCooldown.advance(mc.player.tickCount);
        return true;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce();
            currentTarget = null;
            pendingReturn = null;
            return;
        }
        if (!prepareAttackTick()) return;
        tickSwordGap();
        if (auraActive) return;

        if (GuangxiElytraChaseModule.isWeaponControlActive()) {
            currentTarget = null;
            return;
        }

        if (!isMaceAttackReady()) {
            GuangxiCrystalModule ac = GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
            if (ac != null && ac.isEnabled() && ac.getLastBestDamage() > 0f) {
                currentTarget = null;
                return;
            }
        }

        java.util.List<Entity> found = selectTargets(sort.getValue(), targets.getValue());
        for (int i = 0; i < found.size(); i++) {
            if (attackCooldown.active()) return;
            if (tryAttack(found.get(i), rotate.getValue(), noAc.getValue())) return;
        }
    }

    /**
     * Shared attack used by AutoSword and KillAura. Silent main-hand swap only,
     * so CombatSync can keep the offhand.
     */
    public boolean tryAttack(Entity target, boolean rotate, boolean noAc) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (target == null || !target.isAlive() || target.isRemoved() || attackCooldown.active()) return false;
        if (nullCheck() || mc.player.isDeadOrDying() || mc.gameMode == null || mc.getConnection() == null) return false;
        ensureSession();
        if (!restorePosition()) return false;

        int weaponSlot = getWeapon(target);
        if (weaponSlot == -1 || !canUseWeapon(weaponSlot)) return false;
        ItemStack weaponStack = mc.player.getInventory().getItem(weaponSlot);
        Vec3 origin = mc.player.position();
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        float[] angles = MathUtil.calcAngle(eyePos, getClosestPointToEye(eyePos, target.getBoundingBox()));
        boolean inReach = hitAllowed(target, eyePos, angles[0], angles[1], noAc);
        boolean distant = !inReach && tp.getValue() && !tpBlocked;
        final List<Vec3> path = distant ? teleportPath(target) : List.of();
        if (distant) {
            if (path.isEmpty()) {
                pathRetry.put(target.getUUID(), mc.player.tickCount + 10);
                return false;
            }
            eyePos = path.getLast().add(0, mc.player.getEyeHeight(), 0);
            Vec3 aim = aimPoint(target, eyePos);
            if (aim == null) return false;
            angles = MathUtil.calcAngle(eyePos, aim);
        } else if (!inReach) {
            return false;
        }
        if (rotate) {
            if (!GuizhouWare.rotationManager.trySubmit(new RotationRequest(
                    "AutoSword", 6, angles[0], angles[1], RotationRequest.Mode.SILENT))) return false;
        }
        float serverYaw = GuizhouWare.rotationManager.getServerYaw();
        float serverPitch = GuizhouWare.rotationManager.getServerPitch();
        if (!hitAllowed(target, eyePos, serverYaw, serverPitch, noAc)) return false;

        boolean doCrit = shouldCrit(weaponStack) && !distant;
        Vec3 strikeEye = eyePos;
        Result weapon = new Result(weaponSlot, weaponStack, ResultType.HOTBAR);
        boolean[] struck = {false};
        boolean swung = GuizhouWare.swapManager.withSwap(weapon, SwapMode.SILENT, SwapPriority.WEAPON, () -> {
            if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
            if (doCrit && mc.getConnection() != null) {
                double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
                boolean hc = mc.player.horizontalCollision;
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y, z, serverYaw, serverPitch, mc.player.onGround()));
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.0625, z, serverYaw, serverPitch, false));
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.045, z, serverYaw, serverPitch, false));
            }
            if (!distant) struck[0] = strike(target);
            else {
                pendingReturn = origin;
                AuraTeleport.Result result = AuraTeleport.roundTrip(origin, path,
                        point -> sendTeleport(point, serverYaw, serverPitch),
                        () -> {
                            if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
                            if (!attackFrom(target, strikeEye, serverYaw, serverPitch, noAc)) return false;
                            struck[0] = strike(target);
                            return struck[0];
                        },
                        point -> moveTo(point, serverYaw, serverPitch));
                if (result.restored()) pendingReturn = null;
            }
        });
        if (!swung || !struck[0]) return false;
        float tps = switch (tpsMode.getValue()) {
            case LATEST -> GuizhouWare.tpsCounterService.getLatestTPS();
            case AVERAGE -> GuizhouWare.tpsCounterService.getAverageTPS();
            default -> 20f;
        };
        if (!Float.isFinite(tps) || tps < 1f) tps = 1f;
        attackCooldown.start(getBaseCooldownTicks(weaponStack), tps, delay.getValue());
        currentTarget = target;
        return true;
    }

    public boolean coolingDown() { return attackCooldown.active(); }

    public static boolean deflectableProjectile(Entity entity) {
        return entity instanceof Projectile projectile
                && (entity.getType().is(EntityTypeTags.REDIRECTABLE_PROJECTILE) || entity instanceof ShulkerBullet)
                && entity.isAlive() && !entity.isRemoved() && entity.isPickable()
                && projectile.getOwner() != mc.player;
    }

    /** Projectile damage/deflection has no weapon cooldown. Never swap weapons, TP, or arm the melee timer. */
    public boolean tryDeflect(Entity target, boolean rotate, boolean noAc) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (nullCheck() || mc.player.isDeadOrDying() || mc.gameMode == null || mc.getConnection() == null
                || !deflectableProjectile(target) || pendingReturn != null) return false;
        int tick = mc.player.tickCount;
        if (projectileTick != tick) { hitProjectiles.clear(); projectileTick = tick; }
        if (hitProjectiles.contains(target.getId())) return false;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 aim = aimPoint(target, eye);
        if (aim == null) return false;
        float[] angles = MathUtil.calcAngle(eye, aim);
        if (rotate && !GuizhouWare.rotationManager.trySubmit(new RotationRequest(
                "AutoSwordProjectiles", 10, angles[0], angles[1], RotationRequest.Mode.SILENT))) return false;
        if (!hitAllowed(target, eye, GuizhouWare.rotationManager.getServerYaw(),
                GuizhouWare.rotationManager.getServerPitch(), noAc) || !strike(target)) return false;
        hitProjectiles.add(target.getId());
        return true;
    }

    private boolean hitAllowed(Entity target, Vec3 eye, float yaw, float pitch, boolean noAc) {
        return noAc ? aimPoint(target, eye) != null : canHit(target, eye, yaw, pitch);
    }

    /** Final attack entry. A denied licence must not send the swing or the hit. */
    private boolean strike(Entity target) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (mc.gameMode == null || target == null || !target.isAlive() || target.isRemoved()) return false;
        mc.gameMode.attack(mc.player, target);
        if (swing.getValue()) mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean canHit(Entity target, float yaw, float pitch) {
        return canHit(target, mc.player.getEyePosition(1.0f), yaw, pitch);
    }

    private boolean canHit(Entity target, Vec3 eye, float yaw, float pitch) {
        AABB box = target.getBoundingBox();
        if (box.contains(eye)) return true;
        Vec3 end = eye.add(getLookVector(yaw, pitch).scale(RANGE));
        var hit = box.clip(eye, end);
        if (hit.isEmpty()) return false;
        var block = mc.level.clip(new ClipContext(eye, hit.get(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return block.getType() == HitResult.Type.MISS
                || eye.distanceToSqr(block.getLocation()) + 1.0E-6 >= eye.distanceToSqr(hit.get());
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

    public boolean isMaceAttackReady() {
        if (!isEnabled() || nullCheck() || mc.player.isDeadOrDying() || mc.gameMode == null
                || mc.getConnection() == null || attackCooldown.active()
                || GuangxiElytraChaseModule.isWeaponControlActive()
                || !MaceItem.canSmashAttack(mc.player)) return false;
        Entity target = findTarget();
        if (target == null || !mc.player.hasLineOfSight(target)) return false;
        int slot = getWeapon(target);
        return slot >= 0 && mc.player.getInventory().getItem(slot).getItem() instanceof MaceItem
                && canUseWeapon(slot);
    }

    private boolean canUseWeapon(int slot) {
        boolean needSwap = slot != GuizhouWare.swapManager.serverSlot();
        return !needSwap || !(mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND)
                && !EatUtil.shouldDefer();
    }

    private boolean shouldCrit(ItemStack weaponStack) {
        if (!criticals.getValue()) return false;

        if (!mc.player.onGround()) return false;
        if (mc.player.isInWater() || mc.player.isInLava()) return false;
        if (mc.player.onClimbable() || mc.player.isPassenger()) return false;
        if (mc.player.hasEffect(MobEffects.LEVITATION)) return false;

        if (critsWithSword.getValue() && !weaponStack.is(ItemTags.SWORDS)) return false;

        if (strict.getValue() && !isPlayerPhasedIntoBlock()) return false;
        return true;
    }

    private boolean isPlayerPhasedIntoBlock() {
        AABB bb = mc.player.getBoundingBox();
        double eyeY = mc.player.getEyeY();
        double headMinY = Mth.clamp(eyeY - 0.1, bb.minY, bb.maxY);
        AABB headBox = new AABB(bb.minX, headMinY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);

        int minX = Mth.floor(headBox.minX);
        int maxX = Mth.floor(headBox.maxX - 1.0E-7);
        int minY = Mth.floor(headBox.minY);
        int maxY = Mth.floor(headBox.maxY - 1.0E-7);
        int minZ = Mth.floor(headBox.minZ);
        int maxZ = Mth.floor(headBox.maxZ - 1.0E-7);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (blockIntersectsBox(new BlockPos(x, y, z), headBox)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean blockIntersectsBox(BlockPos pos, AABB box) {
        VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
        if (shape.isEmpty()) return false;
        return shape.bounds().move(pos).intersects(box);
    }

    public enum TargetSort { DISTANCE, CROSSHAIR, HEALTH }

    private final java.util.ArrayList<Entity> targetCache = new java.util.ArrayList<>();
    private TargetSort cachedSort = TargetSort.DISTANCE;
    private int cachedLimit = 1;
    private boolean cachedAllowTp;
    private Predicate<Entity> cachedFilter;
    private final Predicate<Entity> globalFilter = entity -> {
        TargetsModule policy = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        return policy != null && policy.isValidTarget(entity);
    };

    private Entity findTarget() {
        java.util.List<Entity> found = collectTargets(TargetSort.DISTANCE, 1, false, globalFilter);
        return found.isEmpty() ? null : found.get(0);
    }

    public java.util.List<Entity> selectTargets(TargetSort sort, int limit) {
        return collectTargets(sort, limit, tp.getValue() && !tpBlocked, globalFilter);
    }

    public List<Entity> selectTargets(TargetSort sort, int limit, Predicate<Entity> filter) {
        return collectTargets(sort, limit, tp.getValue() && !tpBlocked, filter);
    }

    private java.util.List<Entity> collectTargets(TargetSort sort, int limit, boolean allowTp, Predicate<Entity> filter) {
        int tick = mc.player.tickCount;
        int capped = Math.max(1, limit);
        if (tick == targetTick && sort == cachedSort && capped == cachedLimit && allowTp == cachedAllowTp && filter == cachedFilter)
            return java.util.List.copyOf(targetCache);
        targetTick = tick;
        cachedSort = sort;
        cachedLimit = capped;
        cachedAllowTp = allowTp;
        cachedFilter = filter;
        targetCache.clear();
        if (filter == null || mc.player == null) {
            currentTarget = null;
            return java.util.List.of();
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double search = allowTp ? Math.max(RANGE, tpRange.getValue()) : RANGE;
        double rangeSq = search * search;
        for (Entity entity : mc.level.getEntities(null, mc.player.getBoundingBox().inflate(search + 1))) {
            if (!filter.test(entity) || !entity.isAlive() || entity.isRemoved()) continue;
            if (allowTp && pathRetry.getOrDefault(entity.getUUID(), 0) > tick) continue;
            if (sqDistToBox(eye, entity.getBoundingBox()) > rangeSq) continue;
            targetCache.add(entity);
        }
        targetCache.sort(switch (sort) {
            case HEALTH -> java.util.Comparator.comparingDouble(entity -> entity instanceof LivingEntity living
                    ? living.getHealth() + living.getAbsorptionAmount() : Double.MAX_VALUE);
            case CROSSHAIR -> java.util.Comparator.comparingDouble(entity -> {
                float[] need = MathUtil.calcAngle(eye, getClosestPointToEye(eye, entity.getBoundingBox()));
                double dyaw = MathUtil.wrapDegrees(need[0] - mc.player.getYRot());
                double dpitch = need[1] - mc.player.getXRot();
                return dyaw * dyaw + dpitch * dpitch;
            });
            case DISTANCE -> java.util.Comparator.comparingDouble(entity -> sqDistToBox(eye, entity.getBoundingBox()));
        });
        if (targetCache.size() > capped) targetCache.subList(capped, targetCache.size()).clear();
        currentTarget = targetCache.isEmpty() ? null : targetCache.get(0);
        weaponTick = Integer.MIN_VALUE;
        return java.util.List.copyOf(targetCache);
    }

    private double getBaseCooldownTicks(ItemStack stack) {
        double speed = dev.guizhou.util.combat.AuraWeaponAttributes.value(mc.player, stack, Attributes.ATTACK_SPEED);
        return 20 / Math.clamp(Double.isFinite(speed) ? speed : 4, 0.05, 1024);
    }

    private int getWeapon(Entity target) {
        int tick = mc.player.tickCount;
        int id = target == null ? -1 : target.getId();
        if (tick == weaponTick && id == weaponTargetId) return cachedWeapon;
        weaponTick = tick;
        weaponTargetId = id;
        cachedWeapon = selectWeapon(target);
        return cachedWeapon;
    }

    private int selectWeapon(Entity target) {
        int bestSlot = -1;
        double bestDamage = -1.0;
        boolean smash = target != null && MaceItem.canSmashAttack(mc.player);

        for (int slot = 0; slot < 9; slot++) {
            ItemStack held = mc.player.getInventory().getItem(slot);
            if (held.isEmpty()) continue;

            boolean isSword = held.is(ItemTags.SWORDS);
            boolean isAxe = held.is(ItemTags.AXES);
            boolean isTrident = held.getItem() instanceof TridentItem;
            boolean isMace = held.getItem() instanceof MaceItem;

            if (!isSword && !isAxe && !isTrident && !isMace) continue;

            if (smash) {
                double damage = smashScore(held, target);
                if (damage > bestDamage) {
                    bestDamage = damage;
                    bestSlot = slot;
                }
                continue;
            }

            float attackDamage = 0f;

            if (held.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
                ItemAttributeModifiers modifiers = held.get(DataComponents.ATTRIBUTE_MODIFIERS);
                for (var entry : modifiers.modifiers()) {
                    if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                        attackDamage += (float) entry.modifier().amount();
                    }
                }
            }

            if (isSword) attackDamage += 5f;

            attackDamage += EnchantmentUtil.getLevel(Enchantments.SHARPNESS, held) * 1.25f;
            attackDamage += EnchantmentUtil.getLevel(Enchantments.SMITE, held) * 2.5f;
            attackDamage += EnchantmentUtil.getLevel(Enchantments.BANE_OF_ARTHROPODS, held) * 2.5f;

            if (isAxe && target instanceof Player player && player.isBlocking()) attackDamage += 8f;

            if (attackDamage > bestDamage) {
                bestDamage = attackDamage;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private double smashScore(ItemStack weapon, Entity target) {
        double base = dev.guizhou.util.combat.AuraWeaponAttributes.value(mc.player, weapon, Attributes.ATTACK_DAMAGE);
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
        if (weapon.getItem() instanceof MaceItem mace) {
            // The client branch provides vanilla fall damage; Density is applied only on ServerLevel.
            fallBonus = mace.getAttackDamageBonus(target, (float) base, mc.player.damageSources().source(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "mace_smash")), mc.player));
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

    private static double sqDistToBox(Vec3 point, AABB box) {
        double dx = point.x < box.minX ? box.minX - point.x : (point.x > box.maxX ? point.x - box.maxX : 0);
        double dy = point.y < box.minY ? box.minY - point.y : (point.y > box.maxY ? point.y - box.maxY : 0);
        double dz = point.z < box.minZ ? box.minZ - point.z : (point.z > box.maxZ ? point.z - box.maxZ : 0);
        return dx * dx + dy * dy + dz * dz;
    }

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }

    @Subscribe
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundPlayerPositionPacket)) return;
        Object connection = mc.getConnection();
        mc.execute(() -> {
            if (!isEnabled() || nullCheck() || connection != mc.getConnection() || !sameSession()
                    || (long) mc.player.tickCount - lastTeleportTick > 40) return;
            tpBlocked = stopOnSetback.getValue();
            pendingReturn = null;
            currentTarget = null;
            if (GuizhouWare.rotationManager != null) GuizhouWare.rotationManager.cancel("AutoSword");
        });
    }

    @Override
    public String getDisplayInfo() {
        return tpBlocked ? "TP paused" : null;
    }

    private void onSubmitted(Packet<?> packet) {
        if (!mc.isSameThread() || nullCheck() || !sameSession()) return;
        if (packet instanceof ServerboundMovePlayerPacket move && move.hasPosition()) {
            submittedPosition = new Vec3(move.getX(mc.player.getX()), move.getY(mc.player.getY()), move.getZ(mc.player.getZ()));
        }
    }

    private void ensureSession() {
        if (nullCheck() || sameSession()) return;
        pendingReturn = null;
        tpBlocked = false;
        pathRetry.clear();
        attackCooldown.reset();
        targetTick = weaponTick = Integer.MIN_VALUE;
        hitProjectiles.clear();
        projectileTick = Integer.MIN_VALUE;
        sessionPlayer = mc.player;
        sessionLevel = mc.level;
        sessionConnection = mc.getConnection();
        submittedPosition = mc.player.position();
    }

    private boolean sameSession() {
        return mc.player == sessionPlayer && mc.level == sessionLevel && mc.getConnection() == sessionConnection;
    }

    private boolean attackFrom(Entity target, Vec3 eye, float yaw, float pitch, boolean noAc) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (pendingReturn != null && (submittedPosition == null
                || submittedPosition.add(0, mc.player.getEyeHeight(), 0).distanceToSqr(eye) > 1e-8)) return false;
        return hitAllowed(target, eye, yaw, pitch, noAc);
    }

    private List<Vec3> teleportPath(Entity target) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return List.of();
        if (!tp.getValue() || tpBlocked || mc.player.isPassenger() || mc.player.isFallFlying()) return List.of();
        Vec3 origin = mc.player.position();
        Vec3 away = origin.subtract(target.position()).multiply(1, 0, 1).normalize();
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
        Vec3 goal = target.position().add(away.scale(Math.min(2, RANGE * 0.65)));
        if (aimPoint(target, goal.add(0, mc.player.getEyeHeight(), 0)) == null) return List.of();
        return AuraPath.find(point(origin), point(goal), tpStep.getValue(), tpRange.getValue(), 384,
                (from, to) -> clearSegment(vec(from), vec(to))).stream().map(AutoSwordModule::vec).toList();
    }

    private Vec3 aimPoint(Entity target, Vec3 eye) {
        AABB box = target.getBoundingBox();
        Vec3 closest = getClosestPointToEye(eye, box);
        Vec3 center = box.getCenter();
        for (Vec3 point : List.of(closest, center, new Vec3(center.x, box.maxY - 0.1, center.z), new Vec3(center.x, box.minY + 0.1, center.z))) {
            if (eye.distanceTo(point) > RANGE) continue;
            HitResult block = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (block.getType() == HitResult.Type.MISS
                    || eye.distanceToSqr(block.getLocation()) + 1.0E-6 >= eye.distanceToSqr(point)) return point;
        }
        return null;
    }

    private boolean clearSegment(Vec3 from, Vec3 to) {
        AABB swept = mc.player.getBoundingBox().move(from.subtract(mc.player.position())).expandTowards(to.subtract(from)).deflate(1e-5);
        if (mc.level.isOutsideBuildHeight(Mth.floor(swept.minY))
                || mc.level.isOutsideBuildHeight(Mth.floor(swept.maxY - 1.0E-7))
                || !mc.level.getWorldBorder().isWithinBounds(swept)) return false;
        for (int x = Mth.floor(swept.minX) >> 4; x <= Mth.floor(swept.maxX) >> 4; x++)
            for (int z = Mth.floor(swept.minZ) >> 4; z <= Mth.floor(swept.maxZ) >> 4; z++)
                if (!mc.level.hasChunk(x, z)) return false;
        return mc.level.noCollision(mc.player, swept);
    }

    private boolean sendTeleport(Vec3 point, float yaw, float pitch) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (nullCheck() || !sameSession() || !tp.getValue() || tpBlocked || submittedPosition == null
                || !clearSegment(submittedPosition, point)) return false;
        lastTeleportTick = mc.player.tickCount;
        return moveTo(point, yaw, pitch);
    }

    private boolean moveTo(Vec3 point, float yaw, float pitch) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (nullCheck() || !sameSession()) return false;
        boolean sent = PacketSendUtil.send(new ServerboundMovePlayerPacket.PosRot(
                point.x, point.y, point.z, yaw, pitch, false));
        // RotationManager observes the final submitted packet, including any replacement.
        return sent && submittedPosition != null && submittedPosition.distanceToSqr(point) < 1e-10;
    }

    private boolean restorePosition() {
        if (pendingReturn == null) return true;
        if (!sameSession() || nullCheck()) {
            pendingReturn = null;
            return true;
        }
        if (!moveTo(pendingReturn, GuizhouWare.rotationManager.getServerYaw(), GuizhouWare.rotationManager.getServerPitch()))
            return false;
        pendingReturn = null;
        return true;
    }

    private static AuraPath.Point point(Vec3 v) { return new AuraPath.Point(v.x, v.y, v.z); }
    private static Vec3 vec(AuraPath.Point p) { return new Vec3(p.x(), p.y(), p.z()); }
}
