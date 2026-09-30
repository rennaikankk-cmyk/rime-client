package dev.guizhou.features.modules.world;

import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk timeline + spawner radar for anarchy travel.
 *
 * Two honest signals, both derived from what the client actually receives:
 *  1. first-seen: the first time the server sends a full chunk payload for a
 *     position, we timestamp it ("new to you" — the only claim a client can
 *     make without server state; NOT a claim that the chunk is newly
 *     generated server-side).
 *  2. spawners: dungeon spawner block entities inside loaded chunks are hard
 *     detections and get their own marker (the same anchor Obelisk's chunk
 *     module scans for).
 *
 * Render: green fade for recent first-seen chunks, red boxes on spawner
 * positions. Off-server claims are deliberately avoided.
 */
public class NewChunksModule extends Module {

    private final Setting<Boolean> markFirstSeen = bool("MarkFirstSeen", true).described("为首次收到的完整区块数据打渐隐标记。");
    private final Setting<Integer> fadeSeconds = num("FadeSeconds", 10, 1, 120).described("首次可见标记的持续时长（秒）。");
    private final Setting<Boolean> spawners = bool("Spawners", true).described("在已加载区块中检测地牢刷怪笼。");
    private final Setting<Boolean> spawnerBoxes = bool("SpawnerBoxes", true).described("以红色方框渲染刷怪笼位置。");
    private final Setting<Integer> renderDistance = num("RenderDistance", 8, 2, 32).described("扫描与渲染的区块半径。");
    private final Setting<Integer> scanInterval = num("ScanIntervalTicks", 40, 10, 200).described("区块扫描的节流间隔（tick）。");

    private static final Color FRESH = new Color(80, 220, 120, 70);
    private static final Color SPAWNER = new Color(235, 70, 70, 160);

    /** chunkPos long -> first seen epoch ms */
    private final ConcurrentHashMap<Long, Long> firstSeen = new ConcurrentHashMap<>();
    /** spawner positions discovered this session */
    private final Set<BlockPos> spawnerPos = ConcurrentHashMap.newKeySet();
    private final Set<Long> scannedChunks = new HashSet<>();
    private int tickCounter;

    public NewChunksModule() {
        super("NewChunks", "记录服务器推送区块的首次可见时间线并提供地牢刷怪笼雷达；首次可见指本会话首次收到，不代表服务器新生成。",
                Category.WORLD);
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (!(event.getPacket() instanceof ClientboundLevelChunkWithLightPacket packet)) return;
        if (!markFirstSeen.getValue()) return;
        long key = net.minecraft.world.level.ChunkPos.asLong(packet.getX(), packet.getZ());
        firstSeen.putIfAbsent(key, System.currentTimeMillis());
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (nullCheck() || mc.level == null) return;
        if (!spawners.getValue()) return;
        if (++tickCounter < scanInterval.getValue()) return;
        tickCounter = 0;

        int cx = mc.player.chunkPosition().x;
        int cz = mc.player.chunkPosition().z;
        int r = Math.min(renderDistance.getValue(), 8);
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                long key = net.minecraft.world.level.ChunkPos.asLong(x, z);
                if (scannedChunks.contains(key)) continue;
                if (!mc.level.hasChunk(x, z)) continue;
                LevelChunk chunk = mc.level.getChunk(x, z);
                for (var be : chunk.getBlockEntities().values()) {
                    if (be instanceof SpawnerBlockEntity) {
                        spawnerPos.add(be.getBlockPos().immutable());
                    }
                }
                scannedChunks.add(key);
            }
        }
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (nullCheck() || mc.level == null) return;
        long now = System.currentTimeMillis();

        if (markFirstSeen.getValue()) {
            long fadeMs = fadeSeconds.getValue() * 1000L;
            for (var entry : firstSeen.entrySet()) {
                long age = now - entry.getValue();
                if (age > fadeMs) continue;
                var pos = new net.minecraft.world.level.ChunkPos(entry.getKey());
                int alpha = (int) (FRESH.getAlpha() * (1.0 - (double) age / fadeMs));
                if (alpha <= 0) continue;
                var box = new net.minecraft.world.phys.AABB(
                        pos.getMinBlockX(), mc.level.getMinY(), pos.getMinBlockZ(),
                        pos.getMaxBlockX() + 1.0, mc.level.getMinY() + 1.0, pos.getMaxBlockZ() + 1.0);
                RenderUtil.drawBoxFilled(event.getMatrix(), box,
                        new Color(FRESH.getRed(), FRESH.getGreen(), FRESH.getBlue(), alpha));
            }
        }

        if (spawners.getValue() && spawnerBoxes.getValue()) {
            for (BlockPos pos : spawnerPos) {
                if (mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)
                        > (double) (renderDistance.getValue() * 16) * (renderDistance.getValue() * 16)) continue;
                RenderUtil.drawBoxFilled(event.getMatrix(), pos, SPAWNER);
            }
        }
    }
}
