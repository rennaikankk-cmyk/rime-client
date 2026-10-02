package dev.guizhou.features.modules.client;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.ClientEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.gui.GuizhouWareGui;
import dev.guizhou.features.gui.Labeled;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.settings.Setting;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

public class ClickGuiModule extends Module {
    private static ClickGuiModule INSTANCE;

    public final Setting<dev.guizhou.util.lang.ClientLanguage.Language> language =
            mode("Language", dev.guizhou.util.lang.ClientLanguage.Language.CHINESE)
                    .described("界面语言：中文或 English，即时生效。");
    private final Setting<Integer> languageRevision = num("LanguageRevision", 2, 2, 2);

    public final Setting<Boolean> clientCompatibility = bool("ClientCompatibility", true).setPage("Compatibility")
            .described("Yield overlapping flight, armor and item actions to active SlimefunHelper or RusherHack modules.");
    public Setting<String>  prefix           = str("Prefix", "rime");
    public Setting<Boolean> blurBackground   = bool("BlurBackground", true);
    public Setting<Boolean> descriptions     = bool("Descriptions", true);
    /** Forces 1.12 packet gates (dual view, legacy firework/input, crystal/TP 1.12) even if Via did not detect them. */
    public Setting<Boolean> protocol112      = bool("1.12协议", false);
    public Setting<String>  menuNotice       = str("MenuNotice", "");
    public Setting<String>  menuWatermark    = str("MenuWatermark", "");
    public Setting<Theme>   theme            = mode("Theme", Theme.GUIZHOUWARE);
    public Setting<Frame>   frame            = mode("菜单样式", Frame.GLASS);
    public Setting<Color>   customColor      = color("Custom Color", 114, 168, 216, 220);
    public Setting<Boolean> smooth           = bool("Smooth", true);
    public Setting<Double> menuScale = num("菜单大小 (%)", 100.0, 60.0, 150.0);
    public Setting<Integer> rainbowHue       = num("Delay", 240, 0, 600);
    public Setting<Float>   rainbowBrightness = num("Brightness", 200.0f, 1.0f, 255.0f);
    public Setting<Float>   rainbowSaturation = num("Saturation", 140.0f, 1.0f, 255.0f);

    public Setting<Boolean> clickGuiFont = bool("ClickGUI Font", false);
    public Setting<Boolean> hudFont      = bool("HUD Font", false);
    public Setting<String>  fontName     = str("Font Name", "");
    /** Comma-separated module names pinned in the ClickGUI favourites view. */
    public Setting<String>  favorites    = str("Favorites", "");
    /** Panel positions and collapse flags per panel style: STYLE.CATEGORY=x,y,collapsed;... */
    public Setting<String>  panelLayout  = str("PanelLayout", "");

    /* IB (InternalBeyond) 色板：雾蓝玻璃 + 烛火暗红点缀，低饱和统一气质 */
    private static final Color CAT_COMBAT   = new Color(160,  82,  82); // Infernal 烛火暗红
    private static final Color CAT_WORLD    = new Color(123, 168, 160); // 雾青
    private static final Color CAT_RENDER   = new Color(114, 168, 216); // 雾蓝 (--ibr-acc #72a8d8)
    private static final Color CAT_MOVEMENT = new Color(201, 168, 106); // 烛光金
    private static final Color CAT_PLAYER   = new Color(178, 192, 216); // 月光银灰 (--silver)
    private static final Color CAT_FUNNY    = new Color(154, 134, 184); // 紫雾
    private static final Color CAT_CLIENT   = new Color( 90, 105, 135); // 深夜蓝灰
    private static final Color CAT_HUD      = new Color(128, 176, 224); // 雾浅蓝

    private static final Color GUIZHOUWARE_ACCENT = new Color(224, 138, 108); // terracotta #E08A6C
    private static final Color GUIZHOUWARE_MODULE = new Color(224, 138, 108);

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
        // the rebuilt HUD share the Anthropic Serif/CJK chain, so these are not UI controls.
        clickGuiFont.setVisibility(v -> false);
        hudFont.setVisibility(v -> false);
        fontName.setVisibility(v -> false);
        favorites.setVisibility(v -> false);
        panelLayout.setVisibility(v -> false);

        INSTANCE = this;
        languageRevision.setVisibility(v -> false);
        dev.guizhou.util.lang.ClientLanguage.bind(() -> language.getValue()
                == dev.guizhou.util.lang.ClientLanguage.Language.CHINESE);
    }

    @Override
    public void fromJson(com.google.gson.JsonElement element) {
        super.fromJson(element);
        if (element != null && element.isJsonObject()) {
            var object = element.getAsJsonObject();
            var revision = object.get("LanguageRevision");
            if (revision == null || !revision.isJsonPrimitive() || !"2".equals(revision.getAsString())) {
                // Revision 2: Chinese is now the default audience language. Existing configs were
                // force-migrated to English back in revision 1 without an explicit user choice,
                // so they migrate back to Chinese; users who genuinely prefer English switch once.
                language.setValue(dev.guizhou.util.lang.ClientLanguage.Language.CHINESE);
            }
            languageRevision.setValue(2);
        }
    }

    @Subscribe
    public void onSettingChange(ClientEvent event) {
        if (event.getType() == ClientEvent.Type.SETTING_UPDATE
                && event.getSetting().getFeature().equals(this)) {
            if (event.getSetting().equals(this.prefix)) {
                GuizhouWare.commandManager.setCommandPrefix(this.prefix.getPlannedValue());
                Command.sendMessage(dev.guizhou.util.lang.ClientLanguage.text("命令前缀已改为 {accent} %s"),
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
        Color fixedAccent = new Color(224, 138, 108, 230);
        GuizhouWare.colorManager.register("ui",          () -> fixedAccent);
        GuizhouWare.colorManager.register("chat",        this::chatAccent);
        GuizhouWare.colorManager.register("chatBracket", this::chatAccent);
        GuizhouWare.commandManager.setCommandPrefix(this.prefix.getValue());
    }

    @Override
    public void onTick() {
        if (!(mc.screen instanceof GuizhouWareGui) && !(mc.screen instanceof dev.guizhou.features.gui.HudEditorScreen)
                && !(mc.screen instanceof dev.guizhou.features.gui.CloudConfigsScreen)) {
            this.disable();
        }
    }

    public static ClickGuiModule getInstance() {
        return INSTANCE;
    }

    public enum Theme {
        GUIZHOUWARE, COLORS, CUSTOM, RAINBOW
    }

    /** Glass columns, or one tight category stack with the open row filled. */
    public enum Frame implements Labeled {
        GLASS("玻璃"), LIST("分类列");

        private final String label;

        Frame(String label) { this.label = label; }

        @Override public String label() { return dev.guizhou.util.lang.ClientLanguage.text(label); }
    }

}
