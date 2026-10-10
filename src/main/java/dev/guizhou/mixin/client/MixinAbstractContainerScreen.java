package dev.guizhou.mixin.client;

import dev.guizhou.features.modules.render.ShulkerPreviewModule;
import dev.guizhou.util.render.HudDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public class MixinAbstractContainerScreen<T extends AbstractContainerMenu> {

    @Shadow
    @Final
    protected T menu;

    @Shadow
    protected Slot hoveredSlot;

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    protected int imageWidth;

    @Inject(method = "renderContents", at = @At("HEAD"))
    private void onDecorateContainer(GuiGraphics graphics, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        HudDraw.containerAccent(graphics, leftPos, topPos, imageWidth);
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void onRenderShulkerPreview(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        ShulkerPreviewModule module = ShulkerPreviewModule.get();
        if (module == null || !module.isEnabled()) return;
        if (hoveredSlot == null || !hoveredSlot.hasItem()) return;
        if (!menu.getCarried().isEmpty()) return;

        if (module.renderPreview(graphics, hoveredSlot.getItem(), mouseX, mouseY)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderContents", at = @At("TAIL"))
    private void onRenderShulkerList(GuiGraphics graphics, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        ShulkerPreviewModule module = ShulkerPreviewModule.get();
        if (module == null || !module.isEnabled() || !module.shulkerList.getValue()) return;
        if (!isEnderChestScreen()) return;

        Minecraft mc = Minecraft.getInstance();
        Container playerInv = mc.player != null ? mc.player.getInventory() : null;

        List<ItemStack> shulkers = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (playerInv != null && slot.container == playerInv) continue;
            ItemStack stack = slot.getItem();
            if (ShulkerPreviewModule.isShulkerBox(stack) && stack.has(DataComponents.CONTAINER)) {
                shulkers.add(stack);
            }
        }
        module.renderShulkerList(graphics, shulkers, leftPos);
    }

    private boolean isEnderChestScreen() {
        Component title = ((Screen) (Object) this).getTitle();
        return title != null
                && title.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals("container.enderchest");
    }

    @Inject(method = "renderSlot", at = @At("TAIL"))
    private void onRenderShulkerThumbnail(GuiGraphics graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        ShulkerPreviewModule module = ShulkerPreviewModule.get();
        if (module == null || !module.isEnabled()) return;
        if (slot == null || !slot.hasItem()) return;
        module.renderThumbnail(graphics, slot.getItem(), slot.x, slot.y);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void onMouseDragged(MouseButtonEvent mouseButtonEvent, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getAbilities().instabuild) return;

        ItemStack cursorStack = menu.getCarried();
        if (cursorStack == null || cursorStack.isEmpty()) return;
        if (!cursorStack.isStackable() || cursorStack.getItem() instanceof MapItem || cursorStack.getItem() instanceof BannerItem) {
            cir.setReturnValue(true);
        }
    }
}
