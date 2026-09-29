package dev.guizhou.features.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/** One ClickGUI layout. GuizhouWareGui owns the Screen and forwards frames and input here. */
abstract class ShellStyle {
    protected final GuizhouWareGui screen;
    protected final Minecraft mc = Minecraft.getInstance();
    protected int width, height;

    ShellStyle(GuizhouWareGui screen) {
        this.screen = screen;
    }

    final void sync() {
        width = screen.width;
        height = screen.height;
    }

    void init() { }

    abstract void render(GuiGraphics g, int mouseX, int mouseY, float delta);

    boolean mouseClicked(MouseButtonEvent e, boolean doubled) { return true; }

    boolean mouseDragged(MouseButtonEvent e, double dx, double dy) { return false; }

    boolean mouseReleased(MouseButtonEvent e) { return true; }

    boolean mouseScrolled(double mx, double my, double horizontal, double vertical) { return true; }

    boolean keyPressed(KeyEvent e) { return true; }

    boolean charTyped(CharacterEvent e) { return true; }

    boolean capturesMovementKeys() { return false; }

    void removed() { }

    float scale() { return 1; }

    void reload() { }

    static String labelOf(Object value) {
        if (value instanceof Labeled labeled) return labeled.label();
        // Enum constants display through the catalog when a Chinese label exists; unknown
        // constants pass through unchanged so partial coverage never breaks a dropdown.
        return dev.guizhou.util.lang.ClientLanguage.text(String.valueOf(value));
    }
}
