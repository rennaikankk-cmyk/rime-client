package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.ModuleNames;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** A cached local-only view. Capturing is controlled by .diag, never by rendering this card. */
public final class DiagnosticsHudModule extends HudModule {
    private long lastRefresh;
    private List<String> lines = List.of();
    private String status = "";
    private boolean visible, active;
    private Set<String> moduleIds = Set.of();

    public DiagnosticsHudModule() { super("Diagnostics"); }

    @Override public void render(Render2DEvent event) {
        long now = System.nanoTime();
        HudClientModule hud = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        boolean preview = hud != null && hud.isEditorOpen();
        if (lastRefresh == 0 || now - lastRefresh >= 250_000_000L) {
            lastRefresh = now;
            refresh(ClientDiagnostics.snapshot());
        }
        if (!visible && !preview) return;
        List<String> rows = visible ? lines : List.of(dev.guizhou.util.lang.ClientLanguage.text(".diag start 60 开始采样"), dev.guizhou.util.lang.ClientLanguage.text(".diag export 导出本地报告"));
        String state = visible ? status : dev.guizhou.util.lang.ClientLanguage.text("待采样");
        int tone = !visible ? ShadcnDraw.MUTED : active ? ShadcnDraw.SUCCESS : ShadcnDraw.ACCENT_2;
        var g = event.getContext();
        int w = 232, h = 30 + rows.size() * 12 + 4;
        int[] origin = origin(screenWidth() - w - 8, 38, w, h);
        int x = origin[0], y = origin[1];
        HudDraw.card(g, x, y, w, h, HudDraw.RADIUS);
        HudDraw.textMid(g, dev.guizhou.util.lang.ClientLanguage.text("本地诊断"), x + 8f, y + 13f, 9f, HudFonts.SEMI, ShadcnDraw.TEXT);
        HudDraw.textMidRight(g, state, x + w - 8f, y + 13f, 6.5f, HudFonts.SEMI, tone);
        HudDraw.rule(g, x + 7f, y + 25f, w - 14f);
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            boolean section = row.startsWith(dev.guizhou.util.lang.ClientLanguage.text("耗时最多"));
            float mid = y + 34f + i * 12f;
            HudDraw.textMid(g, ShadcnDraw.fit(row, w - 16, section ? 6.5f : 8f), x + 8f, mid,
                    section ? 6.5f : 8f, section ? HudFonts.SEMI : HudFonts.REGULAR,
                    section ? ShadcnDraw.SUBTLE : i == 0 ? ShadcnDraw.TEXT : ShadcnDraw.MUTED);
        }
        bounds(x, y, w, h);
    }

    private void refresh(ClientDiagnostics.Snapshot snapshot) {
        if (moduleIds.isEmpty()) moduleIds = GuizhouWare.moduleManager.getModules().stream()
                .map(dev.guizhou.features.modules.Module::getName).collect(Collectors.toUnmodifiableSet());
        visible = snapshot.startedAtEpochMillis() > 0 && (snapshot.active()
                || System.currentTimeMillis() - snapshot.startedAtEpochMillis() - snapshot.durationMillis() <= 15_000);
        if (!visible) { lines = List.of(); return; }
        active = snapshot.active();
        status = active ? dev.guizhou.util.lang.ClientLanguage.text("采样中 ") + ((snapshot.remainingMillis() + 999) / 1000) + "s" : dev.guizhou.util.lang.ClientLanguage.text("已结束");
        var out = new ArrayList<String>();
        var frame = snapshot.frameTiming();
        out.add(String.format(Locale.ROOT, dev.guizhou.util.lang.ClientLanguage.text("渲染间隔  %.2f ms · P95 ≤ %.2f ms"), frame.meanNanos() / 1e6, frame.p95Nanos() / 1e6));
        out.add(String.format(Locale.ROOT, dev.guizhou.util.lang.ClientLanguage.text("峰值 %.2f ms · 堆内存 %.0f MB"), frame.maxNanos() / 1e6, snapshot.heapUsedBytes() / 1048576.0));
        out.add("GC " + snapshot.gcCollections() + dev.guizhou.util.lang.ClientLanguage.text(" 次 / ") + snapshot.gcMillis() + " ms");
        long submitted = snapshot.counters().getOrDefault("packet.submitted.total", 0L);
        long cancelled = snapshot.counters().getOrDefault("packet.cancelled.total", 0L);
        out.add(dev.guizhou.util.lang.ClientLanguage.text("本地发包 ") + submitted + dev.guizhou.util.lang.ClientLanguage.text(" · 取消 ") + cancelled);
        String motion = dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.hudTargetMotion();
        if (!"OFF".equals(motion)) out.add(dev.guizhou.util.lang.ClientLanguage.text("空战目标 · ") + FlightMotionLabels.label(motion));
        int beforeTimings = out.size();
        out.add(dev.guizhou.util.lang.ClientLanguage.text("耗时最多的回调（含嵌套）"));
        snapshot.timings().entrySet().stream()
                .filter(e -> isModuleTiming(e.getKey()))
                .sorted(Map.Entry.<String, ClientDiagnostics.TimingSnapshot>comparingByValue(
                        Comparator.comparingLong(ClientDiagnostics.TimingSnapshot::totalNanos)).reversed())
                .limit(3)
                .forEach(e -> out.add(String.format(Locale.ROOT, dev.guizhou.util.lang.ClientLanguage.text("%s  平均 %.2f / 峰值 %.2f ms"),
                        friendly(e.getKey()), e.getValue().meanNanos() / 1e6, e.getValue().maxNanos() / 1e6)));
        if (out.size() == beforeTimings + 1) out.add(dev.guizhou.util.lang.ClientLanguage.text("等待模块活动…"));
        if (snapshot.droppedKeys() > 0) out.add(dev.guizhou.util.lang.ClientLanguage.text("部分指标超出采样上限：") + snapshot.droppedKeys());
        out.add(dev.guizhou.util.lang.ClientLanguage.text(".diag export 保存报告 · 不上传"));
        lines = List.copyOf(out);
    }

    private boolean isModuleTiming(String key) {
        String[] parts = key.split("\\.", 3);
        return parts.length == 3 && (parts[0].equals("module") || parts[0].equals("listener"))
                && moduleIds.contains(parts[1]);
    }

    private static String friendly(String key) {
        String[] parts = key.split("\\.", 3);
        if (parts.length < 3) return key;
        String scope = switch (parts[2]) {
            case "tick", "onTick", "onPreTick" -> dev.guizhou.util.lang.ClientLanguage.text("更新");
            case "render2d", "onRender2D" -> dev.guizhou.util.lang.ClientLanguage.text("界面");
            case "render3d", "onRender3D" -> dev.guizhou.util.lang.ClientLanguage.text("场景");
            case "onPacketSend", "onPacketReceive" -> dev.guizhou.util.lang.ClientLanguage.text("网络");
            default -> dev.guizhou.util.lang.ClientLanguage.text("事件");
        };
        return ModuleNames.displayName(parts[1]) + " · " + scope;
    }
}
