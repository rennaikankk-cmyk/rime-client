package compat.fov;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Isolated reproduction of Mio's required FOV constant injection, not the Mio client. */
@Mixin(targets = "net.minecraft.class_315", remap = false)
public class MioStyleOptions {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 110), require = 1, remap = false)
    private int fovCompat$otherClientCap(int original) {
        int cap = Integer.getInteger("fovCompat.otherCap", 150);
        System.out.println("FOV_COMPAT_OTHER_INJECTION cap=" + cap);
        return cap;
    }
}
