package probe;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.render.NametagsModule;
import dev.guizhou.util.render.font.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.nio.file.Files;
import java.util.*;

public final class FontProbe implements ClientModInitializer {
    private int tick, stage, wait;
    private volatile boolean captured;
    private final List<String> checks = new ArrayList<>();
    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private void tick(Minecraft mc) {
        if (stage == 99) return;
        try {
            if (++tick > 700) throw new AssertionError("Font render probe timed out");
            if (mc.player == null || mc.level == null) return;
            if (stage == 0) {
                for (var m : GuizhouWare.moduleManager.getModules()) if (m.isEnabled()) m.disable();
                for (int weight : new int[]{400, 600, 700}) {
                    String name = FontFaces.resolve(mc.gameDirectory.toPath(), weight)[0].getFontName(Locale.ROOT);
                    if (!name.startsWith("Anthropic Serif Web")) throw new AssertionError(name);
                    checks.add("weight " + weight + " = " + name);
                }
                String text = "FakePlayer 6m 64 / 中文测试";
                if (Fonts.width(text) <= 0 || HudFonts.width(text, 14, 400) <= 0) throw new AssertionError("Missing text metrics");
                if (Fonts.width(text) != Fonts.nametagWidth(text)) throw new AssertionError("Nametag uses a different font");
                mc.options.pauseOnLostFocus = false;
                mc.setScreen(new Sample()); stage = 1; return;
            }
            if (stage == 1 && ++wait > 45) {
                if (!FontFallback.customEnabled()) throw new AssertionError("Renderer silently fell back to native");
                checks.add("custom renderer active; Nametag/overlay metrics agree");
                Screenshot.grab(mc.gameDirectory, "font-audit.png", mc.getMainRenderTarget(), 1, message -> captured = true);
                stage = 2;
            }
            if (stage == 2 && captured) {
                if (!Files.exists(mc.gameDirectory.toPath().resolve("screenshots/font-audit.png"))) throw new AssertionError("Screenshot failed");
                Files.write(mc.gameDirectory.toPath().resolve("complete.txt"), checks);
                stage = 99; mc.stop();
            }
        } catch (Throwable error) {
            error.printStackTrace();
            try { Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"), error.toString()); } catch (Exception ignored) {}
            stage = 99; mc.stop();
        }
    }
    private static final class Sample extends Screen {
        Sample() { super(Component.literal("Font audit")); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(8, 8, width - 8, 238, 0xED30302E);
            HudFonts.draw(g, "Anthropic Serif / 字体检查", 20, 18, 20, 400, 0xFFFAF9F5);
            HudFonts.draw(g, "菜单与 HUD  ·  Regular 400 / 中文回退", 20, 53, 14, 400, 0xFFFAF9F5);
            HudFonts.draw(g, "Semibold 600  ·  Rime 1.1.1", 20, 80, 14, 600, 0xFFE08A6C);
            HudFonts.draw(g, "Bold 700  ·  0123456789", 20, 107, 14, 700, 0xFFFAF9F5);
            Fonts.drawNametagString(g, "Nametag: FakePlayer 6m / 玩家名称", 20, 146, 0xFFFAF9F5);
            Fonts.drawString(g, "World label / Damage 19.1 / Mining 85%", 20, 169, 0xFFFAF9F5);
            ItemStack crystal = new ItemStack(Items.END_CRYSTAL, 64);
            g.renderItem(crystal, 20, 193); Fonts.renderNametagItemDecorations(g, crystal, 20, 193);
            ItemStack obsidian = new ItemStack(Items.OBSIDIAN, 63);
            g.renderItem(obsidian, 47, 193); Fonts.renderItemDecorations(g, obsidian, 47, 193);
            Fonts.drawString(g, "64 / 63  ·  物品数量", 82, 197, 0xFFFAF9F5);
            // Also exercise the real world-anchored nametag, including equipment/count layout.
            var mc = Minecraft.getInstance();
            var pos = mc.player.getEyePosition().add(mc.player.getLookAngle().scale(6));
            var armor = new EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
            for (var slot : EquipmentSlot.values()) armor.put(slot, ItemStack.EMPTY);
            GuizhouWare.moduleManager.getModuleByClass(NametagsModule.class).renderNametag(g,
                    pos.x, pos.y, pos.z, 6, "FakePlayer", 0xFFFAF9F5, " 6m", 1,
                    armor, obsidian, crystal, List.of());
        }
    }
}
