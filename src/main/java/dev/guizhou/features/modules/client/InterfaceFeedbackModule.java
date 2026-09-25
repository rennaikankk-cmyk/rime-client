package dev.guizhou.features.modules.client;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;

/** Local-only interface polish: UI sound cues. The loading-screen artwork follows the same switches. */
public class InterfaceFeedbackModule extends Module {
    public final Setting<Boolean> bootAnimation = bool("启动动画", true);
    public final Setting<Boolean> interfaceSounds = bool("界面音效", true);
    public final Setting<Integer> volume = num("音效音量 (%)", 70, 0, 100);
    public final Setting<Boolean> moduleToggleSounds = bool("模块开关音效", true);
    public final Setting<Boolean> startupSound = bool("启动音效", true);

    public InterfaceFeedbackModule() {
        super("InterfaceFeedback", "Branded loading screen and interface sound cues", Category.CLIENT);
        this.enabled.setValueNoEvent(true);
    }

    /** Null before module registration (early loading frames); callers treat that as defaults. */
    public static InterfaceFeedbackModule get() {
        return GuizhouWare.moduleManager == null ? null
                : GuizhouWare.moduleManager.getModuleByClass(InterfaceFeedbackModule.class);
    }
}
