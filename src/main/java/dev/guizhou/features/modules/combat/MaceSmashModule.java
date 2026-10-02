package dev.guizhou.features.modules.combat;

import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Mace smash height spoof: on the tick you press attack with a mace on a living
 * target, inserts a PositionAndOnGround packet that lifts the server-side Y by
 * the configured offset, so the vanilla fall-distance smash multiplier sees a
 * drop. PreTick runs before the client sends the attack packet in the same
 * tick, so the spoofed position lands first without touching any attack path.
 *
 * Packet-shape reference: the Obelisk client ships the same technique as a
 * standalone module (y-offset move packet ahead of the attack, bounded offset).
 */
public class MaceSmashModule extends Module {

    private final Setting<Boolean> onlyGround = bool("OnlyOnGround", true).described("未离地时才发送偏移包。");
    private final Setting<Double> yOffset = num("YOffset", 25.0, 0.0, 2000.0).described("伪造的抬升高度，实际发送时上限 512。");
    private final Setting<Integer> cooldown = num("CooldownMs", 250, 0, 2000).described("两次伪造之间的最小间隔。");

    private long lastSpoof;

    public MaceSmashModule() {
        super("MaceSmash", "在重锤攻击命中前发送一条抬升 Y 坐标的移动包，伪造服务端视角的下落高度，让原版重锤猛击加成生效。",
                Category.COMBAT);
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (nullCheck() || mc.player == null) return;
        if (!mc.options.keyAttack.isDown()) return;
        if (!mc.player.getMainHandItem().is(Items.MACE)) return;
        if (onlyGround.getValue() && !mc.player.onGround()) return;
        // Vanilla smash ignores submerged attackers; a spoof there is a free movement flag.
        if (mc.player.isInWater()) return;
        long now = System.currentTimeMillis();
        if (now - lastSpoof < cooldown.getValue()) return;
        if (!(mc.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity living)
                || living.isDeadOrDying()
                || living.distanceTo(mc.player) > 4.0) return;

        // Vanilla smash bonus caps out far below half a thousand blocks; clamp so
        // a fat-fingered 2000-offset cannot turn into an obvious movement flag.
        double offset = Math.min(yOffset.getValue(), 512.0);
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
                mc.player.getX(), mc.player.getY() + offset, mc.player.getZ(),
                true));
        lastSpoof = now;
    }
}
