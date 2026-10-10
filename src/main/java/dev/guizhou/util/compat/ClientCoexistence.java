package dev.guizhou.util.compat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.ClickGuiModule;
import net.fabricmc.loader.api.FabricLoader;
import java.util.Set;
import java.util.stream.Collectors;
import static dev.guizhou.util.traits.Util.mc;

/** Foreign ownership is sampled once per game tick, with fresh state after world changes. */
public final class ClientCoexistence {
    private static final ForeignClientProbe PROBE = new ForeignClientProbe(
            name -> Class.forName(name, false, ClientCoexistence.class.getClassLoader()));
    private static final Set<String> MODS = FabricLoader.getInstance().getAllMods().stream()
            .map(mod -> mod.getMetadata().getId()).collect(Collectors.toUnmodifiableSet());
    private static Object player, level;
    private static int tick = Integer.MIN_VALUE;
    private static ForeignClientProbe.Snapshot snapshot = new ForeignClientProbe.Snapshot(java.util.Map.of());
    private ClientCoexistence() {}
    public static boolean blocks(ForeignClientProbe.Domain domain) { return reason(domain) != null; }
    public static String flightReason() {
        String flight = reason(ForeignClientProbe.Domain.FLIGHT);
        return flight != null ? flight : reason(ForeignClientProbe.Domain.ARMOR);
    }
    public static String reason(ForeignClientProbe.Domain domain) {
        var gui = GuizhouWare.moduleManager == null ? null : GuizhouWare.moduleManager.getModuleByClass(ClickGuiModule.class);
        if (gui != null && !gui.clientCompatibility.getValue()) return null;
        if (mc.player == null || !mc.isSameThread()) return null;
        if (player != mc.player || level != mc.level || tick != mc.player.tickCount) {
            player = mc.player; level = mc.level; tick = mc.player.tickCount;
            snapshot = PROBE.read(MODS);
        }
        return snapshot.reason(domain);
    }
}
