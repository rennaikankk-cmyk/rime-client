package dev.guizhou.features.modules.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.movement.NoFallModule;
import dev.guizhou.features.modules.world.GuangxiMineModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.mixin.network.MovePlayerPacketAccessor;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.movement.GroundSpoofPolicy;
import dev.guizhou.util.movement.MovementSpoofContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

public final class AntiHungerModule extends Module {
    private final Setting<Boolean> sprint = bool("Sprint", true);
    private final Setting<Boolean> ground = bool("Ground", true);
    private ClientLevel sessionWorld;
    private LocalPlayer sessionPlayer;
    private boolean sprintSuppressed;
    private boolean previouslyGrounded;

    public AntiHungerModule() {
        super("AntiHunger", "Reduces movement exhaustion with sprint and ground spoofing.", Category.PLAYER);
    }

    @Override public void onEnable() { resetState(); }
    @Override public void onDisable() { restoreSprint(); resetState(); }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) {
            HeartbeatManager.notifyDeniedOnce();
            restoreSprint();
            return;
        }
        if (nullCheck()) { resetState(); return; }
        if (sessionPlayer != mc.player || sessionWorld != mc.level) {
            resetState();
            sessionWorld = mc.level;
            sessionPlayer = mc.player;
        }
        boolean suppress = sprint.getValue() && MovementSpoofContext.ordinaryMovement();
        if (suppress && !sprintSuppressed) {
            sprintSuppressed = PacketSendUtil.send(new ServerboundPlayerCommandPacket(mc.player,
                    ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        } else if (!suppress) restoreSprint();
        previouslyGrounded = mc.player.onGround() && mc.player.fallDistance == 0.0;
    }

    @Subscribe(priority = -100)
    private void onPacketSend(PacketEvent.Send event) {
        if (!HeartbeatManager.authorized() || !MovementSpoofContext.ordinaryMovement()
                || sessionPlayer != mc.player || sessionWorld != mc.level) return;
        if (sprint.getValue() && sprintSuppressed) {
            if (event.getPacket() instanceof ServerboundPlayerCommandPacket command
                    && command.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
                event.cancel();
                return;
            }
            // 1.21.1's ServerboundPlayerInputPacket carries only impulses/jump/shift;
            // sprint intent travels on ServerboundPlayerCommandPacket (handled elsewhere),
            // so there is no sprint bit to strip here.
        }
        if (ground.getValue() && mc.player.onGround() && event.getPacket() instanceof ServerboundMovePlayerPacket move
                && GroundSpoofPolicy.hideGround(move.isOnGround(), mc.player.onGround(), previouslyGrounded,
                        mc.options.keyJump.isDown(), mc.player.fallDistance, mc.player.getDeltaMovement().y,
                        mining(), NoFallModule.protectsPacket(move))) {
            ((MovePlayerPacketAccessor) move).guizhouware$setOnGround(false);
        }
    }

    private boolean mining() {
        if (mc.gameMode != null && mc.gameMode.isDestroying()) return true;
        if (GuizhouWare.moduleManager == null) return false;
        GuangxiMineModule mine = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
        if (mine == null || !mine.isEnabled()) return false;
        var primary = mine.getPrimaryPos();
        var secondary = mine.getSecondaryPos();
        return primary != null && !mc.level.isEmptyBlock(primary)
                || secondary != null && !mc.level.isEmptyBlock(secondary);
    }

    private void restoreSprint() {
        if (!sprintSuppressed) return;
        sprintSuppressed = false;
        if (mc.isSameThread() && mc.player != null && sessionPlayer == mc.player && sessionWorld == mc.level
                && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, mc.player.isSprinting()
                    ? ServerboundPlayerCommandPacket.Action.START_SPRINTING
                    : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }
    }

    private void resetState() {
        sessionWorld = null;
        sessionPlayer = null;
        sprintSuppressed = false;
        previouslyGrounded = false;
    }
}
