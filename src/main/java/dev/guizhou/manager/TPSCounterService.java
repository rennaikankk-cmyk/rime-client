package dev.guizhou.manager;

import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.Feature;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;

public class TPSCounterService extends Feature {
    /** A server teleport this far from our client position is a rubberband. */
    private static final double RUBBERBAND_DIST_SQ = 9.0;
    private static final long RUBBERBAND_QUIET_MS = 400L;

    private final float[] tickRates = new float[20];
    private int nextIndex = 0;
    private int countTick = 0;
    private long lastTimeUpdate = -1;
    private long lastRubberbandMs = Long.MIN_VALUE;

    public void init() {
        EVENT_BUS.register(this);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        nextIndex = 0;
        countTick = 0;
        lastTimeUpdate = -1;
        lastRubberbandMs = Long.MIN_VALUE;
    }

    @Subscribe
    public void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundSetTimePacket)) {
            if (event.getPacket() instanceof ClientboundPlayerPositionPacket teleport
                    && mc.player != null) {
                var serverPos = teleport.change().position();
                if (mc.player.position().distanceToSqr(serverPos) > RUBBERBAND_DIST_SQ) {
                    lastRubberbandMs = System.currentTimeMillis();
                }
            }
            return;
        }

        long now = System.currentTimeMillis();

        if (lastTimeUpdate != -1) {
            float elapsed = (now - lastTimeUpdate) / 1000.0f;
            float tps = 20.0f / elapsed;
            tickRates[nextIndex % tickRates.length] = Math.min(Math.max(tps, 0.0f), 20.0f);
            nextIndex++;
            countTick = Math.min(countTick + 1, tickRates.length);
        }

        lastTimeUpdate = now;
    }

    public float getAverageTPS() {
        if (countTick == 0) return 20.0f;

        float sum = 0.0f;
        int valid = 0;
        for (int i = 0; i < countTick; i++) {
            float t = tickRates[i];
            if (t > 0.0f) {
                sum += t;
                valid++;
            }
        }
        return valid == 0 ? 20.0f : Math.min(Math.max(sum / valid, 0.0f), 20.0f);
    }

    public float getLatestTPS() {
        if (countTick == 0) return 20.0f;

        int last = (nextIndex - 1 + tickRates.length) % tickRates.length;
        return Math.min(Math.max(tickRates[last], 0.0f), 20.0f);
    }

    public float getMinTPS() {
        if (countTick == 0) return 20.0f;

        float min = 20.0f;
        for (int i = 0; i < countTick; i++) {
            float t = tickRates[i];
            if (t > 0.0f && t < min) {
                min = t;
            }
        }
        return Math.min(Math.max(min, 0.0f), 20.0f);
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

    /** True within {@link #RUBBERBAND_QUIET_MS} of a server rubberband — placements should pause. */
    public boolean isRubberbandRecent() {
        return lastRubberbandMs != Long.MIN_VALUE
                && System.currentTimeMillis() - lastRubberbandMs < RUBBERBAND_QUIET_MS;
    }
}
