package dev.guizhou.features.debug;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.hud.SpeedHudModule;
import dev.guizhou.util.traits.Util;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.diagnostics.SubmissionRate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.Packet;

public class MoveLimitDebug implements Util {
    private static final MoveLimitDebug INSTANCE = new MoveLimitDebug();

    private static final int LEFT_MARGIN = 2;
    private static final int BOTTOM_MARGIN = 2;
    private static final int CHAT_INPUT_HEIGHT = 14;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    private volatile boolean enabled = false;
    private final SubmissionRate rate = new SubmissionRate();

    private MoveLimitDebug() {
        EVENT_BUS.register(this, "MoveLimitDebug");
        PacketSendUtil.addSubmissionListener(this::onPacketSubmitted);
    }

    public static MoveLimitDebug get() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void toggle() {
        enabled = !enabled;
        rate.reset(System.nanoTime());
    }

    private void onPacketSubmitted(Packet<?> packet) {
        if (!enabled || !(packet instanceof ServerboundMovePlayerPacket)) return;
        synchronized (this) {
            if (enabled) rate.submitted(System.nanoTime());
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!enabled) return;
        if (mc.options.hideGui) return;
        long lastCount = rate.perSecond(System.nanoTime());

        GuiGraphics ctx = event.getContext();
        int ry = bottomAnchor() - BOTTOM_MARGIN - mc.font.lineHeight;

        HudClientModule hud = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        if (hud != null && hud.isElementEnabled(SpeedHudModule.class)) {
            ry -= mc.font.lineHeight;
        }

        String value = String.valueOf(lastCount);
        String label = " move/s (local)";
        dev.guizhou.util.render.font.Fonts.drawString(ctx, value, LEFT_MARGIN, ry, GRAY);
        dev.guizhou.util.render.font.Fonts.drawString(ctx, label, LEFT_MARGIN + dev.guizhou.util.render.font.Fonts.width(value), ry, WHITE);
    }

    private int bottomAnchor() {
        int height = mc.getWindow().getGuiScaledHeight();
        return height - (mc.screen instanceof ChatScreen ? CHAT_INPUT_HEIGHT : 0);
    }
}
