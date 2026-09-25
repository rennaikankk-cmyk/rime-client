package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * Multi-target melee attacks on top of AutoSword. AutoSword stays registered and
 * still attacks on its own when this module is off. While this module is on it is
 * the only one that swings, using AutoSword's range, weapon swap, hit check and TP path.
 */
public class KillAuraModule extends Module {

    private final Setting<AutoSwordModule.TargetSort> sort = mode("Sort", AutoSwordModule.TargetSort.DISTANCE);
    private final Setting<Integer> targets = num("Targets", 1, 1, 3);
    private final Setting<Boolean> rotate = bool("Rotate", true);

    public KillAuraModule() {
        super("KillAura", "Attacks several nearby targets through AutoSword without a second swing.", Category.COMBAT);
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce();
            return;
        }
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        AutoSwordModule sword = GuizhouWare.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword == null) return;
        List<Entity> found = sword.selectTargets(sort.getValue(), targets.getValue());
        for (int i = 0; i < found.size(); i++) {
            if (sword.coolingDown()) return;
            if (!sword.tryAttack(found.get(i), rotate.getValue())) continue;
        }
    }
}
