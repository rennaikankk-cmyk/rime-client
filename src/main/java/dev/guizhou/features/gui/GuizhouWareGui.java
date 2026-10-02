package dev.guizhou.features.gui;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.features.modules.client.HudClientModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import dev.guizhou.util.compat.input.CharacterEvent;
import dev.guizhou.util.compat.input.KeyEvent;
import dev.guizhou.util.compat.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * ClickGUI screen. Glass columns and the compact category list share PanelStyle.
 * The modern card browser is only the config and HUD detour.
 */
public class GuizhouWareGui extends Screen {
    private static GuizhouWareGui instance;
    private static java.awt.Color colorClipboard;
    private final java.util.ArrayList<Widget> legacyWidgets = new java.util.ArrayList<>();
    public java.util.ArrayList<Widget> getComponents() { return legacyWidgets; }
    public static java.awt.Color getColorClipboard() { return colorClipboard; }
    public static void setColorClipboard(java.awt.Color color) { colorClipboard = color; }

    private final PanelStyle hachimi = new PanelStyle(this, PanelStyle.Preset.HACHIMI);
    private final ModernStyle modern = new ModernStyle(this);
    /** A style entered for one task (configs, HUD appearance) without changing the saved choice. */
    private ShellStyle detour;
    private ShellStyle current;

    public GuizhouWareGui() {
        super(Component.literal("Rime"));
        instance = this;
    }

    public static GuizhouWareGui getInstance() {
        if (instance == null) instance = new GuizhouWareGui();
        return instance;
    }

    public static GuizhouWareGui getClickGui() { return getInstance(); }

    public static float getScale() { return instance == null ? 1 : instance.style().scale(); }

    public void reload() {
        hachimi.reload();
    }

    public void openHudAppearance(HudClientModule hud, String element) {
        enterDetour();
        modern.openHudAppearance(hud, element, this::leaveDetour);
    }

    /** Opens the modern configuration manager on top of the saved style. */
    public void openConfigs() {
        enterDetour();
        modern.openConfigs(this::leaveDetour);
    }

    /** Opens the signed cloud preset browser. The licence gate stays in CloudConfigClient. */
    public void openCloudConfigs() {
        save();
        minecraft.setScreen(new CloudConfigsScreen(this));
    }

    public boolean capturesMovementKeys() {
        return style().capturesMovementKeys();
    }

    private ShellStyle style() {
        if (detour != null) return detour;
        ClickGuiModule gui = ClickGuiModule.getInstance();
        return hachimi;
    }

    /** Syncs the viewport and initialises a style the first frame it becomes active. */
    private ShellStyle active() {
        ShellStyle style = style();
        style.sync();
        if (style != current) {
            current = style;
            style.init();
        }
        return style;
    }

    private void enterDetour() {
        detour = modern;
        current = null;
        active();
    }

    private void leaveDetour() {
        detour = null;
        current = null;
    }

    /**
     * Not used by the open ClickGUI. Vanilla {@code renderBlurredBackground} copies the
     * framebuffer every call; the panels dim with one fill instead of a per-frame readback.
     */
    void blurWorld(GuiGraphics g) {
        renderBlurredBackground(0.0f);
    }

    private boolean shown;

    @Override
    public void init() {
        if (!shown) dev.guizhou.util.render.UiSounds.open();
        shown = true;
        ClickGuiModule gui = ClickGuiModule.getInstance();
        if (gui != null && !gui.isEnabled()) gui.enable();
        detour = null;
        modern.clearBack();
        current = null;
        active();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        active().render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) { return active().mouseClicked(new MouseButtonEvent(mx, my, new dev.guizhou.util.compat.input.MouseButtonInfo(button, modifiers())), false); }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) { return active().mouseDragged(new MouseButtonEvent(mx, my, new dev.guizhou.util.compat.input.MouseButtonInfo(button, modifiers())), dx, dy); }

    @Override
    public boolean mouseReleased(double mx, double my, int button) { return active().mouseReleased(new MouseButtonEvent(mx, my, new dev.guizhou.util.compat.input.MouseButtonInfo(button, modifiers()))); }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        return active().mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return active().keyPressed(new KeyEvent(keyCode, scanCode, modifiers)); }

    @Override
    public boolean charTyped(char codepoint, int modifiers) { return active().charTyped(new CharacterEvent(codepoint, modifiers)); }

    @Override
    public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        current = null;
    }

    private static int modifiers() {
        return (Screen.hasShiftDown() ? org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT : 0)
                | (Screen.hasControlDown() ? org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL : 0)
                | (Screen.hasAltDown() ? org.lwjgl.glfw.GLFW.GLFW_MOD_ALT : 0);
    }

    private void save() {
        if (GuizhouWare.configManager != null) GuizhouWare.configManager.save();
    }

    @Override
    public void onClose() {
        save();
        dev.guizhou.util.render.UiSounds.close();
        minecraft.setScreen(null);
    }

    @Override
    public void removed() {
        shown = false;
        modern.removed();
        detour = null;
        save();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) { }
}
