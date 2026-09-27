package dev.guizhou.util.inventory;

import dev.guizhou.GuizhouWare;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.mixin.client.ClientLevelAccessor;
import dev.guizhou.util.PacketSendUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import static dev.guizhou.util.traits.Util.mc;

/** Captures the real hand inside the swap and only after complete cancellable send dispatch. */
public final class ItemUseTracker {
    private record Action(String owner, ItemStack stack, BlockPos position) {}
    private static final PendingSequences<Action> PENDING = new PendingSequences<>();
    private static volatile Object connection;
    private static Object level, player;
    private static String submittingOwner = ""; // All mutating operations are confined to the client thread.
    private static boolean initialized;
    private static String aimingOwner;
    private static int aimExpiry, alignedTicks, sampledTick = Integer.MIN_VALUE;
    private static float aimYaw, aimPitch;
    private static final String AIM_ID = "UtilityItemUse";
    private ItemUseTracker() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        PacketSendUtil.addSubmissionListener(ItemUseTracker::submitted);
        dev.guizhou.util.traits.Util.EVENT_BUS.register(new Lifecycle(), "ItemUseTracker");
    }
    private static final class Lifecycle {
        @dev.guizhou.event.system.Subscribe
        private void beforeTick(dev.guizhou.event.impl.entity.player.PreTickEvent event) {
            session();
            if (aimingOwner != null && (mc.player == null || mc.player.tickCount > aimExpiry
                    || !HeartbeatManager.authorized() || dev.guizhou.util.combat.UtilityActionGuard.pauseReason() != null)) releaseAim();
        }
        @dev.guizhou.event.system.Subscribe
        private void disconnected(dev.guizhou.event.impl.network.DisconnectEvent event) {
            Object departed = connection;
            mc.execute(() -> {
                if (connection != departed) return;
                PENDING.clear(); releaseAim(); connection = null; level = null; player = null;
            });
        }
    }
    private static void session() {
        if (connection != mc.getConnection() || level != mc.level || player != mc.player) {
            PENDING.clear(); releaseAim(); connection = mc.getConnection(); level = mc.level; player = mc.player;
        }
        if (mc.player != null) PENDING.expire(mc.player.tickCount);
    }
    private static void submitted(Packet<?> packet) {
        if (!mc.isSameThread() || mc.player == null) return;
        int sequence; InteractionHand hand; BlockPos position = null;
        if (packet instanceof ServerboundUseItemPacket use) { sequence = use.getSequence(); hand = use.getHand(); }
        else if (packet instanceof ServerboundUseItemOnPacket use) {
            sequence = use.getSequence(); hand = use.getHand(); position = use.getHitResult().getBlockPos().immutable();
        } else return;
        session();
        PENDING.submitted(sequence, new Action(submittingOwner, mc.player.getItemInHand(hand).copy(), position), mc.player.tickCount);
    }
    public static void acknowledge(int sequence) {
        if (!mc.isSameThread()) return;
        session(); PENDING.acknowledge(sequence);
    }
    public static void reset() { if (mc.isSameThread()) { PENDING.clear(); releaseAim(); } }
    public static void cancel(String owner) {
        if (mc.isSameThread() && owner.equals(aimingOwner)) releaseAim();
    }
    private static void releaseAim() {
        if (GuizhouWare.rotationManager != null) GuizhouWare.rotationManager.cancel(AIM_ID);
        aimingOwner = null; alignedTicks = 0; sampledTick = Integer.MIN_VALUE;
    }
    private static boolean aim(String owner, float yaw, float pitch) {
        if (dev.guizhou.util.combat.UtilityActionGuard.pauseReason() != null) { releaseAim(); return false; }
        if (aimingOwner != null && !aimingOwner.equals(owner)) return false;
        if (aimingOwner == null || Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - aimYaw)) > .5
                || Math.abs(pitch - aimPitch) > .5) { alignedTicks = 0; sampledTick = Integer.MIN_VALUE; }
        aimingOwner = owner; aimYaw = yaw; aimPitch = pitch; aimExpiry = mc.player.tickCount + 3;
        var rotation = GuizhouWare.rotationManager;
        rotation.trySubmit(new RotationRequest(AIM_ID, 40, yaw, pitch, RotationRequest.Mode.MOTION, true, true));
        if (sampledTick != mc.player.tickCount) {
            sampledTick = mc.player.tickCount;
            if (rotation.isActive(AIM_ID) && Math.abs(net.minecraft.util.Mth.wrapDegrees(rotation.getServerYaw() - yaw)) < .5
                    && Math.abs(rotation.getServerPitch() - pitch) < .5) alignedTicks++;
            else alignedTicks = 0;
        }
        return alignedTicks >= 2;
    }
    public static boolean waiting(String owner) {
        if (!mc.isSameThread() || mc.player == null) return false;
        session(); return PENDING.any(a -> a.owner.equals(owner));
    }
    public static boolean waitingAt(BlockPos pos) {
        if (!mc.isSameThread() || mc.player == null) return false;
        session(); return PENDING.any(a -> pos.equals(a.position));
    }
    public static ItemStack snapshot(int sequence) {
        if (!mc.isSameThread()) return ItemStack.EMPTY;
        session(); var action = PENDING.get(sequence);
        return action == null ? ItemStack.EMPTY : action.stack.copy();
    }

    public static boolean use(String owner, Result result, float yaw, float pitch) {
        return use(owner, result, yaw, pitch, null);
    }
    public static boolean use(String owner, Result result, float yaw, float pitch, BlockHitResult hit) {
        return useBatch(owner, result, yaw, pitch, hit, 1) > 0;
    }
    public static int useBatch(String owner, Result result, float yaw, float pitch, BlockHitResult hit, int amount) {
        if (!mc.isSameThread() || mc.player == null || mc.level == null || mc.gameMode == null
                || mc.getConnection() == null || !HeartbeatManager.authorized() || !result.found()
                || waiting(owner) || amount <= 0) return 0;
        // Ordinary movement must carry the aim before an action, including on translated older protocols.
        if (!aim(owner, yaw, pitch)) return 0;
        mc.gameMode.ensureHasSentCarriedItem();
        ItemStack expected = result.stack().copy();
        int[] sent = {0};
        GuizhouWare.swapManager.withSwap(result, SwapMode.ALTSILENT, SwapPriority.UTILITY, () -> {
            if (!HeartbeatManager.authorized() || !ItemStack.isSameItemSameComponents(expected, mc.player.getItemInHand(result.hand()))) return;
            String previous = submittingOwner;
            submittingOwner = owner;
            try {
                int limit = Math.min(Math.min(amount, 6), expected.getCount());
                for (int i = 0; i < limit && HeartbeatManager.authorized(); i++) {
                    try (var prediction = ((ClientLevelAccessor) mc.level).guizhouware$getBlockStatePredictionHandler().startPredicting()) {
                        int sequence = prediction.currentSequence();
                        if (!PacketSendUtil.send(hit == null
                                ? new ServerboundUseItemPacket(result.hand(), sequence, yaw, pitch)
                                : new ServerboundUseItemOnPacket(result.hand(), hit, sequence))) break;
                        sent[0]++;
                    }
                }
            } finally { submittingOwner = previous; }
        });
        if (sent[0] > 0) aimExpiry = mc.player.tickCount + 2;
        return sent[0];
    }
}
