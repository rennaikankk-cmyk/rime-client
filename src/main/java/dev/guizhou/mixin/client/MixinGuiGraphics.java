package dev.guizhou.mixin.client;

import dev.guizhou.util.render.font.Fonts;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GuiGraphics.class)
public abstract class MixinGuiGraphics {

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
            at = @At("HEAD"), cancellable = true)
    private void guizhouware$onDrawString(Font font, String text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> cir) {
        if (text == null || text.isEmpty() || !Fonts.drawOverrideActive()) return;
        try {
            Fonts.renderOverrideString((GuiGraphics) (Object) this, text, x, y, color, shadow);
            cir.setReturnValue(x + Fonts.overrideWidth(text) + (shadow ? 1 : 0));
        } catch (Exception ignored) {}
    }

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I",
            at = @At("HEAD"), cancellable = true)
    private void guizhouware$onDrawFormatted(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> cir) {
        if (text == null || !Fonts.drawOverrideActive()) return;
        try {
            Fonts.renderOverrideText((GuiGraphics) (Object) this, text, x, y, color, shadow);
            cir.setReturnValue(x + Fonts.overrideWidth(text) + (shadow ? 1 : 0));
        } catch (Exception ignored) {}
    }
}
