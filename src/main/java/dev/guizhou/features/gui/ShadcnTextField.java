package dev.guizhou.features.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import dev.guizhou.util.compat.input.CharacterEvent;
import dev.guizhou.util.compat.input.KeyEvent;
import java.util.function.Consumer;
import static dev.guizhou.util.render.ShadcnDraw.*;
import static org.lwjgl.glfw.GLFW.*;

/** Small native text control. Selection and clipboard operate on the same text that is drawn. */
final class ShadcnTextField {
    private String value = "";
    private int cursor, anchor;
    private float scroll;
    boolean focused;
    float x, y, w, h;
    /** Extra left inset, e.g. room for a leading icon. */
    float padLeft;
    private final Consumer<String> changed;

    ShadcnTextField(String initial, Consumer<String> changed) {
        this.value = initial; this.cursor = this.anchor = initial.length(); this.changed = changed;
    }
    String value() { return value; }
    void bounds(float x, float y, float w, float h) { this.x=x; this.y=y; this.w=w; this.h=h; }
    void draw(GuiGraphics g, String placeholder) {
        round(g,x,y,w,h,5,focused ? MUTED : BORDER);
        round(g,x+1,y+1,w-2,h-2,4,BG);
        float caret = width(value.substring(0,cursor),10);
        scroll = Math.max(0,Math.min(scroll,Math.max(0,width(value,10)-w+16)));
        if (focused) scroll = Math.max(Math.max(0,caret-w+17), Math.min(scroll,caret));
        dev.guizhou.util.render.ScissorUtil.enableTransformed(g,(int)x+5,(int)y+1,(int)(x+w-5),(int)(y+h-1));
        float tx=x+7+padLeft-scroll, ty=y+(h-10)/2;
        if (focused && anchor!=cursor) {
            float a=width(value.substring(0,Math.min(cursor,anchor)),10);
            float b=width(value.substring(0,Math.max(cursor,anchor)),10);
            g.fill((int)(tx+a),(int)ty-1,(int)(tx+b),(int)ty+12,0xFF414141);
        }
        text(g,value.isEmpty() && !focused ? placeholder : value,tx,ty,10,value.isEmpty()?MUTED:TEXT);
        if (focused && (System.currentTimeMillis()/500)%2==0)
            g.fill((int)(tx+caret),(int)ty,(int)(tx+caret)+1,(int)ty+11,TEXT);
        g.disableScissor();
    }
    boolean click(double mx,double my,boolean shift) {
        focused=contains(mx,my,x,y,w,h);
        if (!focused) return false;
        float target=(float)mx-x-7+scroll;
        cursor=value.length();
        for(int p=0;p<value.length();) {
            int next=value.offsetByCodePoints(p,1);
            if(target<(width(value.substring(0,p),10)+width(value.substring(0,next),10))/2) {cursor=p;break;}
            p=next;
        }
        if(!shift) anchor=cursor;
        return true;
    }
    void selectAll() {anchor=0;cursor=value.length();}
    boolean character(CharacterEvent input) {
        if(!focused) return false;
        String s=input.codepointAsString();
        if(!s.isEmpty() && !Character.isISOControl(s.codePointAt(0))) replace(s);
        return true;
    }
    private void replace(String replacement) {
        int start=Math.min(cursor,anchor), end=Math.max(cursor,anchor);
        // Bound pasted configuration fields; preserve Unicode code points.
        if(value.length()-(end-start)+replacement.length()>4096) return;
        value=value.substring(0,start)+replacement+value.substring(end);
        cursor=anchor=start+replacement.length(); changed.accept(value);
    }
    private void move(int destination,boolean shift) {
        cursor=Math.max(0,Math.min(value.length(),destination)); if(!shift) anchor=cursor;
    }
    private int step(int direction,boolean word) {
        int p=cursor;
        if(!word) return p==0 && direction<0 || p==value.length() && direction>0 ? p : value.offsetByCodePoints(p,direction);
        while(direction<0 ? p>0 : p<value.length()) {
            int n=value.offsetByCodePoints(p,direction);
            if(p!=cursor && Character.isWhitespace(value.codePointAt(direction<0?n:p))) break;
            p=n;
        }
        return p;
    }
    boolean key(KeyEvent input) {
        if(!focused) return false;
        int key=input.input(); boolean ctrl=(input.modifiers()&GLFW_MOD_CONTROL)!=0, shift=(input.modifiers()&GLFW_MOD_SHIFT)!=0;
        if(ctrl && key==GLFW_KEY_A) selectAll();
        else if(ctrl && (key==GLFW_KEY_C || key==GLFW_KEY_X)) {
            if(cursor!=anchor) {Minecraft.getInstance().keyboardHandler.setClipboard(value.substring(Math.min(cursor,anchor),Math.max(cursor,anchor)));if(key==GLFW_KEY_X) replace("");}
        } else if(ctrl && key==GLFW_KEY_V) replace(Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("[\\r\\n\\t]"," "));
        else if(key==GLFW_KEY_HOME) move(0,shift);
        else if(key==GLFW_KEY_END) move(value.length(),shift);
        else if(key==GLFW_KEY_LEFT || key==GLFW_KEY_RIGHT) {
            int d=key==GLFW_KEY_LEFT?-1:1;
            move(!shift && cursor!=anchor ? (d<0?Math.min(cursor,anchor):Math.max(cursor,anchor)) : step(d,ctrl),shift);
        } else if(key==GLFW_KEY_BACKSPACE || key==GLFW_KEY_DELETE) {
            if(cursor==anchor) anchor=step(key==GLFW_KEY_BACKSPACE?-1:1,ctrl);
            replace("");
        }
        return true;
    }
}
