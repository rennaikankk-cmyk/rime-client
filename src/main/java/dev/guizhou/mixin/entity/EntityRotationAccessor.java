package dev.guizhou.mixin.entity;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocalPlayer.class)
public interface EntityRotationAccessor {
    @Accessor("xBob")
    float guizhouware$getXBob();

    @Accessor("xBob")
    void guizhouware$setXBob(float value);

    @Accessor("xBobO")
    float guizhouware$getXBobO();

    @Accessor("xBobO")
    void guizhouware$setXBobO(float value);

    @Accessor("yBob")
    float guizhouware$getYBob();

    @Accessor("yBob")
    void guizhouware$setYBob(float value);

    @Accessor("yBobO")
    float guizhouware$getYBobO();

    @Accessor("yBobO")
    void guizhouware$setYBobO(float value);

    @Accessor("lastOnGround")
    boolean guizhouware$getLastOnGround();
}
