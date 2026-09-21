package dev.guizhou.manager;

import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import java.util.function.BooleanSupplier;

/** Registers settings on the owning module, never as a separate global feature. */
public final class RotationOptions {
    public final Setting<Float> yawStep;
    public final Setting<Boolean> moveFix;
    private final BooleanSupplier enabled;

    public RotationOptions(Module owner, Setting<Boolean> rotate) {
        this(owner, rotate::getValue, rotate.getPage());
    }

    public RotationOptions(Module owner, BooleanSupplier visible, String page) {
        enabled = () -> owner.isEnabled() && visible.getAsBoolean();
        yawStep = owner.num("YawStep", 180f, 5f, 180f).setPage(page).setVisibility(v -> visible.getAsBoolean());
        moveFix = owner.bool("MoveFix", true).setPage(page).setVisibility(v -> visible.getAsBoolean() && yawStep.getValue() < 180f);
    }

    public RotationRequest request(String id, int priority, float yaw, float pitch, RotationRequest.Mode mode) {
        RotationRequest request = new RotationRequest(id, priority, yaw, pitch, mode, false, true);
        request.yawStep = yawStep.getValue();
        request.moveFix = moveFix.getValue();
        request.continueWhile = enabled;
        return request;
    }
}
