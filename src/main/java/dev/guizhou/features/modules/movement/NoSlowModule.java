package dev.guizhou.features.modules.movement;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.mixin.client.ClientLevelAccessor;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.movement.NoSlowSpoofSession;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public class NoSlowModule extends Module {
    public enum EatMode { SlotResend, Grim, Client }

    public final Setting<Boolean> crawl = bool("Crawl", true);
    public final Setting<Boolean> eat = bool("Eat", true);
    public final Setting<EatMode> eatMode = mode("EatMode", EatMode.Grim);
    public final Setting<Boolean> items = bool("Items", false);
    public final Setting<Boolean> shields = bool("Shields", false);
    public final Setting<Boolean> sneak = bool("Sneak", false);
    public final Setting<Boolean> webs = bool("Webs", false);
    public final Setting<Boolean> berryBush = bool("BerryBush", false);
    public final Setting<Boolean> soulSand = bool("SoulSand", false);
    public final Setting<Boolean> honeyBlock = bool("HoneyBlock", false);
    public final Setting<Boolean> slimeBlock = bool("SlimeBlock", false);
    private final Setting<Integer> spoofPackets = num("SpoofPackets", 37, 1, 40);
    private final Setting<Integer> spoofDelay = num("SpoofDelay", 2, 0, 10);
    private final Setting<Integer> spoofInterval = num("SpoofInterval", 1, 1, 10);
    private final Setting<Float> minTPS = num("MinTPS", 15.0f, 0.0f, 20.0f);

    private final NoSlowSpoofSession spoof = new NoSlowSpoofSession();
    private final AtomicBoolean corrected = new AtomicBoolean();
    private ClientLevel sessionWorld;
    private LocalPlayer sessionPlayer;
    private ItemStack sessionItem = ItemStack.EMPTY;
    private int sessionStartTick;
    private int sessionSlot = -1;
    private boolean observedConsumption;
    private boolean forwardingMovement;

    public NoSlowModule() {
        super("NoSlow", "Configurable item, crouch and block slowdown removal.", Category.MOVEMENT);
    }

    @Override public void onEnable() { resetState(); }
    @Override public void onDisable() { resetState(); }

    @Override
    public void onTick() {
        if (!HeartbeatManager.authorized()) { HeartbeatManager.notifyDeniedOnce(); resetState(); return; }
        if (nullCheck() || corrected.getAndSet(false) || sessionPlayer != mc.player || sessionWorld != mc.level) {
            resetState();
            return;
        }
        validateSession();
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket
                || event.getPacket() instanceof ClientboundRespawnPacket) corrected.set(true);
    }

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (!isEnabled() || !HeartbeatManager.authorized() || !mc.isSameThread() || nullCheck()
                || mc.getConnection() == null || corrected.get() || forwardingMovement) return;

        if (event.getPacket() instanceof ServerboundUseItemPacket use) {
            // Pearls, rockets, shields and offhand use must never arm the eat packet sequence.
            if (use.getHand() != InteractionHand.MAIN_HAND || !slotResendEnabled()
                    || !isConsumable(mc.player.getMainHandItem()) || !serverHoldsSelectedSlot()) return;
            if (validSession() && isConsumingMainHandItem()) return;
            sessionPlayer = mc.player;
            sessionWorld = mc.level;
            sessionItem = mc.player.getMainHandItem().copy();
            sessionSlot = mc.player.getInventory().getSelectedSlot();
            sessionStartTick = mc.player.tickCount;
            observedConsumption = false;
            spoof.begin(sessionStartTick, sessionSlot, spoofDelay.getValue(), spoofPackets.getValue(),
                    spoofInterval.getValue());
            return;
        }

        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket move)) return;
        if (eatMode.getValue() == EatMode.Grim && isConsumingMainHandItem() && eat.getValue()) {
            var handler = mc.getConnection();
            float yaw = GuizhouWare.rotationManager == null ? mc.player.getYRot() : GuizhouWare.rotationManager.getServerYaw();
            float pitch = GuizhouWare.rotationManager == null ? mc.player.getXRot() : GuizhouWare.rotationManager.getServerPitch();
            forwardingMovement = true;
            try (var predict = ((ClientLevelAccessor) mc.level).guizhouware$getBlockStatePredictionHandler().startPredicting()) {
                handler.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                        BlockPos.ZERO, Direction.DOWN));
                handler.send(move);
                handler.send(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, predict.currentSequence(), yaw, pitch));
            } finally { forwardingMovement = false; }
            event.cancel();
            return;
        }
        if (!validateSession() || !isConsumingMainHandItem() || !serverHoldsSelectedSlot()) return;

        if (!forwardingMovement && move.hasPosition() && !move.hasRotation()) {
            // Preserve packet-owned coordinates/flags and the last sent rotation. Flight/teleport
            // modules may deliberately send coordinates which differ from the local player.
            float yaw = GuizhouWare.rotationManager == null ? mc.player.getYRot()
                    : GuizhouWare.rotationManager.getServerYaw();
            float pitch = GuizhouWare.rotationManager == null ? mc.player.getXRot()
                    : GuizhouWare.rotationManager.getServerPitch();
            forwardingMovement = true;
            try {
                PacketSendUtil.forward(event, new ServerboundMovePlayerPacket.PosRot(
                        move.getX(mc.player.getX()), move.getY(mc.player.getY()), move.getZ(mc.player.getZ()),
                        yaw, pitch, move.isOnGround(), move.horizontalCollision()));
            } finally { forwardingMovement = false; }
            return;
        }

        if (move.hasPosition() && move.hasRotation()
                && spoof.poll(mc.player.tickCount, sessionSlot, true)) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(sessionSlot));
        }
    }

    private boolean slotResendEnabled() {
        return eat.getValue() && eatMode.getValue() == EatMode.SlotResend
                && GuizhouWare.tpsCounterService != null
                && GuizhouWare.tpsCounterService.getLatestTPS() >= minTPS.getValue();
    }

    private boolean validSession() {
        return spoof.active() && sessionWorld == mc.level && sessionPlayer == mc.player;
    }

    private boolean validateSession() {
        if (!validSession() || !slotResendEnabled() || mc.player.getInventory().getSelectedSlot() != sessionSlot
                || !ItemStack.isSameItemSameComponents(sessionItem, mc.player.getMainHandItem())) {
            resetState();
            return false;
        }
        boolean consuming = isConsumingMainHandItem();
        if (!consuming && (observedConsumption || mc.player.tickCount - sessionStartTick > 2)) {
            resetState();
            return false;
        }
        observedConsumption |= consuming;
        return true;
    }

    private boolean serverHoldsSelectedSlot() {
        return GuizhouWare.swapManager == null
                || GuizhouWare.swapManager.serverSlot() == mc.player.getInventory().getSelectedSlot();
    }

    private void resetState() {
        spoof.reset();
        sessionWorld = null;
        sessionPlayer = null;
        sessionItem = ItemStack.EMPTY;
        sessionSlot = -1;
        observedConsumption = false;
        corrected.set(false);
    }

    private boolean isConsumingMainHandItem() {
        return mc.player != null && mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && isConsumable(mc.player.getUseItem());
    }

    private static boolean isConsumable(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.CONSUMABLE);
    }

    public static boolean shouldCancelConsumeSlow() {
        NoSlowModule module = getInstance();
        if (module == null || !module.isEnabled() || !HeartbeatManager.authorized()
                || mc.player == null || !mc.player.isUsingItem()) return false;
        ItemStack used = mc.player.getUseItem();
        if (isConsumable(used)) {
            if (!module.eat.getValue()) return false;
            if (module.eatMode.getValue() == EatMode.Client || module.eatMode.getValue() == EatMode.Grim) return true;
            return module.slotResendEnabled() && module.validSession()
                    && module.isConsumingMainHandItem() && module.serverHoldsSelectedSlot();
        }
        return used.getUseAnimation() == ItemUseAnimation.BLOCK
                ? module.shields.getValue() : module.items.getValue();
    }

    public static Vec3 stuckMultiplier(BlockState state, Vec3 original) {
        return isActive(m -> state.is(Blocks.COBWEB) && m.webs.getValue()
                || state.is(Blocks.SWEET_BERRY_BUSH) && m.berryBush.getValue()) ? Vec3.ZERO : original;
    }

    public static boolean ignoresSpeedFactor(BlockState state) {
        return isActive(m -> state.is(Blocks.SOUL_SAND) && m.soulSand.getValue()
                || state.is(Blocks.HONEY_BLOCK) && m.honeyBlock.getValue());
    }

    public static NoSlowModule getInstance() {
        if (GuizhouWare.moduleManager == null) return null;
        return GuizhouWare.moduleManager.getModuleByClass(NoSlowModule.class);
    }

    public static boolean isActive(Predicate<NoSlowModule> predicate) {
        NoSlowModule module = getInstance();
        return module != null && module.isEnabled() && HeartbeatManager.authorized() && predicate.test(module);
    }
}
