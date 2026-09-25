package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.GuiBlitCompat;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Compact nearby-player table. Header count is the number of rows drawn
 * (nearest eight). Render-only, same as Radar: no tick and no action entry.
 */
public class PlayersHudModule extends HudModule {
    static final int MAX_ROWS = 8;
    private static final float NAME = 8f, COL = 7f, ROW = 11f, PAD = 4f, HEADER = 12f, HEAD = 8f, GAP = 6f;

    private final List<Player> rows = new ArrayList<>();

    public PlayersHudModule() {
        super("Players");
    }

    @Override
    public void render(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;

        rows.clear();
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive()) continue;
            rows.add(player);
        }
        rows.sort(Comparator.comparingDouble(player -> mc.player.position().distanceTo(player.position())));
        if (rows.size() > MAX_ROWS) rows.subList(MAX_ROWS, rows.size()).clear();

        Objective kills = killObjective(mc.level.getScoreboard());
        GuiGraphics g = event.getContext();
        float distW = Math.max(ShadcnDraw.width("Dist.", COL), ShadcnDraw.width("9999m", COL));
        float hpW = Math.max(ShadcnDraw.width("HP", COL), ShadcnDraw.width("100%", COL, HudFonts.SEMI));
        float killsW = Math.max(ShadcnDraw.width("Kills", COL), ShadcnDraw.width("999", COL));
        float nameW = ShadcnDraw.width("Players " + rows.size(), COL, HudFonts.SEMI);
        for (Player player : rows) {
            nameW = Math.max(nameW, ShadcnDraw.width(nameOf(player), NAME, HudFonts.SEMI));
        }
        nameW = Math.min(nameW, 78f);
        float cardW = PAD + HEAD + 3f + nameW + GAP + distW + GAP + hpW + GAP + killsW + PAD;
        float cardH = HEADER + 2f + (rows.isEmpty() ? 0f : rows.size() * ROW + 2f);

        int[] o = origin(4, 28, Math.round(cardW), Math.round(cardH));
        int ox = o[0], oy = o[1];
        ShadcnDraw.panel(g, ox, oy, cardW, cardH, 6f);

        float headerY = oy + (HEADER - COL) / 2f;
        ShadcnDraw.text(g, "Players " + rows.size(), ox + PAD, headerY, COL, HudFonts.SEMI, ShadcnDraw.MUTED);
        float killsRight = ox + cardW - PAD;
        float hpRight = killsRight - killsW - GAP;
        float distRight = hpRight - hpW - GAP;
        ShadcnDraw.textRight(g, "Dist.", distRight, headerY, COL, HudFonts.REGULAR, ShadcnDraw.MUTED);
        ShadcnDraw.textRight(g, "HP", hpRight, headerY, COL, HudFonts.REGULAR, ShadcnDraw.MUTED);
        ShadcnDraw.textRight(g, "Kills", killsRight, headerY, COL, HudFonts.REGULAR, ShadcnDraw.MUTED);
        ShadcnDraw.round(g, ox + PAD, oy + HEADER, cardW - PAD * 2, 1f, 0f, ShadcnDraw.SUBTLE);

        float nameX = ox + PAD + HEAD + 3f;
        for (int i = 0; i < rows.size(); i++) {
            Player player = rows.get(i);
            float mid = oy + HEADER + 2f + i * ROW + (ROW - NAME) / 2f;
            face(g, player, ox + PAD, mid);
            ShadcnDraw.text(g, clip(nameOf(player), nameW), nameX, mid, NAME, HudFonts.SEMI, nameColor(player));
            int metres = (int) Math.min(9999, Math.max(0, Math.round(mc.player.position().distanceTo(player.position()))));
            ShadcnDraw.textRight(g, metres + "m", distRight, mid, COL, HudFonts.REGULAR, ShadcnDraw.MUTED);
            float maxHp = Math.max(1f, player.getMaxHealth());
            int pct = Math.round(100f * Math.max(0f, player.getHealth()) / maxHp);
            ShadcnDraw.textRight(g, pct + "%", hpRight, mid, COL, HudFonts.SEMI, hpColor(pct / 100f));
            ShadcnDraw.textRight(g, killsOf(player, kills), killsRight, mid, COL, HudFonts.REGULAR, ShadcnDraw.TEXT);
        }
        bounds(ox, oy, Math.round(cardW), Math.round(cardH));
    }

    private static Objective killObjective(Scoreboard board) {
        Objective listed = board.getDisplayObjective(DisplaySlot.LIST);
        if (listed != null && isKillCriterion(listed.getCriteria())) return listed;
        Objective teamKill = null;
        for (Objective objective : board.getObjectives()) {
            ObjectiveCriteria criteria = objective.getCriteria();
            if (criteria == ObjectiveCriteria.KILL_COUNT_PLAYERS) return objective;
            if (teamKill == null && isTeamKill(criteria)) teamKill = objective;
        }
        if (teamKill != null) return teamKill;
        for (DisplaySlot slot : DisplaySlot.values()) {
            if (slot == DisplaySlot.LIST || slot == DisplaySlot.SIDEBAR || slot == DisplaySlot.BELOW_NAME) continue;
            Objective shown = board.getDisplayObjective(slot);
            if (shown != null && isKillCriterion(shown.getCriteria())) return shown;
        }
        return null;
    }

    private static boolean isKillCriterion(ObjectiveCriteria criteria) {
        return criteria == ObjectiveCriteria.KILL_COUNT_PLAYERS || isTeamKill(criteria);
    }

    private static boolean isTeamKill(ObjectiveCriteria criteria) {
        for (ObjectiveCriteria team : ObjectiveCriteria.TEAM_KILL) {
            if (team == criteria) return true;
        }
        return false;
    }

    private String killsOf(Player player, Objective objective) {
        if (objective == null) return "—";
        Scoreboard board = mc.level.getScoreboard();
        ReadOnlyScoreInfo score = board.getPlayerScoreInfo(player, objective);
        if (score == null) {
            PlayerTeam team = board.getPlayersTeam(player.getScoreboardName());
            if (team != null && team.getColor().isColor()) {
                Objective teamSlot = board.getDisplayObjective(DisplaySlot.teamColorToSlot(team.getColor()));
                if (teamSlot != null && isKillCriterion(teamSlot.getCriteria())) {
                    score = board.getPlayerScoreInfo(player, teamSlot);
                }
            }
        }
        return score == null ? "—" : Integer.toString(score.value());
    }

    private void face(GuiGraphics g, Player player, float x, float y) {
        ResourceLocation skin = skinOf(player);
        if (skin == null) return;
        float scale = HEAD / 8f;
        var pose = g.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 0);
            pose.scale(scale, scale, 1f);
            GuiBlitCompat.blitTinted(g, skin, 0, 0, 8f, 8f, 8, 8, 64, 64, 0xFFFFFFFF);
            GuiBlitCompat.blitTinted(g, skin, 0, 0, 40f, 8f, 8, 8, 64, 64, 0xFFFFFFFF);
        } catch (Throwable missing) {
            // Skin texture is not ready; the row stays aligned without a face.
        } finally {
            pose.popPose();
        }
    }

    private ResourceLocation skinOf(Player player) {
        if (mc.getConnection() == null) return null;
        PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
        if (info == null) return null;
        try {
            return info.getSkin().texture();
        } catch (Throwable missing) {
            return null;
        }
    }

    private int nameColor(Player player) {
        HudClientModule hud = GuizhouWare.moduleManager == null ? null
                : GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        if (hud != null && GuizhouWare.friendManager != null && GuizhouWare.friendManager.isFriend(player)) {
            return hud.radarFriendColor.getValue().getRGB();
        }
        if (hud != null && GuizhouWare.enemyManager != null && GuizhouWare.enemyManager.isEnemy(player)) {
            return 0xFF000000 | hud.radarEnemyColor.getValue().getRGB();
        }
        return ShadcnDraw.mix(ShadcnDraw.TEXT, ShadcnDraw.ACCENT, .18f);
    }

    private static int hpColor(float ratio) {
        if (ratio >= 0.6f) return 0xFF4ADE80;
        if (ratio >= 0.3f) return 0xFFFBBF24;
        return 0xFFF87171;
    }

    private static String nameOf(Player player) {
        String name = player.getGameProfile().getName();
        return name == null || name.isEmpty() ? player.getScoreboardName() : name;
    }

    private static String clip(String text, float maxWidth) {
        if (ShadcnDraw.width(text, NAME, HudFonts.SEMI) <= maxWidth) return text;
        String trimmed = text;
        while (trimmed.length() > 1 && ShadcnDraw.width(trimmed + "…", NAME, HudFonts.SEMI) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "…";
    }
}
