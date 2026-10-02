package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.manager.CommandManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

import static dev.guizhou.features.commands.argument.EnemyArgumentType.enemy;
import static dev.guizhou.features.commands.argument.EnemyArgumentType.getEnemy;
import static dev.guizhou.features.commands.argument.OnlinePlayerArgumentType.getOnlinePlayer;
import static dev.guizhou.features.commands.argument.OnlinePlayerArgumentType.onlinePlayer;

public class EnemyCommand extends Command {
    public EnemyCommand() {
        super("enemy", "enemies", "e");
        setDescription("Manages your enemies list");
        category(CommandCategory.SOCIAL).summary("管理敌人列表")
                .usage("list", "查看敌人")
                .usage("add <玩家>", "添加敌人")
                .usage("remove <敌人>", "移除敌人")
                .usage("clear", "清空敌人列表");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(literal("list")
                        .executes((ctx) -> {
                            List<String> enemies = GuizhouWare.enemyManager.getEnemies();
                            if (enemies.isEmpty()) {
                                return replyKeyed("list", ChatTone.MUTED, Component.literal(dev.guizhou.util.lang.ClientLanguage.text("敌人列表为空")));
                            }
                            String prefix = ctx.getSource().getCommandPrefix();
                            MutableComponent body = Component.empty()
                                    .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("敌人 ") + enemies.size() + "  ", ChatPalette.TEXT));
                            for (int i = 0; i < enemies.size(); i++) {
                                if (i > 0) body.append(ChatFeed.text(" · ", ChatPalette.SUBTLE));
                                String name = enemies.get(i);
                                body.append(ChatFeed.suggest(name, prefix + "enemy remove " + name,
                                        ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("移除敌人 ") + name, ChatPalette.MUTED), ChatPalette.DANGER_SOFT));
                            }
                            return replyKeyed("list", ChatTone.INFO, body);
                        }))
                .then(literal("clear")
                        .executes((ctx) -> {
                            GuizhouWare.enemyManager.clearEnemies();
                            return success(dev.guizhou.util.lang.ClientLanguage.text("已清空敌人列表"));
                        }))
                .then(literal("add")
                        .then(argument("username", onlinePlayer())
                                .executes((ctx) -> {
                                    String username = getOnlinePlayer(ctx, "username");
                                    if (GuizhouWare.enemyManager.isEnemy(username)) {
                                        return warn(dev.guizhou.util.lang.ClientLanguage.text("{red} %s {reset} 已在敌人列表中"), username);
                                    }
                                    GuizhouWare.enemyManager.addEnemy(username);
                                    return success(dev.guizhou.util.lang.ClientLanguage.text("已将 {red} %s {reset} 加入敌人列表"), username);
                                })))
                .then(literal("remove")
                        .then(argument("username", enemy())
                                .executes((ctx) -> {
                                    String username = getEnemy(ctx, "username");
                                    if (!GuizhouWare.enemyManager.isEnemy(username)) {
                                        return warn(dev.guizhou.util.lang.ClientLanguage.text("%s 不在敌人列表中"), username);
                                    }
                                    GuizhouWare.enemyManager.removeEnemy(username);
                                    return success(dev.guizhou.util.lang.ClientLanguage.text("已将 {red} %s {reset} 移出敌人列表"), username);
                                })));
    }
}
