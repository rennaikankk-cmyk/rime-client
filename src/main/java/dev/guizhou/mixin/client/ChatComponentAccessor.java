package dev.guizhou.mixin.client;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("allMessages")
    List<GuiMessage> guizhouware$getAllMessages();

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> guizhouware$getTrimmedMessages();

    @Invoker("refreshTrimmedMessages")
    void guizhouware$refreshTrimmedMessages();
}
