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
    public static final int BG = 0xFF181E32, CARD = 0xED182338, SURFACE = 0xF025334B,
            BORDER = 0xB8405471, TEXT = 0xFFE0E6F2, MUTED = 0xFFADB8D0, ACCENT = 0xFF72A8D8;
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
    public static void text(GuiGraphics g,String text,float x,float y,float size,int color) {
        text(g,text,x,y,size,HudFonts.REGULAR,color);
    }
    public static void text(GuiGraphics g,String text,float x,float y,float size,int weight,int color) {
        HudFonts.draw(g,text,x,y,size,weight,HudAppearance.color(color));
    }
    public static float width(String text,float size) { return HudFonts.width(text,size,HudFonts.REGULAR); }
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
