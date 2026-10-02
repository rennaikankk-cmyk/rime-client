package dev.guizhou.mixin.client;
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Keep video uploads as a single native buffer copy on 1.21.1. */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
    @Accessor("pixels") long rime$pixels();
}
