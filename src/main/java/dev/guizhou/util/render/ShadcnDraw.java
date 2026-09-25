package dev.guizhou.util.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;

/** Native GUI-pixel primitives shared by screens and HUD. A rounded rect is one mesh. */
public final class ShadcnDraw {
    // InternalBeyond-inspired moon-blue palette, rendered natively over the world.
    public static final int BG = 0xFF181E32, CARD = 0xED182338, SURFACE = 0xF025334B, RAISED = 0xFF2E3650,
            BORDER = 0xB8405471, TEXT = 0xFFE0E6F2, MUTED = 0xFFADB8D0, SUBTLE = 0xFF5D6478,
            ACCENT = 0xFF72A8D8, ACCENT_2 = 0xFF9CC3E8;
    private ShadcnDraw() {}

    /** One draw: 3 body quads plus 4 corners × 4 quads. Replaces a fill per corner row. */
    public static void round(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        color = HudAppearance.color(color);
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        float r = Math.max(0, Math.min(radius, Math.min(w, h) / 2f));
        Matrix4f matrix = g.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int argb = GuiFade.apply(color);
        if (r <= 0.5f) {
            quad(buffer, matrix, x, y, x, y + h, x + w, y + h, x + w, y, argb);
        } else {
            float y0 = y + r, y1 = y + h - r;
            if (y1 > y0) quad(buffer, matrix, x, y0, x, y1, x + w, y1, x + w, y0, argb);
            float x0 = x + r, x1 = x + w - r;
            if (x1 > x0) {
                quad(buffer, matrix, x0, y, x0, y0, x1, y0, x1, y, argb);
                quad(buffer, matrix, x0, y1, x0, y + h, x1, y + h, x1, y1, argb);
            }
            corner(buffer, matrix, x + r, y + r, r, (float) Math.PI, (float) (Math.PI * 1.5), argb);
            corner(buffer, matrix, x + w - r, y + r, r, (float) (-Math.PI / 2), 0, argb);
            corner(buffer, matrix, x + w - r, y + h - r, r, 0, (float) (Math.PI / 2), argb);
            corner(buffer, matrix, x + r, y + h - r, r, (float) (Math.PI / 2), (float) Math.PI, argb);
        }
        Layers.quads().draw(buffer.buildOrThrow());
    }

    private static final int CORNER = 4;

    private static void corner(BufferBuilder buffer, Matrix4f matrix, float cx, float cy, float radius, float a0, float a1, int argb) {
        float px = cx + (float) Math.cos(a0) * radius, py = cy + (float) Math.sin(a0) * radius;
        for (int i = 1; i <= CORNER; i++) {
            float a = a0 + (a1 - a0) * i / CORNER;
            float nx = cx + (float) Math.cos(a) * radius, ny = cy + (float) Math.sin(a) * radius;
            quad(buffer, matrix, cx, cy, px, py, nx, ny, cx, cy, argb);
            px = nx;
            py = ny;
        }
    }

    private static void quad(BufferBuilder buffer, Matrix4f matrix,
                             float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        buffer.addVertex(matrix, x0, y0, 0).setColor(argb);
        buffer.addVertex(matrix, x1, y1, 0).setColor(argb);
        buffer.addVertex(matrix, x2, y2, 0).setColor(argb);
        buffer.addVertex(matrix, x3, y3, 0).setColor(argb);
    }
    public static void panel(GuiGraphics g, float x, float y, float w, float h, float radius) {
        round(g,x,y,w,h,radius,BORDER);
        round(g,x+1,y+1,w-2,h-2,Math.max(0,radius-1),CARD);
    }

    /** Ring of the given thickness; the interior stays transparent. One mesh, like round(). */
    public static void outline(GuiGraphics g, float x, float y, float w, float h, float radius, float thickness, int color) {
        color = HudAppearance.color(color);
        if (w <= 0 || h <= 0 || thickness <= 0 || (color >>> 24) == 0) return;
        float r = Math.max(0, Math.min(radius, Math.min(w, h) / 2f));
        float t = Math.min(thickness, Math.min(w, h) / 2f);
        Matrix4f matrix = g.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int argb = GuiFade.apply(color);
        if (r <= 0.5f) {
            quad(buffer, matrix, x, y, x + w, y, x + w, y + t, x, y + t, argb);
            quad(buffer, matrix, x, y + h - t, x + w, y + h - t, x + w, y + h, x, y + h, argb);
            quad(buffer, matrix, x, y, x + t, y, x + t, y + h, x, y + h, argb);
            quad(buffer, matrix, x + w - t, y, x + w, y, x + w, y + h, x + w - t, y + h, argb);
        } else {
            float ir = Math.max(0, r - t);
            quad(buffer, matrix, x + r, y, x + w - r, y, x + w - r, y + t, x + r, y + t, argb);
            quad(buffer, matrix, x + r, y + h - t, x + w - r, y + h - t, x + w - r, y + h, x + r, y + h, argb);
            quad(buffer, matrix, x, y + r, x + t, y + r, x + t, y + h - r, x, y + h - r, argb);
            quad(buffer, matrix, x + w - t, y + r, x + w, y + r, x + w, y + h - r, x + w - t, y + h - r, argb);
            cornerRing(buffer, matrix, x + r, y + r, r, ir, (float) Math.PI, (float) (Math.PI * 1.5), argb);
            cornerRing(buffer, matrix, x + w - r, y + r, r, ir, (float) (-Math.PI / 2), 0, argb);
            cornerRing(buffer, matrix, x + w - r, y + h - r, r, ir, 0, (float) (Math.PI / 2), argb);
            cornerRing(buffer, matrix, x + r, y + h - r, r, ir, (float) (Math.PI / 2), (float) Math.PI, argb);
        }
        Layers.quads().draw(buffer.buildOrThrow());
    }

    private static void cornerRing(BufferBuilder buffer, Matrix4f matrix, float cx, float cy, float radius, float inner, float a0, float a1, int argb) {
        float px = cx + (float) Math.cos(a0) * radius, py = cy + (float) Math.sin(a0) * radius;
        float qx = cx + (float) Math.cos(a0) * inner,  qy = cy + (float) Math.sin(a0) * inner;
        for (int i = 1; i <= CORNER; i++) {
            float a = a0 + (a1 - a0) * i / CORNER;
            float nx = cx + (float) Math.cos(a) * radius, ny = cy + (float) Math.sin(a) * radius;
            float mx = cx + (float) Math.cos(a) * inner,  my = cy + (float) Math.sin(a) * inner;
            quad(buffer, matrix, px, py, nx, ny, mx, my, qx, qy, argb);
            px = nx; py = ny; qx = mx; qy = my;
        }
    }

    // ------------------------------------------------------------ icons

    private static final net.minecraft.resources.ResourceLocation ICONS =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("guizhouware", "textures/gui/shell_icons.png");
    private static final int ICON_CELL = 96, ICON_COLUMNS = 8, ATLAS_W = 768, ATLAS_H = 384;

    /** Atlas order must match the 1.21.11 sheet; icon() locates cells by ordinal. */
    public enum Icon {
        COMBAT, MOVEMENT, RENDER, PLAYER, WORLD, CLIENT, FUN, HUD,
        SEARCH, STAR, STAR_FILL, CLOSE, KEYBOARD, FOLDER, RESET, CHEVRON,
        CHECK, PLUS, TRASH, SAVE, PLAY, USERS, SLIDERS, POWER,
        SHIELD, MOVE, EXTERNAL, INFO, ZAP, GRID, LIST, SPARKLE
    }

    public static void icon(GuiGraphics g, Icon icon, float x, float y, float size, int color) {
        color = HudAppearance.color(color);
        if (size <= 0 || (color >>> 24) == 0) return;
        int index = icon.ordinal();
        float factor = size / ICON_CELL;
        var pose = g.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 0);
            pose.scale(factor, factor, 1);
            GuiBlitCompat.blitTinted(g, ICONS, 0, 0,
                    (float) (index % ICON_COLUMNS * ICON_CELL), (float) (index / ICON_COLUMNS * ICON_CELL),
                    ICON_CELL, ICON_CELL, ATLAS_W, ATLAS_H, color);
        } finally {
            pose.popPose();
        }
    }

    // ------------------------------------------------------------ text

    public static void text(GuiGraphics g,String text,float x,float y,float size,int color) {
        text(g,text,x,y,size,HudFonts.REGULAR,color);
    }
    public static void text(GuiGraphics g,String text,float x,float y,float size,int weight,int color) {
        HudFonts.draw(g,text,x,y,size,weight,HudAppearance.color(color));
    }
    public static void textCentered(GuiGraphics g, String text, float cx, float y, float size, int weight, int color) {
        text(g, text, cx - width(text, size, weight) / 2f, y, size, weight, color);
    }
    public static void textRight(GuiGraphics g, String text, float right, float y, float size, int weight, int color) {
        text(g, text, right - width(text, size, weight), y, size, weight, color);
    }
    public static float width(String text,float size) { return HudFonts.width(text,size,HudFonts.REGULAR); }
    public static float width(String text,float size,int weight) { return HudFonts.width(text,size,weight); }
    public static int alpha(int color, float factor) {
        int a = Math.round((color >>> 24) * Math.clamp(factor, 0f, 1f));
        return a << 24 | color & 0xFFFFFF;
    }
    public static int mix(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8)
            result |= Math.round(((a >>> shift) & 255) * (1 - t) + ((b >>> shift) & 255) * t) << shift;
        return result;
    }
    public static int accent() { return ACCENT; }
    public static String fit(String text,float maxWidth,float size) {
        if (maxWidth<=0) return "";
        if (width(text,size)<=maxWidth) return text;
        String suffix="…";
        if (width(suffix,size)>maxWidth) return "";
        int end=text.length();
        while (end>0) {
            end=text.offsetByCodePoints(end,-1);
            if (width(text.substring(0,end)+suffix,size)<=maxWidth) return text.substring(0,end)+suffix;
        }
        return suffix;
    }
    public static boolean contains(double mx,double my,float x,float y,float w,float h) {
        return mx>=x && mx<x+w && my>=y && my<y+h;
    }
}
