package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.hud.SignalHysteresis;
import dev.guizhou.util.lang.ClientLanguage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Warns when the chunk under you — or the one your current speed is about to carry you into —
 * is still unloaded. Ported from SlimefunHelper AntiChunkLag as a warning only: movement is
 * never frozen, and the warning arms and clears with hysteresis so a chunk border crossed at
 * speed does not flap the notice on and off.
 */
public class ChunkLagModule extends Module {

    private final Setting<Double> lookaheadSeconds = num("LookaheadSeconds", 1.0, 0.25, 3.0);
    private final Setting<Integer> sampleTicks = num("SampleTicks", 2, 1, 10);
    private final Setting<Integer> onTicks = num("OnTicks", 5, 1, 40);
    private final Setting<Integer> offTicks = num("OffTicks", 15, 1, 100);
    private final Setting<Boolean> notify = bool("Notify", true);

    private int timer;
    private SignalHysteresis warning;
    private String reason;

    public ChunkLagModule() {
        super("ChunkLag", "Warns when the chunk under you, or the one your speed is about to carry you into, "
                + "is still unloaded. Warning only, with hysteresis so chunk borders do not flap it.", Category.HUD);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;
        if (!HeartbeatManager.authorized()) {
            HeartbeatManager.notifyDeniedOnce();
            return;
        }
        if (warning == null) {
            warning = new SignalHysteresis(onTicks.getValue(), offTicks.getValue());
        }
        if (--timer > 0) return;
        timer = sampleTicks.getValue();
        String current = unloadedChunkReason();
        if (current != null) {
            reason = current;
        } else if (warning.on()) {
            reason = null;
        }
        boolean armed = warning.update(current != null);
        if (armed && notify.getValue() && reason != null) {
            NotifierHudModule.push("chunklag", NotifierHudModule.Icon.DANGER, net.minecraft.network.chat.Component.literal(reason), 750L);
        }
    }

    /** "Under you" first, then the chunk the current velocity points at, with its distance. */
    private String unloadedChunkReason() {
        BlockPos feet = mc.player.blockPosition();
        if (!mc.level.hasChunkAt(feet)) {
            return ClientLanguage.text("脚下区块未加载：") + chunkName(feet);
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() < 0.01) return null;
        Vec3 ahead = mc.player.position().add(velocity.scale(lookaheadSeconds.getValue()));
        BlockPos aheadPos = BlockPos.containing(ahead);
        if (!mc.level.hasChunkAt(aheadPos)) {
            int distance = (int) mc.player.position().distanceTo(ahead);
            return ClientLanguage.text("前方区块未加载：") + chunkName(aheadPos)
                    + ClientLanguage.text("（约 %d 格）").formatted(distance);
        }
        return null;
    }

    private static String chunkName(BlockPos pos) {
        return (pos.getX() >> 4) + ", " + (pos.getZ() >> 4);
    }

    @Override
    public void onDisable() {
        if (warning != null) warning.reset();
        reason = null;
    }

    @Override
    public String getDisplayInfo() {
        return warning != null && warning.on() ? ClientLanguage.text("未加载") : null;
    }
}
