package dev.guizhou.manager;

import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.Feature;
import dev.guizhou.util.models.TickRateWindow;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.entity.PositionMoveRotation;

import java.util.concurrent.atomic.AtomicLong;

public class TPSCounterService extends Feature {
    private static final double RUBBERBAND_DIST_SQ = 9.0;
    private static final long RUBBERBAND_QUIET_NS = 400_000_000L;

    private final TickRateWindow window = new TickRateWindow(20);
    private final AtomicLong sessionGeneration = new AtomicLong();
    private volatile TickRateWindow.Snapshot rates = TickRateWindow.DEFAULT;
    private volatile long lastRubberbandNs = Long.MIN_VALUE;
    private ClientPacketListener sampledConnection;

    public void init() {
        EVENT_BUS.register(this);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        long generation = sessionGeneration.incrementAndGet();
        mc.execute(() -> {
            if (sessionGeneration.get() != generation) return;
            resetSamples(null);
        });
    }

    @Subscribe
    public void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundSetTimePacket packet)) return;
        long arrivalNanos = System.nanoTime();
        long serverTick = packet.gameTime();
        ClientPacketListener connection = mc.getConnection();
        long generation = sessionGeneration.get();
        if (connection == null) return;
        mc.execute(() -> {
            if (sessionGeneration.get() != generation || mc.getConnection() != connection) return;
            if (sampledConnection != connection) resetSamples(connection);
            rates = window.add(serverTick, arrivalNanos);
        });
    }

    /** Called on the client thread immediately before vanilla applies a correction. */
    public double onPlayerCorrection(ClientboundPlayerPositionPacket packet) {
        if (!mc.isSameThread()) throw new IllegalStateException("Player corrections require the client thread");
        if (mc.player == null) return 0.0;
        ClientPacketListener connection = mc.getConnection();
        if (sampledConnection != connection) resetSamples(connection);
        double distanceSquared = correctionDistanceSquared(PositionMoveRotation.of(mc.player), packet);
        if (Double.isFinite(distanceSquared) && distanceSquared > RUBBERBAND_DIST_SQ) {
            lastRubberbandNs = System.nanoTime();
        }
        return distanceSquared;
    }

    static double correctionDistanceSquared(PositionMoveRotation before, ClientboundPlayerPositionPacket packet) {
        PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(before, packet.change(), packet.relatives());
        return before.position().distanceToSqr(absolute.position());
    }

    private void resetSamples(ClientPacketListener connection) {
        sampledConnection = connection;
        rates = window.reset();
        lastRubberbandNs = Long.MIN_VALUE;
    }

    public float getAverageTPS() {
        return rates.average();
    }

    public float getLatestTPS() {
        return rates.latest();
    }

    public float getMinTPS() {
        return rates.minimum();
    }

    /**
     * Multiplier that stretches tick-based delays to keep per-server-tick cadence under lag:
     * 1.0 at 20 TPS, 2.0 at 10 TPS, capped at 4.0.
     */
    public float delayScale() {
        float tps = getAverageTPS();
        if (tps >= 19.5f || tps <= 0.0f) return 1.0f;
        return Math.min(4.0f, 20.0f / tps);
    }

    /** True for 400 milliseconds after a correction larger than three blocks. */
    public boolean isRubberbandRecent() {
        long last = lastRubberbandNs;
        return last != Long.MIN_VALUE && System.nanoTime() - last < RUBBERBAND_QUIET_NS;
    }
}
