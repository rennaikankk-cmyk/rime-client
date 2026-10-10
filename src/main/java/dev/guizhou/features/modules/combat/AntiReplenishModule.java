package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.render.ChestHistoryModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Highlights nearby shulker boxes that you did not place — an opponent mid-resupply.
 * Own boxes (ChestHistory) and boxes beside friends are skipped. No notify / mine / open.
 */
public class AntiReplenishModule extends Module {

    private static final int SCAN_INTERVAL = 5;

    private final Setting<Integer> searchRange = num("SearchRange", 3, 1, 6);
    private final Setting<Color> highlightColor = color("HighlightColor", 227, 154, 146, 90);
    private final Setting<Double> friendClearance = num("FriendClearance", 3.0, 1.0, 8.0);

    private final List<BlockPos> lastScan = new ArrayList<>();
    private int scanTimer;
    private Object connectionScope;
    private Object dimensionScope;

    public AntiReplenishModule() {
        super("AntiReplenish", "Highlights nearby enemy shulker boxes used for resupply. "
                + "Skips your own boxes and boxes a friend stands next to.", Category.COMBAT);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;
        if (!HeartbeatManager.authorized()) {
            HeartbeatManager.notifyDeniedOnce();
            lastScan.clear();
            return;
        }
        if (connectionScope != mc.getConnection() || dimensionScope != mc.level.dimension()) {
            connectionScope = mc.getConnection();
            dimensionScope = mc.level.dimension();
            lastScan.clear();
        }

        if (--scanTimer > 0) return;
        scanTimer = SCAN_INTERVAL;
        lastScan.clear();
        lastScan.addAll(scanEnemyBoxes());
    }

    /** Shulker boxes in range that are neither ours nor beside a friend. */
    private List<BlockPos> scanEnemyBoxes() {
        List<BlockPos> found = new ArrayList<>();
        BlockPos feet = mc.player.blockPosition();
        int radius = searchRange.getValue();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (!mc.level.hasChunkAt(pos) || !isShulker(pos)) continue;
                    if (ChestHistoryModule.store().isSelfPlaced(pos)) continue;
                    if (friendNearby(pos)) continue;
                    found.add(pos.immutable());
                }
            }
        }
        return found;
    }

    private boolean friendNearby(BlockPos pos) {
        double clearanceSq = friendClearance.getValue() * friendClearance.getValue();
        Vec3 center = Vec3.atCenterOf(pos);
        for (Player player : mc.level.players()) {
            if (player == mc.player || !GuizhouWare.friendManager.isFriend(player)) continue;
            if (player.distanceToSqr(center) <= clearanceSq) return true;
        }
        return false;
    }

    private boolean isShulker(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock;
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;
        for (BlockPos pos : lastScan) {
            RenderUtil.drawBox(null, new AABB(pos), highlightColor.getValue(), 1.5f);
        }
    }

    @Override
    public void onDisable() {
        lastScan.clear();
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        lastScan.clear();
    }

    @Override
    public String getDisplayInfo() {
        return lastScan.isEmpty() ? null : String.valueOf(lastScan.size());
    }
}
