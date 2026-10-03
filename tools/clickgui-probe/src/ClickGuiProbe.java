package probe;

import dev.guizhou.features.gui.GuizhouWareGui;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.util.lang.ClientLanguage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/** Real-renderer visual checks; separate test mod, never packaged into the client. */
public final class ClickGuiProbe implements ClientModInitializer {
    private int tick, stage, wait, checks;
    private volatile boolean captured;
    private boolean started;
    private String savedLayout;

    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private Object panel(Minecraft mc) throws Exception { return field(mc.screen, "hachimi"); }
    private void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private void capture(Minecraft mc, String name) {
        captured = false;
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), message -> captured = true);
    }
    @SuppressWarnings("unchecked")
    private void tick(Minecraft mc) {
        if (stage == 99) return;
        try {
            if (++tick > 1500) throw new AssertionError("ClickGUI visual probe timed out");
            if (mc.getOverlay() != null || tick < 100) return;
            var gui = ClickGuiModule.getInstance();
            if (gui == null) return;
            if (!started) {
                mc.options.pauseOnLostFocus = false;
                gui.panelLayout.setValue(""); gui.frame.setValue(ClickGuiModule.Frame.GLASS);
                gui.language.setValue(ClientLanguage.Language.CHINESE);
                mc.setScreen(GuizhouWareGui.getClickGui()); started = true; return;
            }
            if (++wait < 45) return;
            if (stage % 2 == 1 && !captured) return;
            switch (stage) {
                case 0 -> { bounds(mc); capture(mc, "01-chinese-grid"); }
                case 1 -> gui.language.setValue(ClientLanguage.Language.ENGLISH);
                case 2 -> { bounds(mc); capture(mc, "02-english-grid"); }
                case 3 -> {
                    gui.language.setValue(ClientLanguage.Language.CHINESE);
                    set(panel(mc), "query", "ClickGui");
                    ((Set<Object>) field(panel(mc), "expanded")).add(gui);
                    var search = field(panel(mc), "search");
                    set(search, "value", "ClickGui");
                }
                case 4 -> { bounds(mc); capture(mc, "03-settings"); }
                case 5 -> {
                    gui.panelLayout.setValue("HACHIMI.COMBAT=250.0,90.0,0,manual");
                    savedLayout = gui.panelLayout.getValue();
                    mc.setScreen(new GuizhouWareGui());
                    gui.frame.setValue(ClickGuiModule.Frame.LIST);
                }
                case 6 -> { capture(mc, "04-category-list"); }
                case 7 -> {
                    gui.frame.setValue(ClickGuiModule.Frame.GLASS);
                    check(gui.panelLayout.getValue().equals(savedLayout), "List view changed saved glass positions");
                }
                case 8 -> {
                    var combat = ((List<?>) field(panel(mc), "panels")).getFirst();
                    check((float) field(combat, "x") == 250 && (float) field(combat, "y") == 90, "Glass coordinates not restored");
                    capture(mc, "05-restored-layout");
                }
                case 9 -> {
                    gui.panelLayout.setValue("");
                    mc.options.guiScale().set(3); mc.resizeDisplay();
                    mc.setScreen(new GuizhouWareGui());
                }
                case 10 -> { bounds(mc); capture(mc, "06-compact-grid"); }
                case 11 -> {
                    mc.options.guiScale().set(4); mc.resizeDisplay();
                    mc.setScreen(new GuizhouWareGui());
                }
                case 12 -> { bounds(mc); capture(mc, "07-small-grid"); }
                case 13 -> {
                    interactions(mc);
                    Files.writeString(mc.gameDirectory.toPath().resolve("complete.txt"), "ClickGuiProbe passed: " + checks + " checks; seven real screenshots\n");
                    stage = 99; mc.stop(); return;
                }
            }
            stage++; wait = 0;
        } catch (Throwable error) {
            error.printStackTrace();
            try { Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"), error.toString()); } catch (Exception ignored) { }
            stage = 99; mc.stop();
        }
    }
    private void interactions(Minecraft mc) throws Exception {
        var screen = (GuizhouWareGui) mc.screen;
        Object panel = panel(mc), first = ((List<?>) field(panel, "panels")).getFirst();
        float scale = (float) field(panel, "scale");
        double x = ((float) field(first, "drawX") + 30) * scale;
        double y = ((float) field(first, "drawY") + 12) * scale;
        screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0);
        check((boolean) field(first, "collapsed"), "Header click must collapse");
        screen.mouseClicked(x, y, 0);
        screen.mouseDragged(x + 24 * scale, y + 16 * scale, 0, 24 * scale, 16 * scale);
        screen.mouseReleased(x + 24 * scale, y + 16 * scale, 0);
        check((boolean) field(first, "customPosition"), "Dragging must persist custom position");
        float vw = (float) field(panel, "vw");
        screen.mouseClicked((vw - 148) * scale, 23 * scale, 0);
        check(!(boolean) field(first, "customPosition") && !(boolean) field(first, "collapsed"), "Arrange button must clear custom layout");
        check(ClickGuiModule.getInstance().panelLayout.getValue().isEmpty(), "Arrange must clear persisted overrides");
        screen.keyPressed(70, 0, 2);
        check((boolean) field(field(panel, "search"), "focused"), "Ctrl+F must focus search");
        screen.keyPressed(256, 0, 0);
        check(!(boolean) field(field(panel, "search"), "focused"), "Escape must leave empty search");
    }
    private void bounds(Minecraft mc) throws Exception {
        Object panel = panel(mc); float vw = (float) field(panel, "vw"), vh = (float) field(panel, "vh");
        Object search = field(panel, "search");
        check((float) field(search, "x") + (float) field(search, "w") < vw - 138, "Search overlaps toolbar buttons");
        for (Object row : (List<?>) field(panel, "panels")) {
            float x = (float) field(row, "drawX"), y = (float) field(row, "drawY"), body = (float) field(row, "bodyHeight");
            check(Float.isFinite(x) && Float.isFinite(y), "Nonfinite panel geometry");
            check(x >= 12 && x + 126 <= vw - 12 + .1, "Panel outside horizontal bounds");
            check(y >= 48 && y + 24 + body <= vh - 24 + .1, "Panel outside vertical bounds");
        }
    }
}
