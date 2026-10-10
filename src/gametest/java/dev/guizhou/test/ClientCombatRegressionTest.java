package dev.guizhou.test;

import com.google.gson.JsonElement;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.modules.combat.AutoSwordModule;
import dev.guizhou.features.modules.combat.KillAuraModule;
import dev.guizhou.features.modules.hud.CoordinatesHudModule;
import dev.guizhou.features.modules.movement.NoFallModule;
import dev.guizhou.features.modules.movement.NoSlowModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.combat.AttackCooldown;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opt-in development-only tests in a real client and integrated server, excluded from release jars. */
@SuppressWarnings("UnstableApiUsage")
public final class ClientCombatRegressionTest implements FabricClientGameTest {
    private int checks;

    @Override public void runTest(ClientGameTestContext context) {
        Map<Module, JsonElement> original = new LinkedHashMap<>();
        context.runOnClient(mc -> {
            require(HeartbeatManager.authorized(), "development authorization");
            List<Class<? extends Module>> types = List.of(AutoSwordModule.class, KillAuraModule.class,
                    TargetsModule.class, NoSlowModule.class, NoFallModule.class, HudClientModule.class);
            for (Class<? extends Module> type : types) {
                Module module = GuizhouWare.moduleManager.getModuleByClass(type);
                original.put(module, module.toJson());
                if (module.isEnabled() && !(module instanceof TargetsModule)) module.disable();
            }
        });
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksDownload();
            world.getServer().runCommand("fill -8 63 -8 8 63 8 minecraft:stone");
            world.getServer().runCommand("gamemode survival @p");
            world.getServer().runCommand("tp @p 0.5 64 0.5 180 0");
            world.getServer().runCommand("give @p minecraft:diamond_sword");
            context.waitFor(mc -> mc.player != null && !mc.player.isCreative()
                    && Math.abs(mc.player.getY() - 64) < .01
                    && mc.player.getInventory().contains(stack -> stack.is(Items.DIAMOND_SWORD)));
            testLayout(context);
            testNoAcMovement(context);
            testAura(context, world);
            context.takeScreenshot("rime-client-regression");
        } finally {
            context.runOnClient(mc -> original.forEach(Module::fromJson));
        }
        GuizhouWare.LOGGER.info("Rime real-client regression passed: {} checks", checks);
    }

    private void testLayout(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            HudClientModule hud = module(HudClientModule.class);
            CoordinatesHudModule card = hud.getElement(CoordinatesHudModule.class);
            int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
            int w = Math.min(120, sw / 3), h = 24;
            hud.savePosition(card, sw - w - 10, sh - h - 12, w, h);
            int[] expected = hud.savedPosition(card, w, h, sw, sh);
            float x = hud.normalizedX(card), y = hud.normalizedY(card);
            for (int[] viewport : new int[][]{{sw / 2, sh / 2}, {50, 16}, {sw * 2, sh * 2}, {sw, sh}}) {
                hud.savedPosition(card, w * 2, h * 2, viewport[0], viewport[1]);
                require(hud.normalizedX(card) == x && hud.normalizedY(card) == y, "resize retains saved HUD anchor");
            }
            int[] restored = hud.savedPosition(card, w, h, sw, sh);
            require(restored[0] == expected[0] && restored[1] == expected[1], "HUD round-trip restores exact pixels");
        });
    }

    private void testNoAcMovement(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            NoSlowModule slow = module(NoSlowModule.class);
            slow.eatMode.setValue(NoSlowModule.EatMode.NoAC);
            slow.enable();
            int slot = mc.player.getInventory().getSelectedSlot();
            ItemStack held = mc.player.getMainHandItem().copy();
            mc.player.getInventory().setItem(slot, new ItemStack(Items.APPLE));
            mc.player.startUsingItem(InteractionHand.MAIN_HAND);
            require(NoSlowModule.shouldCancelConsumeSlow(), "NoAC consumption needs no TPS or rotation");
            slow.onTick();
            ServerboundUseItemPacket use = new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 180, 0);
            PacketEvent.Send useEvent = new PacketEvent.Send(use);
            dev.guizhou.util.traits.Util.EVENT_BUS.post(useEvent);
            require(!useEvent.isCancelled(), "NoAC does not replace use packets");
            mc.player.stopUsingItem();
            mc.player.getInventory().setItem(slot, held);
            slow.disable();

            NoFallModule fall = module(NoFallModule.class);
            set(fall, "Mode", NoFallModule.Mode.NoAC);
            set(fall, "CheckGround", true);
            set(fall, "GroundRange", .5);
            fall.enable();
            Vec3 pos = mc.player.position(), velocity = mc.player.getDeltaMovement();
            double distance = mc.player.fallDistance;
            boolean ground = mc.player.onGround();
            mc.player.setPos(pos.x, pos.y + 20, pos.z);
            mc.player.setOnGround(false);
            mc.player.fallDistance = 4;
            mc.player.setDeltaMovement(0, -.5, 0);
            ServerboundMovePlayerPacket move = new ServerboundMovePlayerPacket.Rot(180, 0, false, false);
            dev.guizhou.util.traits.Util.EVENT_BUS.post(new PacketEvent.Send(move));
            require(move.isOnGround(), "NoAC protects rotation-only movement far from ground");
            set(fall, "Mode", NoFallModule.Mode.GroundSpoof);
            require(!NoFallModule.protectsPacket(new ServerboundMovePlayerPacket.Rot(180, 0, false, false)),
                    "GroundSpoof retains original position/ground restrictions");
            mc.player.setPos(pos);
            mc.player.setDeltaMovement(velocity);
            mc.player.setOnGround(ground);
            mc.player.fallDistance = distance;
            fall.disable();
        });
    }

    private void testAura(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(mc -> {
            KillAuraModule aura = module(KillAuraModule.class);
            set(aura, "TargetMode", KillAuraModule.TargetMode.CUSTOM);
            set(aura, "Players", false);
            set(aura, "Rotate", false);
            set(aura, "NoAC", true);
            require(!module(AutoSwordModule.class).isEnabled(), "standalone AutoSword is disabled");
        });
        for (boolean bullet : new boolean[]{true, false}) {
            UUID id = world.getServer().computeOnServer(server -> {
                var level = server.overworld();
                Entity entity = (bullet ? EntityType.SHULKER_BULLET : EntityType.FIREBALL)
                        .create(level, EntitySpawnReason.COMMAND);
                entity.setPos(2, 65.4, .5);
                entity.setNoGravity(true);
                level.addFreshEntity(entity);
                return entity.getUUID();
            });
            context.waitFor(mc -> find(id) != null);
            context.runOnClient(mc -> {
                AutoSwordModule sword = module(AutoSwordModule.class);
                require(sword.prepareAttackTick(), "aura owns a valid shared service");
                timer(sword).start(100, 10, 1);
                module(KillAuraModule.class).enable();
            });
            boolean handled = false;
            for (int tick = 0; tick < 10 && !handled; tick++) {
                context.waitTick();
                handled = world.getServer().computeOnServer(server -> {
                    Entity entity = server.overworld().getEntity(id);
                    return bullet ? entity == null || entity.isRemoved()
                            : entity instanceof Projectile p && p.getOwner() == server.getPlayerList().getPlayers().getFirst();
                });
            }
            require(handled, bullet ? "server confirms immediate shulker bullet destruction" : "server confirms ghast fireball deflection");
            context.runOnClient(mc -> {
                require(module(AutoSwordModule.class).coolingDown(), "projectile bypasses an active low-TPS melee timer");
                module(KillAuraModule.class).disable();
            });
        }
        UUID cowId = world.getServer().computeOnServer(server -> {
            var cow = EntityType.COW.create(server.overworld(), EntitySpawnReason.COMMAND);
            cow.setPos(2, 64, .5);
            cow.setNoAi(true);
            cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            cow.setHealth(100);
            server.overworld().addFreshEntity(cow);
            return cow.getUUID();
        });
        context.waitFor(mc -> find(cowId) != null);
        AttackCounter counter = new AttackCounter();
        context.runOnClient(mc -> {
            set(module(TargetsModule.class), "Passives", false);
            KillAuraModule aura = module(KillAuraModule.class);
            set(aura, "Passives", true);
            set(aura, "Projectiles", false);
            set(module(AutoSwordModule.class), "TPS", AutoSwordModule.TpsMode.NONE);
            aura.enable();
            dev.guizhou.util.traits.Util.EVENT_BUS.register(counter, "RimeRegression");
        });
        try {
            context.waitTicks(40);
            context.runOnClient(mc -> {
                require(counter.attacks >= 3, "aura-only cooldown advances between attacks with Rotate off");
                require(!module(AutoSwordModule.class).isEnabled(), "aura did not enable standalone AutoSword");
            });
            require(world.getServer().computeOnServer(server -> {
                var cow = (net.minecraft.world.entity.LivingEntity) server.overworld().getEntity(cowId);
                return cow != null && cow.getHealth() < 90;
            }), "server confirms custom passive targets override the global policy");
        } finally {
            context.runOnClient(mc -> {
                dev.guizhou.util.traits.Util.EVENT_BUS.unregister(counter);
                module(KillAuraModule.class).disable();
            });
        }
    }

    private static Entity find(UUID id) {
        for (Entity entity : net.minecraft.client.Minecraft.getInstance().level.entitiesForRendering())
            if (entity.getUUID().equals(id)) return entity;
        return null;
    }

    private static AttackCooldown timer(AutoSwordModule module) {
        try {
            Field field = AutoSwordModule.class.getDeclaredField("attackCooldown");
            field.setAccessible(true);
            return (AttackCooldown) field.get(module);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static <T extends Module> T module(Class<T> type) {
        return GuizhouWare.moduleManager.getModuleByClass(type);
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(Module module, String name, T value) {
        ((Setting<T>) module.getSettingByName(name)).setValue(value);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static final class AttackCounter {
        private int attacks;
        @Subscribe private void sent(PacketEvent.Send event) {
            if (event.getPacket() instanceof ServerboundInteractPacket) attacks++;
        }
    }
}
