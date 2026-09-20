package dev.guizhou.util;

import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.util.traits.Util;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayDeque;

/** Tracks local cancellation through the complete synchronous send event dispatch.
 * A successful submission is not a server acknowledgement. */
public final class PacketSendUtil implements Util {
    private static final ThreadLocal<ArrayDeque<Attempt>> ATTEMPTS = new ThreadLocal<>();
    private static final Observer OBSERVER = new Observer();

    static { EVENT_BUS.register(OBSERVER); }

    private PacketSendUtil() {}

    public static boolean send(Packet<?> packet) {
        if (!mc.isSameThread() || mc.player == null || mc.getConnection() == null) return false;
        Attempt attempt = new Attempt(packet);
        ArrayDeque<Attempt> attempts = ATTEMPTS.get();
        if (attempts == null) {
            attempts = new ArrayDeque<>();
            ATTEMPTS.set(attempts);
        }
        attempts.push(attempt);
        try {
            mc.getConnection().send(packet);
            return attempt.event != null && (!attempt.event.isCancelled() || attempt.replacementAccepted);
        } finally {
            attempts.pop();
            if (attempts.isEmpty()) ATTEMPTS.remove();
        }
    }

    /** Replaces one outgoing packet while preserving tracked submission success for its caller.
     * The replacement runs through the full event chain and may itself be cancelled. */
    public static boolean forward(PacketEvent.Send original, Packet<?> replacement) {
        if (original.isCancelled() || original.getPacket() == replacement) return false;
        original.cancel();
        boolean accepted = send(replacement);
        ArrayDeque<Attempt> attempts = ATTEMPTS.get();
        if (attempts != null) {
            for (Attempt attempt : attempts) {
                if (attempt.packet == original.getPacket()) {
                    attempt.replacementAccepted = accepted;
                    break;
                }
            }
        }
        return accepted;
    }

    private static final class Attempt {
        private final Packet<?> packet;
        private PacketEvent.Send event;
        private boolean replacementAccepted;
        private Attempt(Packet<?> packet) { this.packet = packet; }
    }

    private static final class Observer {
        @Subscribe(priority = Integer.MAX_VALUE)
        private void onSend(PacketEvent.Send event) {
            ArrayDeque<Attempt> attempts = ATTEMPTS.get();
            if (attempts == null) return;
            for (Attempt attempt : attempts) {
                if (attempt.packet == event.getPacket()) {
                    attempt.event = event;
                    return;
                }
            }
            if (attempts.isEmpty()) ATTEMPTS.remove();
        }
    }
}
