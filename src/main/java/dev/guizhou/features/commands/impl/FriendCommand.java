package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.manager.FriendManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.UUID;

import static dev.guizhou.features.commands.argument.FriendArgumentType.friend;
import static dev.guizhou.features.commands.argument.FriendArgumentType.getFriend;
import static dev.guizhou.features.commands.argument.OnlinePlayerArgumentType.getOnlinePlayer;
import static dev.guizhou.features.commands.argument.OnlinePlayerArgumentType.onlinePlayer;

public class FriendCommand extends Command {
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    public FriendCommand() {
        super("friend", "friends", "f");
        setDescription("Manages your friends list");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(literal("list")
                        .executes((ctx) -> {
                            List<String> friends = GuizhouWare.friendManager.getFriends();
                            if (friends.isEmpty()) {
                                return replyKeyed("list", ChatTone.MUTED, Component.literal(dev.guizhou.util.lang.ClientLanguage.text("好友列表为空")));
                            }
                            String prefix = ctx.getSource().getCommandPrefix();
                            MutableComponent body = Component.empty()
                                    .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("好友 ") + friends.size() + "  ", ChatPalette.TEXT));
                            for (int i = 0; i < friends.size(); i++) {
                                if (i > 0) body.append(ChatFeed.text(" · ", ChatPalette.SUBTLE));
                                String name = friends.get(i);
                                long expiry = GuizhouWare.friendManager.getExpiry(name);
                                String label = expiry == FriendManager.PERMANENT
                                        ? name
                                        : name + " (" + formatRemaining(expiry) + ")";
                                body.append(ChatFeed.suggest(label, prefix + "friend remove " + name,
                                        ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("移除好友 ") + name, ChatPalette.MUTED), ChatPalette.SUCCESS));
                            }
                            return replyKeyed("list", ChatTone.INFO, body);
                        }))
                .then(literal("clear")
                        .executes((ctx) -> {
                            GuizhouWare.friendManager.clearFriends();
                            return success(dev.guizhou.util.lang.ClientLanguage.text("已清空好友列表"));
                        }))
                .then(literal("whisper")
                        .executes((ctx) -> success(dev.guizhou.util.lang.ClientLanguage.text("加好友时私聊提醒%s"),
                                GuizhouWare.friendManager.isWhisperOnAdd() ? dev.guizhou.util.lang.ClientLanguage.text("{green}已开启") : dev.guizhou.util.lang.ClientLanguage.text("{red}已关闭")))
                        .then(argument("enabled", BoolArgumentType.bool())
                                .executes((ctx) -> {
                                    boolean on = BoolArgumentType.getBool(ctx, "enabled");
                                    GuizhouWare.friendManager.setWhisperOnAdd(on);
                                    return success(dev.guizhou.util.lang.ClientLanguage.text("加好友时私聊提醒%s"), on ? dev.guizhou.util.lang.ClientLanguage.text("{green}已开启") : dev.guizhou.util.lang.ClientLanguage.text("{red}已关闭"));
                                })))
                .then(literal("add")
                        .then(argument("username", onlinePlayer())
                                .executes((ctx) -> addPlayer(getOnlinePlayer(ctx, "username"), FriendManager.PERMANENT))))
                .then(literal("remove")
                        .then(argument("username", friend())
                                .executes((ctx) -> {
                                    String username = getFriend(ctx, "username");
                                    if (!GuizhouWare.friendManager.isFriend(username)) {
                                        return warn(dev.guizhou.util.lang.ClientLanguage.text("{green} %s {reset} 不在好友列表中"), username);
                                    }
                                    GuizhouWare.friendManager.removeFriend(username);
                                    return success(dev.guizhou.util.lang.ClientLanguage.text("已将 {green} %s {reset} 移出好友列表"), username);
                                })))

                .then(literal("radius")
                        .then(literal("add")
                                .then(argument("blocks", IntegerArgumentType.integer(1))
                                        .executes((ctx) -> addRadius(IntegerArgumentType.getInteger(ctx, "blocks"), FriendManager.PERMANENT)))))

                .then(literal("temp")
                        .then(argument("days", IntegerArgumentType.integer(1))
                                .then(literal("add")
                                        .then(argument("username", onlinePlayer())
                                                .executes((ctx) -> addPlayer(getOnlinePlayer(ctx, "username"), expiryFromDays(IntegerArgumentType.getInteger(ctx, "days"))))))
                                .then(literal("radius")
                                        .then(literal("add")
                                                .then(argument("blocks", IntegerArgumentType.integer(1))
                                                        .executes((ctx) -> addRadius(IntegerArgumentType.getInteger(ctx, "blocks"), expiryFromDays(IntegerArgumentType.getInteger(ctx, "days")))))))));
    }

    private int addPlayer(String username, long expiry) {
        if (GuizhouWare.friendManager.isFriend(username)) {
            return warn(dev.guizhou.util.lang.ClientLanguage.text("{green} %s {reset} 已在好友列表中"), username);
        }
        GuizhouWare.friendManager.addFriend(username, expiry);
        if (expiry == FriendManager.PERMANENT) {
            return success(dev.guizhou.util.lang.ClientLanguage.text("已将 {green} %s {reset} 加入好友列表"), username);
        }
        return success(dev.guizhou.util.lang.ClientLanguage.text("已将 {green} %s {reset} 加入好友列表，有效期 %s"), username, formatRemaining(expiry));
    }

    private int addRadius(int blocks, long expiry) {
        if (nullCheck()) {
            return fail(dev.guizhou.util.lang.ClientLanguage.text("需要在世界中才能这样做"));
        }
        double radiusSq = (double) blocks * blocks;
        UUID self = mc.player.getUUID();
        int added = 0;
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(self)) continue;
            if (mc.player.distanceToSqr(player) > radiusSq) continue;
            String name = player.getGameProfile().getName();
            if (name == null) continue;
            if (GuizhouWare.friendManager.isFriend(name)) continue;
            // Batch adds never whisper: one message per player in the same tick is a spam kick.
            GuizhouWare.friendManager.addFriend(name, expiry, false);
            added++;
        }
        if (added == 0) {
            return success(dev.guizhou.util.lang.ClientLanguage.text("半径 %s 格内没有新玩家"), blocks);
        }
        if (expiry == FriendManager.PERMANENT) {
            return success(dev.guizhou.util.lang.ClientLanguage.text("已将半径 %s 格内的 %s 名玩家加入好友列表"), blocks, added);
        }
        return success(dev.guizhou.util.lang.ClientLanguage.text("已将半径 %s 格内的 %s 名玩家加入好友列表，有效期 %s"), blocks, added, formatRemaining(expiry));
    }

    private static long expiryFromDays(int days) {
        return System.currentTimeMillis() + days * DAY_MILLIS;
    }

    private static String formatRemaining(long expiry) {
        long ms = Math.max(0L, expiry - System.currentTimeMillis());
        long hours = ms / (60L * 60L * 1000L);
        long days = hours / 24L;
        hours %= 24L;
        if (days > 0) {
            return days + dev.guizhou.util.lang.ClientLanguage.text("天 ") + hours + dev.guizhou.util.lang.ClientLanguage.text("小时");
        }
        long minutes = (ms / (60L * 1000L)) % 60L;
        if (hours > 0) {
            return hours + dev.guizhou.util.lang.ClientLanguage.text("小时 ") + minutes + dev.guizhou.util.lang.ClientLanguage.text("分钟");
        }
        return minutes + dev.guizhou.util.lang.ClientLanguage.text("分钟");
    }
}
