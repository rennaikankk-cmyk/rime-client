package dev.guizhou.features.modules.qinghua;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.TPUtil;
import dev.guizhou.features.settings.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Port of eternal AutoRunner — trigger / vclip escape unchanged.
 * disableIfActive of ETP* modules → disable sibling qinghua modules when present.
 */
public class AutoRunnerModule extends Module {
    public final Setting<Boolean> lowHpEnabled = bool("LowHpEnabled", true)
            .described("Trigger on low HP.");
    public final Setting<Double> healthThreshold = num("HealthThreshold", 8.0, 1.0, 36.0)
            .described("When health drops below this value, trigger the escape.");
    public final Setting<Boolean> blockDetectEnabled = bool("BlockDetectEnabled", true)
            .described("Trigger when a solid block is above your head.");
    public final Setting<Integer> checkHeight = num("CheckHeight", 2, 1, 10)
            .described("How many blocks above head to check for solid blocks.");
    public final Setting<Integer> vclipHeight = num("VclipHeight", 70, 1, 300)
            .described("How many blocks to vclip upward.");
    public final Setting<Boolean> autoDisable = bool("AutoDisable", true)
            .described("Automatically disable protection modules and self after triggering.");
    public final Setting<Double> moveDistance = num("MoveDistance", 10.0, 1.0, 50.0)
            .described("Max distance per move packet.");

    private boolean triggered;

    public AutoRunnerModule() {
        super("AutoRunner", "Runner.", Category.MOVEMENT);
    }

    @Override
    public void onEnable() {
        this.triggered = false;
    }

    @Subscribe
    private void onTick(PreTickEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (this.triggered) return;

        boolean shouldTrigger = false;
        if (this.lowHpEnabled.getValue()) {
            float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            if (hp < this.healthThreshold.getValue()) shouldTrigger = true;
        }
        if (!shouldTrigger && this.blockDetectEnabled.getValue()) {
            BlockPos playerPos = mc.player.blockPosition();
            for (int y = 1; y <= this.checkHeight.getValue(); y++) {
                BlockPos checkPos = playerPos.above(y);
                if (mc.level.getBlockState(checkPos).isSolidRender()) {
                    shouldTrigger = true;
                    break;
                }
            }
        }
        if (!shouldTrigger) return;

        this.triggered = true;
        if (this.autoDisable.getValue()) {
            disableIfActive(AnchorTrapModule.class);
        }
        Vec3 playerPos = mc.player.position();
        Vec3 targetPos = new Vec3(playerPos.x, playerPos.y + this.vclipHeight.getValue(), playerPos.z);
        TPUtil.doTpMove(playerPos, targetPos, this.moveDistance.getValue(), false, () -> {});
        mc.player.setPos(targetPos);
        if (this.autoDisable.getValue()) disable();
    }

    private void disableIfActive(Class<? extends Module> moduleClass) {
        Module mod = GuizhouWare.moduleManager.getModuleByClass(moduleClass);
        if (mod != null && mod.isEnabled()) mod.disable();
    }
}
