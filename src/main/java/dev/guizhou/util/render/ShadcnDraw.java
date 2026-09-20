package dev.guizhou.util.render;

import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;

/** Native GUI-pixel primitives shared by screens and HUD. No web runtime. */
public final class ShadcnDraw {
    // InternalBeyond-inspired moon-blue palette, rendered natively over the world.
    public static final int BG = 0xFF181E32, CARD = 0xED182338, SURFACE = 0xF025334B,
            BORDER = 0xB8405471, TEXT = 0xFFE0E6F2, MUTED = 0xFFADB8D0, ACCENT = 0xFF72A8D8;
    private ShadcnDraw() {}

    public static void round(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        color = HudAppearance.color(color);
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        int left = Math.round(x), top = Math.round(y), right = Math.round(x + w), bottom = Math.round(y + h);
        int r = Math.max(0, Math.min(Math.round(radius), Math.min(right-left, bottom-top)/2));
        if (r == 0) { g.fill(left, top, right, bottom, color); return; }
        g.fill(left, top+r, right, bottom-r, color);
        for (int row = 0; row < r; row++) {
            double dy = r-row-0.5;
            int inset = (int) Math.ceil(r-Math.sqrt(r*r-dy*dy)-0.5);
            g.fill(left+inset, top+row, right-inset, top+row+1, color);
            g.fill(left+inset, bottom-row-1, right-inset, bottom-row, color);
        }
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
