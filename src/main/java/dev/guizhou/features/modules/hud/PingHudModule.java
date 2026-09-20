package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.mixin.client.PlayerTabOverlayAccessor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PingHudModule extends HudModule {
    private static final Pattern PING_SUFFIX = Pattern.compile("(?i)(\\d+)\\s*(?:ms)?\\s*ping");
    private static final Pattern PING_PREFIX = Pattern.compile("(?i)ping\\s*[:=\\-]?\\s*(\\d+)");

    public PingHudModule() {
        super("Ping");
    }

    @Override
    public void render(Render2DEvent event) {
        HudClientModule hud = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        String value = String.valueOf(getPing()) + " ms";

        float pad = 6f;
        float labelW = ShadcnDraw.width("Ping", 10f);
        float cardW = pad + labelW + 8f + ShadcnDraw.width(value, 12f) + pad;
        float cardH = 24f;

        int below = hud != null ? hud.stackBelowHeight(this) : 0;
        int defX = Math.round(screenWidth() - cardW - 4f);
        int defY = Math.round(bottomAnchor() - 4f - below - cardH);
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        ShadcnDraw.panel(event.getContext(), ox, oy, cardW, cardH, 6f);
        float ty = oy + (cardH - 12f) / 2f - 1f;
        ShadcnDraw.text(event.getContext(), "Ping", ox + pad, ty, 10f, ShadcnDraw.MUTED);
        ShadcnDraw.text(event.getContext(), value, ox + pad + labelW + 8f, ty, 12f, ShadcnDraw.TEXT);
        bounds(ox, oy, (int) cardW, (int) cardH);
    }

    private int getPing() {
        ClientPacketListener conn = mc.getConnection();
        if (conn != null && mc.player != null) {
            PlayerInfo info = conn.getPlayerInfo(mc.player.getUUID());
            int latency = info != null ? info.getLatency() : 0;
            if (latency > 0) return latency;
        }
        Integer parsed = parseFromTablist();
        return parsed != null ? Math.max(parsed, 0) : 0;
    }

    private Integer parseFromTablist() {
        if (mc.gui == null) return null;
        PlayerTabOverlay tab = mc.gui.getTabList();
        if (!(tab instanceof PlayerTabOverlayAccessor accessor)) return null;
        Integer fromHeader = parse(accessor.guizhouware$getHeader());
        return fromHeader != null ? fromHeader : parse(accessor.guizhouware$getFooter());
    }

    private Integer parse(Component component) {
        if (component == null) return null;
        String text = component.getString();
        if (text.isEmpty()) return null;
        Matcher m = PING_SUFFIX.matcher(text);
        if (m.find()) return tryInt(m.group(1));
        m = PING_PREFIX.matcher(text);
        if (m.find()) return tryInt(m.group(1));
        return null;
    }

    private Integer tryInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
