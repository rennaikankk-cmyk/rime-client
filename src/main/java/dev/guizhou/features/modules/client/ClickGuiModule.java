package dev.guizhou.features.modules.client;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.ClientEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.gui.GuizhouWareGui;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.settings.Setting;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

public class ClickGuiModule extends Module {
    private static ClickGuiModule INSTANCE;

    public Setting<String>  prefix           = str("Prefix", "rime");
    public Setting<Theme>   theme            = mode("Theme", Theme.GUIZHOUWARE);
    public Setting<Color>   customColor      = color("Custom Color", 114, 168, 216, 220);
    public Setting<Boolean> smooth           = bool("Smooth", true);
    public Setting<Double> menuScale = num("菜单大小 (%)", 100.0, 60.0, 150.0);
    public Setting<Integer> rainbowHue       = num("Delay", 240, 0, 600);
    public Setting<Float>   rainbowBrightness = num("Brightness", 200.0f, 1.0f, 255.0f);
    public Setting<Float>   rainbowSaturation = num("Saturation", 140.0f, 1.0f, 255.0f);

    public Setting<Boolean> clickGuiFont = bool("ClickGUI Font", false);
    public Setting<Boolean> hudFont      = bool("HUD Font", false);
    public Setting<String>  fontName     = str("Font Name", "");

    /* IB (InternalBeyond) 色板：雾蓝玻璃 + 烛火暗红点缀，低饱和统一气质 */
    private static final Color CAT_COMBAT   = new Color(160,  82,  82); // Infernal 烛火暗红
    private static final Color CAT_WORLD    = new Color(123, 168, 160); // 雾青
    private static final Color CAT_RENDER   = new Color(114, 168, 216); // 雾蓝 (--ibr-acc #72a8d8)
    private static final Color CAT_MOVEMENT = new Color(201, 168, 106); // 烛光金
    private static final Color CAT_PLAYER   = new Color(178, 192, 216); // 月光银灰 (--silver)
    private static final Color CAT_FUNNY    = new Color(154, 134, 184); // 紫雾
    private static final Color CAT_CLIENT   = new Color( 90, 105, 135); // 深夜蓝灰
    private static final Color CAT_HUD      = new Color(128, 176, 224); // 雾浅蓝

    private static final Color GUIZHOUWARE_ACCENT = new Color( 80, 128, 176); // IB 深雾蓝 #5080b0
    private static final Color GUIZHOUWARE_MODULE = new Color(114, 168, 216); // IB 亮雾蓝 #72a8d8

    public Color categoryAccent(Module.Category cat) {
        return categoryAccent(cat, 0f);
    }

    public Color categoryAccent(Module.Category cat, float yOffset) {
        Theme t = theme.getValue();
        if (t == Theme.RAINBOW)  return rainbowAt(yOffset);
        if (t == Theme.CUSTOM)   return customColor.getValue();
        if (t == Theme.GUIZHOUWARE) return GUIZHOUWARE_ACCENT;
        if (cat == null) return CAT_CLIENT;
        switch (cat) {
            case COMBAT:   return CAT_COMBAT;
            case WORLD:    return CAT_WORLD;
            case RENDER:   return CAT_RENDER;
            case MOVEMENT: return CAT_MOVEMENT;
            case PLAYER:   return CAT_PLAYER;
            case FUNNY:    return CAT_FUNNY;
            case CLIENT:   return CAT_CLIENT;
            case HUD:      return CAT_HUD;
            default:       return CAT_CLIENT;
        }
    }

    public Color moduleAccent(Module.Category cat, float yOffset) {
        if (theme.getValue() == Theme.GUIZHOUWARE) return GUIZHOUWARE_MODULE;
        return categoryAccent(cat, yOffset);
    }

    public Color chatAccent() {
        Theme t = theme.getValue();
        if (t == Theme.RAINBOW)  return rainbowAt(0f);
        if (t == Theme.CUSTOM)   return customColor.getValue();
        if (t == Theme.GUIZHOUWARE) return GUIZHOUWARE_ACCENT;
        return Color.WHITE;
    }

    public Color rainbowAt(float yOffset) {
        return dev.guizhou.util.ColorUtil.rainbow((int)(yOffset / 10f * rainbowHue.getValue()));
    }

    public float getExpandSpeed() {
        return smooth.getValue() ? 0.5f : 1f;
    }

    public ClickGuiModule() {
        super("ClickGui", "Opens the ClickGui", Module.Category.CLIENT);

        this.bind.setValue(new Bind(GLFW.GLFW_KEY_RIGHT_SHIFT));
        this.bindMode.setVisibility(v -> false);

        rainbowHue.setVisibility(v -> theme.getValue() == Theme.RAINBOW);
        rainbowBrightness.setVisibility(v -> theme.getValue() == Theme.RAINBOW);
        rainbowSaturation.setVisibility(v -> theme.getValue() == Theme.RAINBOW);
        customColor.setVisibility(v -> theme.getValue() == Theme.CUSTOM);
        // Retain legacy font keys for old config compatibility. Native screens and
        // the rebuilt HUD share the bundled Geist/CJK chain, so these are not UI controls.
        clickGuiFont.setVisibility(v -> false);
        hudFont.setVisibility(v -> false);
        fontName.setVisibility(v -> false);

        INSTANCE = this;
    }

    @Subscribe
    public void onSettingChange(ClientEvent event) {
        if (event.getType() == ClientEvent.Type.SETTING_UPDATE
                && event.getSetting().getFeature().equals(this)) {
            if (event.getSetting().equals(this.prefix)) {
                GuizhouWare.commandManager.setCommandPrefix(this.prefix.getPlannedValue());
                Command.sendMessage("Prefix set to {global} %s",
                        GuizhouWare.commandManager.getCommandPrefix());
            }
            if (event.getSetting().equals(this.clickGuiFont)
                    || event.getSetting().equals(this.hudFont)
                    || event.getSetting().equals(this.fontName)) {
                dev.guizhou.util.render.font.Fonts.markDirty();
            }
        }
    }

    @Override
    public void onEnable() {
        if (nullCheck()) return;
        if (!(mc.screen instanceof GuizhouWareGui)) mc.setScreen(GuizhouWareGui.getClickGui());
    }

    @Override
    public void onLoad() {
        Color fixedAccent = new Color(120, 175, 220, 220);
        GuizhouWare.colorManager.register("ui",          () -> fixedAccent);
        GuizhouWare.colorManager.register("chat",        this::chatAccent);
        GuizhouWare.colorManager.register("chatBracket", this::chatAccent);
        GuizhouWare.commandManager.setCommandPrefix(this.prefix.getValue());
    }

    @Override
    public void onTick() {
        if (!(mc.screen instanceof GuizhouWareGui) && !(mc.screen instanceof dev.guizhou.features.gui.HudEditorScreen)) {
            this.disable();
        }
    }

    public static ClickGuiModule getInstance() {
        return INSTANCE;
    }

    public enum Theme {
        GUIZHOUWARE, COLORS, CUSTOM, RAINBOW
    }
}
