package dev.guizhou.features.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.Random;

/**
 * Title-screen back layer: the original storm painting, with the same slow
 * drift, rain and lightning as the living-storm page. A custom background.png
 * still replaces this.
 */
public final class MenuStorm {
    private static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath("guizhouware", "textures/gui/storm.png");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("guizhouware", "dynamic/menu_storm");
    private static final int DROPS = 160;

    private static int width, height;
    private static boolean ready;
    private static float time;
    private static float nextBolt = 1.2f;
    private static float bolt;
    private static final float[] dropX = new float[DROPS];
    private static final float[] dropY = new float[DROPS];
    private static final float[] dropZ = new float[DROPS];
    private static final Random random = new Random(0x57012);

    private MenuStorm() {}

    public static void draw(GuiGraphics g, int screenW, int screenH, float deltaTicks) {
        if (!ensure()) return;
        float dt = Math.min(0.05f, Math.max(0f, deltaTicks) / 20f);
        time += dt;
        if (time >= nextBolt) {
            bolt = 1f;
            nextBolt = time + 2.4f + random.nextFloat() * 3.2f;
        }
        float flash = 0f;
        if (bolt > 0f) {
            flash = bolt;
            bolt = Math.max(0f, bolt - dt * 3.4f);
        }
        float shake = flash * 3.5f;
        float zoom = 1.05f + (float) Math.sin(time * 0.08) * 0.012f;
        float cover = Math.max(screenW / (float) width, screenH / (float) height) * zoom;
        int regionW = Math.max(1, Math.round(screenW / cover));
        int regionH = Math.max(1, Math.round(screenH / cover));
        int u = clamp((width - regionW) / 2 + Math.round((float) Math.sin(time * 0.05) * width * 0.008f), 0, Math.max(0, width - regionW));
        int v = clamp((height - regionH) / 2 + Math.round((float) Math.cos(time * 0.04) * height * 0.006f), 0, Math.max(0, height - regionH));

        var pose = g.pose();
        pose.pushPose();
        pose.translate((random.nextFloat() - 0.5f) * shake, (random.nextFloat() - 0.5f) * shake, 0);
        pose.scale(cover, cover, 1);
        g.blit(TEXTURE, 0, 0, u, v, regionW, regionH, width, height);
        pose.popPose();

        if (flash > 0.04f) {
            int alpha = Math.min(0x55, Math.round(flash * 70f));
            g.fill(0, 0, screenW, screenH, (alpha << 24) | 0xD5E2F0);
        }
        drawRain(g, screenW, screenH, dt, flash);
    }

    private static void drawRain(GuiGraphics g, int screenW, int screenH, float dt, float flash) {
        int alpha = Math.min(0x90, 0x38 + Math.round(flash * 80f));
        int color = (alpha << 24) | 0xD6E4EE;
        for (int i = 0; i < DROPS; i++) {
            dropY[i] += (90f + dropZ[i] * 220f) * dt;
            dropX[i] += 18f * dt;
            if (dropY[i] > screenH + 20) {
                dropY[i] = -20;
                dropX[i] = random.nextFloat() * screenW;
            }
            if (dropX[i] > screenW + 10) dropX[i] -= screenW + 20;
            int x = Math.round(dropX[i]);
            int y = Math.round(dropY[i]);
            int len = Math.round(8f + dropZ[i] * 16f);
            g.fill(x, y, x + 1, y + len, color);
        }
    }

    private static boolean ensure() {
        if (ready) return width > 0;
        ready = true;
        for (int i = 0; i < DROPS; i++) {
            dropX[i] = random.nextFloat() * 2000f;
            dropY[i] = random.nextFloat() * 1200f;
            dropZ[i] = random.nextFloat();
        }
        Minecraft mc = Minecraft.getInstance();
        var resource = mc.getResourceManager().getResource(SOURCE);
        if (resource.isEmpty()) return false;
        try (InputStream in = resource.get().open()) {
            NativeImage image = NativeImage.read(in);
            width = image.getWidth();
            height = image.getHeight();
            mc.getTextureManager().register(TEXTURE, new LinearStormTexture(image));
            return true;
        } catch (Exception e) {
            width = height = 0;
            return false;
        }
    }

    /** 1.21.1: linear filtering so the painting stays smooth under the cover scale. */
    private static final class LinearStormTexture extends DynamicTexture {
        LinearStormTexture(NativeImage image) {
            super(image);
            this.setFilter(true, false);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
