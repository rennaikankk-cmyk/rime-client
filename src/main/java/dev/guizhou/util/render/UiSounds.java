package dev.guizhou.util.render;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.InterfaceFeedbackModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Interface sound cues. Safe to call from any thread and every frame: calls are
 * marshalled to the client thread, gated by {@link InterfaceFeedbackModule},
 * rate limited per cue, and silent until the sound engine has loaded the sounds.
 */
public final class UiSounds {
    public static final SoundEvent HOVER = event("ui.hover");
    public static final SoundEvent CLICK = event("ui.click");
    public static final SoundEvent TOGGLE_ON = event("ui.toggle_on");
    public static final SoundEvent TOGGLE_OFF = event("ui.toggle_off");
    public static final SoundEvent OPEN = event("ui.open");
    public static final SoundEvent CLOSE = event("ui.close");
    public static final SoundEvent SUCCESS = event("ui.success");
    public static final SoundEvent ERROR = event("ui.error");
    public static final SoundEvent STARTUP = event("ui.startup");

    private static final int INTERFACE = 0, MODULE = 1, BOOT = 2;
    private static final float DEFAULT_VOLUME = 0.7f;
    private static final long MS = 1_000_000L;

    private static final Cue HOVER_CUE = new Cue(HOVER, INTERFACE, 40, 0.03f);
    private static final Cue CLICK_CUE = new Cue(CLICK, INTERFACE, 25, 0.02f);
    private static final Cue SWITCH_ON = new Cue(TOGGLE_ON, INTERFACE, 30, 0f);
    private static final Cue SWITCH_OFF = new Cue(TOGGLE_OFF, INTERFACE, 30, 0f);
    private static final Cue MODULE_ON = new Cue(TOGGLE_ON, MODULE, 30, 0f);
    private static final Cue MODULE_OFF = new Cue(TOGGLE_OFF, MODULE, 30, 0f);
    private static final Cue OPEN_CUE = new Cue(OPEN, INTERFACE, 120, 0f);
    private static final Cue CLOSE_CUE = new Cue(CLOSE, INTERFACE, 120, 0f);
    private static final Cue SUCCESS_CUE = new Cue(SUCCESS, INTERFACE, 250, 0f);
    private static final Cue ERROR_CUE = new Cue(ERROR, INTERFACE, 250, 0f);
    private static final Cue STARTUP_CUE = new Cue(STARTUP, BOOT, 3000, 0f);

    // Client thread only.
    private static Object hoverTarget;
    private static boolean failureLogged;

    private UiSounds() {}

    /** Same registration path as the AutoHorse sound; call from {@code onInitialize}. */
    public static void register() {
        for (SoundEvent sound : new SoundEvent[]{HOVER, CLICK, TOGGLE_ON, TOGGLE_OFF, OPEN, CLOSE, SUCCESS, ERROR, STARTUP}) {
            Registry.register(BuiltInRegistries.SOUND_EVENT, sound.location(), sound);
        }
    }

    /**
     * Very light tick when the pointer enters a new target. Call every frame with the
     * currently hovered key (module, setting, button id...) or {@code null} when nothing
     * is hovered; repeats for the same target are ignored until it changes.
     */
    public static void hover(Object target) {
        if (!onClientThread(() -> hover(target))) return;
        if (Objects.equals(target, hoverTarget)) return;
        hoverTarget = target;
        if (target != null) play(HOVER_CUE);
    }

    /** Hover tick without target tracking; only the minimum interval applies. */
    public static void hover() {
        play(HOVER_CUE);
    }

    public static void click() {
        play(CLICK_CUE);
    }

    /** Switch / checkbox inside an interface. Module on/off uses {@link #moduleToggle(boolean)}. */
    public static void toggle(boolean on) {
        play(on ? SWITCH_ON : SWITCH_OFF);
    }

    /** Player-initiated module state change; governed by the module toggle sound setting. */
    public static void moduleToggle(boolean on) {
        play(on ? MODULE_ON : MODULE_OFF);
    }

    public static void open() {
        play(OPEN_CUE);
    }

    public static void close() {
        play(CLOSE_CUE);
    }

    public static void success() {
        play(SUCCESS_CUE);
    }

    public static void error() {
        play(ERROR_CUE);
    }

    public static void startup() {
        play(STARTUP_CUE);
    }

    private static void play(Cue cue) {
        if (!onClientThread(() -> play(cue))) return;
        float volume = volume(cue.channel);
        if (volume <= 0f) return;
        long now = System.nanoTime();
        if (cue.last != 0 && now - cue.last < cue.gapNanos) return;
        try {
            SoundManager sounds = Minecraft.getInstance().getSoundManager();
            if (sounds == null || sounds.getSoundEvent(cue.sound.location()) == null) return;
            cue.last = now;
            float pitch = cue.jitter == 0f ? 1f
                    : 1f + (ThreadLocalRandom.current().nextFloat() * 2f - 1f) * cue.jitter;
            sounds.play(SimpleSoundInstance.forUI(cue.sound, pitch, volume));
        } catch (RuntimeException failure) {
            if (!failureLogged) {
                failureLogged = true;
                GuizhouWare.LOGGER.warn("Interface sound {} failed; later failures are silent", cue.sound.location(), failure);
            }
        }
    }

    /** False when the call was deferred to (or cannot reach) the client thread. */
    private static boolean onClientThread(Runnable retry) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;
        if (mc.isSameThread()) return true;
        mc.execute(retry);
        return false;
    }

    private static float volume(int channel) {
        InterfaceFeedbackModule settings = InterfaceFeedbackModule.get();
        if (settings == null) return DEFAULT_VOLUME;
        if (!settings.isEnabled()) return 0f;
        boolean on = switch (channel) {
            case MODULE -> settings.moduleToggleSounds.getValue();
            case BOOT -> settings.startupSound.getValue();
            default -> settings.interfaceSounds.getValue();
        };
        return on ? Math.clamp(settings.volume.getValue() / 100f, 0f, 1f) : 0f;
    }

    private static SoundEvent event(String path) {
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("guizhouware", path));
    }

    private static final class Cue {
        final SoundEvent sound;
        final int channel;
        final long gapNanos;
        final float jitter;
        long last;

        Cue(SoundEvent sound, int channel, long gapMillis, float jitter) {
            this.sound = sound;
            this.channel = channel;
            this.gapNanos = gapMillis * MS;
            this.jitter = jitter;
        }
    }
}
