package dev.guizhou.mixin.client;

import dev.guizhou.util.player.ChatUtil;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChatComponent.class)
public abstract class MixinChatComponent {

    @Unique private static final double guizhouware$SPEED = 12.0;
    @Unique private static final int guizhouware$MAX_LINES = 3;

    @Unique private boolean guizhouware$pushed;
    @Unique private float guizhouware$offset;
    @Unique private GuiMessage.Line guizhouware$lastHead;
    @Unique private long guizhouware$lastTimeMs;

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V",
            at = @At("HEAD")
    )
    private void guizhouware$slideIn(GuiGraphics graphics, int tickCount, int mouseX, int mouseY,
                                  boolean focused, CallbackInfo ci) {
        guizhouware$pushed = false;

        float dy = guizhouware$update(((ChatComponentAccessor) this).guizhouware$getTrimmedMessages());
        if (dy != 0f) {
            graphics.pose().pushPose();
            graphics.pose().translate(0f, dy, 0);
            guizhouware$pushed = true;
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V",
            at = @At("RETURN")
    )
    private void guizhouware$slideInEnd(GuiGraphics graphics, int tickCount, int mouseX, int mouseY,
                                     boolean focused, CallbackInfo ci) {
        if (guizhouware$pushed) {
            graphics.pose().popPose();
            guizhouware$pushed = false;
        }
    }

    @Unique
    private float guizhouware$update(List<GuiMessage.Line> lines) {
        long now = System.currentTimeMillis();

        float dt = Math.min((now - guizhouware$lastTimeMs) / 1000f, 0.1f);
        guizhouware$lastTimeMs = now;

        GuiMessage.Line head = lines.isEmpty() ? null : lines.get(0);
        if (head != guizhouware$lastHead) {

            if (guizhouware$lastHead != null && head != ChatUtil.noAnimateHead) {

                int newLines = 0;
                for (GuiMessage.Line line : lines) {
                    if (line == guizhouware$lastHead) break;
                    newLines++;
                }
                float pitch = guizhouware$linePitch();
                guizhouware$offset = Math.min(guizhouware$offset + newLines * pitch, guizhouware$MAX_LINES * pitch);
            }
            guizhouware$lastHead = head;
        }

        guizhouware$offset *= (float) Math.exp(-guizhouware$SPEED * dt);
        if (guizhouware$offset < 0.5f) {
            guizhouware$offset = 0f;
        }
        return guizhouware$offset;
    }

    @Unique
    private float guizhouware$linePitch() {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.options.chatScale().get();
        double spacing = mc.options.chatLineSpacing().get();
        return (float) (9.0 * (spacing + 1.0) * scale);
    }
}
