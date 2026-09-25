package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.combat.AuraPolicy.SortPriority;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * Multi-target melee on top of AutoSword. While this module is on, AutoSword does not
 * swing on its own; attacks still go through AutoSword's weapon, swap, crit and TP path.
 */
public class KillAuraModule extends Module {

    private final Setting<SortPriority> sort = mode("Sort", SortPriority.DISTANCE);
    private final Setting<Integer> targets = num("Targets", 1, 1, 3);
    private final Setting<Boolean> rotate = bool("Rotate", true);

    public KillAuraModule() {
        super("KillAura", "Attacks several nearby targets through AutoSword without a second swing.", Category.COMBAT);
    }

    @Subscribe(priority = -1)
    private void onPreTick(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) {
            HeartbeatManager.notifyDeniedOnce();
            return;
        }
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        AutoSwordModule sword = GuizhouWare.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword == null || !sword.serviceTick()) return;
        List<Entity> found = sword.selectTargets(sort.getValue(), targets.getValue());
        for (int i = 0; i < found.size(); i++) {
            if (sword.coolingDown()) return;
            if (!sword.tryAttack(found.get(i), rotate.getValue())) continue;
        }
    }
}
