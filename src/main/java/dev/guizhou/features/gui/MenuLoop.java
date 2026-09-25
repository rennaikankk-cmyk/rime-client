package dev.guizhou.features.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Iterator;

/** Coffee loop behind the title card. Full GIF frames, 70 ms each, cover-fit. */
public final class MenuLoop {
    private static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath("guizhouware", "textures/gui/coffee-loop.gif");
    private static final long FRAME_NS = 70_000_000L;

    private static boolean tried;
    private static ResourceLocation[] frames = new ResourceLocation[0];
    private static int width, height;
    private static long started;

    private MenuLoop() {}

    public static boolean draw(GuiGraphics g, int screenW, int screenH) {
        if (!ensure()) return false;
        int index = (int) ((System.nanoTime() - started) / FRAME_NS) % frames.length;
        float cover = Math.max(screenW / (float) width, screenH / (float) height);
        int regionW = Math.max(1, Math.round(screenW / cover));
        int regionH = Math.max(1, Math.round(screenH / cover));
        int u = Math.max(0, (width - regionW) / 2);
        int v = Math.max(0, (height - regionH) / 2);
        var pose = g.pose();
        pose.pushPose();
        pose.scale(cover, cover, 1f);
        g.blit(frames[index], 0, 0, u, v, regionW, regionH, width, height);
        pose.popPose();
        return true;
    }

    private static boolean ensure() {
        if (tried) return frames.length > 0;
        tried = true;
        Minecraft mc = Minecraft.getInstance();
        var resource = mc.getResourceManager().getResource(SOURCE);
        if (resource.isEmpty()) return false;
        try (InputStream in = resource.get().open()) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try (var stream = ImageIO.createImageInputStream(in)) {
                reader.setInput(stream, false, false);
                int count = reader.getNumImages(true);
                if (count <= 0) return false;
                ResourceLocation[] ids = new ResourceLocation[count];
                for (int i = 0; i < count; i++) {
                    BufferedImage frame = reader.read(i);
                    if (i == 0) {
                        width = frame.getWidth();
                        height = frame.getHeight();
                    }
                    NativeImage image = toNative(frame);
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath("guizhouware", "dynamic/menu_loop_" + i);
                    mc.getTextureManager().register(id, new LinearLoopTexture(image));
                    ids[i] = id;
                }
                frames = ids;
                started = System.nanoTime();
                return true;
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            frames = new ResourceLocation[0];
            return false;
        }
    }

    private static NativeImage toNative(BufferedImage frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        int[] rgb = frame.getRGB(0, 0, w, h, null, 0, w);
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            int a = (p >>> 24) & 0xFF;
            int r = (p >>> 16) & 0xFF;
            int g = (p >>> 8) & 0xFF;
            int b = p & 0xFF;
            image.setPixelRGBA(i % w, i / w, (a << 24) | (b << 16) | (g << 8) | r);
        }
        return image;
    }

    private static final class LinearLoopTexture extends DynamicTexture {
        LinearLoopTexture(NativeImage image) {
            super(image);
            this.setFilter(true, false);
        }
    }
}
