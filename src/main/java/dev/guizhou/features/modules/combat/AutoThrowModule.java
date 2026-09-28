package dev.guizhou.features.modules.combat;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.combat.ThrowEnvironment;
import dev.guizhou.util.combat.UtilityActionGuard;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.ItemUseTracker;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** SlimefunHelper-inspired environment-aware potion use, adapted to Rime's action ownership. */
public final class AutoThrowModule extends Module {
    private final Setting<Boolean> ground = bool("Ground", true).described("贴近地面时允许投掷。");
    private final Setting<Boolean> ceiling = bool("Ceiling", false).described("贴近顶板时允许投掷。");
    private final Setting<Boolean> rising = bool("Rising", false).described("近乎垂直上升时允许投掷，巡航和空战控制期间仍让行。");
    private final Setting<Boolean> airborne = bool("Airborne", false).described("普通滑翔时也投掷；药水可能追不上自己，默认关闭。");
    private final Setting<Double> gap = num("SurfaceGap", 0.5, 0.1, 2.0).described("与地面或顶板的最大间距，单位格。");
    private final Setting<Double> healAt = num("HealBelow", 16.0, 1.0, 20.0).described("生命值低于此数才投治疗或再生药水，20 为满血。");
    private final Setting<Boolean> buffs = bool("MaintainBuffs", false).described("补充即将结束的有益效果；不会投含负面效果的药水。");
    private final Setting<Boolean> avoidEnemies = bool("AvoidEnemies", true).described("附近有非好友玩家时暂停，避免给对方药水效果。");
    private final Setting<Double> enemyRange = num("EnemyRange", 2.0, 0.5, 6.0).described("暂停投掷的敌人距离，单位格。");
    private final Setting<Integer> delay = num("DelayTicks", 10, 2, 40).described("成功提交两次投掷之间的最短 tick 数，并等待上一动作确认。");
    private Object sessionPlayer, sessionLevel;
    private int nextTick;
    private String status;

    public AutoThrowModule() {
        super("AutoThrow", "按地形与血量投掷有益药水，自动让行巡航、空战与甲飞。", Category.COMBAT);
    }
    @Override public void onDisable() { nextTick = 0; status = null; ItemUseTracker.cancel("AutoThrow"); }
    @Override public String getDisplayInfo() { return status; }
    @Subscribe private void onTick(TickEvent event) {
        if (!HeartbeatManager.authorized()) { status = dev.guizhou.util.lang.ClientLanguage.text("授权不可用"); return; }
        if (sessionPlayer != mc.player || sessionLevel != mc.level) {
            nextTick = 0; sessionPlayer = mc.player; sessionLevel = mc.level;
        }
        status = UtilityActionGuard.pauseReason();
        if (status != null) return;
        if (ItemUseTracker.waiting("AutoThrow")) { status = dev.guizhou.util.lang.ClientLanguage.text("等待动作确认"); return; }
        if (mc.player.tickCount < nextTick) { status = dev.guizhou.util.lang.ClientLanguage.text("冷却"); return; }
        if (avoidEnemies.getValue() && ThrowEnvironment.nearEnemy(enemyRange.getValue())) { status = dev.guizhou.util.lang.ClientLanguage.text("附近有敌人"); return; }
        if (!ThrowEnvironment.accepts(ground.getValue(), ceiling.getValue(), rising.getValue(), airborne.getValue(), gap.getValue())) {
            status = dev.guizhou.util.lang.ClientLanguage.text("等待合适地形"); return;
        }
        var potion = InventoryUtil.find(this::needed, InventoryUtil.FULL_SCOPE);
        if (!potion.found()) { status = dev.guizhou.util.lang.ClientLanguage.text("无需投药或缺少药水"); return; }
        if (ItemUseTracker.use("AutoThrow", potion, mc.player.getYRot(), 90f)) {
            nextTick = mc.player.tickCount + delay.getValue(); status = dev.guizhou.util.lang.ClientLanguage.text("已投掷");
        } else status = dev.guizhou.util.lang.ClientLanguage.text("动作暂缓");
    }
    private boolean needed(ItemStack stack) {
        if (!stack.is(Items.SPLASH_POTION)) return false;
        var contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return false;
        boolean useful = false;
        for (var effect : contents.getAllEffects()) {
            if (effect.getEffect().value().getCategory() != MobEffectCategory.BENEFICIAL) return false;
            boolean healing = effect.getEffect().is(MobEffects.INSTANT_HEALTH) || effect.getEffect().is(MobEffects.REGENERATION);
            var current = mc.player.getEffect(effect.getEffect());
            if (healing && mc.player.getHealth() < healAt.getValue()
                    && (effect.getEffect().is(MobEffects.INSTANT_HEALTH) || current == null || current.getDuration() < 60)) useful = true;
            if (!healing && buffs.getValue() && (current == null || current.getAmplifier() < effect.getAmplifier()
                    || current.getDuration() < 100)) useful = true;
        }
        return useful;
    }
}
