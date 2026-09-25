package dev.guizhou.features.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.util.Iterator;

/** Coffee loop behind the title card. Full GIF frames, 70 ms each, cover-fit. */
public final class MenuLoop {
    private static final Identifier SOURCE = Identifier.fromNamespaceAndPath("guizhouware", "textures/gui/coffee-loop.gif");
    private static final long FRAME_NS = 70_000_000L;

    private static boolean tried;
    private static Identifier[] frames = new Identifier[0];
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
        pose.pushMatrix();
        pose.scale(cover, cover);
        g.blit(RenderPipelines.GUI_TEXTURED, frames[index], 0, 0, u, v, regionW, regionH, width, height);
        pose.popMatrix();
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
                Identifier[] ids = new Identifier[count];
                for (int i = 0; i < count; i++) {
                    BufferedImage frame = reader.read(i);
                    if (i == 0) {
                        width = frame.getWidth();
                        height = frame.getHeight();
                    }
                    NativeImage image = toNative(frame);
                    int frameIndex = i;
                    Identifier id = Identifier.fromNamespaceAndPath("guizhouware", "dynamic/menu_loop_" + frameIndex);
                    DynamicTexture texture = new DynamicTexture(() -> "rime menu loop " + frameIndex, image);
                    texture.upload();
                    mc.getTextureManager().register(id, texture);
                    ids[i] = id;
                }
                frames = ids;
                started = System.nanoTime();
                return true;
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            frames = new Identifier[0];
            return false;
        }
    }

    private static NativeImage toNative(BufferedImage frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        int[] rgb = frame.getRGB(0, 0, w, h, null, 0, w);
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        IntBuffer buf = MemoryUtil.memIntBuffer(image.getPointer(), w * h);
        for (int p : rgb) {
            int a = (p >>> 24) & 0xFF;
            int r = (p >>> 16) & 0xFF;
            int g = (p >>> 8) & 0xFF;
            int b = p & 0xFF;
            buf.put((a << 24) | (b << 16) | (g << 8) | r);
        }
        return image;
    }
}
