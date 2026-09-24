package dev.guizhou.features.commands.ui;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.Feature;
import dev.guizhou.mixin.client.ChatComponentAccessor;
import dev.guizhou.util.TextUtil;
import dev.guizhou.util.player.ChatUtil;
import dev.guizhou.util.render.ShadcnDraw;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MutableComponent;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's single chat presentation layer.
 * <pre>GZ › ✔ body  ×2</pre>
 * "GZ" is a two-stop gradient of the theme accent and cyan, the glyph and the chat indicator bar carry
 * the tone. Keyed lines replace their previous line in place; identical unkeyed lines merge with a counter.
 */
public final class ChatFeed {
    private static final int MAX_TRACKED = 192;
    private static final RepeatCollapser COLLAPSER = new RepeatCollapser(4000);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Map<String, MessageSignature> SIGNATURES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MessageSignature> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    private ChatFeed() {}

    // ------------------------------------------------------------------ sending

    public static void send(ChatTone tone, Component body) {
        post(null, tone, null, body);
    }

    public static void send(ChatTone tone, String glyph, Component body) {
        post(null, tone, glyph, body);
    }

    public static void send(ChatTone tone, String text) {
        post(null, tone, null, Component.literal(text));
    }

    /** Replaces the previous line posted under {@code key}, wherever it is in chat. */
    public static void replace(String key, ChatTone tone, Component body) {
        post(key, tone, null, body);
    }

    public static void replace(String key, ChatTone tone, String glyph, Component body) {
        post(key, tone, glyph, body);
    }

    /** Legacy CatFormat template ("{green} %s {reset} ..."); a single leading colour becomes the tone. */
    public static void legacy(ChatTone fallback, String template, Object... args) {
        if (template == null) return;
        LegacyMarkup.Parsed parsed = LegacyMarkup.detect(template, fallback);
        post(null, parsed.tone(), null, TextUtil.text(parsed.body(), args));
    }

    public static void info(String template, Object... args) {
        legacy(ChatTone.INFO, template, args);
    }

    public static void success(String template, Object... args) {
        legacy(ChatTone.SUCCESS, template, args);
    }

    public static void warn(String template, Object... args) {
        legacy(ChatTone.WARN, template, args);
    }

    public static void error(String template, Object... args) {
        legacy(ChatTone.ERROR, template, args);
    }

    /** "Surround · 已开启" — one line per module, updated in place. */
    public static void moduleToggled(String stableName, String displayName, boolean enabled) {
        MutableComponent body = Component.empty()
                .append(text(displayName, ChatPalette.TEXT))
                .append(text("  ·  ", ChatPalette.SUBTLE))
                .append(text(enabled ? "已开启" : "已关闭", enabled ? ChatPalette.SUCCESS : ChatPalette.MUTED));
        post("module:" + stableName, enabled ? ChatTone.SUCCESS : ChatTone.MUTED, enabled ? "●" : "○", body);
    }

    /** "Surround · Range → 4.5" — one line per setting, updated in place. */
    public static void settingChanged(String module, String setting, Object value) {
        MutableComponent body = Component.empty()
                .append(text(module, ChatPalette.TEXT))
                .append(text("  ·  ", ChatPalette.SUBTLE))
                .append(text(setting, ChatPalette.MUTED))
                .append(text("  →  ", ChatPalette.SUBTLE))
                .append(text(String.valueOf(value), ChatPalette.ACCENT_2));
        post("setting:" + module + "." + setting, ChatTone.INFO, "◆", body);
    }

    // --------------------------------------------------------------- components

    public static MutableComponent text(String text, int rgb) {
        return Component.literal(text == null ? "" : text).withColor(rgb);
    }

    /** Clickable text that pre-fills the chat box; hover explains what will be filled. */
    public static MutableComponent suggest(String label, String fill, Component hover, int rgb) {
        MutableComponent tip = Component.empty();
        if (hover != null) tip.append(hover).append("\n");
        tip.append(text("点击填入  ", ChatPalette.SUBTLE)).append(text(fill, ChatPalette.ACCENT_2));
        return Component.literal(label).withStyle(style -> style.withColor(rgb)
                .withClickEvent(new ClickEvent.SuggestCommand(fill))
                .withHoverEvent(new HoverEvent.ShowText(tip)));
    }

    /** A prefixed command chip such as ".friend add " that fills itself into the chat box. */
    public static MutableComponent command(String commandText, Component hover) {
        String full = prefix() + commandText;
        return suggest(full, full, hover, ChatPalette.ACCENT_2);
    }

    public static MutableComponent hover(MutableComponent component, Component hover) {
        return component.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    public static MutableComponent brand() {
        int accent = accent();
        int second = ChatPalette.mix(accent, ChatPalette.ACCENT_2, 0.7f);
        MutableComponent tip = Component.empty()
                .append(text("Rime", accent).withStyle(style -> style.withBold(true)))
                .append(text("\n客户端消息 · 点击查看命令列表", ChatPalette.MUTED));
        return Component.empty()
                .append(Component.literal("G").withStyle(style -> style.withColor(accent).withBold(true)))
                .append(Component.literal("Z").withStyle(style -> style.withColor(second).withBold(true)))
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(tip))
                        .withClickEvent(new ClickEvent.SuggestCommand(prefix() + "help")));
    }

    public static String prefix() {
        return GuizhouWare.commandManager == null ? "rime" : GuizhouWare.commandManager.getCommandPrefix();
    }

    public static int accent() {
        try {
            return ShadcnDraw.accent() & 0xFFFFFF;
        } catch (RuntimeException | LinkageError ignored) {
            return ChatPalette.ACCENT;
        }
    }

    public static int toneColor(ChatTone tone) {
        return tone == ChatTone.INFO ? accent() : tone.color();
    }

    public static MutableComponent compose(ChatTone tone, String glyph, Component body, int count) {
        int toneColor = toneColor(tone);
        MutableComponent line = Component.empty().withColor(ChatPalette.TEXT)
                .append(brand())
                .append(text(" › ", ChatPalette.SUBTLE))
                .append(text((glyph == null ? tone.glyph() : glyph) + " ", toneColor))
                .append(Component.empty().withColor(tone == ChatTone.ERROR ? ChatPalette.DANGER_SOFT : ChatPalette.TEXT)
                        .append(body));
        if (count > 1) line.append(text("  ×" + count, ChatPalette.SUBTLE));
        return line;
    }

    // ----------------------------------------------------------------- plumbing

    private static void post(String key, ChatTone tone, String glyph, Component body) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || body == null) return;
        if (!mc.isSameThread()) {
            mc.execute(() -> post(key, tone, glyph, body));
            return;
        }
        if (Feature.nullCheck() || mc.gui == null) return;

        RepeatCollapser.Decision decision = COLLAPSER.offer(key, tone.name() + '|' + body.getString(),
                System.currentTimeMillis());
        ChatComponent chat = mc.gui.getChat();
        MessageSignature previous = SIGNATURES.remove(decision.key());
        boolean replaced = decision.replace() && previous != null && remove(chat, previous);

        byte[] data = new byte[256];
        RNG.nextBytes(data);
        MessageSignature signature = new MessageSignature(data);
        chat.addMessage(compose(tone, glyph, body, decision.count()), signature, tag(tone));
        SIGNATURES.put(decision.key(), signature);

        if (replaced) {
            List<GuiMessage.Line> trimmed = ((ChatComponentAccessor) chat).guizhouware$getTrimmedMessages();
            ChatUtil.noAnimateHead = trimmed.isEmpty() ? null : trimmed.get(0);
        }
    }

    private static boolean remove(ChatComponent chat, MessageSignature signature) {
        ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
        boolean removed = accessor.guizhouware$getAllMessages().removeIf(line -> signature.equals(line.signature()));
        if (removed) accessor.guizhouware$refreshTrimmedMessages();
        return removed;
    }

    private static GuiMessageTag tag(ChatTone tone) {
        MutableComponent label = Component.empty()
                .append(text("Rime", accent()))
                .append(text(" · " + tone.label(), ChatPalette.MUTED));
        return new GuiMessageTag(toneColor(tone), null, label, "Rime");
    }
}
