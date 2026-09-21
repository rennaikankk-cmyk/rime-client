package dev.guizhou.mixin.entity;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocalPlayer.class)
public interface LocalPlayerInputAccessor {
    @Accessor("wasShiftKeyDown")
    boolean guizhouware$lastSentSneaking();
}
