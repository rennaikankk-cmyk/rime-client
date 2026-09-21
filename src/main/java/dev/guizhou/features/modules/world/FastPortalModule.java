package dev.guizhou.features.modules.world;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import dev.guizhou.mixin.client.ClientLevelAccessor;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static dev.guizhou.util.inventory.InventoryUtil.FULL_SCOPE;

public class FastPortalModule extends Module {
    public Setting<Float> cooldown = num("Cooldown", 2.0f, 0.5f, 20.0f);

    private int cooldownTicks = 0;

    public FastPortalModule() {
        super("FastPortal", "Throws an ender pearl when inside nether portals", Category.WORLD);
    }

    @Override
    public void onDisable() {
        cooldownTicks = 0;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        Vec3 eye = mc.player.getEyePosition();
        BlockPos eyeBlockPos = BlockPos.containing(eye);

        if (!mc.level.getBlockState(eyeBlockPos).is(Blocks.NETHER_PORTAL)) {
            return;
        }

        Result pearl = InventoryUtil.find(Items.ENDER_PEARL, FULL_SCOPE);
        if (!pearl.found()) return;

        if (mc.player.getCooldowns().isOnCooldown(Items.ENDER_PEARL)) return;

        Vec3 portalCenter = new Vec3(eyeBlockPos.getX(), eyeBlockPos.getY() - 1, eyeBlockPos.getZ());
        float yaw = calcYaw(eye, portalCenter);
        float pitch = calcPitch(eye, portalCenter);

        if (!GuizhouWare.rotationManager.tryAction(new RotationRequest("fastportal", 20, yaw, pitch, RotationRequest.Mode.SILENT))) return;

        mc.gameMode.ensureHasSentCarriedItem();
        boolean sent = GuizhouWare.swapManager.withSwap(pearl, SwapMode.ALTSILENT, SwapPriority.UTILITY, () -> {
            try (var handler = ((ClientLevelAccessor) mc.level).guizhouware$getBlockStatePredictionHandler().startPredicting()) {
                mc.getConnection().send(new ServerboundUseItemPacket(pearl.hand(), handler.currentSequence(), yaw, pitch));
            }
        });

        if (sent) cooldownTicks = (int) (cooldown.getValue() * 20);
    }

    private float calcYaw(Vec3 eye, Vec3 target) {
        Vec3 diff = target.subtract(eye);
        return (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
    }

    private float calcPitch(Vec3 eye, Vec3 target) {
        Vec3 diff = target.subtract(eye);
        double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        return (float) -Math.toDegrees(Math.atan2(diff.y, horizontalDist));
    }
}
