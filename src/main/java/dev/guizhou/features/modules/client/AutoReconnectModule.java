package dev.guizhou.features.modules.client;

import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.combat.AutoLogModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.network.ReconnectPlan;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Reconnects to the last multiplayer server from the disconnect screen after a countdown.
 * Disconnects made on purpose by AutoLog/VisualRange are never retried; the screen shows the
 * countdown with "reconnect now" and "cancel" buttons.
 */
public class AutoReconnectModule extends Module {
    private final Setting<Double> delay = num("Delay", 5.0, 1.0, 60.0);
    private final Setting<Integer> maxAttempts = num("MaxAttempts", 0, 0, 50);
    private final Setting<Boolean> backoff = bool("Backoff", true);
    private final Setting<Boolean> respectAutoLog = bool("RespectAutoLog", true);

    private final ReconnectPlan plan = new ReconnectPlan();
    private ServerData lastServer;
    private long joinedAt;
    private Screen armedScreen;
    private Button reconnectButton, cancelButton;

    public AutoReconnectModule() {
        super("AutoReconnect", "Reconnects to the last server after a kick or connection loss, with a countdown "
                + "and cancel button on the disconnect screen. Never reconnects after an AutoLog disconnect.",
                Category.CLIENT);
        ClientTickEvents.END_CLIENT_TICK.register(client -> clientTick());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof DisconnectedScreen disconnected) attach(disconnected, width, height);
        });
    }

    private void clientTick() {
        if (mc.level != null && mc.getConnection() != null && !mc.isSingleplayer() && mc.getCurrentServer() != null) {
            if (lastServer != mc.getCurrentServer() || joinedAt == 0) joinedAt = System.currentTimeMillis();
            lastServer = mc.getCurrentServer();
            armedScreen = null;
            if (System.currentTimeMillis() - joinedAt >= ReconnectPlan.STABLE_SESSION_MS) plan.onStableSession();
        } else {
            if (mc.level != null && mc.isSingleplayer()) lastServer = null;
            joinedAt = 0;
        }
        if (armedScreen != null && mc.screen != armedScreen && !(mc.screen instanceof ConnectScreen)) {
            plan.abandon();
            armedScreen = null;
        }
    }

    private void attach(DisconnectedScreen screen, int width, int height) {
        if (!isEnabled() || lastServer == null) return;
        long now = System.currentTimeMillis();
        if (screen != armedScreen) {
            // A resize re-runs init on the same screen: it must not arm a second attempt.
            armedScreen = screen;
            boolean intentional = respectAutoLog.getValue() && AutoLogModule.recentlyLoggedOut();
            plan.onDisconnected(now, intentional, true, delay.getValue(), maxAttempts.getValue(), backoff.getValue());
            ScreenEvents.afterTick(screen).register(s -> screenTick(screen));
        }
        int y = height - 28;
        reconnectButton = Button.builder(Component.literal(label(now)), b -> reconnectNow())
                .bounds(width / 2 - 152, y, 150, 20).build();
        cancelButton = Button.builder(Component.literal(dev.guizhou.util.lang.ClientLanguage.text("取消自动重连")), b -> plan.cancel("user"))
                .bounds(width / 2 + 2, y, 150, 20).build();
        Screens.getButtons(screen).add(reconnectButton);
        Screens.getButtons(screen).add(cancelButton);
    }

    private void screenTick(Screen screen) {
        if (screen != armedScreen) return;
        long now = System.currentTimeMillis();
        if (reconnectButton != null) reconnectButton.setMessage(Component.literal(label(now)));
        if (cancelButton != null) cancelButton.active = plan.state() == ReconnectPlan.State.WAITING;
        if (isEnabled() && plan.shouldFire(now)) reconnect();
    }

    private String label(long now) {
        return switch (plan.state()) {
            case WAITING -> String.format(dev.guizhou.util.lang.ClientLanguage.text("重连 (%.1fs) · 第%d次"), plan.remainingMs(now) / 1000.0, plan.attempts() + 1);
            case CANCELLED -> "intentional".equals(plan.reason()) ? dev.guizhou.util.lang.ClientLanguage.text("AutoLog 断线 · 手动重连") : dev.guizhou.util.lang.ClientLanguage.text("已取消 · 手动重连");
            case EXHAUSTED -> dev.guizhou.util.lang.ClientLanguage.text("已达重连上限 · 手动重连");
            case IDLE -> dev.guizhou.util.lang.ClientLanguage.text("立即重连");
        };
    }

    private void reconnectNow() {
        plan.fireNow();
        reconnect();
    }

    private void reconnect() {
        if (!HeartbeatManager.authorized()) {
            HeartbeatManager.notifyDeniedOnce();
            return;
        }
        ServerData server = lastServer;
        if (server == null || !ServerAddress.isValidAddress(server.ip)) return;
        armedScreen = null;
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc,
                ServerAddress.parseString(server.ip), server, false, null);
    }

    @Override
    public void onDisable() {
        plan.cancel("disabled");
    }

    @Override
    public String getDisplayInfo() {
        return plan.attempts() > 0 ? String.valueOf(plan.attempts()) : null;
    }
}
