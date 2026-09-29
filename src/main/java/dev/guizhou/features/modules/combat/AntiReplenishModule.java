package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.hud.NotifierHudModule;
import dev.guizhou.features.modules.render.ChestHistoryModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.combat.UtilityActionGuard;
import dev.guizhou.util.compat.ClientCoexistence;
import dev.guizhou.util.compat.ForeignClientProbe;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.lang.ClientLanguage;
import dev.guizhou.util.player.ChatUtil;
import dev.guizhou.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Marks shulker boxes near you that you did not place — an opponent mid-resupply — and can
 * mine them, ported from SlimefunHelper AntiReplenish with the container half deliberately
 * left out: no opening, taking or dropping. Your own boxes are excluded through ChestHistory
 * self-placement, boxes a friend stands next to are left alone, and mining stops the moment a
 * screen, an item use, cruise or aerial control needs the hands.
 */
public class AntiReplenishModule extends Module {

    private static final int SCAN_INTERVAL = 5;
    private static final double REACH = 4.25;

    private final Setting<Integer> searchRange = num("SearchRange", 3, 1, 6);
    private final Setting<Boolean> notify = bool("Notify", true);
    private final Setting<Boolean> highlight = bool("Highlight", true);
    private final Setting<Color> highlightColor = color("HighlightColor", 227, 154, 146, 90);
    private final Setting<Boolean> autoMine = bool("AutoMine", false);
    private final Setting<Integer> mineTimeout = num("MineTimeoutTicks", 120, 20, 300);
    private final Setting<Double> friendClearance = num("FriendClearance", 3.0, 1.0, 8.0);

    /** Alerted positions; the value is the tick of the last scan that still saw them. */
    private final Map<BlockPos, Integer> alerted = new HashMap<>();
    private final List<BlockPos> lastScan = new ArrayList<>();
    private BlockPos miningTarget;
    private int mineTimer;
    private int scanTimer;
    private Object connectionScope;
    private Object dimensionScope;

    public AntiReplenishModule() {
        super("AntiReplenish", "Marks shulker boxes near you that you did not place — someone mid-resupply — and "
                + "can mine them. Never touches your own boxes or boxes a friend stands next to, and stops the "
                + "moment a screen, item use, cruise or aerial control needs the hands.", Category.COMBAT);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;
        if (!HeartbeatManager.authorized()) {
            HeartbeatManager.notifyDeniedOnce();
            stopMining();
            alerted.clear();
            return;
        }
        if (ClientCoexistence.blocks(ForeignClientProbe.Domain.INVENTORY)) {
            stopMining();
            return;
        }
        if (connectionScope != mc.getConnection() || dimensionScope != mc.level.dimension()) {
            connectionScope = mc.getConnection();
            dimensionScope = mc.level.dimension();
            alerted.clear();
            stopMining();
        }

        if (--scanTimer > 0) return;
        scanTimer = SCAN_INTERVAL;
        lastScan.clear();
        lastScan.addAll(scanEnemyBoxes());

        alerted.keySet().removeIf(pos -> !isShulker(pos));
        for (BlockPos pos : lastScan) {
            if (alerted.putIfAbsent(pos, mc.player.tickCount) == null && notify.getValue()) {
                String text = ClientLanguage.text("敌方补给盒：{cyan} %s {}");
                ChatUtil.sendMessage(Component.literal(String.format(text, pos.toShortString())));
                NotifierHudModule.push("antireplenish:" + pos.toShortString(), NotifierHudModule.Icon.DANGER,
                        String.format(text, pos.toShortString()));
            }
        }

        if (!autoMine.getValue()) {
            stopMining();
            return;
        }
        pumpMining();
    }

    /** Everything that needs the hands — a screen, eating, cruise, aerial control — stops mining. */
    private void pumpMining() {
        if (GuizhouWare.swapManager.isLatched(this)) {
            if (mc.screen != null || mc.player.isUsingItem()) {
                stopMining();
                return;
            }
        } else {
            String pause = UtilityActionGuard.pauseReason();
            if (pause != null) {
                stopMining();
                return;
            }
        }
        if (miningTarget != null
                && (!isShulker(miningTarget) || !withinReach(miningTarget) || selfPlacedNow(miningTarget))) {
            // broken, out of reach, or it turned out to be ours: done here
            stopMining();
        }
        if (miningTarget == null) {
            for (BlockPos pos : lastScan) {
                if (withinReach(pos)) {
                    miningTarget = pos;
                    mineTimer = 0;
                    break;
                }
            }
        }
        if (miningTarget == null) return;
        if (++mineTimer > mineTimeout.getValue()) {
            stopMining();
            return;
        }
        mineEnemyBox(miningTarget);
    }

    /** The one action of this module, behind one authorization boundary. */
    private void mineEnemyBox(BlockPos pos) {
        if (!HeartbeatManager.authorized()) {
            stopMining();
            return;
        }
        if (mc.gameMode == null || mc.screen != null) return;
        if (!GuizhouWare.swapManager.isLatched(this)) {
            Result pickaxe = InventoryUtil.find(stack -> stack.is(ItemTags.PICKAXES),
                    java.util.EnumSet.of(ResultType.HOTBAR, ResultType.INVENTORY));
            if (pickaxe.found()) GuizhouWare.swapManager.latch(this, pickaxe);
        }
        mc.gameMode.startDestroyBlock(pos, Direction.UP);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    /** Shulker boxes in range that are neither ours nor beside a friend; unloaded chunks guess nothing. */
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

    private boolean selfPlacedNow(BlockPos pos) {
        return ChestHistoryModule.store().isSelfPlaced(pos);
    }

    private boolean withinReach(BlockPos pos) {
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= REACH * REACH;
    }

    private boolean isShulker(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock;
    }

    private void stopMining() {
        if (GuizhouWare.swapManager != null) GuizhouWare.swapManager.release(this);
        miningTarget = null;
        mineTimer = 0;
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        if (!highlight.getValue() || nullCheck()) return;
        for (BlockPos pos : lastScan) {
            RenderUtil.drawBox(null, new AABB(pos), highlightColor.getValue(), 1.5f);
        }
    }

    @Override
    public void onDisable() {
        alerted.clear();
        lastScan.clear();
        stopMining();
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        alerted.clear();
        lastScan.clear();
        stopMining();
    }

    @Override
    public String getDisplayInfo() {
        String foreign = ClientCoexistence.reason(ForeignClientProbe.Domain.INVENTORY);
        if (foreign != null) return ClientLanguage.text("让行：") + foreign;
        if (miningTarget != null) return ClientLanguage.text("挖 ") + miningTarget.toShortString();
        return alerted.isEmpty() ? null : String.valueOf(alerted.size());
    }
}
