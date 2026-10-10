package dev.guizhou.features.commands.ui;

import com.mojang.brigadier.Message;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import dev.guizhou.GuizhouWare;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.Fonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Chat-box syntax colouring, the suggestion popup and the usage hint card for client commands. */
public final class CommandInputStyle {
    private static final int ROW = 12;
    private static final int CARD = 0xF2151820, BORDER = 0xFF2B3140, TEXT = 0xFF000000 | ChatPalette.TEXT,
            MUTED = 0xFF000000 | ChatPalette.MUTED;

    private CommandInputStyle() {}

    public static boolean isClientCommand(String input) {
        if (input == null || GuizhouWare.commandManager == null) return false;
        return GuizhouWare.commandManager.matchedPrefix(input) != null;
    }

    // ------------------------------------------------------------ input colouring

    /**
     * Colours the visible slice {@code [offset, offset + visible.length())} of the chat input: prefix and
     * command in the accent, literals in text colour, arguments in cyan, unparsable remainder in red.
     * Colour only — glyph widths must match the plain string so the caret stays aligned.
     */
    public static FormattedCharSequence format(ParseResults<?> parse, int prefixLength, String visible, int offset) {
        String full = parse.getReader().getString();
        int length = full.length();
        int[] colors = new int[length];
        Arrays.fill(colors, ChatPalette.MUTED);
        int accent = ChatFeed.accent();
        int prefixEnd = Math.min(prefixLength, length);
        Arrays.fill(colors, 0, prefixEnd, ChatPalette.mix(accent, ChatPalette.SUBTLE, 0.35f));

        boolean first = true;
        for (CommandContextBuilder<?> context = parse.getContext(); context != null; context = context.getChild()) {
            for (ParsedCommandNode<?> node : context.getNodes()) {
                int from = Math.max(0, Math.min(length, node.getRange().getStart()));
                int to = Math.max(from, Math.min(length, node.getRange().getEnd()));
                int color = first ? accent
                        : node.getNode() instanceof ArgumentCommandNode<?, ?> ? ChatPalette.ACCENT_2 : ChatPalette.TEXT;
                Arrays.fill(colors, from, to, color);
                first = false;
            }
        }
        if (parse.getReader().canRead()) {
            int from = Math.max(prefixEnd, Math.min(length, parse.getReader().getCursor()));
            int to = full.indexOf(' ', from);
            Arrays.fill(colors, from, to < 0 ? length : to, ChatPalette.DANGER);
        }

        List<FormattedCharSequence> parts = new ArrayList<>();
        int end = Math.min(length, offset + visible.length());
        int runStart = offset;
        for (int i = offset; i <= end; i++) {
            if (i == end || colors[i] != colors[runStart]) {
                if (i > runStart && runStart < length) {
                    parts.add(FormattedCharSequence.forward(full.substring(runStart, i),
                            Style.EMPTY.withColor(colors[runStart])));
                }
                runStart = i;
            }
        }
        if (visible.length() > end - offset && end - offset >= 0) {
            parts.add(FormattedCharSequence.forward(visible.substring(Math.max(0, end - offset)),
                    Style.EMPTY.withColor(ChatPalette.MUTED)));
        }
        return FormattedCharSequence.composite(parts);
    }

    // -------------------------------------------------------------------- popup

    public static void measurePopup(Rect2i rect, List<Suggestion> list, int screenWidth) {
        int width = 4;
        for (Suggestion suggestion : list) width = Math.max(width, Fonts.width(suggestion.getText()) + 4);
        rect.setWidth(Math.min(width, Math.max(4, screenWidth - 8)));
        rect.setX(Math.clamp(rect.getX(), 4, Math.max(4, screenWidth - rect.getWidth() - 4)));
    }

    public static void renderPopup(GuiGraphics g, Rect2i rect, List<Suggestion> list, int offset, int rows,
                                   int current, String input) {
        Font font = Minecraft.getInstance().font;
        int accent = 0xFF000000 | ChatFeed.accent();
        int x = rect.getX(), y = rect.getY(), w = rect.getWidth(), h = rows * ROW;
        float px = x - 4, py = y - 3, pw = w + 8, ph = h + 4;

        ShadcnDraw.shadow(g, px, py, pw, ph, 5, 5, 0x80000000);
        ShadcnDraw.round(g, px, py, pw, ph, 5, CARD);
        ShadcnDraw.outline(g, px, py, pw, ph, 5, 1, BORDER);
        ShadcnDraw.roundGradient(g, px + 5, py, Math.max(0, pw - 10), 1, 0.5f,
                ShadcnDraw.alpha(accent, 0.85f), ShadcnDraw.alpha(0xFF000000 | ChatPalette.ACCENT_2, 0.15f));

        for (int i = 0; i < rows && i + offset < list.size(); i++) {
            Suggestion suggestion = list.get(i + offset);
            int rowY = y + ROW * i;
            boolean selected = i + offset == current;
            if (selected) {
                ShadcnDraw.round(g, x - 2, rowY, w + 4, ROW, 3, ShadcnDraw.alpha(accent, 0.2f));
                ShadcnDraw.round(g, x - 2, rowY + 2, 1.5f, ROW - 4, 0.75f, accent);
            }
            Fonts.renderOverrideText(g, highlight(suggestion, input, selected, accent).getVisualOrderText(), x + 2, rowY + 2,
                    selected ? TEXT : MUTED, false);
        }

        if (offset > 0) ShadcnDraw.round(g, x + w / 2f - 6, py + 1.5f, 12, 1.5f, 0.75f, ShadcnDraw.alpha(accent, 0.8f));
        if (offset + rows < list.size()) {
            ShadcnDraw.round(g, x + w / 2f - 6, py + ph - 3f, 12, 1.5f, 0.75f, ShadcnDraw.alpha(accent, 0.8f));
        }

        if (current >= 0 && current < list.size()) {
            Message tooltip = list.get(current).getTooltip();
            if (tooltip != null) renderDetail(g, font, ComponentUtils.fromMessage(tooltip), px, py, accent);
        }
    }

    private static void renderDetail(GuiGraphics g, Font font, Component text, float panelX, float panelY, int accent) {
        int width = Fonts.overrideWidth(text.getVisualOrderText()) + 14;
        float cardY = panelY - 17;
        ShadcnDraw.shadow(g, panelX, cardY, width, 14, 4, 4, 0x66000000);
        ShadcnDraw.round(g, panelX, cardY, width, 14, 4, CARD);
        ShadcnDraw.outline(g, panelX, cardY, width, 14, 4, 1, BORDER);
        ShadcnDraw.round(g, panelX + 3, cardY + 3.5f, 1.5f, 7, 0.75f, accent);
        Fonts.renderOverrideText(g, text.getVisualOrderText(), (int) panelX + 8, (int) cardY + 3, MUTED, false);
    }

    private static Component highlight(Suggestion suggestion, String input, boolean selected, int accent) {
        String text = suggestion.getText();
        int start = Math.min(input.length(), suggestion.getRange().getStart());
        int end = Math.min(input.length(), Math.max(start, suggestion.getRange().getEnd()));
        int[] matched = FuzzyMatch.positions(input.substring(start, end), text);
        boolean[] hit = new boolean[text.length()];
        for (int index : matched) if (index < hit.length) hit[index] = true;

        int base = selected ? ChatPalette.TEXT : ChatPalette.MUTED;
        int mark = selected ? ChatPalette.ACCENT_2 : accent & 0xFFFFFF;
        MutableComponent out = Component.empty();
        int runStart = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || hit[i] != hit[runStart]) {
                out.append(Component.literal(text.substring(runStart, i)).withColor(hit[runStart] ? mark : base));
                runStart = i;
            }
        }
        return out;
    }

    // -------------------------------------------------------------------- usage

    public static void renderUsage(GuiGraphics g, List<FormattedCharSequence> lines, int x, int width,
                                   boolean anchorToBottom, int screenHeight) {
        if (lines.isEmpty()) return;
        width = 0;
        for (FormattedCharSequence line : lines) width = Math.max(width, Fonts.overrideWidth(line));
        Font font = Minecraft.getInstance().font;
        int accent = 0xFF000000 | ChatFeed.accent();
        int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            int lineY = lineY(i, anchorToBottom, screenHeight);
            top = Math.min(top, lineY);
            bottom = Math.max(bottom, lineY + ROW);
        }
        float px = x - 7, py = top - 2, pw = width + 11, ph = bottom - top + 3;
        ShadcnDraw.shadow(g, px, py, pw, ph, 4, 4, 0x66000000);
        ShadcnDraw.round(g, px, py, pw, ph, 4, CARD);
        ShadcnDraw.outline(g, px, py, pw, ph, 4, 1, BORDER);
        ShadcnDraw.round(g, px + 2.5f, py + 3, 1.5f, ph - 6, 0.75f, accent);
        for (int i = 0; i < lines.size(); i++) {
            Fonts.renderOverrideText(g, lines.get(i), x, lineY(i, anchorToBottom, screenHeight) + 2, TEXT, false);
        }
    }

    private static int lineY(int index, boolean anchorToBottom, int screenHeight) {
        return anchorToBottom ? screenHeight - 14 - 13 - ROW * index : 72 + ROW * index;
    }
}
