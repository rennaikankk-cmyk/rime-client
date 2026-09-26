package dev.guizhou.features.modules.render;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.hud.NotifierHudModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.combat.ProjectileSim;
import dev.guizhou.util.player.ChatUtil;
import dev.guizhou.util.render.RenderUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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

    private final Setting<Float> lineWidth = num("LineWidth", 1.5f, 0.5f, 4.0f).setPage("Render");
    private final Setting<Color> selfColor = color("SelfColor", 250, 249, 245, 220).setPage("Render");
    private final Setting<Color> enemyColor = color("EnemyColor", 227, 154, 146, 220).setPage("Render");
    private final Setting<Color> otherColor = color("OtherColor", 194, 192, 182, 160).setPage("Render");
    private final Setting<Color> landColor = color("LandColor", 224, 138, 108, 60).setPage("Render");

    private final Set<Integer> alerted = new HashSet<>();

    public TrajectoriesModule() {
        super("Trajectories", "Draws the landing path of what you are about to throw or shoot and of projectiles "
                + "in flight; warns where enemy pearls will land.", Category.RENDER);
    }

    @Override
    public void onDisable() {
        alerted.clear();
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        alerted.clear();
    }

    @Override
    public void onTick() {
        if (nullCheck() || !pearlAlert.getValue()) return;
        Set<Integer> alive = new HashSet<>();
        double rangeSq = range.getValue() * range.getValue();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || pearl.distanceToSqr(mc.player) > rangeSq) continue;
            alive.add(pearl.getId());
            if (alerted.contains(pearl.getId())) continue;
            if (!(pearl.getOwner() instanceof Player owner) || owner == mc.player) continue;
            if (!alertFriends.getValue() && GuizhouWare.friendManager.isFriend(owner)) continue;
            ProjectileSim.Result result = simulate(pearl);
            if (!result.landed()) continue;
            double[] end = result.end();
            double distance = mc.player.position().distanceTo(new Vec3(end[0], end[1], end[2]));
            alerted.add(pearl.getId());
            if (distance > alertRange.getValue()) continue;
            String text = String.format("%s 的珍珠将落在 %d, %d, %d（距你 %.0f 格，%.1f 秒）", owner.getGameProfile().name(),
                    (int) Math.floor(end[0]), (int) Math.floor(end[1]), (int) Math.floor(end[2]), distance, result.ticks() / 20.0);
            ChatUtil.sendMessage(Component.literal(text));
            NotifierHudModule.push("pearl:" + pearl.getId(), NotifierHudModule.Icon.DANGER, text);
        }
        alerted.retainAll(alive);
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
        Vec3 v = projectile.getDeltaMovement();
        return ProjectileSim.simulate(projectile.getX(), projectile.getY(), projectile.getZ(), v.x, v.y, v.z,
                profile(projectile), MAX_TICKS, mc.level.getMinY() - 8, collider(projectile));
    }

    private static ProjectileSim.Profile profile(Projectile projectile) {
        if (projectile instanceof ThrownTrident) return ProjectileSim.TRIDENT;
        if (projectile instanceof AbstractArrow) return ProjectileSim.ARROW;
        if (projectile instanceof AbstractThrownPotion) return ProjectileSim.POTION;
        if (projectile instanceof ThrowableItemProjectile item && item.getItem().is(Items.EXPERIENCE_BOTTLE)) return ProjectileSim.XP_BOTTLE;
        return ProjectileSim.THROWABLE;
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
                MAX_TICKS, mc.level.getMinY() - 8, collider(mc.player));
        draw(result, selfColor.getValue());
    }

    private Shot shotFor(ItemStack stack, InteractionHand hand) {
        if (stack.is(Items.ENDER_PEARL) || stack.is(Items.SNOWBALL) || stack.is(Items.EGG)
                || stack.is(Items.BLUE_EGG) || stack.is(Items.BROWN_EGG)) {
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
        return new ProjectileSim.Collider() {
            @Override
            public double hit(double x0, double y0, double z0, double x1, double y1, double z1) {
                Vec3 from = new Vec3(x0, y0, z0), to = new Vec3(x1, y1, z1);
                double length = from.distanceTo(to);
                if (length < 1.0e-9) return -1;
                double best = -1;
                BlockHitResult block = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, source));
                if (block.getType() == HitResult.Type.BLOCK) best = block.getLocation().distanceTo(from) / length;
                AABB swept = new AABB(from, to).inflate(0.3);
                List<LivingEntity> entities = mc.level.getEntitiesOfClass(LivingEntity.class, swept,
                        e -> e != mc.player && e != source && e.isAlive() && !e.isSpectator());
                for (LivingEntity entity : entities) {
                    var clip = entity.getBoundingBox().inflate(0.3).clip(from, to);
                    if (clip.isEmpty()) continue;
                    double fraction = clip.get().distanceTo(from) / length;
                    if (best < 0 || fraction < best) best = fraction;
                }
                return best;
            }

            @Override
            public boolean inWater(double x, double y, double z) {
                return !mc.level.getFluidState(net.minecraft.core.BlockPos.containing(x, y, z)).isEmpty();
            }
        };
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
