package dev.guizhou.features.modules.movement;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.mixin.network.MovePlayerPacketAccessor;
import dev.guizhou.util.movement.GroundSpoofPolicy;
import dev.guizhou.util.movement.MovementSpoofContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.concurrent.atomic.AtomicBoolean;

/** Ground-bit protection on existing movement packets, with no extra movement packet burst. */
public final class NoFallModule extends Module {
    private final Setting<Double> fallDistance = num("FallDistance", 2.5, 1.0, 10.0);
    private final Setting<Boolean> checkGround = bool("CheckGround", true);
    private final Setting<Double> groundRange = num("GroundRange", 4.0, 0.5, 8.0);
    private final Setting<Integer> correctionPause = num("CorrectionPause", 10, 1, 40);
    private final Setting<Boolean> pauseMace = bool("PauseMace", true);
    private final AtomicBoolean corrected = new AtomicBoolean();
    private ClientLevel sessionWorld;
    private LocalPlayer sessionPlayer;
    private int pauseUntil;

    public NoFallModule() {
        super("NoFall", "Spoofs ground while falling; pauses for flight, fluids, corrections and mace use.", Category.MOVEMENT);
    }

    @Override public void onEnable() { resetState(); }
    @Override public void onDisable() { resetState(); }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) { HeartbeatManager.notifyDeniedOnce(); return; }
        if (nullCheck()) { resetState(); return; }
        ensureSession();
        if (corrected.getAndSet(false)) pauseUntil = mc.player.tickCount + correctionPause.getValue();
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        // Receive callbacks may be on Netty: publish only a flag, never inspect or modify the world here.
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket
                || event.getPacket() instanceof ClientboundRespawnPacket) corrected.set(true);
    }

    @Subscribe(priority = 100)
    private void onPacketSend(PacketEvent.Send event) {
        if (event.getPacket() instanceof ServerboundMovePlayerPacket move && protects(move)) {
            ((MovePlayerPacketAccessor) move).guizhouware$setOnGround(true);
        }
    }

    private boolean protects(ServerboundMovePlayerPacket move) {
        if (!isEnabled() || !HeartbeatManager.authorized() || !MovementSpoofContext.ordinaryMovement()
                || !move.hasPosition() || corrected.get()) return false;
        ensureSession();
        if (mc.player.tickCount < pauseUntil
                || pauseMace.getValue() && dev.guizhou.util.elytra.MappedCombatItems.isMace(mc.player.getMainHandItem())
                || !GroundSpoofPolicy.falling(mc.player.fallDistance, fallDistance.getValue(),
                        mc.player.getDeltaMovement().y, mc.player.onGround())) return false;
        // Search only blocks below the feet: an entity or a wall beside the body is not ground.
        if (!checkGround.getValue()) return true;
        AABB body = mc.player.getBoundingBox();
        AABB below = new AABB(body.minX + 1.0e-4, body.minY - groundRange.getValue(), body.minZ + 1.0e-4,
                body.maxX - 1.0e-4, body.minY - 1.0e-4, body.maxZ - 1.0e-4);
        return mc.level.getBlockCollisions(mc.player, below).iterator().hasNext();
    }

    public static boolean protectsPacket(ServerboundMovePlayerPacket move) {
        if (GuizhouWare.moduleManager == null) return false;
        NoFallModule module = GuizhouWare.moduleManager.getModuleByClass(NoFallModule.class);
        return module != null && module.protects(move);
    }

    private void ensureSession() {
        if (sessionWorld == mc.level && sessionPlayer == mc.player) return;
        sessionWorld = mc.level;
        sessionPlayer = mc.player;
        pauseUntil = 0;
    }

    private void resetState() {
        sessionWorld = null;
        sessionPlayer = null;
        pauseUntil = 0;
        corrected.set(false);
    }

    @Override public String getDisplayInfo() { return "GroundSpoof"; }
}
