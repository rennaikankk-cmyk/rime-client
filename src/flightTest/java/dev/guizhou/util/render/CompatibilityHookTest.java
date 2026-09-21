package dev.guizhou.util.render;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.InputStream;

/** Optional runtime visuals must still match the vanilla bytecode we actually ship against. */
public final class CompatibilityHookTest {
    private static final String MC = "net/minecraft/";
    public static void main(String[] args) throws Exception {
        boolean modern = CompatibilityHookTest.class.getClassLoader().getResource(MC + "resources/Identifier.class") != null;
        String resource = MC + "resources/" + (modern ? "Identifier" : "ResourceLocation");
        call("client/renderer/LevelRenderer", "renderLevel", modern
                ? MC + "client/renderer/ShaderManager;getPostChain(L" + resource + ";Ljava/util/Set;)L" + MC + "client/renderer/PostChain;"
                : MC + "client/renderer/PostChain;process(F)V");
        call("client/gui/Gui", "renderCameraOverlays", MC + "client/gui/Gui;renderTextureOverlay(L" + MC + "client/gui/GuiGraphics;L" + resource + ";F)V");
        call("client/player/LocalPlayer", modern ? "handlePortalTransitionEffect" : "handleConfusionTransitionEffect",
                MC + "client/gui/screens/Screen;" + (modern ? "isAllowedInPortal" : "isPauseScreen") + "()Z");
        call("world/level/block/SlimeBlock", "stepOn", MC + "world/entity/Entity;setDeltaMovement(L" + MC + "world/phys/Vec3;)V");
        if (modern) call("client/renderer/entity/EntityRenderDispatcher", "submit",
                MC + "client/renderer/entity/EntityRenderer;submit(L" + MC + "client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;L" + MC + "client/renderer/SubmitNodeCollector;L" + MC + "client/renderer/state/CameraRenderState;)V");
        boolean inFov = false, ended = false;
        int ranges = 0;
        for (MethodNode m : read(MC + "client/Options").methods) if (m.name.equals("<init>")) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof LdcInsnNode ldc && "options.fov".equals(ldc.cst)) inFov = true;
                if (inFov && insn instanceof MethodInsnNode call && call.owner.equals(MC + "client/OptionInstance$IntRange")
                        && call.name.equals("<init>") && call.desc.equals("(II)V")) ranges++;
                if (inFov && insn instanceof FieldInsnNode field && field.owner.equals(MC + "client/Options") && field.name.equals("fov")) {
                    inFov = false; ended = true;
                }
            }
        }
        if (!ended || ranges != 1) throw new AssertionError("FOV slice changed: " + ranges);
        System.out.println("CompatibilityHookTest passed: vanilla outline, overlay, portal, slime, entity render and scoped FOV targets remain available.");
    }
    private static void call(String owner, String method, String target) throws Exception {
        int matches = 0;
        for (MethodNode m : read(MC + owner).methods) if (m.name.equals(method)) {
            for (AbstractInsnNode insn : m.instructions) if (insn instanceof MethodInsnNode call
                    && (call.owner + ";" + call.name + call.desc).equals(target)) matches++;
        }
        if (matches == 0) throw new AssertionError("Vanilla compatibility target disappeared: " + owner + "." + method + " -> " + target);
    }
    private static ClassNode read(String name) throws Exception {
        try (InputStream stream = CompatibilityHookTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) throw new AssertionError("Missing vanilla class: " + name);
            ClassNode node = new ClassNode(); new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); return node;
        }
    }
}
