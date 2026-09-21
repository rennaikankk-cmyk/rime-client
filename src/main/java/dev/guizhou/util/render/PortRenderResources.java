package dev.guizhou.util.render;

import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;

/** Render resources participate in the native client shader reload lifecycle. */
public final class PortRenderResources {
    private PortRenderResources() {}
    public static void init() {
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            HandShaderChain.invalidate();
            WorldChamsChain.invalidate();
            HandShaderRender.invalidate();
            SeeThroughRender.invalidate();
            SkyboxRenderer.register(context);
        });
    }
}
