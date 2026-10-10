package dev.guizhou.util.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.combat.GuangxiCrystalModule;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import static dev.guizhou.util.traits.Util.mc;

/** New utility actions yield to existing combat and flight ownership, including Rusher. */
public final class UtilityActionGuard {
    private UtilityActionGuard() {}
    public static String pauseReason() {
        if (mc.player == null || mc.level == null || mc.getConnection() == null || mc.gameMode == null) return dev.guizhou.util.lang.ClientLanguage.text("未进入世界");
        if (mc.player.isDeadOrDying() || mc.player.isSpectator()) return dev.guizhou.util.lang.ClientLanguage.text("不可操作");
        if (mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty()) return dev.guizhou.util.lang.ClientLanguage.text("界面操作中");
        if (mc.player.isUsingItem() || GuizhouWare.swapManager.isLatched()) return dev.guizhou.util.lang.ClientLanguage.text("使用物品中");
        var crystal = GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
        if (crystal != null && crystal.isEnabled()) {
            var logic = crystal.getSettingByName("Logic");
            if (logic != null && "RUSHER".equalsIgnoreCase(logic.getValue().toString())) return dev.guizhou.util.lang.ClientLanguage.text("Rusher 优先");
        }
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight != null && flight.gzBoostControlsLook()) return dev.guizhou.util.lang.ClientLanguage.text("GZBoost 巡航中");
        if (GuangxiElytraChaseModule.hasMaceDescentIntent() || GuangxiElytraChaseModule.isMaceDiveActive()) return dev.guizhou.util.lang.ClientLanguage.text("重锤下砸中");
        var chase = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraChaseModule.class);
        if (chase != null && (chase.hasHeading() || chase.wantsFlight())) return dev.guizhou.util.lang.ClientLanguage.text("空战控制中");
        if (flight != null && flight.isArmorSwapActive()) return dev.guizhou.util.lang.ClientLanguage.text("甲飞换装中");
        return null;
    }
}
