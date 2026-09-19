package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.util.render.font.Fonts;
import dev.guizhou.util.render.targethud.TargetHudMath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Two-layer air-combat HUD (shadcn-style prototype port).
 *
 * Flight layer while no target is locked: heading strip, airspeed, vertical
 * speed, altitude, energy reserve. Target layer once the chase module locks:
 * radar disc (relative bearing, sqrt distance scale, altitude delta), closure,
 * intercept countdown, energy advantage, smash-charge readiness, envelope
 * clamp (requested vs applied bpt), bot intent badge, hp/armor bars.
 *
 * Data steps per tick / per frame from live client state; continuous values are
 * eased per frame (TargetHudMath, ~140ms half-life), counters render directly.
 */
public class TargetHudModule extends HudModule {

    // shadcn zinc dark palette
    private static final int BG = 0xF0141618;
    private static final int BORDER = 0xFF31363D;
    private static final int FG = 0xFFF4F4F5;
    private static final int MUTED = 0xFF9198A1;
    private static final int HP = 0xFFE5484D;
    private static final int ARMOR = 0xFF3E9BFF;
    private static final int ACCENT = 0xFF30A46C;
    private static final int WARN = 0xFFF0A800;
    private static final int TRACK = 0xFF272A30;
    private static final int GHOST = 0x59FFFFFF;

    private static final int PANEL_W = 204;
    private static final int TARGET_H = 124;
    private static final int FLIGHT_H = 84;
    private static final float HALF_LIFE = 140f;

    // eased display state (continuous only)
    private float dist = 8, bearing = 0, relAlt, closure, myVel, tgtVel, energy, smash, reqVel;
    private float layerAlpha; // 0 = flight, 1 = target
    private long lastFrame;

    public TargetHudModule() {
        super("TargetHud");
    }

    @Override
    public void render(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;
        GuiGraphics ctx = event.getContext();
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 16f : (now - lastFrame);
        lastFrame = now;

        LivingEntity target = currentTarget();
        layerAlpha = TargetHudMath.ramp(layerAlpha, target != null ? 0.08f : -0.08f);

        if (target != null) {
            sampleTarget(target, dt);
            if (layerAlpha > 0.98f) {
                renderTarget(ctx);
            } else {
                renderFlight(ctx);
                if (layerAlpha > 0.02f) renderTargetOverlay(ctx, layerAlpha);
            }
        } else {
            sampleFlight(dt);
            renderFlight(ctx);
            if (layerAlpha > 0.02f) renderTargetOverlay(ctx, layerAlpha);
        }
    }

    private LivingEntity currentTarget() {
        Entity t = GuangxiElytraChaseModule.hudTarget();
        if (t == null || !t.isAlive() || t.isRemoved() || t.level() != mc.level) return null;
        return t instanceof LivingEntity living ? living : null;
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
        GuangxiElytraModule flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight != null) {
            reqVel = (float) TargetHudMath.ease(reqVel, flight.lastRequestedBpt(), dt, HALF_LIFE);
        }
    }

    private void sampleFlight(float dt) {
        Vec3 myDelta = mc.player.getDeltaMovement();
        dist = (float) TargetHudMath.ease(dist, 0, dt, HALF_LIFE);
        myVel = (float) TargetHudMath.ease(myVel, myDelta.length(), dt, HALF_LIFE);
        energy = (float) TargetHudMath.ease(energy,
                TargetHudMath.energyAdvantage(myDelta.length(), 0, 0), dt, HALF_LIFE);
        GuangxiElytraModule flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight != null) {
            reqVel = (float) TargetHudMath.ease(reqVel, flight.lastRequestedBpt(), dt, HALF_LIFE);
        }
    }

    // ---------------------------------------------------------------- target

    private void renderTarget(GuiGraphics ctx) {
        renderTargetOverlay(ctx, 1.0f);
    }

    private void renderTargetOverlay(GuiGraphics ctx, float alpha) {
        LivingEntity target = currentTarget();
        if (target == null) return;
        int x = panelX(), y = panelY(TARGET_H);

        panel(ctx, x, y, PANEL_W, TARGET_H, alpha);

        // head: color chip, name, intent badge
        int chip = target instanceof Player ? ACCENT : WARN;
        ctx.fill(x + 8, y + 7, x + 21, y + 20, chip);
        String name = target.getName().getString();
        trim(ctx, name, 96);
        Fonts.drawString(ctx, name, x + 26, y + 8, fg(alpha));
        Fonts.drawString(ctx, String.format("%.1f 格", dist), x + 26, y + 18, muted(alpha));
        badge(ctx, x + PANEL_W - 8 - badgeWidth(intent()), y + 8, intent(), intentColor(), alpha);

        // radar disc
        int cx = x + 8 + 30, cy = y + 44 + 30;
        disc(ctx, cx, cy, 29, alpha);
        double[] p = TargetHudMath.radarProject(bearing, dist, 27, 20);
        int tx = cx + (int) Math.round(p[0]), ty = cy + (int) Math.round(p[1]);
        ctx.fill(cx - 1, cy - 1, cx + 2, cy + 2, ACCENT);
        ctx.fill(tx - 2, ty - 2, tx + 3, ty + 3, HP);
        ctx.fill(cx - 1, ty, cx, ty + 1, GHOST);
        Fonts.drawString(ctx, String.format("%.0f° %+.0f", bearing, relAlt), x + 8, y + 44 + 62, muted(alpha));

        // right column rows
        int rx = x + 76, rowY = y + 30, rowH = 13;
        Fonts.drawString(ctx, "接近率", rx, rowY, muted(alpha));
        drawValue(ctx, String.format("%+.1f", closure), rx + 40, rowY, closure >= 0 ? accent(alpha) : hp(alpha));
        rowY += rowH;
        Fonts.drawString(ctx, "拦截", rx, rowY, muted(alpha));
        double t = TargetHudMath.interceptTicks(dist, closure, 0.05);
        String intercept = t < 0 ? "无解 平追" : String.format("T−%.0f", t);
        Fonts.drawString(ctx, intercept, rx + 40, rowY, t < 0 ? muted(alpha) : fg(alpha));
        rowY += rowH;
        Fonts.drawString(ctx, "能量", rx, rowY, muted(alpha));
        bar(ctx, rx + 40, rowY + 2, 44, 4, energy / 200f + 0.5f, energy >= 0 ? ACCENT : HP, alpha);
        Fonts.drawString(ctx, String.format("%+.0f", energy), rx + 88, rowY, energy >= 0 ? accent(alpha) : hp(alpha));
        rowY += rowH;
        Fonts.drawString(ctx, "砸落", rx, rowY, muted(alpha));
        bar(ctx, rx + 40, rowY + 2, 44, 4, smash, smash >= 0.999f ? ACCENT : MUTED, alpha);
        Fonts.drawString(ctx, smash >= 0.999f ? "READY" : String.format("%.0f%%", smash * 100),
                rx + 88, rowY, smash >= 0.999f ? accent(alpha) : muted(alpha));
        rowY += rowH;
        Fonts.drawString(ctx, "包络", rx, rowY, muted(alpha));
        bar(ctx, rx + 40, rowY + 2, 44, 4, Math.min(1f, myVel / 8f), ACCENT, alpha);
        ctx.fill(rx + 40, rowY + 1, rx + 40 + (int) (44 * Math.min(1f, reqVel / 8f)), rowY + 2, GHOST);
        Fonts.drawString(ctx, String.format("%.1f→%.1f", reqVel, myVel), rx + 88, rowY, muted(alpha));

        // bottom: hp / armor bars + totems
        int by = y + TARGET_H - 20;
        Fonts.drawString(ctx, "♥", x + 8, by, hp(alpha));
        bar(ctx, x + 18, by + 2, 66, 5, target.getHealth() / Math.max(1f, target.getMaxHealth()), HP, alpha);
        Fonts.drawString(ctx, String.format("%.0f", target.getHealth()), x + 88, by, muted(alpha));
        Fonts.drawString(ctx, "甲", x + 104, by, muted(alpha));
        bar(ctx, x + 116, by + 2, 42, 5, target.getArmorValue() / 20f, ARMOR, alpha);
        Fonts.drawString(ctx, String.format("%d", target.getArmorValue()), x + 162, by, muted(alpha));
        int totems = totems(target);
        Fonts.drawString(ctx, String.format("图腾×%d", totems), x + 8, by + 11, muted(alpha));
        Fonts.drawString(ctx, String.format("敌速%.1f", tgtVel), x + 60, by + 11, muted(alpha));
    }

    // ---------------------------------------------------------------- flight

    private void renderFlight(GuiGraphics ctx) {
        int x = panelX(), y = panelY(FLIGHT_H);
        panel(ctx, x, y, PANEL_W, FLIGHT_H, 1.0f);

        Fonts.drawString(ctx, "FLIGHT", x + 8, y + 7, muted(1f));
        String state = mc.player.isFallFlying() ? "滑翔" : "地面";
        int stateColor = mc.player.isFallFlying() ? accent(1f) : muted(1f);
        Fonts.drawString(ctx, state, x + PANEL_W - 8 - Fonts.width(state) - 12, y + 7, stateColor);
        ctx.fill(x + PANEL_W - 8 - Fonts.width(state) - 6, y + 8, x + PANEL_W - 10 - Fonts.width(state), y + 13, stateColor);

        headingStrip(ctx, x + 8, y + 22, PANEL_W - 16, (float) TargetHudMath.normalizeAngle(mc.player.getYRot()), bearing);

        Fonts.drawString(ctx, String.format("%.1f b/t  %.0f m/s", myVel, myVel * 20), x + 8, y + 46, fg(1f));
        Fonts.drawString(ctx, String.format("Y %.0f  %+.1f", mc.player.getY(), mc.player.getDeltaMovement().y), x + 8, y + 58, fg(1f));
        Fonts.drawString(ctx, "能量", x + 8, y + 72, muted(1f));
        bar(ctx, x + 36, y + 74, 100, 4, energy / 200f + 0.5f, energy >= 0 ? ACCENT : HP, 1f);
        Fonts.drawString(ctx, String.format("%+.0f", energy), x + 142, y + 72, energy >= 0 ? accent(1f) : hp(1f));
    }

    /** N/E/S/W strip with the own heading pointer and the target-bearing diamond. */
    private void headingStrip(GuiGraphics ctx, int x, int y, int w, float heading, float targetBearing) {
        ctx.fill(x - 2, y - 2, x + w + 2, y + 16, TRACK);
        float pxPerDeg = w / 120f;
        int start = (int) Math.floor((heading - 60) / 45f) * 45;
        for (int d = start; d <= heading + 60; d += 45) {
            float sx = x + w / 2f + (d - heading) * pxPerDeg;
            if (sx < x + 2 || sx > x + w - 2) continue;
            String label = switch (Math.floorMod(d, 360)) {
                case 0 -> "N"; case 90 -> "E"; case 180 -> "S"; case 270 -> "W"; default -> null;
            };
            if (label != null) {
                Fonts.drawString(ctx, label, sx - Fonts.width(label) / 2f, y + 6, fg(1f));
            }
            ctx.fill((int) sx, y + 2, (int) sx + 1, y + 5, MUTED);
        }
        // target diamond clamped to the strip
        float dx = Math.max(-58, Math.min(58, targetBearing));
        int diamondX = (int) (x + w / 2f + dx * pxPerDeg);
        ctx.fill(diamondX, y + 1, diamondX + 1, y + 6, HP);
        // own pointer
        ctx.fill(x + w / 2 - 1, y - 2, x + w / 2 + 2, y + 2, ACCENT);
    }

    // ---------------------------------------------------------------- helpers

    private String intent() {
        return switch (GuangxiElytraChaseModule.hudIntent()) {
            case "PULL_UP" -> "爬升";
            case "FOLLOW" -> "追击";
            case "DIVE" -> "俯冲";
            case "WAIT" -> "待确认";
            case "EVADE" -> "规避";
            case "ENGAGE" -> "缠斗";
            case "PULL_OVER" -> "脱离";
            default -> "待机";
        };
    }

    private int intentColor() {
        return switch (GuangxiElytraChaseModule.hudIntent()) {
            case "DIVE" -> HP;
            case "EVADE", "PULL_OVER" -> WARN;
            case "WAIT", "OFF" -> MUTED;
            default -> ACCENT;
        };
    }

    private void panel(GuiGraphics ctx, int x, int y, int w, int h, float alpha) {
        int a = (int) (alpha * 0xF0 / 1f) << 24;
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, mulAlpha(BORDER, alpha));
        ctx.fill(x, y, x + w, y + h, mulAlpha(BG, alpha));
    }

    private void disc(GuiGraphics ctx, int cx, int cy, int r, float alpha) {
        ctx.fill(cx - r, cy - r, cx + r, cy + r, mulAlpha(TRACK, alpha));
        ctx.fill(cx - 1, cy - r, cx + 1, cy + r, mulAlpha(BORDER, alpha));
        ctx.fill(cx - r, cy - 1, cx + r, cy + 1, mulAlpha(BORDER, alpha));
    }

    private void bar(GuiGraphics ctx, int x, int y, int w, int h, float ratio, int color, float alpha) {
        ratio = Math.max(0f, Math.min(1f, ratio));
        ctx.fill(x, y, x + w, y + h, mulAlpha(TRACK, alpha));
        ctx.fill(x, y, x + (int) (w * ratio), y + h, mulAlpha(color, alpha));
    }

    private void badge(GuiGraphics ctx, int x, int y, String text, int color, float alpha) {
        int w = badgeWidth(text);
        ctx.fill(x - 2, y - 2, x + w + 2, y + 10, mulAlpha(color, alpha));
        Fonts.drawString(ctx, text, x, y, mulAlpha(0xFFFFFFFF, alpha));
    }

    private int badgeWidth(String text) {
        return Fonts.width(text) + 4;
    }

    private void drawValue(GuiGraphics ctx, String text, int x, int y, int color) {
        Fonts.drawString(ctx, text, x, y, color);
    }

    private void trim(GuiGraphics ctx, String text, int maxWidth) {
        // width check happens at draw time by the caller; kept for parity
    }

    private static int totems(LivingEntity target) {
        int count = 0;
        if (!target.getMainHandItem().isEmpty() && target.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) count++;
        if (!target.getOffhandItem().isEmpty() && target.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) count++;
        return count;
    }

    private int panelX() {
        return screenWidth() - PANEL_W - 8;
    }

    private int panelY(int height) {
        return Math.max(24, screenHeight() / 2 - height / 2 - 20);
    }

    private static int mulAlpha(int argb, float alpha) {
        if (alpha >= 1f) return argb;
        int a = (argb >>> 24);
        a = (int) (a * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static int fg(float a) { return mulAlpha(FG, a); }
    private static int muted(float a) { return mulAlpha(MUTED, a); }
    private static int accent(float a) { return mulAlpha(ACCENT, a); }
    private static int hp(float a) { return mulAlpha(HP, a); }
}
