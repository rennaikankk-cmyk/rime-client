package dev.guizhou.mixin.render.gui;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Gui.class)
public abstract class MixinHeldItemTooltip {
    private static final int GUIZHOUWARE$Y_OFFSET = 9;

    @ModifyArg(
        method = "renderSelectedItemName",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"
        ),
        index = 3
    )
    private int guizhouware$shiftHeldItemName(int y) {
        return y - GUIZHOUWARE$Y_OFFSET;
    }
}
