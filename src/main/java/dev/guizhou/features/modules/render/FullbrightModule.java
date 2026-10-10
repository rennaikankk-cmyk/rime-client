package dev.guizhou.features.modules.render;

import dev.guizhou.features.modules.Module;
import dev.guizhou.mixin.client.OptionInstanceAccessor;

public class FullbrightModule extends Module {
    private double previousGamma;
    /** True once the user's real gamma has been captured; restored only then. */
    private boolean captured;

    public FullbrightModule() {
        super("Fullbright", "Sets gamma to maximum for full brightness", Category.RENDER);
    }

    @Override
    public void onEnable() {
        // Boot-time auto-enable can run before Minecraft.options exists; the
        // baseline is then captured lazily by onTick before the first apply.
        // Restoring the field default (0.0) would overwrite the user's real
        // gamma preference, so capture strictly before the first write.
        if (mc.options != null) {
            previousGamma = mc.options.gamma().get();
            captured = true;
            setGamma(16.0);
        }
    }

    @Override
    public void onDisable() {
        if (captured) setGamma(previousGamma);
        captured = false;
    }

    @Override
    public void onTick() {
        if (!captured && mc.options != null) {
            previousGamma = mc.options.gamma().get();
            captured = true;
        }
        if (!captured) return;
        setGamma(16.0);
    }

    @SuppressWarnings("unchecked")
    private void setGamma(double value) {
        if (mc.options == null) return;
        ((OptionInstanceAccessor<Double>) (Object) mc.options.gamma()).guizhouware$setValue(value);
    }
}
