package dev.guizhou.features.modules.render;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.hud.NotifierHudModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.combat.ProjectileSim;
import dev.guizhou.util.combat.ProjectilePrediction;
import dev.guizhou.util.entity.PearlOwnership;
import dev.guizhou.util.player.ChatUtil;
import dev.guizhou.util.render.RenderUtil;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Predicts where your pearl, arrow, trident, snowball, egg, potion or XP bottle lands, and where
 * projectiles already in flight will land. Warns once per enemy pearl with its landing point.
 */
public class TrajectoriesModule extends Module {
    private static final int MAX_TICKS = 200;

    private final Setting<Boolean> self = bool("Self", true).setPage("General");
    private final Setting<Boolean> others = bool("InFlight", true).setPage("General");
    private final Setting<Boolean> onlyPearls = bool("OnlyPearls", false).setPage("General");
    private final Setting<Double> range = num("Range", 64.0, 8.0, 128.0).setPage("General");

    private final Setting<Boolean> pearlAlert = bool("PearlAlert", true).setPage("Alert");
    private final Setting<Double> alertRange = num("AlertRange", 48.0, 4.0, 128.0).setPage("Alert");
    private final Setting<Boolean> alertFriends = bool("AlertFriends", false).setPage("Alert");
    private final Setting<Boolean> alertUnknown = bool("AlertUnknown", true).setPage("Alert");

    private final Setting<Float> lineWidth = num("LineWidth", 1.5f, 0.5f, 4.0f).setPage("Render");
    private final Setting<Color> selfColor = color("SelfColor", 250, 249, 245, 220).setPage("Render");
    private final Setting<Color> enemyColor = color("EnemyColor", 227, 154, 146, 220).setPage("Render");
    private final Setting<Color> otherColor = color("OtherColor", 194, 192, 182, 160).setPage("Render");
    private final Setting<Color> landColor = color("LandColor", 224, 138, 108, 60).setPage("Render");

    private final Set<Integer> alerted = new HashSet<>();
    /** Owner fallback for pearls whose thrower entity the client no longer tracks. */
    private final PearlOwnership ownership = new PearlOwnership();
    private net.minecraft.world.level.Level ownershipScope;

    public TrajectoriesModule() {
        super("Trajectories", "Draws the landing path of what you are about to throw or shoot and of projectiles "
                + "in flight; warns where enemy pearls will land.", Category.RENDER);
    }

    @Override
    public void onDisable() {
        alerted.clear();
        ownership.clear();
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        alerted.clear();
        ownership.clear();
    }

    /** Pearl spawn packets carry the thrower's entity id even after the thrower despawns. */
    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!pearlAlert.getValue() || mc.player == null) return;
        if (event.getPacket() instanceof ClientboundAddEntityPacket packet
                && packet.getType() == EntityType.ENDER_PEARL) {
            mc.execute(() -> ownership.onPearlSpawn(packet.getId(), packet.getData(), System.currentTimeMillis()));
        }
    }

    @Override
    public void onTick() {
        if (nullCheck() || !pearlAlert.getValue()) return;
        if (ownershipScope != mc.level) {
            // another dimension or world: entity ids and bindings no longer mean anything
            ownershipScope = mc.level;
            ownership.clear();
            alerted.clear();
        }
        long now = System.currentTimeMillis();
        for (Player player : mc.level.players()) {
            ownership.observePlayer(player.getId(), player.getUUID(), player.getGameProfile().getName(), now);
        }
        Set<Integer> alive = new HashSet<>();
        double rangeSq = range.getValue() * range.getValue();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || pearl.distanceToSqr(mc.player) > rangeSq) continue;
            alive.add(pearl.getId());
            if (alerted.contains(pearl.getId())) continue;
            Player directOwner = pearl.getOwner() instanceof Player owner ? owner : null;
            PearlOwnership.Identity identity = directOwner != null
                    ? new PearlOwnership.Identity(directOwner.getUUID(), directOwner.getGameProfile().getName())
                    : ownership.resolve(pearl.getId()).orElse(null);
            if (identity == null && directOwner == null) {
                int ownerEid = ownership.ownerEid(pearl.getId());
                if (ownerEid > 0 && mc.level.getEntity(ownerEid) instanceof Player player) {
                    identity = new PearlOwnership.Identity(player.getUUID(), player.getGameProfile().getName());
                }
            }
            if (identity != null && identity.uuid().equals(mc.player.getUUID())) continue;
            if (identity != null && !alertFriends.getValue()
                    && GuizhouWare.friendManager.isFriend(identity.name())) continue;
            if (identity == null && !alertUnknown.getValue()) continue;
            ProjectileSim.Result result = simulate(pearl);
            if (!result.landed()) continue;
            double[] end = result.end();
            double distance = mc.player.position().distanceTo(new Vec3(end[0], end[1], end[2]));
            alerted.add(pearl.getId());
            if (distance > alertRange.getValue()) continue;
            String text = String.format(dev.guizhou.util.lang.ClientLanguage.text("%s 的珍珠将落在 %d, %d, %d（距你 %.0f 格，%.1f 秒）"), describeOwner(directOwner, identity),
                    (int) Math.floor(end[0]), (int) Math.floor(end[1]), (int) Math.floor(end[2]), distance, result.ticks() / 20.0);
            ChatUtil.sendMessage(Component.literal(text));
            NotifierHudModule.push("pearl:" + pearl.getId(), NotifierHudModule.Icon.DANGER, text);
        }
        ownership.retain(alive, now);
        alerted.retainAll(alive);
    }

    /** Name plus an online/offline suffix once the owner entity is gone; unknown stays unknown. */
    private String describeOwner(Player directOwner, PearlOwnership.Identity identity) {
        if (directOwner != null) return directOwner.getGameProfile().getName();
        if (identity == null) return dev.guizhou.util.lang.ClientLanguage.text("未知归属");
        boolean online = mc.getConnection() != null && mc.getConnection().getOnlinePlayers().stream()
                .anyMatch(info -> identity.uuid().equals(info.getProfile().getId()));
        return identity.name() + dev.guizhou.util.lang.ClientLanguage.text(online ? "（在线）" : "（离线）");
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;
        float partial = event.getDelta();
        if (self.getValue()) renderSelf(partial);
        if (!others.getValue()) return;
        double rangeSq = range.getValue() * range.getValue();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Projectile projectile) || !trackable(projectile)) continue;
            if (onlyPearls.getValue() && !(projectile instanceof ThrownEnderpearl)) continue;
            if (projectile.distanceToSqr(mc.player) > rangeSq) continue;
            Entity owner = projectile.getOwner();
            Color color = owner == mc.player ? selfColor.getValue()
                    : owner instanceof Player p && !GuizhouWare.friendManager.isFriend(p) ? enemyColor.getValue()
                    : otherColor.getValue();
            draw(simulate(projectile), color);
        }
    }

    private static boolean trackable(Projectile projectile) {
        // Arrows stuck in a block keep existing with no motion.
        if (projectile instanceof AbstractArrow arrow) return arrow.getDeltaMovement().lengthSqr() > 1.0e-4;
        return projectile instanceof ThrowableItemProjectile;
    }

    private ProjectileSim.Result simulate(Projectile projectile) {
        return ProjectilePrediction.simulate(projectile, MAX_TICKS);
    }

    private record Shot(ProjectileSim.Profile profile, double speed, float pitchOffset) {}

    private void renderSelf(float partial) {
        Shot shot = shotFor(mc.player.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (shot == null) shot = shotFor(mc.player.getOffhandItem(), InteractionHand.OFF_HAND);
        if (shot == null) return;
        float yaw = mc.player.getViewYRot(partial), pitch = mc.player.getViewXRot(partial);
        double[] v = ProjectileSim.launch(yaw, pitch, shot.pitchOffset(), shot.speed());
        Vec3 motion = mc.player.getKnownMovement();
        double vx = v[0] + motion.x, vy = v[1] + (mc.player.onGround() ? 0.0 : motion.y), vz = v[2] + motion.z;
        Vec3 eye = mc.player.getEyePosition(partial);
        ProjectileSim.Result result = ProjectileSim.simulate(eye.x, eye.y - 0.1, eye.z, vx, vy, vz, shot.profile(),
                MAX_TICKS, mc.level.getMinBuildHeight() - 8, collider(mc.player));
        draw(result, selfColor.getValue());
    }

    private Shot shotFor(ItemStack stack, InteractionHand hand) {
        if (stack.is(Items.ENDER_PEARL) || stack.is(Items.SNOWBALL) || stack.is(Items.EGG)
               ) {
            return new Shot(ProjectileSim.THROWABLE, 1.5, 0f);
        }
        if (stack.is(Items.EXPERIENCE_BOTTLE)) return new Shot(ProjectileSim.XP_BOTTLE, 0.7, -20f);
        if (stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION)) return new Shot(ProjectileSim.POTION, 0.5, -20f);
        boolean using = mc.player.isUsingItem() && mc.player.getUsedItemHand() == hand;
        if (stack.is(Items.BOW) && using) {
            double power = ProjectileSim.bowPower(mc.player.getTicksUsingItem());
            return power < 0.1 ? null : new Shot(ProjectileSim.ARROW, power * 3.0, 0f);
        }
        if (stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) return new Shot(ProjectileSim.ARROW, 3.15, 0f);
        if (stack.is(Items.TRIDENT) && using) return new Shot(ProjectileSim.TRIDENT, 2.5, 0f);
        return null;
    }

    private ProjectileSim.Collider collider(Entity source) {
        return ProjectilePrediction.collider(mc.level, source);
    }

    private void draw(ProjectileSim.Result result, Color color) {
        List<double[]> points = result.points();
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1), b = points.get(i);
            RenderUtil.drawLine(new Vec3(a[0], a[1], a[2]), new Vec3(b[0], b[1], b[2]), color, lineWidth.getValue());
        }
        if (!result.landed()) return;
        double[] end = result.end();
        AABB mark = new AABB(end[0] - 0.25, end[1] - 0.25, end[2] - 0.25, end[0] + 0.25, end[1] + 0.25, end[2] + 0.25);
        RenderUtil.drawBoxFilled(null, mark, landColor.getValue());
        RenderUtil.drawBox(null, mark, color, lineWidth.getValue());
    }
}
