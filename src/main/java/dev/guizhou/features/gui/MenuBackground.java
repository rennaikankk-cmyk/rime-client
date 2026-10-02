package dev.guizhou.features.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import dev.guizhou.util.render.GuiTextureCompat;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.awt.AlphaComposite;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.FileDialog;
import java.awt.HeadlessException;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Customer title-screen background. A missing choice keeps the built-in storm/coffee clips.
 * Images and GIFs are copied under {@code guizhouware/background.<ext>} and named from
 * {@code guizhouware/menu-background.txt}. A dropped {@code background.png} still wins when
 * that config file has never been written. Video paths are remembered and not decoded.
 */
public final class MenuBackground {
    private static final int MAX_FRAMES = 48;
    private static final int MAX_EDGE = 1280;
    private static final long DEFAULT_FRAME_NS = 70_000_000L;

    private enum Kind { BUILTIN, IMAGE, GIF, VIDEO, MISSING }

    private static long token = Long.MIN_VALUE;
    private static Kind kind = Kind.BUILTIN;
    private static String label = "";
    private static String banner = "";
    private static Path activeFile;
    private static ResourceLocation[] frames = new ResourceLocation[0];
    private static long[] delays = new long[0];
    private static int imageW, imageH;
    private static long started;
    private static final List<ResourceLocation> live = new ArrayList<>();
    private static MenuVideoPlayer video;

    private MenuBackground() {}

    /** Reloads when the config or chosen file changes. Safe to call every frame. */
    public static void prepare() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Selection selection = read(root(mc));
        long next = token(selection);
        if (next == token) return;
        token = next;
        release(mc);
        kind = selection.kind;
        label = selection.label;
        activeFile = selection.file;
        banner = "";
        imageW = imageH = 0;
        started = System.nanoTime();
        if (selection.kind == Kind.VIDEO) {
            String name = selection.file == null ? "" : selection.file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".webm")) {
                banner = dev.guizhou.util.lang.ClientLanguage.text("webm 暂不支持，请转换为 mp4");
                return;
            }
            video = new MenuVideoPlayer();
            MenuVideoPlayer.start(selection.file);
            return;
        }
        MenuVideoPlayer.stopWorker();
        video = null;
        if (selection.kind == Kind.MISSING) {
            banner = dev.guizhou.util.lang.ClientLanguage.text("背景文件不存在");
            return;
        }
        if (selection.kind == Kind.BUILTIN || selection.file == null) return;
        try {
            if (selection.kind == Kind.GIF) loadGif(mc, selection.file);
            else loadStill(mc, selection.file);
        } catch (Exception e) {
            release(mc);
            kind = Kind.MISSING;
            banner = dev.guizhou.util.lang.ClientLanguage.text("背景无法读取");
        }
    }

    /** Cover-fits a custom still, GIF or MP4. Built-in clips return false. */
    public static boolean draw(GuiGraphics g, int screenW, int screenH) {
        prepare();
        if (kind == Kind.VIDEO && video != null) {
            if (video.draw(g, screenW, screenH)) return true;
            if (MenuVideoPlayer.failed()) {
                banner = dev.guizhou.util.lang.ClientLanguage.text("视频无法解码，已回退默认背景");
                return false;
            }
            return false; // first frame still decoding; built-in clip shows through
        }
        if (frames.length == 0 || imageW <= 0 || imageH <= 0 || screenW <= 0 || screenH <= 0) return false;
        int index = frameIndex();
        float cover = Math.max(screenW / (float) imageW, screenH / (float) imageH);
        int regionW = Math.max(1, Math.min(imageW, Math.round(screenW / cover)));
        int regionH = Math.max(1, Math.min(imageH, Math.round(screenH / cover)));
        int u = Math.max(0, (imageW - regionW) / 2);
        int v = Math.max(0, (imageH - regionH) / 2);
        var pose = g.pose();
        pose.pushPose();
        pose.scale(cover, cover, 1);
        GuiTextureCompat.blit(g, frames[index], 0, 0, u, v, regionW, regionH, imageW, imageH);
        pose.popPose();
        return true;
    }

    public static String banner() {
        prepare();
        return banner == null || banner.isEmpty() ? null : banner;
    }

    public static String statusLine() {
        prepare();
        return switch (kind) {
            case BUILTIN -> dev.guizhou.util.lang.ClientLanguage.text("当前：默认动态背景");
            case VIDEO -> dev.guizhou.util.lang.ClientLanguage.text("当前：") + label
                    + (MenuVideoPlayer.failed() ? dev.guizhou.util.lang.ClientLanguage.text("（视频无法解码）")
                                                 : dev.guizhou.util.lang.ClientLanguage.text("（视频循环播放中）"));
            case MISSING -> dev.guizhou.util.lang.ClientLanguage.text("当前：") + (label.isEmpty() ? dev.guizhou.util.lang.ClientLanguage.text("无法读取") : label);
            default -> dev.guizhou.util.lang.ClientLanguage.text("当前：") + label;
        };
    }

    public static String displayPath() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return "";
        Selection selection = read(root(mc));
        if (selection.file == null) return "";
        return selection.file.toAbsolutePath().toString();
    }

    public static boolean dialogsAvailable() {
        try {
            return !GraphicsEnvironment.isHeadless();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Native Windows file dialog. Null when cancelled, headless, or the dialog cannot open. */
    public static Path pickFile() {
        if (!dialogsAvailable()) return null;
        try {
            FileDialog dialog = new FileDialog((Frame) null, dev.guizhou.util.lang.ClientLanguage.text("选择主菜单背景"), FileDialog.LOAD);
            dialog.setFile("*.png;*.jpg;*.jpeg;*.gif;*.mp4;*.webm");
            dialog.setVisible(true);
            String name = dialog.getFile();
            String directory = dialog.getDirectory();
            dialog.dispose();
            if (name == null || directory == null) return null;
            return Path.of(directory, name);
        } catch (HeadlessException ignored) {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Copies an image/GIF into the game folder, or remembers a video path. Null means success. */
    public static String use(String text) {
        if (text == null || text.isBlank()) return dev.guizhou.util.lang.ClientLanguage.text("请先选择文件");
        Path source;
        try {
            source = Path.of(text.trim());
        } catch (Exception e) {
            return dev.guizhou.util.lang.ClientLanguage.text("路径无效");
        }
        if (!Files.isRegularFile(source)) return dev.guizhou.util.lang.ClientLanguage.text("找不到该文件");
        String ext = extension(source);
        if (!supported(ext)) return dev.guizhou.util.lang.ClientLanguage.text("仅支持 png、jpg、gif、mp4、webm");
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return dev.guizhou.util.lang.ClientLanguage.text("游戏目录不可用");
        Path folder = root(mc);
        try {
            Files.createDirectories(folder);
            String stored;
            if (isVideo(ext)) {
                stored = "video:" + source.toAbsolutePath();
            } else {
                String filename = "background." + ext;
                Path dest = folder.resolve(filename);
                if (!source.toAbsolutePath().normalize().equals(dest.toAbsolutePath().normalize())) {
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                stored = filename;
            }
            Files.writeString(folder.resolve("menu-background.txt"), stored + "\n", StandardCharsets.UTF_8);
            token = Long.MIN_VALUE;
            return null;
        } catch (Exception e) {
            return dev.guizhou.util.lang.ClientLanguage.text("无法保存背景");
        }
    }

    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        try {
            Path folder = root(mc);
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("menu-background.txt"), "default\n", StandardCharsets.UTF_8);
            token = Long.MIN_VALUE;
        } catch (Exception ignored) {
        }
    }

    private static int frameIndex() {
        if (frames.length <= 1) return 0;
        long total = 0;
        for (long delay : delays) total += Math.max(1L, delay);
        if (total <= 0) return 0;
        long elapsed = Math.floorMod(System.nanoTime() - started, total);
        long acc = 0;
        for (int i = 0; i < frames.length; i++) {
            acc += Math.max(1L, i < delays.length ? delays[i] : DEFAULT_FRAME_NS);
            if (elapsed < acc) return i;
        }
        return 0;
    }

    private static void loadStill(Minecraft mc, Path file) throws Exception {
        String ext = extension(file);
        NativeImage image;
        if ("png".equals(ext)) {
            try (InputStream in = Files.newInputStream(file)) {
                image = NativeImage.read(in);
            }
        } else {
            BufferedImage read = ImageIO.read(file.toFile());
            if (read == null) throw new IllegalArgumentException("unreadable");
            image = toNative(scale(read));
        }
        if (image.getWidth() > MAX_EDGE || image.getHeight() > MAX_EDGE) {
            BufferedImage scaled = scale(toBuffered(image));
            image.close();
            image = toNative(scaled);
        }
        register(mc, image, 0);
    }

    private static void loadGif(Minecraft mc, Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IllegalStateException("no gif reader");
            ImageReader reader = readers.next();
            try (var stream = ImageIO.createImageInputStream(in)) {
                reader.setInput(stream, false, false);
                int count = reader.getNumImages(true);
                if (count <= 0) throw new IllegalArgumentException("empty gif");
                int canvasW = reader.getWidth(0);
                int canvasH = reader.getHeight(0);
                Node screen = child(tree(reader.getStreamMetadata(), "javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                canvasW = Math.max(canvasW, number(attr(screen, "logicalScreenWidth")));
                canvasH = Math.max(canvasH, number(attr(screen, "logicalScreenHeight")));
                float scale = Math.min(1f, MAX_EDGE / (float) Math.max(canvasW, canvasH));
                int destW = Math.max(1, Math.round(canvasW * scale));
                int destH = Math.max(1, Math.round(canvasH * scale));
                int limit = Math.min(count, MAX_FRAMES);
                if (count > MAX_FRAMES) banner = dev.guizhou.util.lang.ClientLanguage.text("GIF 较长，只播放前 ") + MAX_FRAMES + dev.guizhou.util.lang.ClientLanguage.text(" 帧");
                frames = new ResourceLocation[limit];
                delays = new long[limit];
                BufferedImage canvas = new BufferedImage(destW, destH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D pen = canvas.createGraphics();
                pen.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                BufferedImage snapshot = null;
                int prevDisposal = 0;
                int prevX = 0, prevY = 0, prevW = 0, prevH = 0;
                try {
                    for (int i = 0; i < limit; i++) {
                        if (prevDisposal == 2) {
                            pen.setComposite(AlphaComposite.Clear);
                            pen.fillRect(prevX, prevY, prevW, prevH);
                            pen.setComposite(AlphaComposite.SrcOver);
                        } else if (prevDisposal == 3 && snapshot != null) {
                            pen.setComposite(AlphaComposite.Src);
                            pen.drawImage(snapshot, 0, 0, null);
                            pen.setComposite(AlphaComposite.SrcOver);
                        }
                        Node imageMeta = tree(reader.getImageMetadata(i), "javax_imageio_gif_image_1.0");
                        Node control = child(imageMeta, "GraphicControlExtension");
                        Node descriptor = child(imageMeta, "ImageDescriptor");
                        int disposal = disposal(attr(control, "disposalMethod"));
                        if (disposal == 3) snapshot = copy(canvas);
                        int x = Math.round(number(attr(descriptor, "imageLeftPosition")) * scale);
                        int y = Math.round(number(attr(descriptor, "imageTopPosition")) * scale);
                        BufferedImage frame = reader.read(i);
                        int fw = Math.max(1, Math.round(frame.getWidth() * scale));
                        int fh = Math.max(1, Math.round(frame.getHeight() * scale));
                        pen.drawImage(frame, x, y, fw, fh, null);
                        prevDisposal = disposal;
                        prevX = x;
                        prevY = y;
                        prevW = fw;
                        prevH = fh;
                        long delay = number(attr(control, "delayTime"));
                        if (delay <= 1) delay = 7;
                        register(mc, toNative(canvas), i);
                        delays[i] = delay * 10_000_000L;
                    }
                } finally {
                    pen.dispose();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static void register(Minecraft mc, NativeImage image, int index) {
        if (index == 0) {
            imageW = image.getWidth();
            imageH = image.getHeight();
        }
        if (frames.length == 0) {
            frames = new ResourceLocation[index + 1];
            delays = new long[index + 1];
        }
        if (index >= frames.length) {
            ResourceLocation[] grown = new ResourceLocation[index + 1];
            long[] grownDelays = new long[index + 1];
            System.arraycopy(frames, 0, grown, 0, frames.length);
            System.arraycopy(delays, 0, grownDelays, 0, delays.length);
            frames = grown;
            delays = grownDelays;
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("guizhouware", "dynamic/menu_custom_" + token + "_" + index);
        DynamicTexture texture = new DynamicTexture(image);
        texture.upload();
        mc.getTextureManager().register(id, texture);
        frames[index] = id;
        live.add(id);
    }

    private static void release(Minecraft mc) {
        MenuVideoPlayer.stopWorker();
        video = null;
        for (ResourceLocation id : live) {
            try {
                mc.getTextureManager().release(id);
            } catch (Exception ignored) {
            }
        }
        live.clear();
        frames = new ResourceLocation[0];
        delays = new long[0];
        imageW = imageH = 0;
    }

    private static Selection read(Path folder) {
        Path config = folder.resolve("menu-background.txt");
        try {
            if (Files.isRegularFile(config)) {
                String line = Files.readString(config).trim();
                if (line.isEmpty() || line.equalsIgnoreCase("default")) return Selection.builtin();
                if (line.startsWith("video:")) {
                    String raw = line.substring("video:".length()).trim();
                    Path file = Path.of(raw);
                    String name = file.getFileName() == null ? raw : file.getFileName().toString();
                    if (!Files.isRegularFile(file)) return new Selection(Kind.MISSING, file, name);
                    return new Selection(Kind.VIDEO, file, name);
                }
                Path file = folder.resolve(line).normalize();
                if (!file.startsWith(folder.normalize())) return new Selection(Kind.MISSING, null, line);
                return fileSelection(file);
            }
        } catch (Exception e) {
            return new Selection(Kind.MISSING, null, dev.guizhou.util.lang.ClientLanguage.text("配置无法读取"));
        }
        Path legacy = folder.resolve("background.png");
        if (Files.isRegularFile(legacy)) return fileSelection(legacy);
        return Selection.builtin();
    }

    private static Selection fileSelection(Path file) {
        String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        if (!Files.isRegularFile(file)) return new Selection(Kind.MISSING, file, name);
        String ext = extension(file);
        if (isVideo(ext)) return new Selection(Kind.VIDEO, file, name);
        if ("gif".equals(ext)) return new Selection(Kind.GIF, file, name);
        if ("png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext)) return new Selection(Kind.IMAGE, file, name);
        return new Selection(Kind.MISSING, file, name);
    }

    private static long token(Selection selection) {
        try {
            long value = selection.kind.ordinal();
            Path file = selection.file;
            if (file != null && Files.exists(file)) {
                value = value * 31 + Files.size(file);
                value = value * 31 + Files.getLastModifiedTime(file).toMillis();
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                Path config = root(mc).resolve("menu-background.txt");
                if (Files.isRegularFile(config)) value = value * 31 + Files.getLastModifiedTime(config).toMillis();
            }
            return value;
        } catch (Exception e) {
            return 0;
        }
    }

    private static Path root(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("guizhouware");
    }

    private static boolean supported(String ext) {
        return "png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext) || "gif".equals(ext) || isVideo(ext);
    }

    private static boolean isVideo(String ext) {
        return "mp4".equals(ext) || "webm".equals(ext);
    }

    private static String extension(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static BufferedImage scale(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight();
        float scale = Math.min(1f, MAX_EDGE / (float) Math.max(w, h));
        if (scale >= 1f) return source;
        int dw = Math.max(1, Math.round(w * scale));
        int dh = Math.max(1, Math.round(h * scale));
        BufferedImage out = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pen = out.createGraphics();
        pen.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        pen.drawImage(source, 0, 0, dw, dh, null);
        pen.dispose();
        return out;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D pen = out.createGraphics();
        pen.drawImage(source, 0, 0, null);
        pen.dispose();
        return out;
    }

    private static BufferedImage toBuffered(NativeImage image) {
        int w = image.getWidth(), h = image.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] rgb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = image.getPixelRGBA(x, y);
                int a = (p >>> 24) & 0xFF;
                int b = (p >>> 16) & 0xFF;
                int g = (p >>> 8) & 0xFF;
                int r = p & 0xFF;
                rgb[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        out.setRGB(0, 0, w, h, rgb, 0, w);
        return out;
    }

    private static NativeImage toNative(BufferedImage frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        int[] rgb = frame.getRGB(0, 0, w, h, null, 0, w);
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        IntBuffer buf = MemoryUtil.memIntBuffer(((dev.guizhou.mixin.client.NativeImageAccessor)(Object)image).rime$pixels(), w * h);
        for (int p : rgb) {
            int a = (p >>> 24) & 0xFF;
            int r = (p >>> 16) & 0xFF;
            int g = (p >>> 8) & 0xFF;
            int b = p & 0xFF;
            buf.put((a << 24) | (b << 16) | (g << 8) | r);
        }
        return image;
    }

    private static Node tree(IIOMetadata metadata, String format) {
        if (metadata == null) return null;
        try {
            return metadata.getAsTree(format);
        } catch (Exception e) {
            return null;
        }
    }

    private static Node child(Node parent, String name) {
        if (parent == null) return null;
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node item = list.item(i);
            if (item != null && name.equals(item.getNodeName())) return item;
        }
        return null;
    }

    private static String attr(Node node, String name) {
        if (node == null) return null;
        NamedNodeMap map = node.getAttributes();
        if (map == null || map.getNamedItem(name) == null) return null;
        return map.getNamedItem(name).getNodeValue();
    }

    private static int number(String text) {
        if (text == null || text.isBlank()) return 0;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int disposal(String method) {
        if (method == null) return 0;
        String value = method.toLowerCase(Locale.ROOT);
        if (value.contains("restoreToBackground")) return 2;
        if (value.contains("restoreToPrevious")) return 3;
        if (value.contains("background")) return 2;
        if (value.contains("previous")) return 3;
        return 0;
    }

    private static final class Selection {
        final Kind kind;
        final Path file;
        final String label;

        Selection(Kind kind, Path file, String label) {
            this.kind = kind;
            this.file = file;
            this.label = label == null ? "" : label;
        }

        static Selection builtin() {
            return new Selection(Kind.BUILTIN, null, "");
        }
    }
}
