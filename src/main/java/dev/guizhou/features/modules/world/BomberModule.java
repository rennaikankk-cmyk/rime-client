package dev.guizhou.features.modules.world;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class BomberModule extends Module {
    private final Setting<Float>   radius      = num("Radius", 4.0f, 1.0f, 6.0f);
    private final Setting<Integer> delay       = num("Delay",  6,    0,    20);
    private final Setting<Boolean> autoDisable = bool("AutoDisable", false);


    private int      ticksWaited = 0;
    private BlockPos lastPos     = null;
    private BlockPos pendingPos;
    private int pendingTick;
    private net.minecraft.client.multiplayer.ClientLevel sessionLevel;

    public BomberModule() {
        super("Bomber", "Automatically places and lights TNT around you.", Category.WORLD);
    }

    @Override
    public void onEnable() {
        ticksWaited = 0;
        lastPos     = null;
        pendingPos = null;
        sessionLevel = mc.level;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (sessionLevel != mc.level) { onEnable(); }
        if (pendingPos != null) {
            if (mc.level.getBlockState(pendingPos).is(net.minecraft.world.level.block.Blocks.TNT)) {
                Result lighter = findHotbar(Items.FLINT_AND_STEEL);
                if (lighter != null && igniteAt(pendingPos, lighter)) { pendingPos = null; return; }
            }
            if (mc.player.tickCount - pendingTick < 20) return;
            pendingPos = null;
            lastPos = null;
        }

        if (ticksWaited < delay.getValue()) {
            ticksWaited++;
            return;
        }
        ticksWaited = 0;

        Result tnt = findHotbar(Items.TNT);
        Result fas = findHotbar(Items.FLINT_AND_STEEL);
        if (tnt == null || fas == null) {
            if (autoDisable.getValue()) disable();
            return;
        }

        placeAndIgnite(tnt, fas);
    }

    private void placeAndIgnite(Result tnt, Result fas) {
        BlockPos player = mc.player.blockPosition();
        double r = radius.getValue();

        for (double x = -r; x <= r; x++) {
            for (double z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;

                BlockPos pos = player.offset((int) x, 0, (int) z);
                if (pos.equals(lastPos)) continue;
                if (!mc.level.getBlockState(pos).isAir()) continue;

                if (!GuizhouWare.placementManager.placeDirect(pos, null, tnt.stack().getItem(),
                        SwapMode.ALTSILENT, InventoryUtil.PLACE_SCOPE)) continue;
                lastPos = pos;
                pendingPos = pos.immutable();
                pendingTick = mc.player.tickCount;
                return;
            }
        }
    }

    private boolean igniteAt(BlockPos pos, Result fas) {
        BlockHitResult hit = GuizhouWare.placementManager.prepareInteractionHit(pos, null,
                mc.player.getEyePosition(), 5.154, 5.154, false);
        if (hit == null) return false;
        boolean[] sent = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(fas, SwapMode.ALTSILENT, SwapPriority.USER_ACTION, () -> {
            sent[0] = GuizhouWare.placementManager.sendUseItemOnDirect(hit, InteractionHand.MAIN_HAND);
            if (sent[0]) mc.player.swing(InteractionHand.MAIN_HAND);
        });
        return swapped && sent[0];
    }

    private Result findHotbar(Item item) {
        Result r = InventoryUtil.find(item, InventoryUtil.PLACE_SCOPE);
        return r.found() ? r : null;
    }
}
