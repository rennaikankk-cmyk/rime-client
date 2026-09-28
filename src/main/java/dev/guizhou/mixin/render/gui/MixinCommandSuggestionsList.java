package dev.guizhou.mixin.render.gui;


import com.mojang.brigadier.suggestion.Suggestion;
import dev.guizhou.features.commands.ui.CommandInputStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Draws the client-command suggestion list in the shell style; geometry and input handling stay vanilla. */
@Mixin(CommandSuggestions.SuggestionsList.class)
public abstract class MixinCommandSuggestionsList {
    @Shadow
    @Final
    private Rect2i rect;
    @Shadow
    @Final
    private String originalContents;
    @Shadow
    @Final
    private List<Suggestion> suggestionList;
    @Shadow
    private int offset;
    @Shadow
    private int current;
    @Shadow
    private Vec2 lastMouse;

    @Shadow
    public abstract void select(int index);

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void guizhouware$render(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (!CommandInputStyle.isClientCommand(originalContents)) {
            return;
        }
        ci.cancel();
        CommandInputStyle.measurePopup(rect, suggestionList, graphics.guiWidth());
        int rows = Math.min(suggestionList.size(), rect.getHeight() / 12);
        boolean moved = lastMouse.x != mouseX || lastMouse.y != mouseY;
        if (moved) {
            lastMouse = new Vec2(mouseX, mouseY);
        }
        boolean inside = mouseX > rect.getX() && mouseX < rect.getX() + rect.getWidth()
                && mouseY > rect.getY() && mouseY < rect.getY() + rows * 12;
        if (moved && inside) {
            int index = (mouseY - rect.getY()) / 12 + offset;
            if (index >= 0 && index < suggestionList.size()) {
                select(index);
            }
        }
        CommandInputStyle.renderPopup(graphics, rect, suggestionList, offset, rows, current, originalContents);
        if (rect.contains(mouseX, mouseY)) {
            // Native cursor selection is unavailable on Minecraft 1.21.1.
        }
    }
}
