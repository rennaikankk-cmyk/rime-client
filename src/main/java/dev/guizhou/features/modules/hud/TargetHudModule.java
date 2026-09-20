package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.util.render.font.HudFonts;
import dev.guizhou.util.render.targethud.TargetHudMath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Two-layer air-combat HUD, a faithful port of the shadcn/ui prototype
 * (targethud-shadcn: TargetHudAir + FlightHud). Layout numbers are the
 * measured DOM geometry, not guesses: card 380 wide, target card 367.5 tall,
 * flight card 344, radius 14, content inset left/right 16 and top 32.
 *
 * Everything renders on one design canvas scaled to the game GUI by a single
 * uniform factor — no per-element font shrinking or reflow. Text is Geist
 * (400/600/700, bundled) at explicit CSS-px sizes with no MC drop shadow.
 * Continuous values ease per frame (140ms half-life); discrete values live in
 * snapshot fields that freeze on target loss so the target layer can finish
 * its 300ms cross-fade without inventing data. Switching targets snaps the
 * eased state to the new target instead of blending across entities.
 */
public class TargetHudModule extends HudModule {

    // ---- shadcn dark palette (measured from the prototype) ----
    private static final int CARD     = 0xF3171717;
    private static final int FG       = 0xFFFAFAFA;
    private static final int PRIMARY  = 0xFFE5E5E5;
    private static final int MUTED_FG = 0xFFA1A1A1;
    private static final int TRACK    = 0xFF26262B;
    private static final int BORDER   = 0x1AFFFFFF;
    private static final int DESTR    = 0xFFF87171;
    private static final int AMBER    = 0xFFF59E0B;
    private static final int GHOST    = 0x59FFFFFF;
    private static final int DARK     = 0xFF171717;
    private static final int CHIP_BG  = 0xCC0A0A0A;

    // ---- measured card geometry (CSS px) ----
    private static final float W = 380f;
    private static final float TARGET_H = 367.5f;
    private static final float FLIGHT_H = 344f;
    private static final float RADIUS = 14f;
    private static final float PAD = 16f;
    private static final float TOP = 32f;
    private static final float CONTENT_W = 348f;

    private static final float HALF_LIFE = 140f;
    private static final float FADE_HALF_LIFE = 75f;

    /** Thin radar rings, precomputed pixel offsets (design units, r=46 / r=25). */
    private static final int[][] RING_OUTER = ringOffsets(46, false);
    private static final int[][] RING_INNER = ringOffsets(25, true);

    // eased continuous display state
    private float dist = 8, bearing, relAlt, closure, myVel, tgtVel, energy, smash = -1, reqVel = Float.NaN;
    private float vspeed, heading, triRot, flightEnergy;
    private float layerA; // 0 = flight visible, 1 = target visible
    private float ghostHp = 20;
    private long lastFrame;

    // discrete snapshot — frozen while the target layer fades out
    private String sName = "", sWeapon = "—", sIntent = "OFF", sPing = "—";
    private float sHp, sArmor;
    private int sTotems;
    private long sElapsedMs;
    private UUID trackedId;
    private long engageStart;

    public TargetHudModule() {
        super("TargetHud");
    }

    @Override
    public void render(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;
        GuiGraphics ctx = event.getContext();
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 16f : Math.max(0f, now - lastFrame);
        lastFrame = now;

        LivingEntity target = currentTarget();
        if (target != null) {
            UUID id = target.getUUID();
            if (!id.equals(trackedId)) adopt(target);
            sampleTarget(target, dt);
        }
        sampleFlight(dt);

        float want = target != null ? 1f : 0f;
        layerA = (float) TargetHudMath.ease(layerA, want, dt, FADE_HALF_LIFE);
        if (ghostHp > sHp) ghostHp = Math.max(sHp, ghostHp - 0.35f * dt / 120f);
        else ghostHp = sHp;

        float s = canvasScale();
        int x0 = screenWidth() - px(W, s) - px(12f, s);
        int y0 = Math.max(px(8f, s), (screenHeight() - px(TARGET_H, s)) / 2 - px(4f, s));

        if (layerA < 0.995f) drawFlight(ctx, x0, y0, 6f * layerA, s);
        if (layerA > 0.005f) drawTarget(ctx, x0, y0, layerA, 6f * (1f - layerA), s);
    }

    // ---------------------------------------------------------------- data

    private LivingEntity currentTarget() {
        Entity t = GuangxiElytraChaseModule.hudTarget();
        if (t == null || !t.isAlive() || t.isRemoved() || t.level() != mc.level) return null;
        return t instanceof LivingEntity living ? living : null;
    }

    /** New lock: snap eased values to the new target so nothing blends across targets. */
    private void adopt(LivingEntity target) {
        trackedId = target.getUUID();
        engageStart = System.currentTimeMillis();
        Vec3 myPos = mc.player.position();
        Vec3 tPos = target.position();
        dist = (float) myPos.distanceTo(tPos);
        bearing = (float) TargetHudMath.wrapDelta(mc.player.getYRot(),
                TargetHudMath.yawTo(tPos.x - myPos.x, tPos.z - myPos.z));
        relAlt = (float) (tPos.y - myPos.y);
        myVel = (float) mc.player.getDeltaMovement().length();
        tgtVel = (float) target.getDeltaMovement().length();
        closure = 0;
        energy = (float) TargetHudMath.energyAdvantage(myVel, tgtVel, relAlt);
        triRot = (float) TargetHudMath.normalizeAngle(target.getYRot() - mc.player.getYRot());
        sHp = target.getHealth();
        ghostHp = sHp;
        sArmor = target.getArmorValue();
        sName = target.getName().getString();
        sPing = pingOf(target);
    }

    private void sampleTarget(LivingEntity target, float dt) {
        Vec3 myPos = mc.player.position();
        Vec3 tPos = target.position();
        double rawDist = myPos.distanceTo(tPos);
        double dx = tPos.x - myPos.x, dz = tPos.z - myPos.z;
        double rawBearing = TargetHudMath.wrapDelta(mc.player.getYRot(), TargetHudMath.yawTo(dx, dz));
        Vec3 myDelta = mc.player.getDeltaMovement();
        Vec3 tDelta = target.getDeltaMovement();
        Vec3 dir = rawDist > 1e-6 ? tPos.subtract(myPos).normalize() : Vec3.ZERO;
        double rawClosure = myDelta.subtract(tDelta).dot(dir);
        double rawEnergy = TargetHudMath.energyAdvantage(myDelta.length(), tDelta.length(), tPos.y - myPos.y);
        double smashRaw = Math.max(0.0, Math.min(1.0, mc.player.fallDistance / 1.5));

        dist = (float) TargetHudMath.ease(dist, rawDist, dt, HALF_LIFE);
        bearing = (float) TargetHudMath.easeAngle(bearing, rawBearing, dt, HALF_LIFE);
        relAlt = (float) TargetHudMath.ease(relAlt, tPos.y - myPos.y, dt, HALF_LIFE);
        closure = (float) TargetHudMath.ease(closure, rawClosure, dt, HALF_LIFE);
        myVel = (float) TargetHudMath.ease(myVel, myDelta.length(), dt, HALF_LIFE);
        tgtVel = (float) TargetHudMath.ease(tgtVel, tDelta.length(), dt, HALF_LIFE);
        energy = (float) TargetHudMath.ease(energy, rawEnergy, dt, HALF_LIFE);
        smash = (float) TargetHudMath.ease(smash, smashRaw, dt, HALF_LIFE);
        triRot = (float) TargetHudMath.easeAngle(triRot,
                TargetHudMath.normalizeAngle(target.getYRot() - mc.player.getYRot()), dt, HALF_LIFE);
        easeReqVel(dt);

        // discrete snapshot stays live while locked; freezes automatically on loss
        sName = target.getName().getString();
        sWeapon = GuangxiElytraChaseModule.hudMode();
        sIntent = GuangxiElytraChaseModule.hudIntent();
        sHp = target.getHealth();
        sArmor = target.getArmorValue();
        sTotems = totems(target);
        sPing = pingOf(target);
        sElapsedMs = System.currentTimeMillis() - engageStart;
    }

    private void sampleFlight(float dt) {
        Vec3 myDelta = mc.player.getDeltaMovement();
        myVel = (float) TargetHudMath.ease(myVel, myDelta.length(), dt, HALF_LIFE);
        vspeed = (float) TargetHudMath.ease(vspeed, myDelta.y, dt, HALF_LIFE);
        heading = (float) TargetHudMath.easeAngle(heading,
                TargetHudMath.normalizeAngle(mc.player.getYRot()), dt, HALF_LIFE);
        flightEnergy = (float) TargetHudMath.ease(flightEnergy,
                Math.max(-100.0, Math.min(100.0, myVel * 12.0 + vspeed * 18.0)), dt, HALF_LIFE);
        easeReqVel(dt);
    }

    private void easeReqVel(float dt) {
        GuangxiElytraModule flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight != null) {
            reqVel = (float) TargetHudMath.ease(reqVel, flight.lastRequestedBpt(), dt, HALF_LIFE);
        } else {
            reqVel = Float.NaN;
        }
    }

    private static int totems(LivingEntity target) {
        int count = 0;
        if (target.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) count++;
        if (target.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) count++;
        return count;
    }

    private static String pingOf(LivingEntity target) {
        if (mc.getConnection() == null || !(target instanceof Player)) return "—";
        PlayerInfo info = mc.getConnection().getPlayerInfo(target.getUUID());
        return info == null ? "—" : info.getLatency() + " ms";
    }

    // ---------------------------------------------------------------- target layer

    private void drawTarget(GuiGraphics ctx, int x0, int y0, float a, float slide, float s) {
        int ox = x0, oy = y0 + px(slide, s);
        cardAt(ctx, ox, oy, TARGET_H, s, a);

        // header: avatar 44, nick 15/600, sub 12, weapon outline + intent badge
        avatar(ctx, ox + px(PAD, s), oy + px(TOP, s), 44f, s, a);
        float nickY = TOP + 6f;
        text(ctx, sName, PAD + 56f, nickY, 15f, HudFonts.SEMI, c(FG, a), ox, oy, s);
        text(ctx, String.format("%.1f 格", dist), PAD + 56f, nickY + 19f, 12f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);

        String intentCn = intentLabel(sIntent);
        float badgeY = TOP + 11f;
        float intentW = badgeWidth(intentCn, 11f) + 13f;
        float weaponW = badgeWidth(sWeapon, 11f) + 13f;
        badge(ctx, ox + px(W - PAD - intentW, s), oy + px(badgeY, s), px(intentW, s), px(22f, s),
                intentCn, 11f, intentBg(sIntent), c(intentFg(sIntent), a), s, true);
        badge(ctx, ox + px(W - PAD - intentW - 6f - weaponW, s), oy + px(badgeY, s), px(weaponW, s), px(22f, s),
                sWeapon, 11f, 0, c(MUTED_FG, a), s, false);

        // radar 104x104, no square backing — thin rings + cross only
        float blockY = TOP + 44f + 16f;
        int cx = ox + px(PAD + 52f, s), cy = oy + px(blockY + 52f, s);
        int ringColor = c(BORDER, a);
        int crossColor = c(0x0FFFFFFF, a);
        for (int[] o : RING_OUTER) ctx.fill(cx + o[0], cy + o[1], cx + o[0] + 1, cy + o[1] + 1, ringColor);
        for (int[] o : RING_INNER) ctx.fill(cx + o[0], cy + o[1], cx + o[0] + 1, cy + o[1] + 1, ringColor);
        line(ctx, cx - px(48f, s), cy, cx + px(48f, s), cy, crossColor);
        line(ctx, cx, cy - px(48f, s), cx, cy + px(48f, s), crossColor);
        double[] p = TargetHudMath.radarProject(bearing, dist, 43f, 20f);
        int tx = cx + px((float) p[0], s), ty = cy + px((float) p[1], s);
        line(ctx, cx, cy, tx, ty, c(0x80A1A1A1, a));
        ctx.fill(cx - 2, cy - 2, cx + 2, cy + 2, c(PRIMARY, a));
        triangle(ctx, tx, ty, triRot, c(DESTR, a));
        String chip = String.format("%d° · %+.0f", Math.round(bearing), relAlt);
        float chipW = HudFonts.width(chip, 10f, HudFonts.REGULAR) + 10f;
        int chipX = cx - px(chipW / 2f, s);
        int chipY = oy + px(blockY + 106f, s);
        rounded(ctx, chipX - ox, chipY - oy, chipW, 13f, 3f, c(CHIP_BG, a), ox, oy, s);
        text(ctx, chip, PAD + 52f - chipW / 2f + 5f, blockY + 108f, 10f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);

        // right column: three blocks across 129.4 starting at blockY
        float rx = PAD + 120f, rw = 228f;
        float b1 = blockY, b2 = blockY + 43.1f, b3 = blockY + 86.2f;

        text(ctx, "接近率 CLOSURE", rx, b1, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        boolean closing = closure >= 0;
        String bigClosure = String.format("%+.1f", closure);
        text(ctx, bigClosure, rx, b1 + 16f, 22f, HudFonts.BOLD, c(closing ? PRIMARY : DESTR, a), ox, oy, s);
        float afterX = rx + HudFonts.width(bigClosure, 22f, HudFonts.BOLD) + 5f;
        text(ctx, "b/t " + (closing ? "接近中" : "被拉开"), afterX, b1 + 27f, 11f, HudFonts.SEMI, c(MUTED_FG, a), ox, oy, s);

        text(ctx, "拦截解算 INTERCEPT", rx, b2, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        double t = TargetHudMath.interceptTicks(dist, closure, 0.05);
        if (t >= 0) {
            String bigT = "T-" + (int) Math.ceil(t);
            text(ctx, bigT, rx, b2 + 16f, 22f, HudFonts.BOLD, c(FG, a), ox, oy, s);
            float tickX = rx + HudFonts.width(bigT, 22f, HudFonts.BOLD) + 5f;
            text(ctx, "tick", tickX, b2 + 27f, 11f, HudFonts.SEMI, c(MUTED_FG, a), ox, oy, s);
        } else {
            text(ctx, "无解 · 平追回退", rx, b2 + 18f, 15f, HudFonts.SEMI, c(MUTED_FG, a), ox, oy, s);
            float cuX = rx + HudFonts.width("无解 · 平追回退", 15f, HudFonts.SEMI) + 5f;
            text(ctx, "catch-up", cuX, b2 + 24f, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        }

        text(ctx, "能量优势 E+", rx, b3, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        String energyText = String.format("%+d", Math.round(energy));
        textRight(ctx, energyText, PAD + CONTENT_W, b3 - 2f, 14f, HudFonts.SEMI, c(energy >= 0 ? PRIMARY : DESTR, a), ox, oy, s);
        float barY = b3 + 18f;
        rounded(ctx, rx, barY, rw, 6f, 3f, c(TRACK, a), ox, oy, s);
        int midX = ox + px(rx + rw / 2f, s);
        ctx.fill(midX, oy + px(barY, s), midX + 1, oy + px(barY + 6f, s), c(BORDER, a));
        float energyFrac = Math.min(1f, Math.abs(energy) / 100f);
        if (energy >= 0) {
            if (energyFrac > 0.005f) rounded(ctx, rx + rw / 2f, barY, rw / 2f * energyFrac, 6f, 3f, c(PRIMARY, a), ox, oy, s);
        } else {
            if (energyFrac > 0.005f) rounded(ctx, rx + rw / 2f - rw * energyFrac, barY, rw * energyFrac, 6f, 3f, c(DESTR, a), ox, oy, s);
        }

        // full-width smash + envelope rows
        float smashY = blockY + 129.4f + 6f;
        text(ctx, "砸落窗口", PAD, smashY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        float smashLabelW = HudFonts.width("砸落窗口", 11f, HudFonts.REGULAR) + 8f;
        boolean ready = smash >= 0.999f;
        float smashValueW = ready ? 38f : 30f;
        float smashBarW = CONTENT_W - smashLabelW - smashValueW - 6f;
        float smashFrac = Math.max(0f, Math.min(1f, smash));
        rounded(ctx, PAD + smashLabelW, smashY + 4f, smashBarW, 4f, 2f, c(TRACK, a), ox, oy, s);
        rounded(ctx, PAD + smashLabelW, smashY + 4f, smashBarW * smashFrac, 4f, 2f, c(ready ? PRIMARY : 0x99A1A1A1, a), ox, oy, s);
        if (ready) {
            badge(ctx, ox + px(W - PAD - 36f, s), oy + px(smashY - 3f, s), px(36f, s), px(18f, s),
                    "READY", 10f, 0xE6E5E5E5, DARK, s, true);
        } else {
            textRight(ctx, Math.round(smashFrac * 100) + "%", W - PAD, smashY, 10f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        }

        float envY = smashY + 17f;
        text(ctx, "包络钳制", PAD, envY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        float envLabelW = HudFonts.width("包络钳制", 11f, HudFonts.REGULAR) + 8f;
        String envText = Float.isNaN(reqVel)
                ? "请求 — → 实际 " + String.format("%.1f", myVel)
                : String.format("请求 %.1f → 实际 %.1f", reqVel, myVel);
        float envValueW = HudFonts.width(envText, 10f, HudFonts.REGULAR) + 8f;
        float envBarW = CONTENT_W - envLabelW - envValueW - 6f;
        rounded(ctx, PAD + envLabelW, envY + 4f, envBarW, 4f, 2f, c(TRACK, a), ox, oy, s);
        if (!Float.isNaN(reqVel)) {
            rounded(ctx, PAD + envLabelW, envY + 4f, envBarW * Math.min(1f, reqVel / 8f), 4f, 2f, c(0x59A1A1A1, a), ox, oy, s);
        }
        rounded(ctx, PAD + envLabelW, envY + 4f, envBarW * Math.min(1f, myVel / 8f), 4f, 2f, c(PRIMARY, a), ox, oy, s);
        textRight(ctx, envText, W - PAD, envY, 10f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);

        // separator + hp/armor row + footer
        float sepY = envY + 27f;
        ctx.fill(ox + px(PAD, s), oy + px(sepY, s), ox + px(W - PAD, s), oy + px(sepY + 1f, s), c(BORDER, a));

        float hpY = sepY + 15f;
        float textW = 36f;
        float barW = (CONTENT_W - 22f - textW - 10f - 22f - textW - 10f) / 2f;
        iconHeart(ctx, ox + px(PAD, s), oy + px(hpY + 1f, s), c(MUTED_FG, a), s);
        float barX = PAD + 22f;
        rounded(ctx, barX, hpY + 3f, barW, 6f, 3f, c(TRACK, a), ox, oy, s);
        rounded(ctx, barX, hpY + 3f, barW * Math.max(0f, Math.min(1f, ghostHp / 20f)), 6f, 3f, c(GHOST, a), ox, oy, s);
        rounded(ctx, barX, hpY + 3f, barW * Math.max(0f, Math.min(1f, sHp / 20f)), 6f, 3f, c(DESTR, a), ox, oy, s);
        textRight(ctx, String.format("%.1f", sHp), barX + barW + textW + 8f, hpY, 12f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        float armorX = barX + barW + textW + 22f;
        iconShield(ctx, ox + px(armorX, s), oy + px(hpY + 1f, s), c(MUTED_FG, a), s);
        rounded(ctx, armorX + 22f, hpY + 3f, barW, 6f, 3f, c(TRACK, a), ox, oy, s);
        rounded(ctx, armorX + 22f, hpY + 3f, barW * Math.max(0f, Math.min(1f, sArmor / 20f)), 6f, 3f, c(PRIMARY, a), ox, oy, s);
        textRight(ctx, String.valueOf((int) Math.ceil(sArmor)), armorX + 22f + barW + textW + 8f, hpY, 12f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);

        float footY = TARGET_H - PAD - 12f;
        String speeds = String.format("敌速 %.1f · 我速 %.1f", tgtVel, myVel);
        String clock = clock(sElapsedMs / 1000L);
        float w1 = HudFonts.width(speeds, 11f, HudFonts.REGULAR);
        float w2 = HudFonts.width("×" + sTotems, 11f, HudFonts.REGULAR);
        float w3 = HudFonts.width(sPing, 11f, HudFonts.REGULAR);
        float gap = (CONTENT_W - w1 - w2 - w3 - HudFonts.width(clock, 11f, HudFonts.REGULAR)) / 3f;
        text(ctx, speeds, PAD, footY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        text(ctx, "×" + sTotems, PAD + w1 + gap, footY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        text(ctx, sPing, PAD + w1 + gap + w2 + gap, footY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        textRight(ctx, clock, W - PAD, footY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
    }

    // ---------------------------------------------------------------- flight layer

    private void drawFlight(GuiGraphics ctx, int x0, int y0, float slide, float s) {
        int ox = x0, oy = y0 + px(slide, s);
        cardAt(ctx, ox, oy, FLIGHT_H, s, 1f);

        text(ctx, "FLIGHT · 巡航层", PAD, TOP + 3f, 13f, HudFonts.SEMI, c(MUTED_FG, 1f), ox, oy, s);
        boolean gliding = mc.player.isFallFlying();
        boolean rocket = gliding && mc.player.isUsingItem() && mc.player.getUseItem().is(Items.FIREWORK_ROCKET);
        String b1Text = gliding ? "滑翔" : "甲飞";
        String b2Text = rocket ? "烟花附着" : "烟花待命";
        float b2W = badgeWidth(b2Text, 11f) + 13f;
        float b1W = badgeWidth(b1Text, 11f) + 13f;
        badge(ctx, ox + px(W - PAD - b2W, s), oy + px(TOP - 1f, s), px(b2W, s), px(20f, s),
                b2Text, 11f, 0, c(MUTED_FG, 1f), s, false);
        badge(ctx, ox + px(W - PAD - b2W - 6f - b1W, s), oy + px(TOP - 1f, s), px(b1W, s), px(20f, s),
                b1Text, 11f, 0xFF26262B, c(FG, 1f), s, true);

        // heading tape: 348 wide, 61 tall, marks every 15°, cardinals labelled
        float tapeY = TOP + 32f;
        float tapeH = 61f;
        rounded(ctx, PAD, tapeY, CONTENT_W, tapeH, 8f, c(0x6626262B, 1f), ox, oy, s);
        float pxPerDeg = CONTENT_W / 120f;
        int start = (int) Math.floor((heading - 60f) / 15f) * 15;
        for (int d = start; d <= heading + 60f; d += 15) {
            float dx = (d - heading) * pxPerDeg + PAD + CONTENT_W / 2f;
            if (dx < PAD + 8f || dx > PAD + CONTENT_W - 8f) continue;
            int norm = Math.floorMod(d, 360);
            String label = norm == 0 ? "N" : norm == 90 ? "E" : norm == 180 ? "S" : norm == 270 ? "W" : null;
            float tickTop = label != null ? tapeY + 11f : tapeY + 19f;
            ctx.fill(ox + px(dx, s), oy + px(tickTop, s), ox + px(dx + 1f, s), oy + px(tapeY + 31f, s), c(MUTED_FG, 1f));
            if (label != null) {
                text(ctx, label, dx - HudFonts.width(label, 11f, HudFonts.REGULAR) / 2f, tapeY + 35f, 11f, HudFonts.REGULAR, c(FG, 1f), ox, oy, s);
            }
        }
        float bearingDx = Math.max(-58f, Math.min(58f, bearing));
        diamond(ctx, ox + px(PAD + CONTENT_W / 2f + bearingDx * pxPerDeg, s), oy + px(tapeY + 7f, s), c(DESTR, 1f));
        ctx.fill(ox + px(PAD + CONTENT_W / 2f - 4f, s), oy + px(tapeY + 3f, s),
                ox + px(PAD + CONTENT_W / 2f + 4f, s), oy + px(tapeY + 9f, s), c(PRIMARY, 1f));

        // airspeed + vertical/altitude
        float rowY = tapeY + tapeH + 12f;
        text(ctx, "空速", PAD, rowY, 11f, HudFonts.REGULAR, c(MUTED_FG, 1f), ox, oy, s);
        String bigSpeed = String.format("%.1f", myVel);
        text(ctx, bigSpeed, PAD, rowY + 14f, 26f, HudFonts.BOLD, c(FG, 1f), ox, oy, s);
        float speedSuffixX = PAD + HudFonts.width(bigSpeed, 26f, HudFonts.BOLD) + 5f;
        text(ctx, String.format("b/t · %.0f m/s", myVel * 20f), speedSuffixX, rowY + 29f, 12f, HudFonts.SEMI, c(MUTED_FG, 1f), ox, oy, s);
        String yText = String.format("Y %d", Math.round(mc.player.getY()));
        textRight(ctx, String.format("垂直 %+.1f b/t", vspeed),
                W - PAD - HudFonts.width(yText, 26f, HudFonts.BOLD) - 10f, rowY, 11f, HudFonts.REGULAR, c(MUTED_FG, 1f), ox, oy, s);
        textRight(ctx, yText, W - PAD, rowY + 14f, 26f, HudFonts.BOLD, c(FG, 1f), ox, oy, s);

        // energy reserve
        float enY = rowY + 52f;
        text(ctx, "能量储备 ENERGY", PAD, enY, 11f, HudFonts.REGULAR, c(MUTED_FG, 1f), ox, oy, s);
        textRight(ctx, String.format("%+d", Math.round(flightEnergy)), W - PAD, enY - 2f, 14f, HudFonts.SEMI, c(PRIMARY, 1f), ox, oy, s);
        rounded(ctx, PAD, enY + 18f, CONTENT_W, 6f, 3f, c(TRACK, 1f), ox, oy, s);
        rounded(ctx, PAD, enY + 18f, CONTENT_W * Math.max(0f, Math.min(1f, (50f + flightEnergy / 2f) / 100f)), 6f, 3f, c(PRIMARY, 1f), ox, oy, s);

        text(ctx, "未锁定目标 — 显示我方飞行状态；锁定后切换为目标层", PAD, FLIGHT_H - PAD - 12f, 11f, HudFonts.REGULAR, c(MUTED_FG, 1f), ox, oy, s);
    }

    // ---------------------------------------------------------------- shared drawing

    private void cardAt(GuiGraphics ctx, int ox, int oy, float h, float s, float a) {
        rounded(ctx, 3f, 4f, W, h, RADIUS, c(0x2E000000, a), ox, oy, s);
        rounded(ctx, 0f, 0f, W, h, RADIUS, c(BORDER, a), ox, oy, s);
        rounded(ctx, 1f, 1f, W - 2f, h - 2f, RADIUS - 1f, c(CARD, a), ox, oy, s);
    }

    /** Rounded avatar frame with the skin face (players) or a stable pixel face. */
    private void avatar(GuiGraphics ctx, int x, int y, float size, float s, float a) {
        int box = px(size, s);
        int r = Math.max(2, Math.round(8f * s));
        roundedAbs(ctx, x, y, box, box, r, c(BORDER, a));
        int inset = Math.max(2, Math.round(4f * s));
        int inner = box - inset * 2;
        if (inner < 4) return;
        Identifier skin = faceTexture();
        if (skin != null) {
            float sc = inner / 8f;
            var pose = ctx.pose();
            pose.pushMatrix();
            pose.scale(sc, sc);
            ctx.blit(RenderPipelines.GUI_TEXTURED, skin,
                    Math.round((x + inset) / sc), Math.round((y + inset) / sc),
                    8f, 8f, 8, 8, 64, 64, c(0xFFFFFFFF, a));
            pose.popMatrix();
        } else {
            pixelFace(ctx, x + inset, y + inset, inner);
        }
    }

    private Identifier faceTexture() {
        if (mc.getConnection() == null || trackedId == null) return null;
        PlayerInfo info = mc.getConnection().getPlayerInfo(trackedId);
        if (info == null) return null;
        try {
            var body = info.getSkin().body();
            return body == null ? null : body.texturePath();
        } catch (Throwable unavailable) {
            return null; // skin not resolved yet — pixel face stands in
        }
    }

    /** Deterministic placeholder face derived from the entity id. */
    private void pixelFace(GuiGraphics ctx, int x, int y, int size) {
        int[] tones = {0xFFB58968, 0xFF96634A, 0xFF8D6E63, 0xFFA1887F};
        int[] hairs = {0xFF4A2F1E, 0xFF2E2A28, 0xFF5D4037, 0xFF6D4C41};
        int h = trackedId == null ? 0 : trackedId.hashCode();
        int tone = tones[(h >>> 3) & 3];
        int hair = hairs[(h >>> 8) & 3];
        int u = Math.max(1, size / 8);
        int w = u * 8;
        ctx.fill(x, y, x + w, y + 2 * u, hair);
        ctx.fill(x, y + 2 * u, x + w, y + 8 * u, tone);
        ctx.fill(x + u, y + 3 * u, x + 2 * u, y + 4 * u, 0xFF3B2A6B);
        ctx.fill(x + 6 * u, y + 3 * u, x + 7 * u, y + 4 * u, 0xFF3B2A6B);
        ctx.fill(x + 3 * u, y + 5 * u, x + 5 * u, y + 6 * u, 0xFF7A4F35);
    }

    private void badge(GuiGraphics ctx, int x, int y, int w, int h, String label, float sizePx,
                       int bg, int fg, float s, boolean filled) {
        int r = Math.max(2, Math.round(5f * s));
        if (filled) {
            roundedAbs(ctx, x, y, w, h, r, bg);
        } else {
            roundedAbs(ctx, x, y, w, h, r, c(BORDER, 1f));
            roundedAbs(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), CARD);
        }
        float tw = HudFonts.width(label, sizePx, HudFonts.SEMI);
        int tx = x + (int) ((w - tw) / 2f);
        int ty = y + (int) ((h - sizePx) / 2f) - 1;
        HudFonts.draw(ctx, label, tx, Math.max(ty, y + 1), sizePx, HudFonts.SEMI, fg);
    }

    private void text(GuiGraphics ctx, String str, float dx, float dy, float sizePx, int weight, int color, int ox, int oy, float s) {
        HudFonts.draw(ctx, str, ox + dx * s, oy + dy * s, sizePx * s, weight, color);
    }

    private void textRight(GuiGraphics ctx, String str, float dxRight, float dy, float sizePx, int weight, int color, int ox, int oy, float s) {
        float w = HudFonts.width(str, sizePx, weight);
        text(ctx, str, dxRight - w, dy, sizePx, weight, color, ox, oy, s);
    }

    private void rounded(GuiGraphics ctx, float dx, float dy, float dw, float dh, float r, int color, int ox, int oy, float s) {
        roundedAbs(ctx, ox + Math.round(dx * s), oy + Math.round(dy * s),
                Math.round(dw * s), Math.round(dh * s), Math.round(r * s), color);
    }

    private void roundedAbs(GuiGraphics ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        int rr = Math.max(0, Math.min(Math.min(w, h) / 2, r));
        for (int row = 0; row < h; row++) {
            int inset = 0;
            if (row < rr) {
                int d = rr - row;
                inset = rr - (int) Math.floor(Math.sqrt((double) rr * rr - (double) d * d) + 0.5);
            } else if (row >= h - rr) {
                int d = row - (h - rr - 1);
                inset = rr - (int) Math.floor(Math.sqrt((double) rr * rr - (double) d * d) + 0.5);
            }
            if (inset * 2 >= w) continue;
            ctx.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
        }
    }

    private void line(GuiGraphics ctx, int x0, int y0, int x1, int y1, int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (steps == 0) {
            ctx.fill(x0, y0, x0 + 1, y0 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int fx = x0 + (x1 - x0) * i / steps;
            int fy = y0 + (y1 - y0) * i / steps;
            ctx.fill(fx, fy, fx + 1, fy + 1, color);
        }
    }

    private void diamond(GuiGraphics ctx, int cx, int cy, int color) {
        for (int i = 0; i < 5; i++) {
            int half = i < 3 ? i : 4 - i;
            if (half < 0) continue;
            ctx.fill(cx - half, cy + i - 2, cx + half + 1, cy + i - 1, color);
        }
    }

    /** Filled triangle pointing up pre-rotation, ~10px tall — the radar contact. */
    private void triangle(GuiGraphics ctx, int cx, int cy, float rotDeg, int color) {
        double rad = Math.toRadians(rotDeg);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double[][] v = {{0, -5.2}, {-2.9, 2.7}, {2.9, 2.7}};
        int[] xs = new int[3], ys = new int[3];
        for (int i = 0; i < 3; i++) {
            xs[i] = cx + (int) Math.round(v[i][0] * cos - v[i][1] * sin);
            ys[i] = cy + (int) Math.round(v[i][0] * sin + v[i][1] * cos);
        }
        int minY = Math.min(ys[0], Math.min(ys[1], ys[2]));
        int maxY = Math.max(ys[0], Math.max(ys[1], ys[2]));
        for (int y = minY; y <= maxY; y++) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            for (int i = 0; i < 3; i++) {
                int j = (i + 1) % 3;
                int y0 = ys[i], y1 = ys[j];
                if (y0 == y1) {
                    if (y == y0) {
                        minX = Math.min(minX, Math.min(xs[i], xs[j]));
                        maxX = Math.max(maxX, Math.max(xs[i], xs[j]));
                    }
                    continue;
                }
                if ((y >= y0 && y <= y1) || (y >= y1 && y <= y0)) {
                    int fx = xs[i] + (y - y0) * (xs[j] - xs[i]) / (y1 - y0);
                    minX = Math.min(minX, fx);
                    maxX = Math.max(maxX, fx);
                }
            }
            if (minX <= maxX) ctx.fill(minX, y, maxX + 1, y + 1, color);
        }
    }

    private void iconHeart(GuiGraphics ctx, int x, int y, int color, float s) {
        int u = Math.max(1, Math.round(s));
        int[][] rows = {{1, 5}, {0, 7}, {0, 7}, {1, 5}, {2, 3}, {3, 1}};
        for (int r = 0; r < rows.length; r++) {
            ctx.fill(x + rows[r][0] * u, y + r * u, x + (rows[r][0] + rows[r][1]) * u, y + (r + 1) * u, color);
        }
    }

    private void iconShield(GuiGraphics ctx, int x, int y, int color, float s) {
        int u = Math.max(1, Math.round(s));
        int[][] rows = {{0, 6}, {0, 6}, {0, 6}, {1, 4}, {1, 4}, {2, 2}, {3, 1}};
        for (int r = 0; r < rows.length; r++) {
            ctx.fill(x + rows[r][0] * u, y + r * u, x + (rows[r][0] + rows[r][1]) * u, y + (r + 1) * u, color);
        }
    }

    // ---------------------------------------------------------------- helpers

    private float canvasScale() {
        float w = screenWidth(), h = screenHeight();
        return Math.max(0.55f, Math.min(1.15f, Math.min(w / 440f, h / 420f)));
    }

    private int px(float design, float s) {
        return Math.round(design * s);
    }

    private static int c(int argb, float alpha) {
        if (alpha >= 1f) return argb;
        int a = (argb >>> 24);
        a = (int) (a * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private float badgeWidth(String label, float sizePx) {
        return HudFonts.width(label, sizePx, HudFonts.SEMI) + 4f;
    }

    private static String intentLabel(String intent) {
        return switch (intent) {
            case "PULL_UP" -> "爬升";
            case "FOLLOW", "CHASE" -> "追击";
            case "DIVE" -> "俯冲";
            case "WAIT" -> "待确认";
            case "EVADE" -> "规避";
            case "ENGAGE" -> "缠斗";
            case "PULL_OVER" -> "脱离";
            default -> "待机";
        };
    }

    private static int intentBg(String intent) {
        return switch (intent) {
            case "DIVE" -> 0xE6F87171;
            case "EVADE" -> 0xE6F59E0B;
            case "WAIT" -> 0xFF26262B;
            default -> 0xE6E5E5E5;
        };
    }

    private static int intentFg(String intent) {
        return switch (intent) {
            case "DIVE", "EVADE" -> 0xFFFFFFFF;
            case "WAIT" -> MUTED_FG;
            default -> DARK;
        };
    }

    private static String clock(long seconds) {
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    private static int[][] ringOffsets(int r, boolean dashed) {
        List<int[]> pts = new ArrayList<>(420);
        Set<Long> seen = new HashSet<>();
        for (double ang = 0; ang < 360.0; ang += 0.15) {
            if (dashed && ang % 18.0 >= 7.2) continue; // 2 on / 3 off rhythm
            double rad = Math.toRadians(ang);
            int x = (int) Math.round(r * Math.cos(rad));
            int y = (int) Math.round(r * Math.sin(rad));
            long key = ((long) (x + 4096) << 13) | (y + 4096);
            if (seen.add(key)) pts.add(new int[]{x, y});
        }
        return pts.toArray(new int[0][]);
    }
}
