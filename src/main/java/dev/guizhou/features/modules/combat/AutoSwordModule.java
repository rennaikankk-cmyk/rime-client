package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.ClipContext;

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

    private Entity currentTarget = null;
    private float attackCooldownTicks = 0f;
    private int targetTick = Integer.MIN_VALUE;
    private int weaponTick = Integer.MIN_VALUE;
    private int weaponTargetId = Integer.MIN_VALUE;
    private int cachedWeapon = -1;

    public AutoSwordModule() {
        super("AutoSword", "Automatically attacks nearby players with the best weapon.", Category.COMBAT);
    }

    @Override
    public void onDisable() {
        currentTarget = null;
        attackCooldownTicks = 0f;
        targetTick = Integer.MIN_VALUE;
        weaponTick = Integer.MIN_VALUE;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce();
            currentTarget = null;
            return;
        }
        if (nullCheck() || mc.player.isDeadOrDying()) return;

        float tps;
        switch (tpsMode.getValue()) {
            case LATEST -> tps = GuizhouWare.tpsCounterService.getLatestTPS();
            case AVERAGE -> tps = GuizhouWare.tpsCounterService.getAverageTPS();
            default -> tps = 20f;
        }

        if (!Float.isFinite(tps) || tps < 1f) tps = 1f;

        attackCooldownTicks -= (tps / 20f);
        if (attackCooldownTicks < 0f) attackCooldownTicks = 0f;

        KillAuraModule killAura = GuizhouWare.moduleManager.getModuleByClass(KillAuraModule.class);
        if (killAura != null && killAura.isEnabled()) return;

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

        currentTarget = findTarget();
        if (currentTarget == null || attackCooldownTicks > 0f) return;
        tryAttack(currentTarget, true);
    }

    /**
     * Shared attack used by AutoSword and KillAura. Silent main-hand swap only,
     * so CombatSync can keep the offhand.
     */
    public boolean tryAttack(Entity target, boolean rotate) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (target == null || !target.isAlive() || target.isRemoved() || attackCooldownTicks > 0f) return false;
        if (nullCheck() || mc.player.isDeadOrDying() || mc.gameMode == null || mc.getConnection() == null) return false;

        int weaponSlot = getWeapon(target);
        if (weaponSlot == -1 || !canUseWeapon(weaponSlot)) return false;
        ItemStack weaponStack = mc.player.getInventory().getItem(weaponSlot);
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        float[] angles = MathUtil.calcAngle(eyePos, getClosestPointToEye(eyePos, target.getBoundingBox()));
        if (!canHit(target, angles[0], angles[1])) return false;
        if (rotate) {
            GuizhouWare.rotationManager.trySubmit(new RotationRequest(
                    "AutoSword", 6, angles[0], angles[1], RotationRequest.Mode.SILENT));
        }
        if (!canHit(target, GuizhouWare.rotationManager.getServerYaw(),
                GuizhouWare.rotationManager.getServerPitch())) return false;

        boolean doCrit = shouldCrit(weaponStack);
        float serverYaw = GuizhouWare.rotationManager.getServerYaw();
        float serverPitch = GuizhouWare.rotationManager.getServerPitch();
        Result weapon = new Result(weaponSlot, weaponStack, ResultType.HOTBAR);
        boolean[] struck = {false};
        boolean swung = GuizhouWare.swapManager.withSwap(weapon, SwapMode.SILENT, SwapPriority.WEAPON, () -> {
            if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
            if (doCrit && mc.getConnection() != null) {
                double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
                boolean hc = mc.player.horizontalCollision;
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y, z, serverYaw, serverPitch, mc.player.onGround(), hc));
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.0625, z, serverYaw, serverPitch, false, hc));
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.045, z, serverYaw, serverPitch, false, hc));
            }
            struck[0] = strike(target);
        });
        if (!swung || !struck[0]) return false;
        float tps = switch (tpsMode.getValue()) {
            case LATEST -> GuizhouWare.tpsCounterService.getLatestTPS();
            case AVERAGE -> GuizhouWare.tpsCounterService.getAverageTPS();
            default -> 20f;
        };
        if (!Float.isFinite(tps) || tps < 1f) tps = 1f;
        attackCooldownTicks = getBaseCooldownTicks(weaponStack, tps) * delay.getValue().floatValue();
        currentTarget = target;
        return true;
    }

    public boolean coolingDown() { return attackCooldownTicks > 0f; }

    /** Final attack entry. A denied licence must not send the swing or the hit. */
    private boolean strike(Entity target) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (mc.gameMode == null || target == null || !target.isAlive() || target.isRemoved()) return false;
        mc.gameMode.attack(mc.player, target);
        if (swing.getValue()) mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean canHit(Entity target, float yaw, float pitch) {
        AABB box = target.getBoundingBox();
        Vec3 eye = mc.player.getEyePosition(1.0f);
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
                || mc.getConnection() == null || attackCooldownTicks > 0.0F
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

    private Entity findTarget() {
        java.util.List<Entity> found = selectTargets(TargetSort.DISTANCE, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    public java.util.List<Entity> selectTargets(TargetSort sort, int limit) {
        int tick = mc.player.tickCount;
        int capped = Math.max(1, limit);
        if (tick == targetTick && sort == cachedSort && capped == cachedLimit) return java.util.List.copyOf(targetCache);
        targetTick = tick;
        cachedSort = sort;
        cachedLimit = capped;
        targetCache.clear();
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        if (targets == null || mc.player == null) {
            currentTarget = null;
            return java.util.List.of();
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double rangeSq = RANGE * RANGE;
        for (Entity entity : mc.level.getEntities(null, mc.player.getBoundingBox().inflate(RANGE + 1))) {
            if (!targets.isValidTarget(entity) || !entity.isAlive()) continue;
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

    private float getBaseCooldownTicks(ItemStack stack, float tps) {
        float baseTicks;
        if (stack.is(ItemTags.SWORDS)) baseTicks = 13f;
        else if (stack.is(ItemTags.AXES)) baseTicks = 21f;
        else if (stack.getItem() instanceof TridentItem) baseTicks = 19f;
        else if (stack.getItem() instanceof MaceItem) baseTicks = 34f;
        else baseTicks = 20f / 4f;

        return (baseTicks * (20f / tps));
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
        ItemAttributeModifiers modifiers = weapon.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        double base = modifiers.compute(Attributes.ATTACK_DAMAGE,
                mc.player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), EquipmentSlot.MAINHAND);
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
            fallBonus = mace.getAttackDamageBonus(target, (float) base, mc.player.damageSources().mace(mc.player));
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
}
