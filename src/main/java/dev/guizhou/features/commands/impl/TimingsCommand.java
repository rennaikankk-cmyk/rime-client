package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.event.system.EventBus;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.util.elytra.FlightPlanningTask;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class TimingsCommand extends Command {

    public TimingsCommand() {
        super("timings");
        setDescription("Inclusive local timings: .timings [topN] reads retained data; .timings reset starts a fresh 60s window.");
        category(CommandCategory.DIAGNOSTICS).summary("本地耗时统计（不上传）")
                .usage("", "查看耗时最高的 15 项")
                .usage("<数量>", "查看耗时最高的 N 项（1-50）")
                .usage("reset", "重新开始 60 秒统计窗口");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> report(15))
                .then(literal("reset")
                        .executes(ctx -> {
                            ClientDiagnostics.start(60);
                            return success("已开始新的 60 秒本地统计窗口；游玩后再输入 %stimings 查看，不会上传", ChatFeed.prefix());
                        }))
                .then(argument("topN", IntegerArgumentType.integer(1, 50))
                        .executes(ctx -> report(IntegerArgumentType.getInteger(ctx, "topN"))));
    }

    private int report(int n) {
        String prefix = ChatFeed.prefix();
        if (ClientDiagnostics.snapshot().startedAtEpochMillis() == 0) {
            ClientDiagnostics.start(60);
            return info("已开启 60 秒本地统计窗口；游玩后再输入 %stimings 查看，不会上传", prefix);
        }
        var flight = FlightPlanningTask.diagnostics();
        MutableComponent body = Component.empty()
                .append(ChatFeed.text("本地耗时", ChatPalette.TEXT))
                .append(ChatFeed.text("   飞行线程 " + flight.workers() + " · 活跃 " + flight.active()
                        + " · 排队 " + flight.queued() + " · 已完成 " + flight.completedRuns(), ChatPalette.MUTED));
        String report = EventBus.timingsReport(n);
        if (report.isEmpty()) {
            body.append("\n  ").append(ChatFeed.text("本次采集没有计时数据；" + prefix + "timings reset 重新开始",
                    ChatPalette.MUTED));
            return replyKeyed("report", ChatTone.INFO, body);
        }
        for (String line : report.split("\n")) {
            if (!line.isBlank()) body.append("\n  ").append(ChatFeed.text(line, ChatPalette.TEXT));
        }
        body.append("\n  ").append(ChatFeed.text("包含式耗时：监听器 / 模块 / 内部作用域相互重叠，请勿相加；P95 为近似值。"
                + prefix + "timings reset 重新采集", ChatPalette.SUBTLE));
        return replyKeyed("report", ChatTone.INFO, body);
    }
}
