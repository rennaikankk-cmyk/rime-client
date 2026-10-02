package dev.guizhou.features.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.guizhou.GuizhouWare;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import dev.guizhou.util.render.GuiTextureCompat;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streams a customer-chosen MP4 as the main-menu background.
 *
 * Decode runs on one low-priority daemon thread (pure-Java H.264 via jcodec —
 * no natives, so it works everywhere the client does). Frames are downscaled
 * to {@link #MAX_EDGE} and throttled to {@link #FPS} before ever touching GL;
 * the render thread only uploads the newest finished frame into a single
 * reused DynamicTexture and loops the clip forever. WebM has no pure-Java
 * decoder and is rejected by the selector with a clear message.
 */
final class MenuVideoPlayer {
    private static final int MAX_EDGE = 640;
    private static final int FPS = 12;
    private static final long FRAME_MS = 1000 / FPS;

    private static Thread worker;
    private static volatile boolean stop;
    private static final AtomicReference<int[]> latest = new AtomicReference<>();
    private static volatile int frameW, frameH;
    private static volatile String error = "";

    private DynamicTexture texture;
    private ResourceLocation id;
    private NativeImage imageRef;
    private int uploadedW, uploadedH;
    private long lastUpload;

    static void start(Path file) {
        stopWorker();
        stop = false;
        error = "";
        latest.set(null);
        frameW = frameH = 0;
        worker = new Thread(() -> decodeLoop(file), "rime-menu-video");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.start();
    }

    static void stopWorker() {
        stop = true;
        Thread w = worker;
        if (w != null) w.interrupt();
        worker = null;
    }

    static boolean failed() {
        return !error.isEmpty();
    }

    static String error() {
        return error;
    }

    private static void decodeLoop(Path file) {
        while (!stop) {
            SeekableByteChannel ch = null;
            try {
                ch = NIOUtils.readableChannel(file.toFile());
                FrameGrab grab = FrameGrab.createFrameGrab(ch);
                Picture pic;
                long nextDue = 0;
                while (!stop && (pic = grab.getNativeFrame()) != null) {
                    long now = System.currentTimeMillis();
                    if (now < nextDue) {
                        try { Thread.sleep(nextDue - now); } catch (InterruptedException e) { return; }
                    }
                    nextDue = System.currentTimeMillis() + FRAME_MS;
                    push(pic);
                }
                // clip finished: loop by reopening the channel
            } catch (Throwable t) {
                if (t instanceof ClosedMarker || t.getCause() instanceof ClosedMarker) return;
                error = t.getClass().getSimpleName();
                GuizhouWare.LOGGER.warn("menu video decode failed: {}", error);
                return;
            } finally {
                closeQuietly(ch);
            }
        }
    }

    private static final class ClosedMarker extends RuntimeException {}

    private static void closeQuietly(SeekableByteChannel ch) {
        if (ch == null) return;
        try { ch.close(); } catch (Exception ignored) {}
    }

    private static void push(Picture pic) {
        int w = pic.getWidth(), h = pic.getHeight();
        if (w <= 0 || h <= 0) return;
        byte[] planeY = pic.getPlaneData(0);
        byte[] planeU = pic.getPlaneData(1);
        byte[] planeV = pic.getPlaneData(2);
        if (planeY == null || planeU == null || planeV == null) return;
        int cw = w, ch = h;
        BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        int[] yuv = new int[cw * ch];
        for (int y = 0; y < ch; y++) {
            int row = y * cw, crow = (y >> 1) * (cw >> 1);
            for (int x = 0; x < cw; x++) {
                int yy = planeY[row + x] & 0xFF;
                int u = planeU[crow + (x >> 1)] & 0xFF;
                int v = planeV[crow + (x >> 1)] & 0xFF;
                int c = yy - 16;
                int d = u - 128;
                int e = v - 128;
                int r = clamp((298 * c + 409 * e + 128) >> 8);
                int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = clamp((298 * c + 516 * d + 128) >> 8);
                yuv[row + x] = (r << 16) | (g << 8) | b;
            }
        }
        img.setRGB(0, 0, cw, ch, yuv, 0, cw);
        float scale = Math.min(1f, MAX_EDGE / (float) Math.max(w, h));
        int dw = Math.max(2, Math.round(w * scale) & ~1);
        int dh = Math.max(2, Math.round(h * scale) & ~1);
        BufferedImage small = img;
        if (dw != w || dh != h) {
            small = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pen = small.createGraphics();
            try {
                pen.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                pen.drawImage(img, 0, 0, dw, dh, null);
            } finally {
                pen.dispose();
            }
        }
        frameW = dw;
        frameH = dh;
        latest.set(small.getRGB(0, 0, dw, dh, null, 0, dw));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** Uploads and draws the newest frame; the texture is created lazily on the render thread. */
    boolean draw(GuiGraphics g, int screenW, int screenH) {
        int[] argb = latest.get();
        if (argb == null || frameW <= 0 || frameH <= 0 || screenW <= 0 || screenH <= 0) return false;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (texture == null || uploadedW != frameW || uploadedH != frameH) {
            if (texture != null && id != null) mc.getTextureManager().release(id);
            imageRef = new NativeImage(NativeImage.Format.RGBA, frameW, frameH, false);
            texture = new DynamicTexture(imageRef);
            id = ResourceLocation.fromNamespaceAndPath("guizhouware", "dynamic/menu_video_" + System.nanoTime());
            mc.getTextureManager().register(id, texture);
            uploadedW = frameW;
            uploadedH = frameH;
            lastUpload = 0;
        }
        if (now - lastUpload >= FRAME_MS) {
            java.nio.IntBuffer buf = org.lwjgl.system.MemoryUtil.memIntBuffer(((dev.guizhou.mixin.client.NativeImageAccessor)(Object)imageRef).rime$pixels(), frameW * frameH);
            for (int p : argb) {
                int a = (p >>> 24) & 0xFF;
                int rr = (p >>> 16) & 0xFF;
                int gg = (p >>> 8) & 0xFF;
                int bb = p & 0xFF;
                buf.put((a << 24) | (bb << 16) | (gg << 8) | rr);
            }
            texture.upload();
            lastUpload = now;
        }
        float cover = Math.max(screenW / (float) frameW, screenH / (float) frameH);
        int regionW = Math.max(1, Math.min(frameW, Math.round(screenW / cover)));
        int regionH = Math.max(1, Math.min(frameH, Math.round(screenH / cover)));
        int u = Math.max(0, (frameW - regionW) / 2);
        int v = Math.max(0, (frameH - regionH) / 2);
        var pose = g.pose();
        pose.pushPose();
        pose.scale(cover, cover, 1);
        GuiTextureCompat.blit(g, id, 0, 0, u, v, regionW, regionH, frameW, frameH);
        pose.popPose();
        return true;
    }
}
