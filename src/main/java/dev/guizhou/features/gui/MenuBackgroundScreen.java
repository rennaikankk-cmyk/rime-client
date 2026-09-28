package dev.guizhou.features.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

import static dev.guizhou.util.render.ShadcnDraw.*;

/** Title-screen page for choosing a custom menu background. */
public final class MenuBackgroundScreen extends Screen {
    private final Screen parent;
    private ShadcnTextField field;
    private String notice = "";
    private float browseX, browseY, browseW, applyX, resetX, backX, buttonY, buttonW, buttonH;

    public MenuBackgroundScreen(Screen parent) {
        super(Component.literal(dev.guizhou.util.lang.ClientLanguage.text("主菜单背景")));
        this.parent = parent;
    }

    @Override
    public void init() {
        if (field == null) field = new ShadcnTextField(MenuBackground.displayPath(), value -> {});
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xF008080A);
        float cardW = Math.min(380, width - 24);
        float cardH = 188;
        float x = (width - cardW) / 2f;
        float y = (height - cardH) / 2f;
        round(g, x, y, cardW, cardH, 3, 0xF00E0E11);
        outline(g, x, y, cardW, cardH, 3, 1, 0x26FFFFFF);
        text(g, dev.guizhou.util.lang.ClientLanguage.text("主菜单背景"), x + 14, y + 12, 13, HudFonts.BOLD, 0xFFFFFFFF);
        text(g, fit(MenuBackground.statusLine(), cardW - 28, 7.5f), x + 14, y + 32, 7.5f, HudFonts.REGULAR, 0xC8FFFFFF);
        text(g, dev.guizhou.util.lang.ClientLanguage.text("PNG、JPG、GIF 会铺满菜单。MP4 / WEBM 可以记住，但还不能播放。"), x + 14, y + 46, 6.5f, HudFonts.REGULAR, 0x88FFFFFF);
        field.bounds(x + 14, y + 64, cardW - 28, 22);
        field.draw(g, dev.guizhou.util.lang.ClientLanguage.text("文件路径"));

        buttonH = 18;
        buttonW = (cardW - 28 - 18) / 4f;
        browseX = x + 14;
        applyX = browseX + buttonW + 6;
        resetX = applyX + buttonW + 6;
        backX = resetX + buttonW + 6;
        browseY = buttonY = y + 98;
        button(g, dev.guizhou.util.lang.ClientLanguage.text("浏览"), browseX, buttonY, buttonW, buttonH, mouseX, mouseY);
        button(g, dev.guizhou.util.lang.ClientLanguage.text("应用"), applyX, buttonY, buttonW, buttonH, mouseX, mouseY);
        button(g, dev.guizhou.util.lang.ClientLanguage.text("默认"), resetX, buttonY, buttonW, buttonH, mouseX, mouseY);
        button(g, dev.guizhou.util.lang.ClientLanguage.text("返回"), backX, buttonY, buttonW, buttonH, mouseX, mouseY);
        if (!notice.isEmpty()) {
            text(g, fit(notice, cardW - 28, 7.5f), x + 14, y + 128, 7.5f, HudFonts.REGULAR, 0xFFF2C14C);
        }
        text(g, dev.guizhou.util.lang.ClientLanguage.text("不选文件时，仍随机播放风暴或咖啡动画。"), x + 14, y + cardH - 22, 6.5f, HudFonts.REGULAR, 0x70FFFFFF);
    }

    private void button(GuiGraphics g, String label, float x, float y, float w, float h, int mx, int my) {
        boolean hover = contains(mx, my, x, y, w, h);
        g.fill(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), hover ? 0x2CFFFFFF : 0x38000000);
        outline(g, x, y, w, h, 0, 1, hover ? 0xE6FFFFFF : 0x8CFFFFFF);
        textCentered(g, label, x + w / 2f, y + (h - 7.5f) / 2f + .5f, 7.5f, HudFonts.REGULAR, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubled) {
        if (e.button() != 0) return super.mouseClicked(e, doubled);
        double mx = e.x(), my = e.y();
        if (contains(mx, my, browseX, browseY, buttonW, buttonH)) {
            browse();
            return true;
        }
        if (contains(mx, my, applyX, buttonY, buttonW, buttonH)) {
            applyPath();
            return true;
        }
        if (contains(mx, my, resetX, buttonY, buttonW, buttonH)) {
            MenuBackground.reset();
            field = new ShadcnTextField("", value -> {});
            notice = dev.guizhou.util.lang.ClientLanguage.text("已恢复默认背景");
            return true;
        }
        if (contains(mx, my, backX, buttonY, buttonW, buttonH)) {
            onClose();
            return true;
        }
        field.click(mx, my, false);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.input() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (e.input() == GLFW.GLFW_KEY_ENTER || e.input() == GLFW.GLFW_KEY_KP_ENTER) {
            applyPath();
            return true;
        }
        if (field.key(e)) return true;
        return super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        return field.character(e) || super.charTyped(e);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {}

    private void browse() {
        if (!MenuBackground.dialogsAvailable()) {
            notice = dev.guizhou.util.lang.ClientLanguage.text("无法打开文件窗口，请把路径粘贴到上方");
            return;
        }
        Path picked = MenuBackground.pickFile();
        if (picked == null) {
            notice = dev.guizhou.util.lang.ClientLanguage.text("未选择文件");
            return;
        }
        field = new ShadcnTextField(picked.toAbsolutePath().toString(), value -> {});
        applyPath();
    }

    private void applyPath() {
        String error = MenuBackground.use(field.value());
        notice = error == null ? dev.guizhou.util.lang.ClientLanguage.text("已更换背景") : error;
    }
}
