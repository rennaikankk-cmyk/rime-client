package dev.guizhou.features.gui;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.util.compat.input.CharacterEvent;
import dev.guizhou.util.compat.input.KeyEvent;
import dev.guizhou.util.compat.input.MouseButtonEvent;
import dev.guizhou.util.compat.input.MouseButtonInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;

/** ClickGUI screen; every frame and input event is forwarded to the Hachimi glass panel style. */
public class GuizhouWareGui extends Screen {
    private static GuizhouWareGui instance;
    private static java.awt.Color colorClipboard;

    /** Legacy widget surface; the panel style keeps this list empty. */
    private final ArrayList<Widget> legacyWidgets = new ArrayList<>();
    public ArrayList<Widget> getComponents() { return legacyWidgets; }
    public int getTextOffset() { return -6; }
    public static java.awt.Color getColorClipboard() { return colorClipboard; }
    public static void setColorClipboard(java.awt.Color color) { colorClipboard = color; }

    private final PanelStyle hachimi = new PanelStyle(this, PanelStyle.Preset.HACHIMI);
    private ShellStyle current;

    public GuizhouWareGui() {
        super(Component.literal("Rime"));
        instance = this;
    }

    public static GuizhouWareGui getInstance() { if (instance == null) instance = new GuizhouWareGui(); return instance; }
    public static GuizhouWareGui getClickGui() { return getInstance(); }
    public static float getScale() { return instance == null ? 1 : instance.hachimi.scale(); }

    public void reload() { hachimi.reload(); }

    /** Opens the signed cloud preset browser. The licence gate stays in CloudConfigClient. */
    public void openCloudConfigs() {
        save();
        minecraft.setScreen(new CloudConfigsScreen(this));
    }

    /** Opens the ClickGUI focused on the HUD module so its appearance settings are one step away. */
    public void openHudAppearance(HudClientModule hud, String element) {
        minecraft.setScreen(this);
        hachimi.focusModule(hud);
    }

    public boolean capturesMovementKeys() { return hachimi.capturesMovementKeys(); }

    private ShellStyle active() {
        hachimi.sync();
        if (current != hachimi) {
            current = hachimi;
            hachimi.init();
        }
        return current;
    }

    @Override
    public void init() {
        dev.guizhou.util.render.UiSounds.open();
        ClickGuiModule gui = ClickGuiModule.getInstance();
        if (gui != null && !gui.isEnabled()) gui.enable();
        current = null;
        active();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) { active().render(g, mouseX, mouseY, delta); }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        return active().mouseClicked(new MouseButtonEvent(mx, my, new MouseButtonInfo(button, 0)), false);
    }
    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return active().mouseDragged(new MouseButtonEvent(mx, my, new MouseButtonInfo(button, 0)), dx, dy);
    }
    @Override public boolean mouseReleased(double mx, double my, int button) {
        return active().mouseReleased(new MouseButtonEvent(mx, my, new MouseButtonInfo(button, 0)));
    }
    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        return active().mouseScrolled(mx, my, horizontal, vertical);
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return active().keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
    }
    @Override public boolean charTyped(char codePoint, int modifiers) {
        return active().charTyped(new CharacterEvent(codePoint, modifiers));
    }

    @Override public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        current = null;
    }

    private void save() { if (GuizhouWare.configManager != null) GuizhouWare.configManager.save(); }

    @Override public void onClose() {
        save();
        dev.guizhou.util.render.UiSounds.close();
        minecraft.setScreen(null);
    }

    @Override public void removed() {
        hachimi.removed();
        save();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {}
}
