package dev.guizhou.features.modules.world;

import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts storage block entities in loaded chunks and flags clusters away from
 * your own base. Client-side only: it sees what the server streams, so the
 * honest scope is "containers visible in loaded chunks", not a radar.
 * Cluster heuristic: >= {@code minCluster} containers within {@code clusterRange}
 * blocks, at least {@code homeRange} away from you (skip your own stash corner).
 */
public class StashFinderModule extends Module {

    private final Setting<Boolean> shulkers = bool("Shulkers", true).described("统计潜影盒。");
    private final Setting<Boolean> chests = bool("Chests", true).described("统计箱子与大型箱子。");
    private final Setting<Boolean> barrels = bool("Barrels", true).described("统计木桶。");
    private final Setting<Boolean> hoppers = bool("Hoppers", false).described("统计漏斗（默认关，刷怪塔噪音大）。");
    private final Setting<Boolean> dispensers = bool("Dispensers", false).described("统计发射器。");
    private final Setting<Boolean> brewingStands = bool("BrewingStands", false).described("统计酿造台。");
    private final Setting<Integer> minCluster = num("MinCluster", 4, 2, 30).described("判定为物资簇所需的最少容器数量。");
    private final Setting<Double> clusterRange = num("ClusterRange", 8.0, 2.0, 32.0).described("容器之间的归簇距离。");
    private final Setting<Double> homeRange = num("HomeRange", 16.0, 4.0, 64.0).described("忽略以你为中心该半径内的容器（自家基地）。");
    private final Setting<Boolean> render = bool("Render", true).described("高亮物资簇范围框。");
    private final Setting<Boolean> chatReport = bool("ChatReport", false).described("发现新簇时发送聊天提示。");
    private final Setting<Integer> scanInterval = num("ScanIntervalTicks", 100, 20, 600).described("区块扫描的节流间隔（tick）。");

    private static final Color STASH = new Color(255, 190, 60, 130);

    /** cluster anchor pos -> container count */
    private final Map<BlockPos, Integer> clusters = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> reported = new ConcurrentHashMap<>();
    private int tickCounter;

    public StashFinderModule() {
        super("StashFinder", "在已加载区块中寻找容器簇（潜影盒/箱子/木桶等）并高亮；仅限客户端可见范围，不是区块雷达。",
                Category.WORLD);
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (nullCheck() || mc.level == null) return;
        if (++tickCounter < scanInterval.getValue()) return;
        tickCounter = 0;

        Map<BlockPos, Integer> found = new ConcurrentHashMap<>();
        var pc = mc.player.chunkPosition();
        int r = Math.min(8, mc.options.renderDistance().get());
        for (int cx = pc.x - r; cx <= pc.x + r; cx++) {
            for (int cz = pc.z - r; cz <= pc.z + r; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                scanChunk(mc.level.getChunk(cx, cz), found);
            }
        }

        clusters.clear();
        double rangeSq = clusterRange.getValue() * clusterRange.getValue();
        for (var entry : found.entrySet()) {
            BlockPos anchor = entry.getKey();
            int count = 0;
            for (var other : found.keySet()) {
                if (anchor.distSqr(other) <= rangeSq) count++;
            }
            if (count >= minCluster.getValue()) {
                clusters.put(anchor, count);
                if (chatReport.getValue()) {
                    Long last = reported.get(anchor);
                    long now = System.currentTimeMillis();
                    if (last == null || now - last > 60_000) {
                        reported.put(anchor, now);
                        dev.guizhou.util.player.ChatUtil.sendMessage(
                                net.minecraft.network.chat.Component.literal(
                                        String.format(dev.guizhou.util.lang.ClientLanguage.text("[物资搜索] %d 个容器 @ %s"),
                                        count, anchor.toShortString())));
                    }
                }
            }
        }
    }

    private void scanChunk(net.minecraft.world.level.chunk.LevelChunk chunk, Map<BlockPos, Integer> found) {
        for (var be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getBlockPos();
            boolean wanted = (shulkers.getValue() && be instanceof ShulkerBoxBlockEntity)
                    || (chests.getValue() && be instanceof ChestBlockEntity
                        && !(be instanceof net.minecraft.world.level.block.entity.TrappedChestBlockEntity))
                    || (barrels.getValue() && be instanceof BarrelBlockEntity)
                    || (hoppers.getValue() && be instanceof HopperBlockEntity)
                    || (dispensers.getValue() && be instanceof DispenserBlockEntity)
                    || (brewingStands.getValue() && be instanceof BrewingStandBlockEntity);
            if (!wanted) continue;
            if (mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                    < homeRange.getValue() * homeRange.getValue()) continue;
            found.merge(pos, 1, Integer::sum);
        }
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (!render.getValue() || nullCheck() || mc.level == null) return;
        for (var entry : clusters.entrySet()) {
            BlockPos anchor = entry.getKey();
            var box = new net.minecraft.world.phys.AABB(anchor).inflate(clusterRange.getValue() * 0.5);
            RenderUtil.drawBoxFilled(event.getMatrix(), box, STASH);
        }
    }
}
