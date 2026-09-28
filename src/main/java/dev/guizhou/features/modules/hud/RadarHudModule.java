package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.features.modules.render.NametagsModule;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import dev.guizhou.util.render.GuiTextureCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Nearby-player list: header with count, then one row per player (farthest
 * first so the nearest sits next to the bottom anchor, you last) with a
 * relation dot, name, totem-pop pill and distance.
 */
public class RadarHudModule extends HudModule {
    private static final int DEFAULT_POP_COLOR = 0xFFE08A6C;
    private static final float NAME = 8.5f, META = 7f, ROW = 12f, PAD = 6f, HEADER = 14f;
    private static final float LOGO = 8f;

    private static final ResourceLocation LOGO_TEX = ResourceLocation.fromNamespaceAndPath("guizhouware", "textures/font/logo_white.png");
    private static final int LOGO_TEX_W = 128;
    private static final int LOGO_TEX_H = 128;
    private static final String[] DISTANCES = new String[1000];

    private final List<Entry> entries = new ArrayList<>();

    public RadarHudModule() {
        super("Radar");
    }

    public int renderedLineCount() {
        if (mc.player == null || mc.level == null) return 0;
        int count = 1;
        UUID selfId = mc.player.getUUID();
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(selfId)) continue;
            if (!player.isAlive()) continue;
            count++;
        }
        return count;
    }

    @Override
    public void render(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;

        entries.clear();
        UUID selfId = mc.player.getUUID();
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(selfId)) continue;
            if (!player.isAlive()) continue;
            entries.add(new Entry(player, mc.player.position().distanceTo(player.position())));
        }
        entries.sort(Comparator.comparingDouble((Entry e) -> e.dist).reversed());
        int others = entries.size();
        entries.add(new Entry(mc.player, 0.0));

        GuiGraphics ctx = event.getContext();
        HudClientModule hudClient = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        int popColor = resolvePopColor();

        float rowW = 0f;
        for (Entry e : entries) {
            int pops = GuizhouWare.playerInfoManager.getTotemPops(e.player.getUUID());
            float w = 7f + ShadcnDraw.width(e.player.getGameProfile().getName(), NAME, HudFonts.SEMI);
            if (pops > 0) w += 4f + ShadcnDraw.width("-" + pops, META, HudFonts.SEMI) + 8f;
            if (e.player != mc.player) w += 8f + ShadcnDraw.width(distance(e.dist), META);
            rowW = Math.max(rowW, w);
        }
        String count = others == 0 ? "仅自己" : others + " 人";
        float headW = ShadcnDraw.width("附近玩家", META, HudFonts.SEMI) + 10f + ShadcnDraw.width(count, META);
        float cardW = PAD * 2 + Math.max(rowW, headW);
        float cardH = HEADER + 3f + entries.size() * ROW + 3f;

        int below = hudClient != null ? hudClient.stackBelowHeight(this) : 0;
        int defX = Math.round(screenWidth() - cardW - 4f);
        int defY = Math.round(bottomAnchor() - 4f - below - cardH);
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        HudDraw.card(ctx, ox, oy, cardW, cardH, HudDraw.RADIUS);
        float hm = oy + HEADER / 2f + 1.5f;
        HudDraw.textMid(ctx, "附近玩家", ox + PAD, hm, META, HudFonts.SEMI, ShadcnDraw.MUTED);
        HudDraw.textMidRight(ctx, count, ox + cardW - PAD, hm, META, HudFonts.REGULAR, ShadcnDraw.SUBTLE);
        HudDraw.rule(ctx, ox + PAD, oy + HEADER + 1f, cardW - PAD * 2);

        float firstMid = oy + HEADER + 3f + ROW / 2f;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            float mid = firstMid + i * ROW;
            boolean self = entry.player == mc.player;
            float tx = ox + PAD;
            if (self) {
                GuiTextureCompat.blit(ctx, LOGO_TEX, Math.round(tx - 1.5f), Math.round(mid - LOGO / 2f),
                        0.0f, 0.0f, (int) LOGO, (int) LOGO, LOGO_TEX_W, LOGO_TEX_H, HudDraw.accent());
            } else {
                HudDraw.dot(ctx, tx + 2f, mid, 4f, relationTone(entry.player));
            }
            tx += 7f;
            String name = entry.player.getGameProfile().getName();
            HudDraw.textMid(ctx, name, tx, mid, NAME, HudFonts.SEMI, resolveNameColor(hudClient, entry.player));
            tx += ShadcnDraw.width(name, NAME, HudFonts.SEMI) + 4f;
            int pops = GuizhouWare.playerInfoManager.getTotemPops(entry.player.getUUID());
            if (pops > 0) HudDraw.pill(ctx, "-" + pops, tx, mid - 4f, 8f, META, popColor);
            if (!self) {
                HudDraw.textMidRight(ctx, distance(entry.dist), ox + cardW - PAD, mid, META, HudFonts.REGULAR,
                        entry.dist <= 6 ? ShadcnDraw.WARNING : ShadcnDraw.MUTED);
            }
        }
        bounds(ox, oy, (int) cardW, (int) cardH);
    }

    private static String distance(double d) {
        int m = (int) Math.min(999, Math.max(0, Math.round(d)));
        String s = DISTANCES[m];
        if (s == null) DISTANCES[m] = s = m + "m";
        return s;
    }

    private int relationTone(Player player) {
        if (GuizhouWare.enemyManager != null && GuizhouWare.enemyManager.isEnemy(player)) return ShadcnDraw.DANGER;
        if (GuizhouWare.friendManager.isFriend(player)) return ShadcnDraw.SUCCESS;
        return ShadcnDraw.SUBTLE;
    }

    private int resolveNameColor(HudClientModule hudClient, Player player) {
        if (hudClient == null) return ShadcnDraw.TEXT;
        if (player == mc.player) return hudClient.radarSelfColor.getValue().getRGB();
        if (GuizhouWare.friendManager.isFriend(player)) return hudClient.radarFriendColor.getValue().getRGB();
        int rgb = hudClient.radarEnemyColor.getValue().getRGB() & 0xFFFFFF;
        return 0xFF000000 | rgb;
    }

    private int resolvePopColor() {
        NametagsModule nametags = GuizhouWare.moduleManager.getModuleByClass(NametagsModule.class);
        if (nametags == null || nametags.popColor.getValue() == null) return DEFAULT_POP_COLOR;
        return 0xFF000000 | (nametags.popColor.getValue().getRGB() & 0xFFFFFF);
    }

    private record Entry(Player player, double dist) {}
}
