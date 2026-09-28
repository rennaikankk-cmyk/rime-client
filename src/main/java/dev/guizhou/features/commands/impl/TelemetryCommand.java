package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.util.telemetry.TelemetryUploadStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TelemetryCommand extends Command {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public TelemetryCommand() {
        super("telemetry");
        setDescription("自动诊断上传状态：telemetry [status]。本地 diag 报告不会自动上传。");
        category(CommandCategory.DIAGNOSTICS).summary("查看自动诊断上传状态")
                .usage("", "查看状态；本地 diag 报告不会自动上传");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(context -> status())
                .then(literal("status").executes(context -> status()));
    }

    private int status() {
        var manager = GuizhouWare.telemetryManager;
        if (manager == null) {
            return replyKeyed("status", ChatTone.MUTED, Component.literal("诊断上传管理器尚未初始化"));
        }
        long retry = manager.retryInSeconds();
        boolean available = manager.canUpload();
        String state = !available
                ? manager.capabilityExpired() ? "暂不可用：上传凭据已过期，请通过登录器更新" : "暂不可用：当前 JAR 无有效上传凭据"
                : manager.isSending() ? "正在发送"
                : !manager.hasSession() ? "等待进入游戏"
                : retry > 0 ? "退避中，约 " + retry + " 秒后可重试" : "等待下一批事件（约每 15 秒检查）";

        MutableComponent body = Component.empty()
                .append(ChatFeed.text("诊断上传", ChatPalette.TEXT))
                .append(ChatFeed.text("  默认自动 · ", ChatPalette.SUBTLE))
                .append(ChatFeed.text(state, available ? ChatPalette.ACCENT_2 : ChatPalette.WARNING));

        var upload = manager.uploadStatus();
        line(body, "本次运行：尝试 " + upload.attempts() + " 批，接收器确认 " + upload.confirmedBatches()
                + " 批，失败 " + upload.failedBatches() + " 批；队列 " + manager.queuedEvents() + " 条 / "
                + manager.queuedBytes() + " 字节", ChatPalette.MUTED);
        if (upload.lastConfirmedAt() > 0) {
            line(body, "最近接收器确认：" + time(upload.lastConfirmedAt()) + "，HTTP " + upload.lastConfirmedHttp()
                    + "，该请求含 " + upload.lastConfirmedBatchEvents() + " 条事件。确认不等于已核实后台持久化", ChatPalette.MUTED);
        } else {
            line(body, "本次运行尚无接收器确认", ChatPalette.MUTED);
        }
        if (upload.lastFailureAt() > 0) {
            line(body, "最近失败：" + time(upload.lastFailureAt()) + "，" + failure(upload)
                    + "；当前连续失败 " + upload.consecutiveFailures() + " 批", ChatPalette.WARNING);
        }
        if (!manager.identitySaved()) {
            line(body, "安装标识未能持久保存，请检查 guizhouware/telemetry.json 文件权限或格式", ChatPalette.DANGER);
        }
        if (manager.flightSummaryBlocked()) {
            line(body, "本会话飞行摘要因接收器 HTTP 400 已暂停，其他事件继续尝试发送", ChatPalette.WARNING);
        }
        line(body, "不上传聊天、玩家名、玩家 UUID 或坐标；使用 " + GuizhouWare.commandManager.getCommandPrefix()
                + "diag 导出的报告仍仅保存在本地", ChatPalette.SUBTLE);
        return replyKeyed("status", available ? ChatTone.INFO : ChatTone.WARN, body);
    }

    private static void line(MutableComponent body, String text, int color) {
        body.append("\n  ").append(ChatFeed.text(text, color));
    }

    private static String time(long epochMillis) { return TIME.format(Instant.ofEpochMilli(epochMillis)); }

    private static String failure(TelemetryUploadStatus.Snapshot status) {
        return switch (status.lastFailure()) {
            case HTTP -> "接收器返回 HTTP " + status.lastFailureHttp();
            case TIMEOUT -> "请求超时";
            case NETWORK -> "网络或请求失败";
            case INVALID_RESPONSE -> "未获得有效 HTTP 响应";
            case NONE -> "无";
        };
    }
}
