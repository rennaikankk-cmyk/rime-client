package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.mixin.client.PlayerTabOverlayAccessor;
import dev.guizhou.util.render.Anim;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Connection card: ping with signal bars, optional server TPS and client FPS cells. */
public class PingHudModule extends HudModule {
    private static final Pattern PING_SUFFIX = Pattern.compile("(?i)(\\d+)\\s*(?:ms)?\\s*ping");
    private static final Pattern PING_PREFIX = Pattern.compile("(?i)ping\\s*[:=\\-]?\\s*(\\d+)");
    private static final float PAD = 7f, H = 27f, LABEL = 6f, VALUE = 10.5f, UNIT = 6.5f;

    private final Anim anim = new Anim();
    private final Cell ping = new Cell("PING", "ms"), tps = new Cell("TPS", ""), fps = new Cell("FPS", "");

    private static final class Cell {
        final String label, unit;
        String value = "—";
        int key = Integer.MIN_VALUE, tone = ShadcnDraw.TEXT;
        float width;

        Cell(String label, String unit) {
            this.label = label;
            this.unit = unit;
        }

        void set(int key, String value, int tone) {
            this.tone = tone;
            if (key == this.key) return;
            this.key = key;
            this.value = value;
        }

        float measure(float extra) {
            float v = ShadcnDraw.width(value, VALUE, HudFonts.BOLD) + (unit.isEmpty() ? 0f : 2f + ShadcnDraw.width(unit, UNIT));
            width = Math.max(ShadcnDraw.width(label, LABEL, HudFonts.SEMI) + extra, v);
            return width;
        }
    }

    public PingHudModule() {
        super("Ping");
    }

    @Override
    public void render(Render2DEvent event) {
        GuiGraphics g = event.getContext();
        HudClientModule hud = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        boolean showTps = hud == null || hud.pingShowTps.getValue();
        boolean showFps = hud == null || hud.pingShowFps.getValue();

        int raw = getPing();
        int shown = Math.round(anim.to("ping", raw, 8f));
        int pingTone = raw <= 0 ? ShadcnDraw.MUTED : raw <= 80 ? ShadcnDraw.SUCCESS : raw <= 160 ? ShadcnDraw.WARNING : ShadcnDraw.DANGER;
        ping.set(raw <= 0 ? -1 : shown, raw <= 0 ? "—" : Integer.toString(shown), pingTone);
        if (showTps) {
            float t = GuizhouWare.tpsCounterService == null ? 0f : GuizhouWare.tpsCounterService.getAverageTPS();
            int tenth = Math.round(Math.clamp(t, 0f, 20f) * 10f);
            tps.set(t > 0 ? tenth : -1, t > 0 ? String.format(Locale.ROOT, "%.1f", tenth / 10f) : "—",
                    t <= 0 ? ShadcnDraw.MUTED : t >= 18f ? ShadcnDraw.SUCCESS : t >= 14f ? ShadcnDraw.WARNING : ShadcnDraw.DANGER);
        }
        if (showFps) {
            int f = mc.getFps();
            fps.set(f, Integer.toString(f), f >= 60 ? ShadcnDraw.TEXT : f >= 30 ? ShadcnDraw.WARNING : ShadcnDraw.DANGER);
        }

        float cardW = PAD + ping.measure(12f) + PAD;
        if (showTps) cardW += tps.measure(0) + PAD * 2;
        if (showFps) cardW += fps.measure(0) + PAD * 2;

        int below = hud != null ? hud.stackBelowHeight(this) : 0;
        int defX = Math.round(screenWidth() - cardW - 4f);
        int defY = Math.round(bottomAnchor() - 4f - below - H);
        int[] o = origin(defX, defY, (int) cardW, (int) H);
        int ox = o[0], oy = o[1];

        HudDraw.card(g, ox, oy, cardW, H, HudDraw.RADIUS);
        float x = ox + PAD;
        drawCell(g, ping, x, oy);
        signal(g, x + ShadcnDraw.width(ping.label, LABEL, HudFonts.SEMI) + 3f, oy + 9.5f, raw, pingTone);
        x += ping.width + PAD;
        if (showTps) { HudDraw.ruleV(g, x, oy + 6f, H - 12f); x += PAD; drawCell(g, tps, x, oy); x += tps.width + PAD; }
        if (showFps) { HudDraw.ruleV(g, x, oy + 6f, H - 12f); x += PAD; drawCell(g, fps, x, oy); }
        bounds(ox, oy, (int) cardW, (int) H);
    }

    private static void drawCell(GuiGraphics g, Cell c, float x, float oy) {
        HudDraw.textMid(g, c.label, x, oy + 8f, LABEL, HudFonts.SEMI, ShadcnDraw.MUTED);
        HudDraw.textMid(g, c.value, x, oy + 18.5f, VALUE, HudFonts.BOLD, c.tone);
        if (!c.unit.isEmpty()) {
            HudDraw.textMid(g, c.unit, x + ShadcnDraw.width(c.value, VALUE, HudFonts.BOLD) + 2f, oy + 19.5f,
                    UNIT, HudFonts.REGULAR, ShadcnDraw.SUBTLE);
        }
    }

    /** Four rising bars; lit count follows the ping grade. */
    private static void signal(GuiGraphics g, float x, float baseline, int ms, int tone) {
        int lit = ms <= 0 ? 0 : ms <= 50 ? 4 : ms <= 100 ? 3 : ms <= 180 ? 2 : 1;
        for (int i = 0; i < 4; i++) {
            float h = 1.5f + i * 1.2f;
            ShadcnDraw.round(g, x + i * 2.2f, baseline - h, 1.4f, h, .5f, i < lit ? tone : HudDraw.TRACK);
        }
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
