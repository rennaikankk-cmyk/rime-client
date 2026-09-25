package dev.guizhou.features.gui;

import dev.guizhou.GuizhouWare;
import dev.guizhou.manager.CloudConfigClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.guizhou.util.render.ShadcnDraw.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/** Native private-sync and public-preset browser; account identity comes from the licence. */
public final class CloudConfigsScreen extends Screen {
    private final Screen parent;
    private ShadcnTextField name;
    private String scope = "private", status = "云参数与当前会员账号绑定";
    private List<String> names = List.of();
    private final List<Hit> hits = new ArrayList<>();
    private float x, y, w, h, scale = 1;
    private int scroll;
    private int requestVersion;

    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean at(double mx, double my) { return contains(mx, my, x, y, w, h); }
    }

    public CloudConfigsScreen(Screen parent) {
        super(Component.literal("云参数"));
        this.parent = parent;
    }

    @Override protected void init() {
        name = new ShadcnTextField("", ignored -> {});
        refresh();
    }

    private boolean publicPreset() { return "public".equals(scope); }

    private void refresh() { refresh(null); }
    private void refresh(String success) {
        int version = ++requestVersion;
        String currentScope = scope;
        status = "正在读取云参数…";
        CloudConfigClient.request(currentScope, "list", null, null, reply -> {
            if (version != requestVersion || !currentScope.equals(scope)) return;
            if (!reply.ok()) { status = reply.message(); return; }
            names = reply.names();
            scroll = 0;
            status = success != null ? success : names.isEmpty()
                    ? "这里还没有参数预设"
                    : "选择预设，或输入名称后操作";
        });
    }

    private void request(String action) {
        String selected = name.value().trim();
        boolean qualified = publicPreset() && ("load".equals(action) || "delete".equals(action));
        if (!(selected.matches("[A-Za-z0-9_-]{1,40}")
                || qualified && selected.matches("[^/]{1,48}/[A-Za-z0-9_-]{1,40}"))) {
            status = "请输入有效的参数名称";
            return;
        }
        String bundle = null;
        if ("save".equals(action)) {
            try { bundle = GuizhouWare.configManager.exportBundle(null, publicPreset()); }
            catch (Exception failure) { status = "导出当前参数失败"; return; }
        }
        int version = ++requestVersion;
        String currentScope = scope;
        status = "正在连接云端…";
        CloudConfigClient.request(currentScope, action, selected, bundle, reply -> {
            if (version != requestVersion || !currentScope.equals(scope)) return;
            if (!reply.ok()) { status = reply.message(); return; }
            if ("load".equals(action)) {
                if (!GuizhouWare.configManager.applyBundle(reply.bundle(), publicPreset())) {
                    status = "云端参数格式不兼容"; return;
                }
                GuizhouWare.configManager.save();
                status = "已应用参数：" + selected;
            } else {
                refresh("save".equals(action)
                        ? "已保存：" + reply.message()
                        : "已删除：" + selected);
            }
        });
    }

    private void button(GuiGraphics g, String label, float bx, float by, float bw, float bh,
                        double mx, double my, Runnable action) {
        boolean hover = contains(mx, my, bx, by, bw, bh);
        round(g, bx, by, bw, bh, 5, hover ? accent() : SURFACE);
        text(g, fit(label, bw - 12, 10), bx + 7, by + (bh - 10) / 2, 10, hover ? BG : TEXT);
        hits.add(new Hit(bx, by, bw, bh, action));
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        hits.clear();
        g.fill(0, 0, width, height, 0xA9000000);
        scale = Math.min(1f, Math.min(width / 640f, height / 440f));
        w = Math.min(width / scale - 20, 620); h = Math.min(height / scale - 20, 420);
        x = (width / scale - w) / 2; y = (height / scale - h) / 2;
        double mx = mouseX / scale, my = mouseY / scale;
        g.pose().pushMatrix(); g.pose().scale(scale, scale);
        panel(g, x, y, w, h, 9);
        round(g, x + 1, y + 1, w - 2, 3, 2, accent());
        text(g, "云参数", x + 18, y + 14, 18, 700, TEXT);
        text(g, "私人同步与公开预设", x + 18, y + 41, 10, MUTED);
        button(g, "返回", x + w - 66, y + 14, 48, 24, mx, my, this::onClose);
        button(g, "私人参数", x + 18, y + 68, 92, 26, mx, my, () -> switchScope("private"));
        button(g, "公开预设", x + 116, y + 68, 92, 26, mx, my, () -> switchScope("public"));
        round(g, x + (publicPreset() ? 116 : 18), y + 95, 92, 2, 1, accent());
        text(g, publicPreset()
                ? "公开预设只分享模块参数，其他会员可以载入"
                : "私人参数只对当前会员账号可见", x + 18, y + 108, 9, MUTED);
        float listTop = y + 128, listBottom = y + h - 107;
        g.enableScissor((int) x + 12, (int) listTop, (int) (x + w - 12), (int) listBottom);
        int visible = Math.max(1, (int) ((listBottom - listTop) / 27));
        scroll = Math.clamp(scroll, 0, Math.max(0, names.size() - visible));
        for (int i = scroll; i < names.size() && i < scroll + visible; i++) {
            String item = names.get(i);
            float row = listTop + (i - scroll) * 27;
            boolean hover = contains(mx, my, x + 18, row, w - 36, 24);
            round(g, x + 18, row, w - 36, 24, 4, hover ? SURFACE : 0x501B293A);
            text(g, fit(item, w - 70, 10), x + 28, row + 7, 10, hover ? TEXT : MUTED);
            hits.add(new Hit(x + 18, row, w - 36, 24, () -> name = new ShadcnTextField(item, ignored -> {})));
        }
        g.disableScissor();
        name.bounds(x + 18, y + h - 89, w - 36, 27);
        name.draw(g, publicPreset() ? "输入名称，载入时可输入 账号/名称" : "输入参数名称");
        float bw = (w - 54) / 4;
        button(g, "刷新", x + 18, y + h - 53, bw, 25, mx, my, this::refresh);
        button(g, publicPreset() ? "公开发布" : "同步当前", x + 24 + bw, y + h - 53, bw, 25, mx, my, () -> request("save"));
        button(g, "载入", x + 30 + 2 * bw, y + h - 53, bw, 25, mx, my, () -> request("load"));
        button(g, "删除我的", x + 36 + 3 * bw, y + h - 53, bw, 25, mx, my, () -> request("delete"));
        text(g, fit(status, w - 36, 9), x + 18, y + h - 17, 9, MUTED);
        g.pose().popMatrix();
    }

    private void switchScope(String next) {
        if (scope.equals(next)) return;
        scope = next;
        name = new ShadcnTextField("", ignored -> {});
        names = List.of();
        refresh();
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x() / scale, my = event.y() / scale;
        if (name.click(mx, my, false)) return true;
        if (event.button() != 0) return true;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.at(mx, my)) { hit.action().run(); return true; }
        }
        return true;
    }

    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll = Math.max(0, scroll - (int) Math.signum(vertical));
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.input() == GLFW_KEY_ESCAPE) { onClose(); return true; }
        return name.key(event);
    }

    @Override public boolean charTyped(CharacterEvent event) { return name.character(event); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {}
}
