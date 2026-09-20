package dev.guizhou.util;

import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.util.traits.Util;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import dev.guizhou.util.diagnostics.ClientDiagnostics;

import java.util.ArrayDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Tracks local cancellation through the complete synchronous send event dispatch.
 * A successful submission is not a server acknowledgement. */
public final class PacketSendUtil implements Util {
    private static final ThreadLocal<ArrayDeque<Attempt>> ATTEMPTS = new ThreadLocal<>();
    private static final Observer OBSERVER = new Observer();
    private static final CopyOnWriteArrayList<Consumer<Packet<?>>> SUBMISSION_LISTENERS = new CopyOnWriteArrayList<>();

    static { EVENT_BUS.register(OBSERVER, "PacketSend"); }

    private PacketSendUtil() {}

    /** Observers run on the sending thread after all cancellable listeners. Never a server ACK. */
    public static void addSubmissionListener(Consumer<Packet<?>> observer) {
        SUBMISSION_LISTENERS.addIfAbsent(java.util.Objects.requireNonNull(observer));
    }

    public static void removeSubmissionListener(Consumer<Packet<?>> observer) {
        SUBMISSION_LISTENERS.remove(observer);
    }

    public static void onSubmitted(Packet<?> packet) {
        recordDisposition(packet, "submitted");
        for (Consumer<Packet<?>> observer : SUBMISSION_LISTENERS) {
            try { observer.accept(packet); }
            catch (RuntimeException failure) {
                // Diagnostics must never prevent a gameplay packet from being sent or flood logs.
                SUBMISSION_LISTENERS.remove(observer);
                dev.guizhou.GuizhouWare.LOGGER.warn("Disabled a failed local packet diagnostic observer", failure);
            }
        }
    }

    public static void onCancelled(Packet<?> packet) {
        recordDisposition(packet, "cancelled");
    }

    private static void recordDisposition(Packet<?> packet, String result) {
        if (!ClientDiagnostics.isCollecting()) return;
        ClientDiagnostics.count("packet." + result + ".total", 1);
        String kind = packet instanceof ServerboundMovePlayerPacket ? "move"
                : packet instanceof ServerboundInteractPacket ? "interact"
                : packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundUseItemPacket ? "use"
                : packet instanceof ServerboundPlayerActionPacket ? "action"
                : packet instanceof ServerboundSetCarriedItemPacket ? "slot" : "other";
        ClientDiagnostics.count("packet." + result + "." + kind, 1);
    }

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
