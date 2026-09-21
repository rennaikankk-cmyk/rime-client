package dev.guizhou.mixin.client;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(Options.class)
public class MixinOptions {

    // Leave the vanilla constant available to clients such as Mio, which require
    // their own ModifyConstant injection. Only adjust the FOV range argument.
    @ModifyArg(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance$IntRange;<init>(II)V"),
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.fov"),
                    to = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;fov:Lnet/minecraft/client/OptionInstance;")),
            index = 1, require = 1, allow = 1)
    private int guizhouware$raiseFovCap(int originalMax) {
        return Math.max(originalMax, 160);
    }
}
