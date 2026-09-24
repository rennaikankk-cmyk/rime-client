package dev.guizhou.features.gui;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.features.modules.client.HudClientModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * ClickGUI screen. The layout comes from ClickGuiModule.Style: two classic
 * panel presets and the modern card browser. Every style writes through the
 * normal Setting path, so events and persistence are identical.
 */
public class GuizhouWareGui extends Screen {
    private static GuizhouWareGui instance;

    private final PanelStyle purple = new PanelStyle(this, PanelStyle.Preset.PURPLE);
    private final PanelStyle blue = new PanelStyle(this, PanelStyle.Preset.BLUE);
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
        purple.reload();
        blue.reload();
        modern.reload();
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

    public boolean capturesMovementKeys() {
        return style().capturesMovementKeys();
    }

    private ShellStyle style() {
        if (detour != null) return detour;
        ClickGuiModule gui = ClickGuiModule.getInstance();
        ClickGuiModule.Style chosen = gui == null ? ClickGuiModule.Style.PURPLE : gui.style.getValue();
        return switch (chosen) {
            case PURPLE -> purple;
            case BLUE -> blue;
            case MODERN -> modern;
        };
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

    /** Blurs the world behind panel styles. The fullscreen capture is reused for a few frames. */
    private int blurCapturedAt = Integer.MIN_VALUE;

    void blurWorld(GuiGraphics g) {
        int now = minecraft != null && minecraft.level != null ? (int) minecraft.level.getGameTime() : 0;
        if (blurCapturedAt == Integer.MIN_VALUE || now - blurCapturedAt >= 8 || now < blurCapturedAt) {
            renderBlurredBackground(g);
            blurCapturedAt = now;
        }
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
        blurCapturedAt = Integer.MIN_VALUE;
        active();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        active().render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubled) { return active().mouseClicked(e, doubled); }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) { return active().mouseDragged(e, dx, dy); }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) { return active().mouseReleased(e); }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        return active().mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent e) { return active().keyPressed(e); }

    @Override
    public boolean charTyped(CharacterEvent e) { return active().charTyped(e); }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        current = null;
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
        purple.removed();
        blue.removed();
        modern.removed();
        detour = null;
        save();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) { }
}
