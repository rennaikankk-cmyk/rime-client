package dev.guizhou.mixin.input;

import dev.guizhou.event.impl.input.MouseInputEvent;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.guizhou.util.traits.Util.EVENT_BUS;

@Mixin(MouseHandler.class)
public class MixinMouse {
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if (EVENT_BUS.post(new MouseInputEvent(button, action))) {
            ci.cancel();
        }
    }
}
