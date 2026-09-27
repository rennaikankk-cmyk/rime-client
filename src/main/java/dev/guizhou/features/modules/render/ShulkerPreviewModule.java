package dev.guizhou.features.modules.render;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.joml.Matrix3x2fStack;

import java.awt.Color;

public class ShulkerPreviewModule extends Module {

    private static final int COLUMNS = 9;
    private static final int ROWS = 3;
    private static final int SLOTS = COLUMNS * ROWS;
    private static final int CELL = 18;
    private static final int PADDING = 3;
    private static final int CURSOR_GAP = 12;
    private static final int SCREEN_MARGIN = 2;

    public enum Thumbnail { Off, MostCommon, First }

    public final Setting<Color> background = color("Background", 0x30, 0x30, 0x2E, 240);
    public final Setting<Color> border = color("Border", 0xE0, 0x8A, 0x6C, 255);
    public final Setting<Boolean> hideEmpty = bool("HideEmpty", false);
    public final Setting<Thumbnail> thumbnail = mode("Thumbnail", Thumbnail.Off);
    public final Setting<Double> thumbnailScale = num("ThumbnailScale", 0.6, 0.3, 1.0)
            .setVisibility(v -> thumbnail.getValue() != Thumbnail.Off);
    public final Setting<Boolean> thumbnailCount = bool("ThumbnailCount", true)
            .setVisibility(v -> thumbnail.getValue() != Thumbnail.Off);
    public final Setting<Boolean> shulkerList = bool("ShulkerList", false);
    public final Setting<Boolean> listNames = bool("ListNames", true)
            .setVisibility(v -> shulkerList.getValue());

    public ShulkerPreviewModule() {
        super("ShulkerPreview", "Shows the contents of a shulker box when you hover it", Category.RENDER);
    }

    public static ShulkerPreviewModule get() {
        if (GuizhouWare.moduleManager == null) return null;
        return GuizhouWare.moduleManager.getModuleByClass(ShulkerPreviewModule.class);
    }

    public boolean renderPreview(GuiGraphics graphics, ItemStack stack, int mouseX, int mouseY) {
        if (!isShulkerBox(stack)) return false;

        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return false;

        NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        contents.copyInto(items);

        if (hideEmpty.getValue() && items.stream().allMatch(ItemStack::isEmpty)) return false;
        migrateLegacyColors();

        int width = COLUMNS * CELL + PADDING * 2;
        int height = HEADER + ROWS * CELL + PADDING * 2;

        int x = clamp(mouseX + CURSOR_GAP, SCREEN_MARGIN, graphics.guiWidth() - width - SCREEN_MARGIN);
        int y = clamp(mouseY - CURSOR_GAP, SCREEN_MARGIN, graphics.guiHeight() - height - SCREEN_MARGIN);

        graphics.nextStratum();
        drawCard(graphics, x, y, width, height, stack.getHoverName().getString(), used(items));
        for (int i = 0; i < SLOTS; i++) {
            int ix = x + PADDING + (i % COLUMNS) * CELL + 1;
            int iy = y + HEADER + PADDING + (i / COLUMNS) * CELL + 1;
            graphics.fill(ix - 1, iy - 1, ix + 17, iy + 17, SLOT);
            ItemStack item = items.get(i);
            if (item.isEmpty()) continue;
            graphics.renderItem(item, ix, iy);
            dev.guizhou.util.render.font.Fonts.renderItemDecorations(graphics, item, ix, iy);
        }

        return true;
    }

    private static final int HEADER = 13;
    private static final int SLOT = 0x661D212C;

    /** Thin-line panel: background/border settings, name and fill count. */
    private void drawCard(GuiGraphics graphics, float x, float y, float w, float h, String name, int used) {
        int bg = background.getValue().getRGB(), ring = border.getValue().getRGB();
        HudDraw.roundDirect(graphics, x, y, w, h, 2f, bg);
        HudDraw.outlineDirect(graphics, x, y, w, h, 2f, 1f, ShadcnDraw.alpha(ring, .75f));
        String count = used + " / " + SLOTS;
        float countW = ShadcnDraw.width(count, 7f);
        HudDraw.textMid(graphics, ShadcnDraw.fit(name, w - PADDING * 2 - countW - 8f, 8f), x + PADDING + 1f, y + 8f, 8f,
                HudFonts.SEMI, ShadcnDraw.TEXT);
        HudDraw.textMidRight(graphics, count, x + w - PADDING - 1f, y + 8f, 7f, HudFonts.REGULAR, ShadcnDraw.MUTED);
    }

    private static int used(NonNullList<ItemStack> items) {
        int n = 0;
        for (ItemStack item : items) if (!item.isEmpty()) n++;
        return n;
    }

    /** Untouched pre-2026-09-25 defaults follow the graphite + iris palette. */
    private void migrateLegacyColors() {
        if (background.getValue().getRGB() == 0xF0101014) background.setValue(background.getDefaultValue());
        if (border.getValue().getRGB() == 0xFF8250FF) border.setValue(border.getDefaultValue());
    }

    public void renderThumbnail(GuiGraphics graphics, ItemStack stack, int slotX, int slotY) {
        if (thumbnail.getValue() == Thumbnail.Off) return;
        if (!isShulkerBox(stack)) return;

        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return;

        ItemStack icon = selectThumbnail(contents);
        if (icon.isEmpty()) return;

        float scale = thumbnailScale.getValue().floatValue();
        float offset = 8.0f * (1.0f - scale);

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(slotX + offset, slotY + offset);
        pose.scale(scale, scale);
        graphics.renderItem(icon, 0, 0);
        if (thumbnailCount.getValue()) {
            dev.guizhou.util.render.font.Fonts.renderItemDecorations(graphics, icon, 0, 0);
        }
        pose.popMatrix();
    }

    private ItemStack selectThumbnail(ItemContainerContents contents) {
        ItemStack best = ItemStack.EMPTY;
        int bestCount = -1;
        for (ItemStack item : contents.nonEmptyItems()) {
            if (item.isEmpty()) continue;
            if (thumbnail.getValue() == Thumbnail.First) return item;
            if (item.getCount() > bestCount) {
                bestCount = item.getCount();
                best = item;
            }
        }
        return best;
    }

    private static final int LIST_COLS = 9;
    private static final int LIST_ROWS = 3;
    private static final int LIST_CELL = 16;
    private static final int LIST_PAD = 3;
    private static final int LIST_HEADER = 11;
    private static final int LIST_GAP = 4;
    private static final int LIST_MARGIN = 4;
    private static final int LIST_GRID_COLS = 5;

    public void renderShulkerList(GuiGraphics graphics, java.util.List<ItemStack> shulkers, int containerLeft) {
        if (shulkers.isEmpty()) return;

        int panelW = LIST_COLS * LIST_CELL + LIST_PAD * 2;
        int gridInnerH = LIST_ROWS * LIST_CELL;
        int panelH = LIST_PAD * 2 + (listNames.getValue() ? LIST_HEADER : 0) + gridInnerH;

        int cols = Math.min(LIST_GRID_COLS, shulkers.size());
        int rows = (shulkers.size() + cols - 1) / cols;

        int neededW = cols * panelW + (cols - 1) * LIST_GAP;
        int neededH = rows * panelH + (rows - 1) * LIST_GAP;

        int availW = containerLeft - LIST_MARGIN * 2;
        int availH = graphics.guiHeight() - LIST_MARGIN * 2;
        if (availW <= 0) return;

        float scale = Math.min(1.0f, Math.min((float) availW / neededW, (float) availH / neededH));

        float stepX = (panelW + LIST_GAP) * scale;
        float stepY = (panelH + LIST_GAP) * scale;

        graphics.nextStratum();
        Matrix3x2fStack pose = graphics.pose();
        for (int i = 0; i < shulkers.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            float x = LIST_MARGIN + col * stepX;
            float y = LIST_MARGIN + row * stepY;

            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(scale, scale);
            drawListPanel(graphics, shulkers.get(i), panelW, panelH);
            pose.popMatrix();
        }
    }

    private void drawListPanel(GuiGraphics graphics, ItemStack shulker, int panelW, int panelH) {
        migrateLegacyColors();
        int bg = background.getValue().getRGB(), ring = border.getValue().getRGB();
        HudDraw.roundDirect(graphics, 0, 0, panelW, panelH, 2f, bg);
        HudDraw.outlineDirect(graphics, 0, 0, panelW, panelH, 2f, 1f, ShadcnDraw.alpha(ring, .75f));

        ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
        NonNullList<ItemStack> items = NonNullList.withSize(LIST_COLS * LIST_ROWS, ItemStack.EMPTY);
        if (contents != null) contents.copyInto(items);

        int gridY = LIST_PAD;
        if (listNames.getValue()) {
            String name = shulker.getHoverName().getString();
            String count = String.valueOf(used(items));
            HudDraw.textMid(graphics, ShadcnDraw.fit(name, panelW - LIST_PAD * 2 - 14f, 7.5f), LIST_PAD + 1f,
                    LIST_PAD + LIST_HEADER / 2f - .5f, 7.5f, HudFonts.SEMI, ShadcnDraw.TEXT);
            HudDraw.textMidRight(graphics, count, panelW - LIST_PAD - 1f, LIST_PAD + LIST_HEADER / 2f - .5f, 6.5f,
                    HudFonts.REGULAR, ShadcnDraw.MUTED);
            gridY += LIST_HEADER;
        }
        if (contents == null) return;
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item.isEmpty()) continue;
            int ix = LIST_PAD + (i % LIST_COLS) * LIST_CELL;
            int iy = gridY + (i / LIST_COLS) * LIST_CELL;
            graphics.renderItem(item, ix, iy);
            dev.guizhou.util.render.font.Fonts.renderItemDecorations(graphics, item, ix, iy);
        }
    }

    public static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
