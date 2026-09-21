package dev.guizhou.util.render;

import com.mojang.blaze3d.shaders.Uniform;
import dev.guizhou.mixin.render.PostChainAccessor;
import dev.guizhou.mixin.render.PostPassAccessor;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;

import java.util.List;
import java.util.Map;

/**
 * 1.21.1 adapter replacing the 1.21.5+ UBO writer: uniforms are set per pass through
 * {@link EffectInstance#getUniform(String)}. Values are flat scalars; the shader JSONs
 * declare the same names with defaults.
 */
public final class UniformWriter {

    public sealed interface Value permits FVal, IVal {}
    public record FVal(float value) implements Value {}
    public record IVal(int value) implements Value {}

    public UniformWriter() {}

    public void setUniforms(PostChain chain, Map<String, List<Value>> configs) {
        if (configs.isEmpty() || !(chain instanceof PostChainAccessor accessor)) return;

        for (PostPass pass : accessor.guizhouware$getPasses()) {
            EffectInstance effect = ((PostPassAccessor) pass).guizhouware$getEffect();
            for (Map.Entry<String, List<Value>> entry : configs.entrySet()) {
                List<Value> values = entry.getValue();
                if (values.size() == 1) {
                    Uniform uniform = effect.getUniform(entry.getKey());
                    if (uniform == null) continue;
                    Value value = values.get(0);
                    if (value instanceof FVal f) uniform.set(f.value());
                    else if (value instanceof IVal i) uniform.set(i.value());
                } else {
                    // vector uniforms are declared as Name0..NameN scalars
                    for (int i = 0; i < values.size(); i++) {
                        Uniform uniform = effect.getUniform(entry.getKey() + i);
                        if (uniform == null) continue;
                        Value value = values.get(i);
                        if (value instanceof FVal f) uniform.set(f.value());
                        else if (value instanceof IVal in) uniform.set(in.value());
                    }
                }
            }
        }
    }
}
