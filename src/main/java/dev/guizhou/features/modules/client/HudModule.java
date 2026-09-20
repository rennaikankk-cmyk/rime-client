package dev.guizhou.features.modules.client;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.util.traits.Util;
import net.minecraft.client.gui.screens.ChatScreen;

public abstract class HudModule implements Util {
    private static final int CHAT_INPUT_HEIGHT = 14;

    protected static final int SCREEN_MARGIN = 2;

    private final String name;

    // Editor/positioning support: last rendered bounds, cleared each frame.
    private int boundX, boundY, boundW, boundH;
    private boolean renderedThisFrame;

    public HudModule(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public abstract void render(Render2DEvent event);

    protected int screenWidth() {
        return mc.getWindow().getGuiScaledWidth();
    }

    protected int screenHeight() {
        return mc.getWindow().getGuiScaledHeight();
    }

    protected int chatOffset() {
        return mc.screen instanceof ChatScreen ? CHAT_INPUT_HEIGHT : 0;
    }

    protected int bottomAnchor() {
        return screenHeight() - chatOffset();
    }

    protected int lineX(HudPosition pos, int lineWidth) {
        return pos.isLeft() ? SCREEN_MARGIN : screenWidth() - SCREEN_MARGIN - lineWidth;
    }

    protected int blockTop(HudPosition pos, int ownLines, int linesBelow, int gap) {
        if (pos.isBottom()) {
            return bottomAnchor() - SCREEN_MARGIN - (linesBelow + ownLines) * mc.font.lineHeight - gap;
        }
        return screenHeight() / 2;
    }

    // ------------------------------------------------------------- editor plumbing

    /** Modules call this once per frame with their real drawn rectangle (screen px). */
    protected void bounds(int x, int y, int w, int h) {
        boundX = x;
        boundY = y;
        boundW = w;
        boundH = h;
        renderedThisFrame = true;
    }

    public void beginFrame() {
        renderedThisFrame = false;
    }

    public boolean wasRendered() {
        return renderedThisFrame;
    }

    /** True when a custom editor position is saved for this element. */
    public boolean isCustomBound() {
        HudClientModule hud = GuizhouWare.moduleManager == null
                ? null : GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        return hud != null && hud.isCustomPosition(this);
    }

    public int boundX() { return boundX; }
    public int boundY() { return boundY; }
    public int boundW() { return boundW; }
    public int boundH() { return boundH; }

    /**
     * Final origin for this element: the module's anchored default, overridden by a
     * saved normalized position or a live editor drag. Always clamped onscreen.
     */
    protected int[] origin(int defX, int defY, int w, int h) {
        int x = defX;
        int y = defY;
        HudClientModule hud = GuizhouWare.moduleManager == null
                ? null : GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        if (hud != null) {
            int[] live = hud.livePosition(this);
            if (live != null) {
                x = live[0];
                y = live[1];
            } else if (hud.isCustomPosition(this)) {
                x = Math.round(hud.normalizedX(this) * screenWidth());
                y = Math.round(hud.normalizedY(this) * screenHeight());
            } else if (!(this instanceof dev.guizhou.features.modules.hud.TargetHudModule)) {
                var placed = hud.defaultPlacement(this, x, y, w, h);
                x = placed.x();
                y = placed.y();
            }
        }
        if (w > 0) x = Math.max(0, Math.min(x, Math.max(0, screenWidth() - w)));
        if (h > 0) y = Math.max(0, Math.min(y, Math.max(0, screenHeight() - h)));
        return new int[]{x, y};
    }
}
