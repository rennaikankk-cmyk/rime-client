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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

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

    public AutoSwordModule() {
        super("AutoSword", "Automatically attacks nearby players with the best weapon.", Category.COMBAT);
    }

    @Override
    public void onDisable() {
        currentTarget = null;
        attackCooldownTicks = 0f;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
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
        if (currentTarget == null) return;

        if (attackCooldownTicks > 0f) return;

        int weaponSlot = getWeapon(currentTarget);
        if (weaponSlot == -1) return;

        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 closestPoint = getClosestPointToEye(eyePos, currentTarget.getBoundingBox());
        float[] angles = MathUtil.calcAngle(eyePos, closestPoint);

        if (!GuizhouWare.rotationManager.tryAction(new RotationRequest(
            "AutoSword", 6, angles[0], angles[1], RotationRequest.Mode.SILENT
        ))) return;

        float serverYaw = GuizhouWare.rotationManager.getServerYaw();
        float serverPitch = GuizhouWare.rotationManager.getServerPitch();
        if (!currentTarget.getBoundingBox().contains(eyePos)) {
            Vec3 lookVec = getLookVector(serverYaw, serverPitch);
            Vec3 reachEnd = eyePos.add(lookVec.scale(RANGE));
            if (currentTarget.getBoundingBox().clip(eyePos, reachEnd).isEmpty()) return;
        }

        ItemStack weaponStack = mc.player.getInventory().getItem(weaponSlot);
        boolean doCrit = shouldCrit(weaponStack);

        if (!canUseWeapon(weaponSlot)) return;

        Result weapon = new Result(weaponSlot, weaponStack, ResultType.HOTBAR);
        boolean swung = GuizhouWare.swapManager.withSwap(weapon, SwapMode.SILENT, SwapPriority.WEAPON, () -> {
            if (doCrit) {
                double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
                boolean hc = mc.player.horizontalCollision;
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y,          z, serverYaw, serverPitch, mc.player.onGround(), hc));
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.0625, z, serverYaw, serverPitch, false,                 hc));
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.045,  z, serverYaw, serverPitch, false,                 hc));
            }

            mc.gameMode.attack(mc.player, currentTarget);
            if (swing.getValue()) mc.player.swing(InteractionHand.MAIN_HAND);
        });
        if (!swung) return;

        attackCooldownTicks = getBaseCooldownTicks(weaponStack, tps) * delay.getValue().floatValue();
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

    private Entity findTarget() {
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        if (targets == null) return null;

        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity entity : mc.level.getEntities(null, mc.player.getBoundingBox().inflate(RANGE + 1))) {
            if (!targets.isValidTarget(entity)) continue;

            double dist = eyePos.distanceTo(clampToBox(eyePos, entity.getBoundingBox()));
            if (dist > RANGE) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = entity;
            }
        }
        return best;
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
