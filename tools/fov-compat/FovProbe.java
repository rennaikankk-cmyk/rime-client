package compat.fov;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Checks the actual transformed Options instance in the production intermediary namespace. */
@Mixin(targets = "net.minecraft.class_315", remap = false)
public class FovProbe {
    @Inject(method = "<init>", at = @At("RETURN"), require = 1, remap = false)
    private void fovCompat$verify(CallbackInfo ci) throws ReflectiveOperationException {
        var field = getClass().getDeclaredField("field_1826");
        field.setAccessible(true);
        Object option = field.get(this);
        Object range = option.getClass().getMethod("method_41754").invoke(option);
        int min = (int) range.getClass().getMethod("comp_593").invoke(range);
        int max = (int) range.getClass().getMethod("comp_594").invoke(range);
        int expected = Integer.getInteger("fovCompat.expectedCap", 160);
        if (min != 30 || max != expected) {
            throw new AssertionError("FOV_COMPAT_FAIL range=" + min + ".." + max + " expected=30.." + expected);
        }
        System.out.println("FOV_COMPAT_PASS range=" + min + ".." + max);
    }
}
