package dev.guizhou.features.commands.impl;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.manager.CommandManager;
import net.minecraft.network.chat.Component;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class FakePlayerCommand extends Command {

    private static final byte TOTEM_POP_EVENT_ID = 35;
    private static final String NOT_IN_WORLD = "需要先进入世界";

    private static final EquipmentSlot[] COPIED_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final List<RemotePlayer> spawned = new ArrayList<>();
    private int nextId = -13337;

    public FakePlayerCommand() {
        super("fakeplayer", "fp");
        setDescription("Spawns a client-side fake player to test on");
        category(CommandCategory.TOOLS).summary("生成仅本地可见的假人用于测试")
                .usage("", "在你的位置生成假人")
                .usage("<名称>", "生成指定名字的假人")
                .usage("pop", "为假人播放图腾动画")
                .usage("swim", "切换假人的游泳姿势")
                .usage("remove", "移除全部假人");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> spawn("FakePlayer"))
                .then(literal("remove")
                        .executes(ctx -> removeAll()))
                .then(literal("pop")
                        .executes(ctx -> popAll()))
                .then(literal("swim")
                        .executes(ctx -> toggleSwim()))
                .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> spawn(StringArgumentType.getString(ctx, "name"))));
    }

    private int popAll() {
        if (nullCheck() || mc.getConnection() == null) {
            return fail(NOT_IN_WORLD);
        }

        int popped = 0;
        for (RemotePlayer fake : spawned) {
            if (fake.level() != mc.level) continue;
            mc.getConnection().handleEntityEvent(new ClientboundEntityEventPacket(fake, TOTEM_POP_EVENT_ID));
            popped++;
        }

        if (popped == 0) {
            return fail("还没有假人，先生成一个");
        }
        return success("已为 %s 个假人播放图腾动画", popped);
    }

    private int toggleSwim() {
        if (nullCheck()) {
            return fail(NOT_IN_WORLD);
        }

        boolean anyStanding = false;
        int found = 0;
        for (RemotePlayer fake : spawned) {
            if (fake.level() != mc.level) continue;
            found++;
            if (fake.getPose() != Pose.SWIMMING) anyStanding = true;
        }
        if (found == 0) {
            return fail("还没有假人，先生成一个");
        }

        Pose pose = anyStanding ? Pose.SWIMMING : Pose.STANDING;
        for (RemotePlayer fake : spawned) {
            if (fake.level() != mc.level) continue;
            fake.setPose(pose);
            fake.refreshDimensions();
        }
        return success(pose == Pose.SWIMMING
                ? "假人已切换为游泳姿势"
                : "假人已恢复站立");
    }

    private int spawn(String name) {
        if (nullCheck()) {
            return fail(NOT_IN_WORLD);
        }

        int despawned = despawnAll();

        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        RemotePlayer fake = new RemotePlayer(mc.level, profile);
        fake.setId(nextId--);
        fake.copyPosition(mc.player);
        fake.setOnGround(mc.player.onGround());
        fake.setYHeadRot(mc.player.getYHeadRot());
        fake.setHealth(20f);
        for (EquipmentSlot slot : COPIED_SLOTS) {
            fake.setItemSlot(slot, mc.player.getItemBySlot(slot).copy());
        }
        mc.level.addEntity(fake);
        spawned.add(fake);
        ChatFeed.send(ChatTone.MUTED, Component.literal("本地假人可用于观察追踪与接近动作；它不在服务器上，不会收到真实伤害。伤害/图腾测试请使用服务器训练目标（"
                + ChatFeed.prefix() + "fakeplayer pop 仅模拟图腾动画）。"));
        if (despawned > 0) {
            return success("已替换为假人 {cyan} %s", name);
        }
        return success("已生成假人 {cyan} %s", name);
    }

    private int removeAll() {
        if (nullCheck()) {
            spawned.clear();
            return fail(NOT_IN_WORLD);
        }
        int removed = despawnAll();
        if (removed == 0) {
            return info("没有需要移除的假人");
        }
        return success("已移除 %s 个假人", removed);
    }

    private int despawnAll() {
        int removed = 0;
        Iterator<RemotePlayer> it = spawned.iterator();
        while (it.hasNext()) {
            RemotePlayer fake = it.next();
            it.remove();
            if (fake.level() != mc.level) continue;
            mc.level.removeEntity(fake.getId(), Entity.RemovalReason.DISCARDED);
            removed++;
        }
        return removed;
    }
}
