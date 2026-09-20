package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.util.render.font.HudFonts;
import dev.guizhou.util.render.targethud.TargetHudMath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
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
 * (targethud-shadcn: TargetHudAir + FlightHud). Layout follows the measured
 * DOM geometry: card 380 wide, target card 367.5 tall, flight card 344
 * (unlocked layout, footer at y239.94), radius 14, content inset l/r 16,
 * top 32, bottom 32.
 *
 * One design canvas, one uniform scale — every primitive, icon cell and text
 * size multiplies by the same factor. Text is Geist (400/600/700, bundled)
 * at explicit CSS-px sizes, no MC drop shadow. Continuous values ease per
 * frame (140ms half-life); discrete values live in a snapshot that freezes
 * on target loss so the 300ms bidirectional cross-fade never invents data.
 * A new target snaps the eased state; a world/player change resets it all.
 * Envelope numbers come from the live flight module (applied tick within 5t)
 * and fall back to dashes — never from speed magnitude masquerading as applied.
 */
public class TargetHudModule extends HudModule {

    // ---- shadcn dark palette (measured from the prototype) ----
    private static final int CARD     = 0xFF171717;
    private static final int FG       = 0xFFFAFAFA;
    private static final int PRIMARY  = 0xFFE5E5E5;
    private static final int MUTED_FG = 0xFFA1A1A1;
    private static final int TRACK    = 0xFF262626;
    private static final int BORDER   = 0x1AFFFFFF;
    private static final int DESTR    = 0xFFF87171;
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
    private static final float FADE_MS = 300f;

    /** Thin radar rings, precomputed pixel offsets in DESIGN units (r=46 / r=25). */
    private static final int[][] RING_OUTER = ringOffsets(46, false);
    private static final int[][] RING_INNER = ringOffsets(25, true);

    // Original Lucide paths from the prototype, baked once into a small atlas.
    private static final Identifier ICON_ATLAS = Identifier.fromNamespaceAndPath(
            "guizhouware", "textures/gui/air_hud_icons.png");
    private static final int ICON_HEART = 0, ICON_SHIELD = 1, ICON_WIND = 2,
            ICON_SPARKLE = 3, ICON_TIMER = 4, ICON_FEATHER = 5, ICON_FLAME = 6,
            ICON_ARROW_UP = 7, ICON_ARROW_DOWN = 8;

    // eased continuous state
    private float dist = 8, bearing, relAlt, closure, myVel, tgtVel, energy, smash = -1;
    private float envReq = Float.NaN, envApl = Float.NaN;
    private float vspeed, heading, triRot, flightEnergy;
    private float layerA; // 0 = flight visible, 1 = target visible
    private float fadeFrom;
    private boolean layerTarget;
    private long fadeStarted;
    private float displayedHp;
    private float ghostHp = 20;
    private float pulseA = 0.6f;
    private long lastFrame;

    // discrete snapshot — frozen while the target layer fades out
    private String sName = "", sWeapon = "—", sIntent = "OFF", sPing = "—";
    private float sHp, sArmor, sMaxHp = 20;
    private int sTotems;
    private long sElapsedMs;
    private UUID trackedId;
    private long engageStart;

    // session identity — world/player change wipes everything
    private net.minecraft.world.entity.player.Player trackedPlayer;
    private net.minecraft.client.multiplayer.ClientLevel trackedLevel;

    public TargetHudModule() {
        super("TargetHud");
    }

    @Override
    public void render(Render2DEvent event) {
        if (mc.player == null || mc.level == null) {
            if (trackedPlayer != null || trackedLevel != null || trackedId != null) hardReset();
            return;
        }
        if (mc.player != trackedPlayer || mc.level != trackedLevel) {
            hardReset();
            trackedPlayer = mc.player;
            trackedLevel = mc.level;
        }
        GuiGraphics ctx = event.getContext();
        long now = System.nanoTime() / 1_000_000L;
        float dt = lastFrame == 0 ? 16f : Math.max(0f, now - lastFrame);
        lastFrame = now;
        pulseA = 0.55f + 0.45f * (float) Math.sin(now / 160.0);

        LivingEntity target = currentTarget();
        if (target != null) {
            UUID id = target.getUUID();
            if (!id.equals(trackedId)) adopt(target);
            sampleTarget(target, dt);
        }
        sampleShared(dt);
        sampleFlight(dt);

        boolean targetVisible = target != null;
        if (targetVisible != layerTarget) {
            layerTarget = targetVisible;
            fadeFrom = layerA;
            fadeStarted = now;
        }
        float t = Math.min(1f, Math.max(0f, (now - fadeStarted) / FADE_MS));
        float eased = 1f - (1f - t) * (1f - t) * (1f - t);
        layerA = fadeFrom + ((layerTarget ? 1f : 0f) - fadeFrom) * eased;
        displayedHp = (float) TargetHudMath.ease(displayedHp, sHp, dt, HALF_LIFE);
        if (ghostHp > sHp) ghostHp = Math.max(sHp, ghostHp - 0.35f * dt / 120f);
        else ghostHp = sHp;

        float s = canvasScale();
        int defX = screenWidth() - px(W, s) - px(12f, s);
        int defY = Math.max(px(8f, s), (screenHeight() - px(TARGET_H, s)) / 2 - px(4f, s));
        int[] o = origin(defX, defY, px(W, s), px(TARGET_H, s));
        int x0 = o[0];
        int y0 = o[1];
        bounds(x0, y0, px(W, s), px(TARGET_H, s));

        // Quantize primitives in the design canvas, then transform once. Scaling
        // each pixel before drawing drops thin icon strokes at normal HUD size.
        var pose = ctx.pose();
        pose.pushMatrix();
        pose.translate(x0, y0);
        pose.scale(s, s);
        try {
            float flightA = 1f - layerA;
            if (flightA > 0.005f) drawFlight(ctx, 0, 0, flightA, 6f * layerA, 1f);
            if (layerA > 0.005f) drawTarget(ctx, 0, 0, layerA, 6f * (1f - layerA), 1f);
        } finally {
            pose.popMatrix();
        }
    }

    private void hardReset() {
        trackedId = null;
        trackedPlayer = mc.player;
        trackedLevel = mc.level;
        engageStart = 0;
        layerA = 0;
        fadeFrom = 0;
        fadeStarted = 0;
        layerTarget = false;
        lastFrame = 0;
        displayedHp = 0;
        ghostHp = 20;
        dist = 8;
        bearing = relAlt = closure = myVel = tgtVel = energy = vspeed = heading = triRot = flightEnergy = 0;
        smash = -1;
        envReq = Float.NaN;
        envApl = Float.NaN;
        sName = "";
        sWeapon = "—";
        sIntent = "OFF";
        sPing = "—";
        sHp = 0;
        sArmor = 0;
        sMaxHp = 20;
        sTotems = 0;
        sElapsedMs = 0;
    }

    // ---------------------------------------------------------------- data

    private LivingEntity currentTarget() {
        Entity t = GuangxiElytraChaseModule.hudTarget();
        if (t == null || !t.isAlive() || t.isRemoved() || t.level() != mc.level) return null;
        return t instanceof LivingEntity living ? living : null;
    }

    /** New lock: snap every continuous target field so nothing blends across entities. */
    private void adopt(LivingEntity target) {
        trackedId = target.getUUID();
        engageStart = System.nanoTime() / 1_000_000L;
        Vec3 myPos = mc.player.position();
        Vec3 tPos = target.position();
        dist = (float) myPos.distanceTo(tPos);
        bearing = (float) TargetHudMath.wrapDelta(mc.player.getYRot(),
                TargetHudMath.yawTo(tPos.x - myPos.x, tPos.z - myPos.z));
        relAlt = (float) (tPos.y - myPos.y);
        tgtVel = (float) target.getDeltaMovement().length();
        Vec3 myDelta = mc.player.getDeltaMovement();
        myVel = (float) myDelta.length();
        closure = dist > 1e-6 ? (float) myDelta.subtract(target.getDeltaMovement())
                .dot(tPos.subtract(myPos).normalize()) : 0f;
        energy = (float) TargetHudMath.energyAdvantage(myVel, tgtVel, relAlt);
        smash = Math.max(0f, Math.min(1f, (float) mc.player.fallDistance / 1.5f));
        triRot = (float) TargetHudMath.normalizeAngle(target.getYRot() - mc.player.getYRot());
        sHp = target.getHealth();
        displayedHp = sHp;
        ghostHp = sHp;
        sMaxHp = Math.max(1f, target.getMaxHealth());
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
        tgtVel = (float) TargetHudMath.ease(tgtVel, tDelta.length(), dt, HALF_LIFE);
        energy = (float) TargetHudMath.ease(energy, rawEnergy, dt, HALF_LIFE);
        smash = (float) TargetHudMath.ease(smash, smashRaw, dt, HALF_LIFE);
        triRot = (float) TargetHudMath.easeAngle(triRot,
                TargetHudMath.normalizeAngle(target.getYRot() - mc.player.getYRot()), dt, HALF_LIFE);

        // discrete snapshot stays live while locked; freezes automatically on loss
        sName = target.getName().getString();
        sWeapon = GuangxiElytraChaseModule.hudMode();
        sIntent = GuangxiElytraChaseModule.hudIntent();
        sHp = target.getHealth();
        sMaxHp = Math.max(1f, target.getMaxHealth());
        sArmor = target.getArmorValue();
        sTotems = totems(target);
        sPing = pingOf(target);
        sElapsedMs = System.nanoTime() / 1_000_000L - engageStart;
    }

    /** Shared fields ease exactly once per frame (both layers read them). */
    private void sampleShared(float dt) {
        myVel = (float) TargetHudMath.ease(myVel, mc.player.getDeltaMovement().length(), dt, HALF_LIFE);
        GuangxiElytraModule flight = flightModule();
        boolean live = envelopeLive(flight);
        double rawReq = live ? flight.lastRequestedBpt() : Double.NaN;
        double rawApl = live ? flight.lastAppliedBpt() : Double.NaN;
        envReq = easeOrNaN(envReq, rawReq, dt);
        envApl = easeOrNaN(envApl, rawApl, dt);
    }

    private void sampleFlight(float dt) {
        Vec3 myDelta = mc.player.getDeltaMovement();
        vspeed = (float) TargetHudMath.ease(vspeed, myDelta.y, dt, HALF_LIFE);
        heading = (float) TargetHudMath.easeAngle(heading,
                TargetHudMath.normalizeAngle(mc.player.getYRot() + 180.0), dt, HALF_LIFE);
        flightEnergy = (float) TargetHudMath.ease(flightEnergy,
                Math.max(-100.0, Math.min(100.0, myVel * 12.0 + vspeed * 18.0)), dt, HALF_LIFE);
    }

    private static float easeOrNaN(float current, double raw, float dt) {
        if (Double.isNaN(raw)) return Float.NaN;
        float start = Float.isNaN(current) ? (float) raw : current;
        return (float) TargetHudMath.ease(start, raw, dt, HALF_LIFE);
    }

    private GuangxiElytraModule flightModule() {
        return GuizhouWare.moduleManager == null ? null
                : GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
    }

    /** Envelope numbers are truthful only while the flight module applies them. */
    private boolean envelopeLive(GuangxiElytraModule flight) {
        return flight != null && flight.isEnabled()
                && flight.lastAppliedTick() != Integer.MIN_VALUE
                && mc.player.tickCount >= flight.lastAppliedTick()
                && mc.player.tickCount - flight.lastAppliedTick() <= 5;
    }

    private static int totems(LivingEntity target) {
        int count = 0;
        if (target.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) count++;
        if (target.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) count++;
        return count;
    }

    private static String pingOf(LivingEntity target) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || !(target instanceof Player)) return "—";
        PlayerInfo info = conn.getPlayerInfo(target.getUUID());
        return info == null ? "—" : info.getLatency() + " ms";
    }

    // ---------------------------------------------------------------- target layer

    private void drawTarget(GuiGraphics ctx, int x0, int y0, float a, float slide, float s) {
        int ox = x0, oy = y0 + px(slide, s);
        cardAt(ctx, ox, oy, TARGET_H, s, a);

        // header: avatar 44, nick 15/600 (clamped), sub 12, weapon outline + intent badge
        avatar(ctx, ox + px(PAD, s), oy + px(TOP, s), 44f, s, a);
        String intentCn = intentLabel(sIntent);
        float intentW = HudFonts.width(intentCn, 12f, HudFonts.SEMI) + 32f;
        float weaponW = HudFonts.width(sWeapon, 12f, HudFonts.SEMI) + 20f;
        float badgeY = 44f;
        badge(ctx, ox + px(W - PAD - intentW, s), oy + px(badgeY, s), px(intentW, s), px(20f, s),
                intentCn, 12f, intentBg(sIntent), intentFg(sIntent), s, a, true, true);
        badge(ctx, ox + px(W - PAD - intentW - 6f - weaponW, s), oy + px(badgeY, s), px(weaponW, s), px(20f, s),
                sWeapon, 12f, 0, FG, s, a, false, false);
        float nickX = PAD + 56f;
        float nickAvail = (W - PAD - intentW - 6f - weaponW - 8f) - nickX;
        text(ctx, clip(sName, nickAvail, 15f, HudFonts.SEMI), nickX, 34.3f, 15f, HudFonts.SEMI, c(FG, a), ox, oy, s);
        text(ctx, String.format("%.1f 格", dist), nickX, 57.7f, 12f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);

        // radar 104x104 at y92 — thin rings + cross, no square backing
        float blockY = 92f;
        int cx = ox + px(PAD + 52f, s), cy = oy + px(blockY + 52f, s);
        int ringColor = c(BORDER, a);
        int crossColor = c(0x0FFFFFFF, a);
        for (int[] o : RING_OUTER) {
            int fx = px(o[0], s), fy = px(o[1], s);
            ctx.fill(cx + fx, cy + fy, cx + fx + Math.max(1, px(1, s)), cy + fy + Math.max(1, px(1, s)), ringColor);
        }
        for (int[] o : RING_INNER) {
            int fx = px(o[0], s), fy = px(o[1], s);
            ctx.fill(cx + fx, cy + fy, cx + fx + Math.max(1, px(1, s)), cy + fy + Math.max(1, px(1, s)), ringColor);
        }
        line(ctx, cx - px(48f, s), cy, cx + px(48f, s), cy, crossColor);
        line(ctx, cx, cy - px(48f, s), cx, cy + px(48f, s), crossColor);
        double[] p = TargetHudMath.radarProject(bearing, dist, 43f, 20f);
        int tx = cx + px((float) p[0], s), ty = cy + px((float) p[1], s);
        line(ctx, cx, cy, tx, ty, c(0x80A1A1A1, a));
        int dot = Math.max(1, px(2, s));
        ctx.fill(cx - dot, cy - dot, cx + dot, cy + dot, c(PRIMARY, a));
        triangle(ctx, tx, ty, triRot, c(DESTR, a), s);
        String chip = String.format("%d° · %+.0f", Math.round(bearing), relAlt);
        float chipW = HudFonts.width(chip, 10f, HudFonts.REGULAR) + 10f;
        int chipX = cx - px(chipW / 2f, s);
        int chipY = oy + px(208f, s);
        roundedAbs(ctx, chipX, chipY, px(chipW, s), px(13f, s), Math.max(1, px(3f, s)), c(CHIP_BG, a));
        text(ctx, chip, PAD + 52f - chipW / 2f + 5f, 211.1f, 10f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);

        // right column at measured label y: 94 / 141.7 / 193.41 (+bar 213.41)
        float rx = PAD + 120f, rw = 228f;
        text(ctx, "接近率 CLOSURE", rx, 94f, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        boolean closing = closure >= 0;
        String bigClosure = String.format("%+.1f", closure);
        text(ctx, bigClosure, rx, 110f, 22f, HudFonts.BOLD, c(closing ? PRIMARY : DESTR, a), ox, oy, s);
        float afterX = rx + HudFonts.width(bigClosure, 22f, HudFonts.BOLD) + 5f;
        text(ctx, "b/t " + (closing ? "接近中" : "被拉开"), afterX, 121f, 11f, HudFonts.SEMI, c(MUTED_FG, a), ox, oy, s);

        text(ctx, "拦截解算 INTERCEPT", rx, 141.7f, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        double t = TargetHudMath.interceptTicks(dist, closure, 0.05);
        if (t >= 0) {
            String bigT = "T−" + (int) Math.ceil(t);
            text(ctx, bigT, rx, 157.7f, 22f, HudFonts.BOLD, c(FG, a), ox, oy, s);
            float tickX = rx + HudFonts.width(bigT, 22f, HudFonts.BOLD) + 5f;
            text(ctx, "tick", tickX, 168.7f, 11f, HudFonts.SEMI, c(MUTED_FG, a), ox, oy, s);
        } else {
            text(ctx, "无解 · 平追回退", rx, 159.7f, 15f, HudFonts.SEMI, c(MUTED_FG, a), ox, oy, s);
            float cuX = rx + HudFonts.width("无解 · 平追回退", 15f, HudFonts.SEMI) + 5f;
            text(ctx, "catch-up", cuX, 165.7f, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        }

        text(ctx, "能量优势 E+", rx, 193.4f, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        textRight(ctx, String.format("%+d", Math.round(energy)), PAD + CONTENT_W, 191.4f, 14f,
                HudFonts.SEMI, c(energy >= 0 ? PRIMARY : DESTR, a), ox, oy, s);
        float barY = 213.4f;
        rounded(ctx, rx, barY, rw, 6f, 3f, c(TRACK, a), ox, oy, s);
        int midX = ox + px(rx + rw / 2f, s);
        ctx.fill(midX, oy + px(barY, s), midX + 1, oy + px(barY + 6f, s), c(BORDER, a));
        float frac = Math.min(1f, Math.abs(energy) / 100f);
        if (frac > 0.005f) {
            if (energy >= 0) rounded(ctx, rx + rw / 2f, barY, rw / 2f * frac, 6f, 3f, c(PRIMARY, a), ox, oy, s);
            else rounded(ctx, rx + rw / 2f - rw / 2f * frac, barY, rw / 2f * frac, 6f, 3f, c(DESTR, a), ox, oy, s);
        }

        // full-width smash + envelope rows at measured y
        float smashY = 225.4f;
        text(ctx, "砸落窗口", PAD, smashY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        float smashLabelW = HudFonts.width("砸落窗口", 11f, HudFonts.REGULAR) + 8f;
        boolean ready = smash >= 0.999f;
        float smashValueW = ready ? 38f : 30f;
        float smashBarW = CONTENT_W - smashLabelW - smashValueW - 6f;
        float smashFrac = Math.max(0f, Math.min(1f, smash));
        rounded(ctx, PAD + smashLabelW, 231.25f, smashBarW, 4f, 2f, c(TRACK, a), ox, oy, s);
        rounded(ctx, PAD + smashLabelW, 231.25f, smashBarW * smashFrac, 4f, 2f, c(ready ? PRIMARY : 0x99A1A1A1, a), ox, oy, s);
        if (ready) {
            badge(ctx, ox + px(W - PAD - 36f, s), oy + px(smashY - 3f, s), px(36f, s), px(18f, s),
                    "READY", 10f, 0xE6E5E5E5, DARK, s, a, true, false);
        } else {
            textRight(ctx, Math.round(smashFrac * 100) + "%", W - PAD, smashY, 10f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        }

        float envY = 247.1f;
        text(ctx, "包络钳制", PAD, envY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        float envLabelW = HudFonts.width("包络钳制", 11f, HudFonts.REGULAR) + 8f;
        String envText = String.format("请求 %s → 实际 %s",
                Float.isNaN(envReq) ? "—" : String.format("%.1f", envReq),
                Float.isNaN(envApl) ? "—" : String.format("%.1f", envApl));
        float envValueW = HudFonts.width(envText, 10f, HudFonts.REGULAR) + 8f;
        float envBarW = CONTENT_W - envLabelW - envValueW - 6f;
        rounded(ctx, PAD + envLabelW, 252.95f, envBarW, 4f, 2f, c(TRACK, a), ox, oy, s);
        if (!Float.isNaN(envReq)) {
            rounded(ctx, PAD + envLabelW, 252.95f, envBarW * Math.min(1f, envReq / 8f), 4f, 2f, c(0x59A1A1A1, a), ox, oy, s);
        }
        if (!Float.isNaN(envApl)) {
            rounded(ctx, PAD + envLabelW, 252.95f, envBarW * Math.min(1f, envApl / 8f), 4f, 2f, c(PRIMARY, a), ox, oy, s);
        }
        textRight(ctx, envText, W - PAD, envY, 10f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);

        // separator + hp/armor row + footer at measured y
        ctx.fill(ox + px(PAD, s), oy + px(276.8f, s), ox + px(W - PAD, s), oy + px(277.8f, s), c(BORDER, a));

        icon(ctx, ICON_HEART, PAD, 292.8f, c(MUTED_FG, a), ox, oy, s);
        float hpFrac = Math.max(0f, Math.min(1f, displayedHp / sMaxHp));
        float ghostFrac = Math.max(0f, Math.min(1f, ghostHp / sMaxHp));
        rounded(ctx, 40f, 296.8f, 93f, 6f, 3f, c(TRACK, a), ox, oy, s);
        rounded(ctx, 40f, 296.8f, 93f * ghostFrac, 6f, 3f, c(GHOST, a), ox, oy, s);
        rounded(ctx, 40f, 296.8f, 93f * hpFrac, 6f, 3f, c(DESTR, a), ox, oy, s);
        textRight(ctx, String.format("%.1f", displayedHp), 183f, 292.8f, 12f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        icon(ctx, ICON_SHIELD, 197f, 292.8f, c(MUTED_FG, a), ox, oy, s);
        rounded(ctx, 221f, 296.8f, 91f, 6f, 3f, c(TRACK, a), ox, oy, s);
        rounded(ctx, 221f, 296.8f, 91f * Math.max(0f, Math.min(1f, sArmor / 20f)), 6f, 3f, c(PRIMARY, a), ox, oy, s);
        textRight(ctx, String.valueOf((int) Math.ceil(sArmor)), 364f, 292.8f, 12f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);

        // footer y319.81 with inline icons at y321.66
        String speeds = String.format("敌速 %.1f · 我速 %.1f", tgtVel, myVel);
        String totemsText = "×" + sTotems;
        String clockText = clock(sElapsedMs / 1000L);
        float wS = HudFonts.width(speeds, 11f, HudFonts.REGULAR);
        float wT = HudFonts.width(totemsText, 11f, HudFonts.REGULAR);
        float wP = HudFonts.width(sPing, 11f, HudFonts.REGULAR);
        float wC = HudFonts.width(clockText, 11f, HudFonts.REGULAR);
        float iconSlot = 12f;
        float total = iconSlot + wS + iconSlot + wT + wP + iconSlot + wC;
        float gap = Math.max(4f, (CONTENT_W - total) / 3f);
        float fx = PAD;
        icon(ctx, ICON_WIND, fx, 321.7f, c(MUTED_FG, a), ox, oy, s);
        text(ctx, speeds, fx + iconSlot, 319.8f, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        fx += iconSlot + wS + gap;
        icon(ctx, ICON_SPARKLE, fx, 321.7f, c(MUTED_FG, a), ox, oy, s);
        text(ctx, totemsText, fx + iconSlot, 319.8f, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        fx += iconSlot + wT + gap;
        text(ctx, sPing, fx, 319.8f, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        fx += wP + gap;
        icon(ctx, ICON_TIMER, fx, 321.7f, c(MUTED_FG, a), ox, oy, s);
        text(ctx, clockText, fx + iconSlot, 319.8f, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
    }

    // ---------------------------------------------------------------- flight layer

    private void drawFlight(GuiGraphics ctx, int x0, int y0, float a, float slide, float s) {
        int ox = x0, oy = y0 + px(slide, s);
        cardAt(ctx, ox, oy, FLIGHT_H, s, a);

        GuangxiElytraModule flight = flightModule();
        boolean gliding = mc.player.isFallFlying();
        boolean armorFly = flight != null && flight.armorFlyRequested();
        boolean rocket = flight != null && flight.isEnabled() && flight.isBoostAttached();

        text(ctx, "FLIGHT · 巡航层", PAD, TOP + 3f, 13f, HudFonts.SEMI, c(MUTED_FG, a), ox, oy, s);
        String b1Text = armorFly ? "甲飞" : gliding ? "滑翔" : "地面";
        String b2Text = rocket ? "烟花附着" : "烟花待命";
        float b2W = HudFonts.width(b2Text, 11f, HudFonts.SEMI) + 32f;
        float b1W = HudFonts.width(b1Text, 11f, HudFonts.SEMI) + 32f;
        iconBadge(ctx, ox + px(W - PAD - b2W, s), oy + px(TOP, s), px(b2W, s), px(20f, s),
                b2Text, ICON_FLAME, TRACK, rocket ? FG : MUTED_FG, s, a, rocket);
        iconBadge(ctx, ox + px(W - PAD - b2W - 6f - b1W, s), oy + px(TOP, s), px(b1W, s), px(20f, s),
                b1Text, ICON_FEATHER, TRACK, FG, s, a, armorFly || gliding);

        // heading tape: 348 wide, 61.23 tall, 15° marks, cardinals labelled
        float tapeY = 64f;
        float tapeH = 61.23f;
        rounded(ctx, PAD, tapeY, CONTENT_W, tapeH, 8f, c(0x6626262B, a), ox, oy, s);
        float pxPerDeg = CONTENT_W / 120f;
        int start = (int) Math.floor((heading - 60f) / 15f) * 15;
        for (int d = start; d <= heading + 60f; d += 15) {
            float dx = (d - heading) * pxPerDeg + PAD + CONTENT_W / 2f;
            if (dx < PAD + 8f || dx > PAD + CONTENT_W - 8f) continue;
            int norm = Math.floorMod(d, 360);
            String label = norm == 0 ? "N" : norm == 90 ? "E" : norm == 180 ? "S" : norm == 270 ? "W" : null;
            float tickTop = label != null ? tapeY + 11f : tapeY + 19f;
            ctx.fill(ox + px(dx, s), oy + px(tickTop, s), ox + px(dx + 1f, s), oy + px(tapeY + 31f, s), c(MUTED_FG, a));
            if (label != null) {
                text(ctx, label, dx - HudFonts.width(label, 11f, HudFonts.REGULAR) / 2f, tapeY + 35f, 11f,
                        HudFonts.REGULAR, c(FG, a), ox, oy, s);
            }
        }
        float bearingDx = Math.max(-58f, Math.min(58f, bearing));
        diamond(ctx, ox + px(PAD + CONTENT_W / 2f + bearingDx * pxPerDeg, s), oy + px(tapeY + 7f, s), c(DESTR, a), s);
        // own-heading pointer: an up triangle, not a rectangle
        int pxC = ox + px(PAD + CONTENT_W / 2f, s);
        int pyT = oy + px(tapeY + 5.6f, s);
        for (int r = 0; r < 9; r++) {
            int half = px(r * 5.6f / 8f, s);
            ctx.fill(pxC - half, pyT + px(r, s), pxC + half + 1, pyT + px(r + 1, s), c(PRIMARY, a));
        }

        // airspeed + vertical/altitude at measured y (both columns right-aligned to 364)
        float rowY = 137.2f;
        icon(ctx, ICON_WIND, PAD, rowY, c(MUTED_FG, a), ox, oy, s);
        text(ctx, "空速", PAD + 13f, rowY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        String bigSpeed = String.format("%.1f", myVel);
        text(ctx, bigSpeed, PAD, 151.2f, 26f, HudFonts.BOLD, c(FG, a), ox, oy, s);
        float speedSuffixX = PAD + HudFonts.width(bigSpeed, 26f, HudFonts.BOLD) + 5f;
        text(ctx, String.format("b/t · %.0f m/s", myVel * 20f), speedSuffixX, 166.2f, 12f, HudFonts.SEMI, c(MUTED_FG, a), ox, oy, s);
        icon(ctx, vspeed >= 0 ? ICON_ARROW_UP : ICON_ARROW_DOWN, W - PAD - HudFonts.width(String.format("Y %d", Math.round(mc.player.getY())), 26f, HudFonts.BOLD) - 16f, rowY + 1f, c(MUTED_FG, a), ox, oy, s);
        textRight(ctx, String.format("垂直 %+.1f b/t", vspeed), W - PAD, rowY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        textRight(ctx, String.format("Y %d", Math.round(mc.player.getY())), W - PAD, 151.2f, 26f, HudFonts.BOLD, c(FG, a), ox, oy, s);

        // energy reserve at measured y
        float enY = 201.9f;
        text(ctx, "能量储备 ENERGY", PAD, enY, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
        textRight(ctx, String.format("%+d", Math.round(flightEnergy)), W - PAD, enY - 2f, 14f, HudFonts.SEMI, c(PRIMARY, a), ox, oy, s);
        rounded(ctx, PAD, 221.9f, CONTENT_W, 6f, 3f, c(TRACK, a), ox, oy, s);
        rounded(ctx, PAD, 221.9f, CONTENT_W * Math.max(0f, Math.min(1f, (50f + flightEnergy / 2f) / 100f)), 6f, 3f, c(PRIMARY, a), ox, oy, s);

        // unlocked-layout footer sits right after energy (real DOM behavior, y239.94)
        text(ctx, "未锁定目标 — 显示我方飞行状态；锁定后切换为目标层", PAD, 239.9f, 11f, HudFonts.REGULAR, c(MUTED_FG, a), ox, oy, s);
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
        int inset = Math.max(1, Math.round(s));
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
            pixelFace(ctx, x + inset, y + inset, inner, a);
        }
    }

    private Identifier faceTexture() {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || trackedId == null) return null;
        PlayerInfo info = conn.getPlayerInfo(trackedId);
        if (info == null) return null;
        try {
            var body = info.getSkin().body();
            return body == null ? null : body.texturePath();
        } catch (Throwable unavailable) {
            return null; // skin not resolved yet — pixel face stands in
        }
    }

    /** Deterministic placeholder face derived from the entity id. */
    private void pixelFace(GuiGraphics ctx, int x, int y, int size, float a) {
        int[] tones = {0xFFB58968, 0xFF96634A, 0xFF8D6E63, 0xFFA1887F};
        int[] hairs = {0xFF4A2F1E, 0xFF2E2A28, 0xFF5D4037, 0xFF6D4C41};
        int h = trackedId == null ? 0 : trackedId.hashCode();
        int u = Math.max(1, size / 8);
        int w = u * 8;
        ctx.fill(x, y, x + w, y + 2 * u, c(hairs[(h >>> 8) & 3], a));
        ctx.fill(x, y + 2 * u, x + w, y + 8 * u, c(tones[(h >>> 3) & 3], a));
        ctx.fill(x + u, y + 3 * u, x + 2 * u, y + 4 * u, c(0xFF3B2A6B, a));
        ctx.fill(x + 6 * u, y + 3 * u, x + 7 * u, y + 4 * u, c(0xFF3B2A6B, a));
        ctx.fill(x + 3 * u, y + 5 * u, x + 5 * u, y + 6 * u, c(0xFF7A4F35, a));
    }

    private void badge(GuiGraphics ctx, int x, int y, int w, int h, String label, float sizePx,
                       int bg, int fg, float s, float a, boolean filled, boolean withDot) {
        int r = Math.max(2, h / 2);
        if (filled) {
            roundedAbs(ctx, x, y, w, h, r, c(bg, a));
        } else {
            roundedAbs(ctx, x, y, w, h, r, c(BORDER, a));
            roundedAbs(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), c(CARD, a));
        }
        float size = sizePx * s;
        float tw = HudFonts.width(label, sizePx, HudFonts.SEMI) * s;
        int dotGap = Math.round(12f * s);
        int dot = Math.max(2, Math.round(6f * s));
        int tx = x + (int) ((w - tw - (withDot ? dotGap : 0)) / 2f);
        int ty = y + (int) ((h - size) / 2f) - Math.round(s);
        if (withDot) {
            // status dot with a soft pulse
            ctx.fill(tx, y + h / 2 - dot / 2, tx + dot, y + h / 2 - dot / 2 + dot, c(fg, a * pulseA));
            tx += dotGap;
        }
        HudFonts.draw(ctx, label, Math.max(tx, x + 2), Math.max(ty, y + 1), size, HudFonts.SEMI, c(fg, a));
    }

    private void text(GuiGraphics ctx, String str, float dx, float dy, float sizePx, int weight, int color, int ox, int oy, float s) {
        HudFonts.draw(ctx, str, ox + dx * s, oy + dy * s, sizePx * s, weight, color);
    }

    private void textRight(GuiGraphics ctx, String str, float dxRight, float dy, float sizePx, int weight, int color, int ox, int oy, float s) {
        float w = HudFonts.width(str, sizePx, weight);
        text(ctx, str, dxRight - w, dy, sizePx, weight, color, ox, oy, s);
    }

    /** Truncate with an ellipsis so the header never paints under the badges. */
    private static String clip(String text, float maxWidth, float sizePx, int weight) {
        if (HudFonts.width(text, sizePx, weight) <= maxWidth) return text;
        String ellipsis = "…";
        float ellW = HudFonts.width(ellipsis, sizePx, weight);
        String trimmed = text;
        while (trimmed.length() > 1 && HudFonts.width(trimmed + ellipsis, sizePx, weight) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ellipsis;
    }

    private void icon(GuiGraphics ctx, int index, float dx, float dy, int color, int ox, int oy, float s) {
        if ((color >>> 24) == 0) return;
        float size = index == ICON_HEART || index == ICON_SHIELD ? 14f : 12f;
        float factor = size * s / 96f;
        var pose = ctx.pose();
        pose.pushMatrix();
        pose.scale(factor, factor);
        ctx.blit(RenderPipelines.GUI_TEXTURED, ICON_ATLAS,
                Math.round((ox + dx * s) / factor), Math.round((oy + dy * s) / factor),
                (float) (index * 96), 0f, 96, 96, 864, 96, color);
        pose.popMatrix();
    }

    private void iconBadge(GuiGraphics ctx, int x, int y, int w, int h, String label,
                           int icon, int bg, int fg, float s, float a, boolean filled) {
        badge(ctx, x, y, w, h, "", 11f, bg, fg, s, a, filled, false);
        icon(ctx, icon, 8f, 4f, c(fg, a), x, y, s);
        HudFonts.draw(ctx, label, x + 24f * s, y + 4f * s, 11f * s, HudFonts.SEMI, c(fg, a));
    }

    private void rounded(GuiGraphics ctx, float dx, float dy, float dw, float dh, float r, int color, int ox, int oy, float s) {
        roundedAbs(ctx, ox + Math.round(dx * s), oy + Math.round(dy * s),
                Math.round(dw * s), Math.round(dh * s), Math.round(r * s), color);
    }

    private void roundedAbs(GuiGraphics ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        int rr = Math.max(0, Math.min(Math.min(w, h) / 2, r));
        if (h > 2 * rr) ctx.fill(x, y + rr, x + w, y + h - rr, color);
        for (int row = 0; row < h; row++) {
            if (row >= rr && row < h - rr) continue;
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

    private void diamond(GuiGraphics ctx, int cx, int cy, int color, float s) {
        int span = Math.max(2, Math.round(5f * s));
        for (int i = 0; i < span; i++) {
            int half = i < (span + 1) / 2 ? i : span - 1 - i;
            if (half < 0) continue;
            ctx.fill(cx - half, cy + i - span / 2, cx + half + 1, cy + i - span / 2 + 1, color);
        }
    }

    /** Filled contact triangle pointing up pre-rotation, design-scaled. */
    private void triangle(GuiGraphics ctx, int cx, int cy, float rotDeg, int color, float s) {
        double rad = Math.toRadians(rotDeg);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double[][] v = {{0, -5.2}, {-2.9, 2.7}, {2.9, 2.7}};
        int[] xs = new int[3], ys = new int[3];
        for (int i = 0; i < 3; i++) {
            xs[i] = cx + (int) Math.round((v[i][0] * cos - v[i][1] * sin) * s);
            ys[i] = cy + (int) Math.round((v[i][0] * sin + v[i][1] * cos) * s);
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

    // ---------------------------------------------------------------- helpers

    private float canvasScale() {
        float w = screenWidth(), h = screenHeight();
        return Math.max(0.05f, Math.min(204f / W, Math.min((w - 16f) / W, (h - 24f) / TARGET_H)));
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
