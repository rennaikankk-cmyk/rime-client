package dev.guizhou.features.modules.extra;

import dev.guizhou.features.settings.Setting;

public final class ViewLockModule extends UtilityModule {
    private final Setting<Boolean> yaw = bool("Yaw", true), pitch = bool("Pitch", true);
    private final Setting<Double> yawValue = num("YawValue", 0.0, -180.0, 180.0), pitchValue = num("PitchValue", 0.0, -90.0, 90.0);
    public ViewLockModule() { super("ViewLock", "Locks the camera axes to configurable angles; captures current angles on enable.", Category.PLAYER); }
    @Override public void onEnable() {
        super.onEnable();
        if (!nullCheck()) { yawValue.setValue((double)mc.player.getYRot()); pitchValue.setValue((double)mc.player.getXRot()); }
    }
    @Override protected void tick() {
        if (mc.screen != null) return;
        if (yaw.getValue()) mc.player.setYRot(yawValue.getValue().floatValue());
        if (pitch.getValue()) mc.player.setXRot(pitchValue.getValue().floatValue());
    }
}
