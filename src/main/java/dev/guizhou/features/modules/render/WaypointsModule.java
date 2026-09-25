package dev.guizhou.features.modules.render;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.ConfigFiles;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.render.WorldLabel;
import dev.guizhou.util.world.WaypointMath;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Named world markers saved per server, plus automatic death and logout points. Waypoints from
 * the overworld show at their 1:8 position in the nether and vice versa.
 */
public class WaypointsModule extends Module {
    public enum Kind { MANUAL, DEATH, LOGOUT }

    public static final class Waypoint {
        public final String name;
        public final int x, y, z;
        public final String dimension;
        public final Kind kind;
        public final long created;
        public boolean visible;

        public Waypoint(String name, int x, int y, int z, String dimension, Kind kind, long created, boolean visible) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.kind = kind;
            this.created = created;
            this.visible = visible;
        }
    }

    private static final Path DIRECTORY = FabricLoader.getInstance().getGameDir().resolve("guizhouware").resolve("waypoints");
    private static WaypointsModule INSTANCE;

    private final Setting<Boolean> deaths = bool("DeathPoints", true).setPage("General");
    private final Setting<Integer> maxDeaths = num("MaxDeaths", 5, 1, 20).setPage("General");
    private final Setting<Boolean> logout = bool("LogoutPoint", true).setPage("General");
    private final Setting<Boolean> convert = bool("NetherConvert", true).setPage("General");

    private final Setting<Boolean> beam = bool("Beam", true).setPage("Render");
    private final Setting<Boolean> box = bool("Box", true).setPage("Render");
    private final Setting<Double> boxDistance = num("BoxDistance", 256.0, 16.0, 1024.0).setPage("Render");
    private final Setting<Float> scale = num("Scale", 1.2f, 0.3f, 3.0f).setPage("Render");
    private final Setting<Color> manualColor = color("ManualColor", 90, 200, 255, 255).setPage("Render");
    private final Setting<Color> deathColor = color("DeathColor", 255, 80, 80, 255).setPage("Render");
    private final Setting<Color> logoutColor = color("LogoutColor", 255, 170, 60, 255).setPage("Render");
    private final Setting<Color> background = color("Background", 0, 0, 0, 120).setPage("Render");

    private final List<Waypoint> waypoints = new ArrayList<>();
    private String loadedKey;
    private boolean inWorld, deathRecorded;
    private Vec3 lastPos;
    private String lastDimension;

    public WaypointsModule() {
        super("Waypoints", "Per-server waypoints with automatic death and logout points, nether/overworld "
                + "conversion and in-world markers. Manage with .waypoint add/del/list/clear.", Category.RENDER);
        INSTANCE = this;
        ClientTickEvents.END_CLIENT_TICK.register(client -> clientTick());
    }

    public static WaypointsModule get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- tracking

    private void clientTick() {
        if (mc.level != null && mc.player != null) {
            String key = serverKey();
            if (!key.equals(loadedKey)) load(key);
            inWorld = true;
            lastPos = mc.player.position();
            lastDimension = dimension();
            if (mc.player.isDeadOrDying()) {
                if (!deathRecorded && isEnabled() && deaths.getValue()) recordDeath();
                deathRecorded = true;
            } else {
                deathRecorded = false;
            }
            return;
        }
        if (inWorld) {
            inWorld = false;
            if (isEnabled() && logout.getValue() && lastPos != null && loadedKey != null) {
                waypoints.removeIf(w -> w.kind == Kind.LOGOUT);
                waypoints.add(new Waypoint("Logout", floor(lastPos.x), floor(lastPos.y), floor(lastPos.z),
                        lastDimension, Kind.LOGOUT, System.currentTimeMillis(), true));
                save();
            }
        }
    }

    private void recordDeath() {
        waypoints.add(new Waypoint("Death", floor(mc.player.getX()), floor(mc.player.getY()), floor(mc.player.getZ()),
                dimension(), Kind.DEATH, System.currentTimeMillis(), true));
        List<Waypoint> deathPoints = waypoints.stream().filter(w -> w.kind == Kind.DEATH)
                .sorted(Comparator.comparingLong(w -> w.created)).toList();
        for (int i = 0; i < deathPoints.size() - maxDeaths.getValue(); i++) waypoints.remove(deathPoints.get(i));
        save();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private String serverKey() {
        if (mc.getSingleplayerServer() != null) {
            return "sp_" + WaypointMath.serverKey(mc.getSingleplayerServer().getWorldData().getLevelName());
        }
        return WaypointMath.serverKey(mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip);
    }

    public String dimension() {
        return mc.level == null ? WaypointMath.OVERWORLD : mc.level.dimension().location().toString();
    }

    // ---------------------------------------------------------------- API used by .waypoint

    /** Client thread only. Loads the current server's file on first use, even while disabled. */
    public List<Waypoint> list() {
        ensureLoaded();
        return List.copyOf(waypoints);
    }

    public boolean add(String name, int x, int y, int z) {
        ensureLoaded();
        if (find(name) != null) return false;
        waypoints.add(new Waypoint(name, x, y, z, dimension(), Kind.MANUAL, System.currentTimeMillis(), true));
        save();
        return true;
    }

    public boolean remove(String name) {
        ensureLoaded();
        boolean removed = waypoints.removeIf(w -> w.name.equalsIgnoreCase(name));
        if (removed) save();
        return removed;
    }

    public int clear(Kind kind) {
        ensureLoaded();
        int before = waypoints.size();
        waypoints.removeIf(w -> kind == null || w.kind == kind);
        int removed = before - waypoints.size();
        if (removed > 0) save();
        return removed;
    }

    public Boolean toggle(String name) {
        ensureLoaded();
        Waypoint waypoint = find(name);
        if (waypoint == null) return null;
        waypoint.visible = !waypoint.visible;
        save();
        return waypoint.visible;
    }

    public Waypoint find(String name) {
        for (Waypoint waypoint : waypoints) if (waypoint.name.equalsIgnoreCase(name)) return waypoint;
        return null;
    }

    private void ensureLoaded() {
        if (mc.level == null) return;
        String key = serverKey();
        if (!key.equals(loadedKey)) load(key);
    }

    // ---------------------------------------------------------------- persistence

    private void load(String key) {
        waypoints.clear();
        loadedKey = key;
        try {
            JsonElement root = ConfigFiles.read(DIRECTORY.resolve(key + ".json"));
            if (root == null || !root.isJsonObject() || !root.getAsJsonObject().has("waypoints")) return;
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("waypoints")) {
                JsonObject o = element.getAsJsonObject();
                Kind kind;
                try {
                    kind = Kind.valueOf(o.get("kind").getAsString().toUpperCase(Locale.ROOT));
                } catch (RuntimeException unknown) {
                    kind = Kind.MANUAL;
                }
                waypoints.add(new Waypoint(o.get("name").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(),
                        o.get("z").getAsInt(), o.get("dimension").getAsString(), kind,
                        o.has("created") ? o.get("created").getAsLong() : 0L,
                        !o.has("visible") || o.get("visible").getAsBoolean()));
            }
        } catch (Exception e) {
            GuizhouWare.LOGGER.error("Failed to load waypoints for {}", key, e);
        }
    }

    private void save() {
        if (loadedKey == null) return;
        JsonArray array = new JsonArray();
        for (Waypoint waypoint : waypoints) {
            JsonObject o = new JsonObject();
            o.addProperty("name", waypoint.name);
            o.addProperty("x", waypoint.x);
            o.addProperty("y", waypoint.y);
            o.addProperty("z", waypoint.z);
            o.addProperty("dimension", waypoint.dimension);
            o.addProperty("kind", waypoint.kind.name());
            o.addProperty("created", waypoint.created);
            o.addProperty("visible", waypoint.visible);
            array.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("waypoints", array);
        try {
            ConfigFiles.write(DIRECTORY.resolve(loadedKey + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception e) {
            GuizhouWare.LOGGER.error("Failed to save waypoints for {}", loadedKey, e);
        }
    }

    // ---------------------------------------------------------------- rendering

    /** Where the waypoint appears in the current dimension, or null when it has no mapping here. */
    private Vec3 displayPos(Waypoint waypoint) {
        String here = dimension();
        if (!here.equals(waypoint.dimension) && !convert.getValue()) return null;
        double[] xz = WaypointMath.convert(waypoint.x + 0.5, waypoint.z + 0.5, waypoint.dimension, here);
        return xz == null ? null : new Vec3(xz[0], waypoint.y, xz[1]);
    }

    private Color colorOf(Waypoint waypoint) {
        return switch (waypoint.kind) {
            case MANUAL -> manualColor.getValue();
            case DEATH -> deathColor.getValue();
            case LOGOUT -> logoutColor.getValue();
        };
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;
        Vec3 eye = mc.player.getEyePosition();
        for (Waypoint waypoint : waypoints) {
            if (!waypoint.visible) continue;
            Vec3 pos = displayPos(waypoint);
            if (pos == null || pos.distanceTo(eye) > boxDistance.getValue()) continue;
            Color color = colorOf(waypoint);
            if (box.getValue()) {
                BlockPos block = BlockPos.containing(pos.x, pos.y, pos.z);
                RenderUtil.drawBox(event.getMatrix(), new AABB(block), color, 1.5f);
            }
            if (beam.getValue()) {
                RenderUtil.drawLine(new Vec3(pos.x, pos.y, pos.z), new Vec3(pos.x, pos.y + 64, pos.z), color, 1.5f);
            }
        }
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (nullCheck()) return;
        Vec3 eye = mc.player.getEyePosition();
        String here = dimension();
        for (Waypoint waypoint : waypoints) {
            if (!waypoint.visible) continue;
            Vec3 pos = displayPos(waypoint);
            if (pos == null) continue;
            double distance = pos.distanceTo(eye);
            String suffix = here.equals(waypoint.dimension) ? "" : " (" + WaypointMath.shortDimension(waypoint.dimension) + "换算)";
            String text = String.format("%s %.0fm%s", waypoint.name, distance, suffix);
            WorldLabel.draw(event.getContext(), pos.x, pos.y + 1.2, pos.z, Math.min(distance, 64.0), text,
                    colorOf(waypoint).getRGB(), background.getValue().getRGB(), scale.getValue(), 0.6f);
        }
    }

    @Override
    public String getDisplayInfo() {
        return waypoints.isEmpty() ? null : String.valueOf(waypoints.size());
    }
}
