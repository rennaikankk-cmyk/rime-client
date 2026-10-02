package dev.guizhou.util.render;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.InputStream;

/** Optional runtime visuals must still match the vanilla bytecode we actually ship against. */
public final class CompatibilityHookTest {
    private static final String MC = "net/minecraft/";
    public static void main(String[] args) throws Exception {
        boolean modern = CompatibilityHookTest.class.getClassLoader().getResource(MC + "client/renderer/ShaderManager.class") != null;
        String resource = MC + "resources/" + (modern ? "ResourceLocation" : "ResourceLocation");
        if (!modern) {
            MethodNode center = read("dev/guizhou/mixin/entity/MixinClientPlayerEntity").methods.stream()
                    .filter(m -> m.name.equals("guizhouware$surroundCenter")).findFirst().orElseThrow();
            int centers = 0, impulses = 0;
            for (AbstractInsnNode insn : center.instructions) {
                if (insn instanceof MethodInsnNode c && c.owner.equals("dev/guizhou/features/modules/combat/SurroundModule")
                        && c.name.equals("centerInput")) centers++;
                if (insn instanceof FieldInsnNode f && f.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD) {
                    if (f.name.equals("input")) throw new AssertionError("Centering must retain live KeyboardInput");
                    if (f.owner.equals(MC + "client/player/Input") && f.name.endsWith("Impulse")) impulses++;
                }
            }
            if (centers != 1 || impulses != 2) throw new AssertionError("Native surround centering hook disconnected");
            String send = "(L" + MC + "network/protocol/Packet;L" + MC + "network/PacketSendListener;Z)V";
            if (read(MC + "network/Connection").methods.stream().noneMatch(m -> m.name.equals("sendPacket") && m.desc.equals(send)))
                throw new AssertionError("Native network callback changed");
            String handler = send.substring(0, send.length() - 2) + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
            if (read("dev/guizhou/mixin/network/MixinClientConnection").methods.stream()
                    .noneMatch(m -> m.name.equals("sendImmediately") && m.desc.equals(handler)))
                throw new AssertionError("Network injection must use native PacketSendListener");
        }
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
