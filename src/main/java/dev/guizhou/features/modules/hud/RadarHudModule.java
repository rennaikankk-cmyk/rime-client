package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.features.modules.render.NametagsModule;
import dev.guizhou.util.render.ShadcnDraw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class RadarHudModule extends HudModule {
    private static final int DEFAULT_POP_COLOR = 0xFFFF5050;
    private static final float TEXT = 11f;
    private static final float ROW = 15f;
    private static final float PAD = 6f;
    private static final float LOGO = 12f;

    private static final Identifier LOGO_TEX = Identifier.fromNamespaceAndPath("guizhouware", "textures/font/logo_white.png");
    private static final int LOGO_TEX_W = 128;
    private static final int LOGO_TEX_H = 128;

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

        List<Entry> entries = new ArrayList<>();
        UUID selfId = mc.player.getUUID();
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(selfId)) continue;
            if (!player.isAlive()) continue;
            entries.add(new Entry(player, mc.player.position().distanceTo(player.position())));
        }
        entries.sort(Comparator.comparingDouble((Entry e) -> e.dist).reversed());
        entries.add(new Entry(mc.player, 0.0));
        if (entries.isEmpty()) return;

        GuiGraphics ctx = event.getContext();
        HudClientModule hudClient = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        int popColor = resolvePopColor();

        float textW = 0f;
        for (Entry e : entries) {
            String label = e.player.getGameProfile().name();
            String pops = GuizhouWare.playerInfoManager.getTotemPops(e.player.getUUID()) > 0
                    ? " -" + GuizhouWare.playerInfoManager.getTotemPops(e.player.getUUID()) : "";
            textW = Math.max(textW, ShadcnDraw.width(label + pops, TEXT));
        }
        float cardW = PAD + LOGO + 4f + textW + PAD;
        float cardH = PAD * 2 + entries.size() * ROW - (ROW - TEXT - 2f);

        int below = hudClient != null ? hudClient.stackBelowHeight(this) : 0;
        int defX = Math.round(screenWidth() - cardW - 4f);
        int defY = Math.round(bottomAnchor() - 4f - below - cardH);
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        ShadcnDraw.panel(ctx, ox, oy, cardW, cardH, 6f);
        float firstRow = oy + PAD - 1f;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            float ry = firstRow + i * ROW;
            int pops = GuizhouWare.playerInfoManager.getTotemPops(entry.player.getUUID());
            String popsStr = pops > 0 ? " -" + pops : "";
            int nameColor = resolveNameColor(hudClient, entry.player);
            float tx = ox + PAD;
            if (entry.player == mc.player) {
                ctx.blit(RenderPipelines.GUI_TEXTURED, LOGO_TEX,
                        (int) tx, (int) (ry + (TEXT - LOGO) / 2f), 0.0f, 0.0f,
                        (int) LOGO, (int) LOGO, LOGO_TEX_W, LOGO_TEX_H, 0xFFFFFFFF);
                tx += LOGO + 4f;
            } else {
                tx += LOGO + 4f;
            }
            String name = entry.player.getGameProfile().name();
            ShadcnDraw.text(ctx, name, tx, ry, TEXT, nameColor);
            if (!popsStr.isEmpty()) {
                ShadcnDraw.text(ctx, popsStr, tx + ShadcnDraw.width(name, TEXT), ry, TEXT, popColor);
            }
        }
        bounds(ox, oy, (int) cardW, (int) cardH);
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
