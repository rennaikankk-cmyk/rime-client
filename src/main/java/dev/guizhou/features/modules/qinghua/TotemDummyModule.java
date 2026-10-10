package dev.guizhou.features.modules.qinghua;

import com.mojang.authlib.GameProfile;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Port of Pattern TotemDummy — fake player + totem pop loop.
 * Meteor FakePlayerEntity → RemotePlayer (same local-only spawn path as FakePlayerCommand).
 */
public class TotemDummyModule extends Module {
    private static final byte TOTEM_POP_EVENT_ID = 35;
    private static int nextId = -23337;

    private final Setting<String> name = str("Name", "TotemDummy")
            .described("假人显示名，可被锁定");
    private final Setting<Double> forwardOffset = num("ForwardOffset", 1.0, 0.0, 5.0)
            .described("沿朝向向前偏移（格），0=与你重叠");
    private final Setting<Boolean> infiniteTotem = bool("InfiniteTotem", true)
            .described("图腾 pop 后自动补满副手");

    private RemotePlayer dummy;
    private int totemPops;
    private int hitCooldown;

    public TotemDummyModule() {
        super("TotemDummy", "合金套+图腾假人，可被锁定并持续 pop 图腾", Category.FUNNY);
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            disable();
            return;
        }
        totemPops = 0;
        hitCooldown = 0;
        despawnDummy();
        createAndSpawnDummy();
        if (dummy == null) disable();
    }

    @Override
    public void onDisable() {
        despawnDummy();
    }

    private void createAndSpawnDummy() {
        if (mc.player == null || mc.level == null) return;

        String dummyName = name.getValue().trim();
        if (dummyName.isEmpty()) dummyName = "TotemDummy";

        GameProfile profile = new GameProfile(UUID.randomUUID(), dummyName);
        dummy = new RemotePlayer(mc.level, profile);
        dummy.setId(nextId--);

        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        double rad = Math.toRadians(yaw);
        double off = forwardOffset.getValue();
        double x = mc.player.getX() - Math.sin(rad) * off;
        double y = mc.player.getY();
        double z = mc.player.getZ() + Math.cos(rad) * off;

        dummy.setPos(x, y, z);
        dummy.setYRot(yaw);
        dummy.setXRot(pitch);
        dummy.setYHeadRot(yaw);
        dummy.yBodyRot = yaw;
        dummy.xRotO = pitch;

        equipNetherite(dummy);
        dummy.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        dummy.setHealth(20.0f);
        dummy.hurtTime = 0;

        mc.level.addEntity(dummy);
        if (!dummy.isAlive()) {
            dummy = null;
        }
    }

    private static void equipNetherite(RemotePlayer player) {
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
    }

    private void despawnDummy() {
        if (dummy != null) {
            if (mc.level != null && dummy.level() == mc.level) {
                mc.level.removeEntity(dummy.getId(), Entity.RemovalReason.DISCARDED);
            }
            dummy = null;
        }
    }

    private boolean isOurDummy(Entity entity) {
        return dummy != null && entity == dummy;
    }

    private void applyHitDamage() {
        if (dummy == null || dummy.hurtTime != 0 || hitCooldown > 0) return;
        hitCooldown = 3;

        mc.level.playSound(mc.player,
                dummy.getX(), dummy.getY(), dummy.getZ(),
                SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0f, 1.0f);

        dummy.animateHurt(0.0f);

        float damage;
        if (mc.player.getAttackStrengthScale(0.0f) >= 0.85f) {
            damage = 8.0f;
        } else {
            damage = 1.0f;
        }
        dummy.setHealth(dummy.getHealth() + dummy.getAbsorptionAmount() - damage);

        if (dummy.getHealth() <= 0.0f) {
            popTotem();
        }
    }

    private void popTotem() {
        if (dummy == null) return;
        if (!dummy.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;

        dummy.setHealth(10.0f);
        dummy.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        dummy.hurtTime = 0;

        mc.level.playSound(mc.player,
                dummy.getX(), dummy.getY(), dummy.getZ(),
                SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
        if (mc.getConnection() != null) {
            mc.getConnection().handleEntityEvent(new ClientboundEntityEventPacket(dummy, TOTEM_POP_EVENT_ID));
        }
        totemPops++;
    }

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (dummy == null || mc.level == null) return;
        if (!(event.getPacket() instanceof ServerboundInteractPacket interact)) return;
        interact.dispatch(new ServerboundInteractPacket.Handler() {
            @Override
            public void onInteraction(InteractionHand hand) {}

            @Override
            public void onInteraction(InteractionHand hand, net.minecraft.world.phys.Vec3 pos) {}

            @Override
            public void onAttack() {
                Entity target = mc.level.getEntity(interact.entityId);
                if (isOurDummy(target)) applyHitDamage();
            }
        });
    }

    @Subscribe
    private void onTick(PreTickEvent event) {
        if (!isEnabled() || dummy == null) return;

        if (hitCooldown > 0) hitCooldown--;

        if (!dummy.isAlive()) {
            despawnDummy();
            disable();
            return;
        }

        if (infiniteTotem.getValue() && !dummy.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            dummy.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        }

        if (dummy.getHealth() <= 0.0f && dummy.hurtTime == 0) {
            dummy.setHealth(20.0f);
        }
    }

    @Override
    public String getDisplayInfo() {
        return dummy == null ? null : String.valueOf(totemPops);
    }
}
