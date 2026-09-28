package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Explicit local capture. Independent from the remote .telemetry preference/capability. */
public final class DiagnosticsCommand extends Command {
    public DiagnosticsCommand() {
        super("diagnostics", "diag");
        setDescription("Local performance diagnostics: start [1-60 seconds] | stop | status | export. No upload.");
        category(CommandCategory.DIAGNOSTICS).summary("本地性能诊断（不上传）")
                .usage("status", "查看采集状态")
                .usage("start [秒数]", "开始采集（1-60 秒）")
                .usage("stop", "停止采集")
                .usage("export", "导出为本地 JSON");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> status())
                .then(literal("start").executes(ctx -> start(ClientDiagnostics.DEFAULT_SECONDS))
                        .then(argument("seconds", IntegerArgumentType.integer(1, ClientDiagnostics.MAX_SECONDS))
                                .executes(ctx -> start(IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(literal("stop").executes(ctx -> {
                    ClientDiagnostics.stop();
                    return status();
                }))
                .then(literal("status").executes(ctx -> status()))
                .then(literal("export").executes(ctx -> exportLocal()));
    }

    private int start(int seconds) {
        ClientDiagnostics.start(seconds);
        return success(dev.guizhou.util.lang.ClientLanguage.text("本地诊断已开始，采集 %s 秒；%sdiag stop / status / export，不会上传"), seconds, ChatFeed.prefix());
    }

    private int status() {
        String prefix = ChatFeed.prefix();
        var snapshot = ClientDiagnostics.snapshot();
        if (snapshot.startedAtEpochMillis() == 0) {
            return replyKeyed("status", ChatTone.MUTED, Component.empty()
                    .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("本地诊断未开启   "), ChatPalette.MUTED))
                    .append(ChatFeed.command("diag start ", ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("开始新的采集窗口（1-60 秒）"), ChatPalette.MUTED))));
        }
        MutableComponent body = Component.empty()
                .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("本地诊断"), ChatPalette.TEXT))
                .append(ChatFeed.text(snapshot.active() ? dev.guizhou.util.lang.ClientLanguage.text("  采集中") : dev.guizhou.util.lang.ClientLanguage.text("  已停止"),
                        snapshot.active() ? ChatPalette.SUCCESS : ChatPalette.MUTED))
                .append("\n  ").append(ChatFeed.text(String.format(Locale.ROOT,
                        dev.guizhou.util.lang.ClientLanguage.text("已采集 %.1f 秒 · 剩余 %.1f 秒 · %d 个计时作用域 · %d 个计数器"),
                        snapshot.durationMillis() / 1000.0, snapshot.remainingMillis() / 1000.0,
                        snapshot.timings().size(), snapshot.counters().size()), ChatPalette.MUTED))
                .append("\n  ").append(ChatFeed.text(String.format(Locale.ROOT,
                        dev.guizhou.util.lang.ClientLanguage.text("HUD 渲染间隔 平均 %.2fms · 峰值 %.2fms · P95≈ %.2fms · GC %d 次 / %dms · 堆 %.1fMiB"),
                        snapshot.frameTiming().meanNanos() / 1e6, snapshot.frameTiming().maxNanos() / 1e6,
                        snapshot.frameTiming().p95Nanos() / 1e6, snapshot.gcCollections(), snapshot.gcMillis(),
                        snapshot.heapUsedBytes() / 1048576.0), ChatPalette.MUTED));
        if (snapshot.droppedKeys() != 0) {
            body.append("\n  ").append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("有界存储拒绝了 ") + snapshot.droppedKeys() + dev.guizhou.util.lang.ClientLanguage.text(" 个新键的样本"),
                    ChatPalette.WARNING));
        }
        body.append("\n  ").append(ChatFeed.text(prefix + dev.guizhou.util.lang.ClientLanguage.text("timings 查看包含式耗时（嵌套合计会重叠）· ")
                + prefix + dev.guizhou.util.lang.ClientLanguage.text("diag export 仅写入本地 JSON"), ChatPalette.SUBTLE));
        return replyKeyed("status", ChatTone.INFO, body);
    }

    private int exportLocal() {
        if (ClientDiagnostics.snapshot().startedAtEpochMillis() == 0) {
            return fail(dev.guizhou.util.lang.ClientLanguage.text("没有可导出的采集数据，请先使用 %sdiag start"), ChatFeed.prefix());
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path file = FabricLoader.getInstance().getGameDir().resolve("guizhouware").resolve("diagnostics")
                .resolve("diagnostics-" + stamp + ".json");
        try {
            Path written = ClientDiagnostics.export(file);
            MutableComponent link = Component.literal(String.valueOf(written)).withStyle(style -> style
                    .withColor(ChatPalette.ACCENT_2).withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenFile(written.getParent()))
                    .withHoverEvent(new HoverEvent.ShowText(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("打开所在文件夹"), ChatPalette.MUTED))));
            return reply(ChatTone.SUCCESS, Component.empty()
                    .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("本地诊断已保存  "), ChatPalette.TEXT)).append(link));
        } catch (IOException | SecurityException exception) {
            return fail(dev.guizhou.util.lang.ClientLanguage.text("无法写入本地诊断文件（%s）"), exception.getClass().getSimpleName());
        }
    }
}
