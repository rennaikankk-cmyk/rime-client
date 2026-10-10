package dev.guizhou.features.modules.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.DamageSyncTracker;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.MatrixCapture;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.traits.Jsonable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.guizhou.util.render.font.Fonts;

public class NametagsModule extends Module {

    /** Shared Anthropic Serif/CJK font chain for nametag text. */
    public Setting<Boolean> nametagFont = bool("NametagFont", true).setPage("Info");
    public Setting<Boolean> showPearls  = bool("ShowPearls",  true).setPage("Info");
    public Setting<Boolean> showItems   = bool("ShowItems",   true).setPage("Info");
    public Setting<Boolean> showArmor   = bool("ShowArmor",   true).setPage("Info");
    public Setting<Boolean> showDist    = bool("ShowDist",    true).setPage("Info");
    public Setting<Boolean> showPops    = bool("ShowPops",    true).setPage("Info");
    public Setting<Boolean> showResistance = bool("ShowResistance", true).setPage("Info");
    public Setting<Boolean> showStrength   = bool("ShowStrength",   true).setPage("Info");

    public Setting<Color>   nameColor   = color("NameColor",   250, 249, 245, 255).setPage("Colors");
    public Setting<Color>   friendColor = color("FriendColor", 0x9B, 0xBF, 0xA6, 255).setPage("Colors");
    public Setting<Color>   enemyColor  = color("EnemyColor",  0xE3, 0x9A, 0x92, 255).setPage("Colors");
    public Setting<Color>   bgColor     = color("BgColor",     0x30, 0x30, 0x2E, 184).setPage("Colors");
    public Setting<Color>   distColor   = color("DistColor",   0xC2, 0xC0, 0xB6, 255).setPage("Colors");
    public Setting<Color>   popColor    = color("PopColor",   224, 138, 108, 255).setPage("Colors");

    public Setting<Float>   gap         = num("Gap",       1.0f, 0.1f, 15.0f).setPage("Render");
    public Setting<Float>   armorGap    = num("ArmorGap",  2.0f, 0.0f, 20.0f).setPage("Render");
    public Setting<Float>   scale       = num("Scale",     1.0f, 0.1f,  3.0f).setPage("Render");
    public Setting<Float>   minScale    = num("MinScale",  0.3f, 0.1f,  1.0f).setPage("Render");
    public Setting<Boolean> itemsOnArmor = bool("ItemsOnArmor", false).setPage("Render");

    private record PearlEntry(String ownerName, long lastSeenMs) {}

    private static final long FIVE_DAYS_MS = 5L * 24 * 60 * 60 * 1000;

    private final Map<UUID, PearlEntry> pearlOwnerCache = new HashMap<>();

    private final Jsonable pearlCacheJson = new Jsonable() {
        @Override
        public JsonElement toJson() {
            JsonObject root = new JsonObject();
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, PearlEntry> e : pearlOwnerCache.entrySet()) {
                if (now - e.getValue().lastSeenMs() <= FIVE_DAYS_MS) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("owner", e.getValue().ownerName());
                    obj.addProperty("lastSeen", e.getValue().lastSeenMs());
                    root.add(e.getKey().toString(), obj);
                }
            }
            return root;
        }

        @Override
        public void fromJson(JsonElement element) {
            if (element == null || element.isJsonNull()) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                try {
                    JsonObject obj = e.getValue().getAsJsonObject();
                    String owner = obj.get("owner").getAsString();
                    long lastSeen = obj.get("lastSeen").getAsLong();
                    if (now - lastSeen <= FIVE_DAYS_MS) {
                        pearlOwnerCache.put(UUID.fromString(e.getKey()), new PearlEntry(owner, lastSeen));
                    }
                } catch (Exception ignored) {}
            }
        }

        @Override
        public String getFileName() {
            return "pearl_owners.json";
        }
    };

    static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final ItemStack TURTLE_MASTER_ICON = makeTurtleMasterIcon();

    private static final ItemStack GAPPLE_ICON = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);

    private static final ItemStack STRENGTH_ICON = makePotionIcon(Potions.STRENGTH);

    private static ItemStack makeTurtleMasterIcon() {
        return makePotionIcon(Potions.TURTLE_MASTER);
    }

    private static ItemStack makePotionIcon(net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion) {
        ItemStack stack = new ItemStack(Items.SPLASH_POTION);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));
        return stack;
    }

    public NametagsModule() {
        super("Nametags", "Renders custom nametags above players", Category.RENDER);
        GuizhouWare.configManager.addConfig(pearlCacheJson);
    }

    /** Untouched pre-2026-09-25 defaults follow the graphite palette; customised colours stay. */
    private void migrateLegacyColors() {
        migrate(friendColor, 0xFF00FF64);
        migrate(enemyColor, 0xFFFF3C3C);
        migrate(bgColor, 0x80000000);
        migrate(distColor, 0xFFAAAAAA);
    }

    private static void migrate(Setting<Color> setting, int legacy) {
        if (setting.getValue().getRGB() == legacy) setting.setValue(setting.getDefaultValue());
    }

    /** Rounded tag plate; a hairline in the name colour marks friend / enemy at a glance. */
    private static void plate(GuiGraphics graphics, float x, float y, float w, float h, int bg, int nameArgb) {
        HudDraw.roundDirect(graphics, x, y, w, h, 1.5f, bg);
        if (w > 8f) HudDraw.roundDirect(graphics, x + 3f, y + h - 1f, w - 6f, 1f, 0,
                ShadcnDraw.alpha(nameArgb, .55f * ((bg >>> 24) / 255f) + .25f));
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (nullCheck()) return;
        if (MatrixCapture.projection == null) return;
        migrateLegacyColors();

        GuiGraphics graphics = event.getContext();
        float delta = event.getDelta();

        record RenderJob(double distSq, Runnable draw) {}
        List<RenderJob> jobs = new ArrayList<>();

        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (!player.isAlive()) continue;

            double px = player.xo + (player.getX() - player.xo) * delta;
            double py = player.yo + (player.getY() - player.yo) * delta + player.getBbHeight()
                    + gap.getValue() * 0.5;
            double pz = player.zo + (player.getZ() - player.zo) * delta;

            double dist = mc.player.position().distanceTo(player.position());
            boolean isEnemy = GuizhouWare.enemyManager.isEnemy(player);
            boolean isFriend = !isEnemy && GuizhouWare.friendManager.isFriend(player);
            int nameArgb = (isEnemy ? enemyColor.getValue()
                    : isFriend ? friendColor.getValue()
                    : nameColor.getValue()).getRGB();
            String secondaryStr = showDist.getValue() ? " " + (int) dist + "m" : "";
            int pops = GuizhouWare.playerInfoManager.getTotemPops(player.getUUID());

            Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                armor.put(slot, GuizhouWare.playerInfoManager.getEquipment(player, slot));
            }
            ItemStack mainHand = GuizhouWare.playerInfoManager.getMainHandItem(player);
            ItemStack offHand  = GuizhouWare.playerInfoManager.getOffHandItem(player);
            String name = player.getGameProfile().name();
            // Lead icons render left-to-right before the name; order here is their left-to-right order,
            // so Strength sits to the right of any resistance potion icon.
            List<ItemStack> leadIcons = new ArrayList<>(2);
            if (showResistance.getValue()) {
                if (DamageSyncTracker.isTurtleMaster(player)) leadIcons.add(TURTLE_MASTER_ICON);
                else if (DamageSyncTracker.hasResistance(player)) leadIcons.add(GAPPLE_ICON);
            }
            if (showStrength.getValue() && DamageSyncTracker.hasStrength(player)) leadIcons.add(STRENGTH_ICON);

            jobs.add(new RenderJob(dist * dist, () ->
                    renderNametag(graphics, px, py, pz, dist,
                            name, nameArgb, secondaryStr, pops,
                            armor, mainHand, offHand, leadIcons)));
        }

        if (showPearls.getValue()) {
            long now = System.currentTimeMillis();
            pearlOwnerCache.entrySet().removeIf(e -> now - e.getValue().lastSeenMs() > FIVE_DAYS_MS);

            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof ThrownEnderpearl pearl)) continue;
                if (pearl.tickCount <= 2) continue;

                UUID uuid = pearl.getUUID();

                if (pearl.getOwner() instanceof Player thrower) {
                    pearlOwnerCache.put(uuid, new PearlEntry(thrower.getGameProfile().name(), now));
                } else {
                    PearlEntry existing = pearlOwnerCache.get(uuid);
                    if (existing != null) {
                        pearlOwnerCache.put(uuid, new PearlEntry(existing.ownerName(), now));
                    }
                }

                PearlEntry entry = pearlOwnerCache.get(uuid);
                if (entry == null) continue;

                double px = pearl.xo + (pearl.getX() - pearl.xo) * delta;
                double py = pearl.yo + (pearl.getY() - pearl.yo) * delta + pearl.getBbHeight()
                        + gap.getValue() * 0.5;
                double pz = pearl.zo + (pearl.getZ() - pearl.zo) * delta;

                double dist = mc.player.position().distanceTo(pearl.position());

                boolean isEnemy = GuizhouWare.enemyManager.isEnemy(entry.ownerName());
                boolean isFriend = !isEnemy && GuizhouWare.friendManager.isFriend(entry.ownerName());
                int nameArgb = (isEnemy ? enemyColor.getValue()
                        : isFriend ? friendColor.getValue()
                        : nameColor.getValue()).getRGB();
                String ownerName = entry.ownerName();

                jobs.add(new RenderJob(dist * dist, () ->
                        renderPearlTag(graphics, px, py, pz, dist, ownerName, nameArgb)));
            }
        }

        jobs.sort(Comparator.comparingDouble(RenderJob::distSq).reversed());
        for (RenderJob job : jobs) job.draw.run();
    }

    private void renderPearlTag(GuiGraphics graphics,
                                double wx, double wy, double wz,
                                double dist,
                                String name, int nameArgb) {
        float[] screen = MatrixCapture.worldToScreen(wx, wy, wz);
        if (screen == null) return;

        float anchorX = screen[0];
        float anchorY = screen[1];
        float s = (float) Math.max(minScale.getValue(), scale.getValue() * 8.0 / (dist + 8.0));

        int nameW = Fonts.nametagWidth(name);
        int halfW = nameW / 2;
        int textH = Fonts.nametagLineHeight();
        int textTopY = -textH;

        graphics.pose().pushMatrix();
        graphics.pose().translate(anchorX, anchorY);
        graphics.pose().scale(s, s);

        plate(graphics, -halfW - 3, textTopY - 1.5f, halfW * 2 + 6, textH + 3f, bgColor.getValue().getRGB(), nameArgb);
        Fonts.drawNametagString(graphics, name, -halfW, textTopY, nameArgb);

        graphics.pose().popMatrix();
    }

    public void renderNametag(GuiGraphics graphics,
                               double wx, double wy, double wz,
                               double dist,
                               String name, int nameArgb,
                               String secondaryStr,
                               int totemPops,
                               Map<EquipmentSlot, ItemStack> armor,
                               ItemStack mainHand, ItemStack offHand,
                               List<ItemStack> leadIcons) {
        float[] screen = MatrixCapture.worldToScreen(wx, wy, wz);
        if (screen == null) return;

        float anchorX = screen[0];
        float anchorY = screen[1];

        float s = (float) Math.max(minScale.getValue(), scale.getValue() * 8.0 / (dist + 8.0));

        int nameW      = Fonts.nametagWidth(name);
        int secondaryW = Fonts.nametagWidth(secondaryStr);

        String popsStr = (showPops.getValue() && totemPops > 0) ? " -" + totemPops : "";
        int popsW = Fonts.nametagWidth(popsStr);

        int textH    = Fonts.nametagLineHeight();
        int textTopY = -textH;

        int iconCount = (leadIcons == null) ? 0 : leadIcons.size();
        int iconSize  = iconCount > 0 ? textH : 0;
        int iconGap   = 1;
        int leadW     = iconCount * (iconSize + iconGap);

        int totalW   = leadW + nameW + secondaryW + popsW;
        int halfW    = totalW / 2;
        int nameX    = -halfW + leadW;

        graphics.pose().pushMatrix();
        graphics.pose().translate(anchorX, anchorY);
        graphics.pose().scale(s, s);

        plate(graphics, -halfW - 3, textTopY - 1.5f, halfW * 2 + 6, textH + 3f, bgColor.getValue().getRGB(), nameArgb);

        if (iconCount > 0) {
            float itemScale = iconSize / 16.0f;
            int iconY = textTopY + (textH - iconSize) / 2;
            for (int k = 0; k < iconCount; k++) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(-halfW + k * (iconSize + iconGap), iconY);
                graphics.pose().scale(itemScale, itemScale);
                graphics.renderItem(leadIcons.get(k), 0, 0);
                graphics.pose().popMatrix();
            }
        }

        Fonts.drawNametagString(graphics, name, nameX, textTopY, nameArgb);
        if (!secondaryStr.isEmpty()) {
            Fonts.drawNametagString(graphics, secondaryStr, nameX + nameW, textTopY, distColor.getValue().getRGB());
        }
        if (!popsStr.isEmpty()) {
            Fonts.drawNametagString(graphics, popsStr, nameX + nameW + secondaryW, textTopY, popColor.getValue().getRGB());
        }

        if (showArmor.getValue()) {
            boolean hasArmor = false;
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                if (!armor.get(slot).isEmpty()) { hasArmor = true; break; }
            }
            if (hasArmor) {
                int slotSize  = 16;
                int armorTopY = textTopY - 1 - armorGap.getValue().intValue() - slotSize;
                for (int i = 0; i < 4; i++) {
                    ItemStack armorStack = armor.get(ARMOR_SLOTS[i]);
                    int slotX = -(slotSize * 4) / 2 + i * slotSize;
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(slotX, armorTopY);
                    graphics.renderItem(armorStack, 0, 0);
                    Fonts.renderNametagItemDecorations(graphics, armorStack, 0, 0);
                    graphics.pose().popMatrix();
                }
            }
        }

        if (showItems.getValue()) {
            int slotSize = 16;
            int itemY, offX, mainX;
            if (itemsOnArmor.getValue()) {
                itemY = textTopY - 1 - armorGap.getValue().intValue() - slotSize;
                offX  = -(slotSize * 4) / 2 - slotSize;
                mainX = (slotSize * 4) / 2;
            } else {
                itemY = textTopY + textH / 2 - slotSize / 2;
                offX  = -halfW - 2 - slotSize;
                mainX = halfW + 2;
            }
            if (!offHand.isEmpty()) {
                graphics.renderItem(offHand, offX, itemY);
                Fonts.renderNametagItemDecorations(graphics, offHand, offX, itemY);
            }
            if (!mainHand.isEmpty()) {
                graphics.renderItem(mainHand, mainX, itemY);
                Fonts.renderNametagItemDecorations(graphics, mainHand, mainX, itemY);
            }
        }

        graphics.pose().popMatrix();
    }
}
