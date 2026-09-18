package dev.guizhou.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.ClientEvent;
import dev.guizhou.event.impl.entity.DeathEvent;
import dev.guizhou.event.impl.entity.TotemPopEvent;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.Feature;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.BuildConfig;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.util.telemetry.FlightSummary;
import dev.guizhou.util.telemetry.TelemetryBuffer;
import dev.guizhou.util.telemetry.TelemetryCapability;
import dev.guizhou.util.telemetry.TelemetryPrivacy;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional diagnostics. Only explicitly constructed, non-identifying fields reach the wire. */
public class TelemetryManager extends Feature {
    public static final String ENDPOINT = "http://62.234.117.226/telemetryapi/v1/events";
    private static final Path PREFERENCES = Path.of("guizhouware", "telemetry.json");
    private static final long SECOND = 1_000_000_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final EquipmentSlot[] EQUIPMENT = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final TelemetryBuffer queue = new TelemetryBuffer();
    private final ArrayBlockingQueue<PendingPacket> packets = new ArrayBlockingQueue<>(128);
    private final ArrayDeque<Sample> history = new ArrayDeque<>(8);
    private final Map<Integer, Long> combatTargets = new HashMap<>();
    private final Map<Integer, Long> deaths = new HashMap<>();
    private final Map<String, Long> rateLimits = new HashMap<>();
    private final AtomicBoolean sending = new AtomicBoolean();
    private final TelemetryCapability capability = loadToken();
    private volatile boolean active = true;
    private volatile String sessionId;
    private volatile long retryAfter;
    private volatile int failures;
    private volatile CompletableFuture<?> request;
    private String installId = UUID.randomUUID().toString();
    private String serverHash;
    private ClientPacketListener connection;
    private boolean noticeShown;
    private boolean noticeUpload;
    private boolean preferencesSaved = true;
    private long sessionStarted;
    private long lastFlush;
    private long lastSample;
    // server schema events: movement_sample (periodic) + combat_summary (per engagement window)
    private long lastMovementSample;
    private long combatWindowStart;
    private long lastCombatEvent;
    private int cwAttacks;
    private int cwDamageDealt;
    private int cwDamageTaken;
    private int cwKills;
    private int cwDeaths;
    private int cwTotems;
    private int cwRubberbacks;
    private long lastCorrection;
    private PendingCorrection correction;

    // flight diagnostics window: memory-only per-tick accumulation while armorfly or pursuit is active
    private FlightSummary flightWindow;
    private JsonObject flightWindowConfig;
    private String flightWindowTag;
    private String flightWindowMode;
    private long flightWindowStart;
    private long flightLastActive;
    private Vec3 flightLastPos;
    private boolean flightHasLastPos;
    private volatile boolean flightSummaryBlocked;

    private record PendingPacket(String session, PacketEvent event) {}
    private record Sample(long nanos, JsonObject data) {}
    private static final class PendingCorrection {
        final long nanos;
        final long at;
        final JsonObject data;
        final JsonArray post = new JsonArray();
        PendingCorrection(long nanos, JsonObject data) {
            this.nanos = nanos;
            this.at = System.currentTimeMillis();
            this.data = data;
        }
    }

    public TelemetryManager() {
        loadPreferences();
        EVENT_BUS.register(this);
    }

    private static TelemetryCapability loadToken() {
        try (InputStream stream = TelemetryManager.class.getResourceAsStream("/META-INF/gzwx-telemetry.json")) {
            return TelemetryCapability.read(stream);
        } catch (Exception ignored) { return null; }
    }

    private void loadPreferences() {
        try {
            if (Files.exists(PREFERENCES)) {
                if (Files.size(PREFERENCES) > 4096) throw new IllegalArgumentException("Oversize preferences");
                JsonObject object = JsonParser.parseString(Files.readString(PREFERENCES)).getAsJsonObject();
                installId = UUID.fromString(object.get("install").getAsString()).toString();
                active = object.get("enabled").getAsBoolean();
                noticeShown = object.has("notice_shown") && object.get("notice_shown").getAsBoolean();
                noticeUpload = object.has("notice_upload") && object.get("notice_upload").getAsBoolean();
            } else { savePreferences(); }
        } catch (Exception ignored) {
            // A broken preference file must not silently re-enable a previous opt-out.
            active = false;
            preferencesSaved = false;
        }
    }

    private void savePreferences() {
        JsonObject object = new JsonObject();
        object.addProperty("install", installId);
        object.addProperty("enabled", active);
        object.addProperty("notice_shown", noticeShown);
        object.addProperty("notice_upload", noticeUpload);
        try {
            Files.createDirectories(PREFERENCES.getParent());
            Path temporary = PREFERENCES.resolveSibling("telemetry.json.tmp");
            Files.writeString(temporary, object.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, PREFERENCES, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, PREFERENCES, StandardCopyOption.REPLACE_EXISTING);
            }
            preferencesSaved = true;
        } catch (Exception ignored) { preferencesSaved = false; }
    }

    public void setActive(boolean value) {
        if (!mc.isSameThread()) { mc.execute(() -> setActive(value)); return; }
        active = value;
        noticeShown = true;
        noticeUpload = canUpload();
        resetSession();
        if (!value && request != null) request.cancel(true);
        savePreferences();
    }

    public boolean isActive() { return active; }
    public boolean canUpload() { return active && capability != null && capability.validAt(System.currentTimeMillis() / 1000); }
    public boolean capabilityExpired() { return capability != null && !capability.validAt(System.currentTimeMillis() / 1000); }
    public boolean preferencesSaved() { return preferencesSaved; }

    /** Compatibility adapter for old probe callers; unknown fields and event types are dropped. */
    public static void record(String type, String jsonFields) {
        if (!"probe_result".equals(type) || jsonFields == null || jsonFields.length() > 4096) return;
        try {
            String value = jsonFields.strip();
            record(type, JsonParser.parseString(value.startsWith("{") ? value : "{" + value + "}").getAsJsonObject());
        } catch (RuntimeException ignored) {}
    }

    public static void record(String type, JsonObject fields) {
        TelemetryManager manager = GuizhouWare.telemetryManager;
        if (manager == null || !manager.ready() || !"probe_result".equals(type) || fields == null) return;
        if (manager.allow("probe", 10 * SECOND)) manager.enqueue(type, TelemetryPrivacy.probe(fields));
    }

    @Subscribe(priority = -1000)
    private void onTick(TickEvent event) {
        if (!mc.isSameThread() || mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (!noticeShown || noticeUpload != canUpload()) {
            String prefix = GuizhouWare.commandManager.getCommandPrefix();
            Command.sendMessage("[Diagnostics] " + (canUpload() ? "Anonymous combat/correction upload is ON. "
                    : "Diagnostic upload is OFF for this installation. ")
                    + "No chat, names, player UUIDs or coordinates. " + prefix + "telemetry off disables it permanently.");
            noticeShown = true;
            noticeUpload = canUpload();
            savePreferences();
        }
        if (!canUpload()) {
            if (sessionId != null) resetSession();
            return;
        }
        ensureSession();
        long now = System.nanoTime();
        for (int i = 0; i < 128; i++) {
            PendingPacket pending = packets.poll();
            if (pending == null) break;
            if (!sessionId.equals(pending.session()) || pending.event().isCancelled()) continue;
            if (pending.event() instanceof PacketEvent.Send send) handleAttack(send);
            else if (pending.event() instanceof PacketEvent.Receive receive) handleDamage(receive);
        }
        if (now - lastSample >= SECOND / 4) {
            lastSample = now;
            JsonObject data = sample(false);
            if (history.size() >= 8) history.removeFirst();
            history.addLast(new Sample(now, data));
            if (correction != null && now > correction.nanos) {
                correction.post.add(relative(data, (now - correction.nanos) / 1_000_000L));
                if (correction.post.size() >= 8 || now - correction.nanos >= 2 * SECOND) finishCorrection();
            }
        }
        if (now - lastFlush >= 15 * SECOND) { lastFlush = now; flush(); }
        if (now - lastMovementSample >= 60 * SECOND) {
            lastMovementSample = now;
            if (allow("movement_sample", 60 * SECOND)) {
                JsonObject movement = sample(false);
                movement.remove("equipment");
                movement.addProperty("rel_ms", 0);
                enqueue("movement_sample", movement);
            }
        }
        if (combatWindowStart != 0
                && (combatTargets.isEmpty() && now - lastCombatEvent >= 15 * SECOND
                    || now - combatWindowStart >= 10 * 60 * SECOND)) {
            flushCombatSummary(now);
        }
        if (!flightSummaryBlocked) tickFlight(now);
        combatTargets.entrySet().removeIf(entry -> now - entry.getValue() > 15 * SECOND);
        deaths.entrySet().removeIf(entry -> {
            Entity entity = mc.level.getEntity(entry.getKey());
            return entity == null || entity instanceof LivingEntity living && living.getHealth() > 0;
        });
    }

    private void ensureSession() {
        if (connection == mc.getConnection() && sessionId != null) return;
        resetSession();
        connection = mc.getConnection();
        sessionId = UUID.randomUUID().toString();
        serverHash = TelemetryPrivacy.serverHash(
                mc.getCurrentServer() == null ? "singleplayer" : mc.getCurrentServer().ip);
        sessionStarted = lastFlush = System.nanoTime();
        JsonObject start = new JsonObject();
        start.addProperty("loader", "fabric");
        start.addProperty("mc", "1.21.11");
        start.addProperty("telemetry_version", 1);
        enqueue("session_start", start);
    }

    private void resetSession() {
        sessionId = null;
        connection = null;
        queue.clear();
        packets.clear();
        history.clear();
        combatTargets.clear();
        deaths.clear();
        rateLimits.clear();
        correction = null;
        lastCorrection = 0;
        lastSample = 0;
        combatWindowStart = 0;
        lastCombatEvent = 0;
        cwAttacks = cwDamageDealt = cwDamageTaken = cwKills = cwDeaths = cwTotems = cwRubberbacks = 0;
        flightWindow = null;
        flightWindowConfig = null;
        flightWindowTag = null;
        flightWindowMode = null;
        flightWindowStart = 0;
        flightLastActive = 0;
        flightLastPos = null;
        flightHasLastPos = false;
        flightSummaryBlocked = false;
    }

    /** Accumulates the flight diagnostics window; observation only, never sends packets. */
    private void tickFlight(long now) {
        GuangxiElytraModule flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        GuangxiElytraChaseModule chase = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraChaseModule.class);
        boolean armorfly = flight != null && flight.isArmorSwapActive();
        boolean chaseActive = chase != null && chase.isEnabled() && chase.hasHeading();
        boolean dive = GuangxiElytraChaseModule.isMaceDiveActive();
        if (!armorfly && !chaseActive && !dive) {
            if (flightWindow != null && now - flightLastActive >= SECOND) settleFlight(now, false);
            return;
        }
        flightLastActive = now;
        if (flightWindow == null) {
            openFlightWindow(now);
        } else if (flightWindow.shouldFlush(now) || now - flightWindowStart >= FlightSummary.MAX_WINDOW_NANOS) {
            settleFlight(now, false);
            openFlightWindow(now);
        }
        Vec3 position = mc.player.position();
        double dx = 0, dy = 0, dz = 0;
        if (flightHasLastPos && flightLastPos != null) {
            dx = position.x - flightLastPos.x;
            dy = position.y - flightLastPos.y;
            dz = position.z - flightLastPos.z;
        }
        flightLastPos = position;
        flightHasLastPos = true;
        boolean gliding = mc.player.isFallFlying();
        double requested = Double.NaN, applied = Double.NaN;
        if (flight != null && flight.lastAppliedTick() == mc.player.tickCount) {
            requested = flight.lastRequestedBpt();
            applied = flight.lastAppliedBpt();
        }
        flightWindow.tick(now, dx, dy, dz, gliding, armorfly, chaseActive, dive,
                flight != null && flight.isBoostAttached(),
                mc.player.getDeltaMovement().horizontalDistance() * 20,
                requested, applied, flight == null ? FlightSummary.BLOCK_NONE : flight.lastRocketBlock());
    }

    private void openFlightWindow(long now) {
        flightWindow = new FlightSummary();
        flightWindowConfig = flightConfig();
        flightWindowMode = flightChaseMode();
        flightWindowTag = flightWindowConfig + "|" + flightWindowMode;
        flightWindowStart = now;
        flightHasLastPos = false;
    }

    /** Closes the window; uploaded only when still valid and its configuration is unchanged. */
    private void settleFlight(long now, boolean forceUpload) {
        if (flightWindow == null) return;
        FlightSummary.Window window = flightWindow.close(now);
        String tag = flightConfig() + "|" + flightChaseMode();
        boolean uploadable = window != null && tag.equals(flightWindowTag);
        if (uploadable && (forceUpload || canUpload())) {
            enqueue("flight_summary", FlightSummary.render(window, flightWindowMode, flightWindowConfig));
        }
        flightWindow = null;
        flightWindowConfig = null;
        flightWindowTag = null;
        flightWindowMode = null;
        flightWindowStart = 0;
        flightLastPos = null;
        flightHasLastPos = false;
    }

    private String flightChaseMode() {
        GuangxiElytraChaseModule chase = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraChaseModule.class);
        if (chase == null || !chase.isEnabled()) return "OFF";
        var setting = chase.getSettingByName("Mode");
        return setting != null && setting.getValue() instanceof Enum<?> mode ? mode.name() : "OFF";
    }

    /** Small fixed whitelist covering the settings this comparison round depends on. */
    private JsonObject flightConfig() {
        JsonObject config = new JsonObject();
        GuangxiElytraModule flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        GuangxiElytraChaseModule chase = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraChaseModule.class);
        if (flight != null) {
            putSetting(config, flight, "FlightMode", "flight_mode");
            putSetting(config, flight, "MotionMode", "motion_mode");
            putSetting(config, flight, "Speed", "speed");
            putSetting(config, flight, "AutoRescale", "auto_rescale");
            putSetting(config, flight, "RescaleAmount", "rescale_amount");
            putSetting(config, flight, "YCap", "y_cap");
            putSetting(config, flight, "CornerClimb", "corner_climb");
            putSetting(config, flight, "ArmorFly", "armor_fly");
            putSetting(config, flight, "GrimBoost", "grim_boost");
            putSetting(config, flight, "PullupAngle", "pullup_angle");
        }
        if (chase != null) {
            putSetting(config, chase, "Mode", "chase_mode");
            putSetting(config, chase, "MaceAdaptive", "mace_adaptive");
            putSetting(config, chase, "MaceTerminal", "mace_terminal");
            putSetting(config, chase, "MaceHeight", "mace_height");
            putSetting(config, chase, "MaceMinFallDistance", "mace_min_fall");
            putSetting(config, chase, "AutoMaceDive", "auto_mace_dive");
            putSetting(config, chase, "CombatRange", "combat_range");
        }
        return config;
    }

    private static void putSetting(JsonObject target, Module module, String name, String key) {
        Setting<?> setting = module.getSettingByName(name);
        if (setting == null) return;
        Object value = setting.getValue();
        if (value instanceof Boolean flag) target.addProperty(key, flag);
        else if (value instanceof Number number) target.addProperty(key, TelemetryPrivacy.bounded(number.doubleValue(), -60_000_000, 60_000_000));
        else if (value instanceof Enum<?> option && TelemetryPrivacy.safeIdentifier(option.name())) target.addProperty(key, option.name());
    }

    @Subscribe(priority = Integer.MIN_VALUE)
    private void onSend(PacketEvent.Send event) {
        if (canUpload() && sessionId != null && event.getPacket() instanceof ServerboundInteractPacket) {
            packets.offer(new PendingPacket(sessionId, event));
        }
    }

    @Subscribe(priority = Integer.MIN_VALUE)
    private void onReceive(PacketEvent.Receive event) {
        if (canUpload() && sessionId != null && event.getPacket() instanceof ClientboundDamageEventPacket) {
            packets.offer(new PendingPacket(sessionId, event));
        }
    }

    private void handleAttack(PacketEvent.Send event) {
        ServerboundInteractPacket packet = (ServerboundInteractPacket) event.getPacket();
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            public void onInteraction(InteractionHand hand) {}
            public void onInteraction(InteractionHand hand, Vec3 position) {}
            public void onAttack() {
                rememberTarget(packet.entityId);
                if (!allow("attack", SECOND)) return;
                Entity target = mc.level.getEntity(packet.entityId);
                JsonObject data = new JsonObject();
                data.addProperty("target_kind", kind(target));
                data.addProperty("weapon", BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString());
                if (target != null) data.addProperty("distance", bounded(mc.player.distanceTo(target), 0, 60_000_000));
                data.addProperty("cooldown", bounded(mc.player.getAttackStrengthScale(0), 0, 1));
                data.addProperty("sprinting", mc.player.isSprinting());
                data.addProperty("on_ground", mc.player.onGround());
                data.add("context", sample(allow("combat_config", 10 * SECOND)));
                enqueue("attack_intent", data);
            }
        });
    }

    private void handleDamage(PacketEvent.Receive event) {
        ClientboundDamageEventPacket packet = (ClientboundDamageEventPacket) event.getPacket();
        boolean incoming = packet.entityId() == mc.player.getId();
        boolean outgoing = packet.sourceCauseId() == mc.player.getId() || packet.sourceDirectId() == mc.player.getId();
        if (!incoming && !outgoing) return;
        if (outgoing) cwDamageDealt++; else cwDamageTaken++;
        if (outgoing || incoming) markCombat();
        if (outgoing) rememberTarget(packet.entityId());
        if (!allow(incoming ? "hurt_in" : "hurt_out", SECOND)) return;
        Entity target = mc.level.getEntity(packet.entityId());
        JsonObject data = new JsonObject();
        data.addProperty("direction", incoming ? "incoming" : "outgoing");
        data.addProperty("source_kind", packet.sourceType().unwrapKey().map(key -> key.identifier().toString()).orElse("unknown"));
        data.addProperty("target_kind", kind(target));
        if (target instanceof LivingEntity living) {
            data.addProperty("health", bounded(living.getHealth(), 0, 1_000_000));
            data.addProperty("absorption", bounded(living.getAbsorptionAmount(), 0, 1_000_000));
        }
        data.add("context", sample(false));
        enqueue("hurt", data);
    }

    private void rememberTarget(int id) {
        if (combatTargets.size() >= 64 && !combatTargets.containsKey(id)) combatTargets.clear();
        combatTargets.put(id, System.nanoTime());
    }

    @Subscribe(priority = Integer.MIN_VALUE)
    private void onTotem(TotemPopEvent event) {
        if (!ready() || !relevant(event.getEntity()) || !allow("totem", SECOND / 4)) return;
        cwTotems++;
        markCombat();
        enqueue("totem", entityEvent(event.getEntity()));
    }

    @Subscribe(priority = Integer.MIN_VALUE)
    private void onDeath(DeathEvent event) {
        if (!ready() || !relevant(event.getEntity()) || deaths.containsKey(event.getEntity().getId())) return;
        if (deaths.size() >= 64) deaths.clear();
        deaths.put(event.getEntity().getId(), System.nanoTime());
        if (event.getEntity() == mc.player) cwDeaths++; else cwKills++;
        markCombat();
        enqueue("death", entityEvent(event.getEntity()));
    }

    private boolean relevant(Entity entity) { return entity == mc.player || combatTargets.containsKey(entity.getId()); }

    private JsonObject entityEvent(Entity entity) {
        JsonObject data = new JsonObject();
        data.addProperty("subject", entity == mc.player ? "self" : "other");
        data.addProperty("target_kind", kind(entity));
        data.add("context", sample(false));
        return data;
    }

    private void markCombat() {
        long now = System.nanoTime();
        if (combatWindowStart == 0) combatWindowStart = now;
        lastCombatEvent = now;
    }

    /** Per-engagement aggregate; field set fixed by the server schema. */
    private void flushCombatSummary(long now) {
        if (combatWindowStart == 0) return;
        JsonObject data = new JsonObject();
        data.addProperty("duration_ms", Math.max(0, (now - combatWindowStart) / 1_000_000L));
        data.addProperty("attacks", cwAttacks);
        data.addProperty("damage_dealt", cwDamageDealt);
        data.addProperty("damage_taken", cwDamageTaken);
        data.addProperty("kills", cwKills);
        data.addProperty("deaths", cwDeaths);
        data.addProperty("totems", cwTotems);
        data.addProperty("rubberbacks", cwRubberbacks);
        enqueue("combat_summary", data);
        combatWindowStart = 0;
        lastCombatEvent = 0;
        cwAttacks = cwDamageDealt = cwDamageTaken = cwKills = cwDeaths = cwTotems = cwRubberbacks = 0;
    }

    @Subscribe(priority = Integer.MIN_VALUE)
    private void onModuleToggle(ClientEvent event) {
        if (!ready() || event.getType() != ClientEvent.Type.TOGGLE_MODULE
                || !(event.getFeature() instanceof Module module) || !relevantModule(module)) {
            if (event.getType() == ClientEvent.Type.TOGGLE_MODULE
                    && event.getFeature() instanceof Module toggled
                    && (toggled.getName().equals("GuangxiElytra") || toggled.getName().equals("GuangxiElytraChase"))) {
                settleFlight(System.nanoTime(), false);
            }
            return;
        }
        if ((module.getName().equals("GuangxiElytra") || module.getName().equals("GuangxiElytraChase"))) {
            settleFlight(System.nanoTime(), false);
        }
        if (!allow("toggle:" + module.getName(), SECOND)) return;
        JsonObject data = new JsonObject();
        data.addProperty("module", module.getName());
        data.addProperty("enabled", module.isEnabled());
        enqueue("module_toggle", data);
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        String expected = sessionId;
        mc.execute(() -> {
            if (expected == null || !expected.equals(sessionId)) return;
            finishCorrection();
            flushCombatSummary(System.nanoTime());
            settleFlight(System.nanoTime(), true);
            JsonObject data = new JsonObject();
            data.addProperty("duration_ms", Math.max(0, (System.nanoTime() - sessionStarted) / 1_000_000L));
            enqueue("session_end", data);
            flush();
            resetSession();
        });
    }

    /** Resolved by the vanilla application hook; teleports have no known anti-cheat cause. */
    public void onPlayerCorrection(double distanceSq) {
        if (!ready() || !Double.isFinite(distanceSq) || distanceSq <= 0.01) return;
        long now = System.nanoTime();
        // Counted before the detailed-event rate limit: the summary total must not
        // undercount corrections that arrive inside the five-second window.
        cwRubberbacks++;
        if (flightWindow != null) {
            flightWindow.correction();
            flightHasLastPos = false;
        }
        if (lastCorrection != 0 && now - lastCorrection < 5 * SECOND) return;
        lastCorrection = now;
        finishCorrection();
        JsonObject data = new JsonObject();
        data.addProperty("distance", bounded(Math.sqrt(distanceSq), 0, 60_000_000));
        JsonArray pre = new JsonArray();
        for (Sample sample : history) {
            if (now - sample.nanos() <= 3 * SECOND) pre.add(relative(sample.data(), (sample.nanos() - now) / 1_000_000L));
        }
        data.add("pre", pre);
        data.add("context", sample(true));
        correction = new PendingCorrection(now, data);
    }

    private void finishCorrection() {
        if (correction == null) return;
        correction.data.add("post", correction.post);
        enqueue("correction", correction.data, correction.at);
        correction = null;
    }

    private static JsonObject relative(JsonObject sample, long milliseconds) {
        JsonObject data = sample.deepCopy();
        data.addProperty("rel_ms", Math.max(-600_000, Math.min(600_000, milliseconds)));
        return data;
    }

    private JsonObject sample(boolean includeConfiguration) {
        JsonObject data = new JsonObject();
        Vec3 velocity = mc.player.getDeltaMovement();
        data.addProperty("speed", bounded(velocity.horizontalDistance() * 20, 0, 10_000));
        data.addProperty("vertical_speed", bounded(velocity.y * 20, -10_000, 10_000));
        JsonObject vector = new JsonObject();
        vector.addProperty("x", bounded(velocity.x, -10_000, 10_000));
        vector.addProperty("y", bounded(velocity.y, -10_000, 10_000));
        vector.addProperty("z", bounded(velocity.z, -10_000, 10_000));
        data.add("velocity", vector);
        data.addProperty("on_ground", mc.player.onGround());
        data.addProperty("fall_flying", mc.player.isFallFlying());
        data.addProperty("sprinting", mc.player.isSprinting());
        data.addProperty("sneaking", mc.player.isShiftKeyDown());
        data.addProperty("fall_distance", bounded(mc.player.fallDistance, 0, 60_000_000));
        data.addProperty("health", bounded(mc.player.getHealth(), 0, 1_000_000));
        data.addProperty("absorption", bounded(mc.player.getAbsorptionAmount(), 0, 1_000_000));
        var playerInfo = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        data.addProperty("ping_ms", playerInfo == null ? 0 : Math.max(0, Math.min(600_000, playerInfo.getLatency())));
        data.addProperty("tps", bounded(GuizhouWare.tpsCounterService == null ? 20
                : GuizhouWare.tpsCounterService.getAverageTPS(), 0, 100));
        JsonArray equipment = new JsonArray();
        for (EquipmentSlot slot : EQUIPMENT) equipment.add(BuiltInRegistries.ITEM.getKey(mc.player.getItemBySlot(slot).getItem()).toString());
        data.add("equipment", equipment);
        if (includeConfiguration) data.add("modules", modules());
        return data;
    }

    private JsonArray modules() {
        JsonArray result = new JsonArray();
        for (Module module : GuizhouWare.moduleManager.getModules()) {
            if (!module.isEnabled() || !relevantModule(module) || result.size() >= 16) continue;
            JsonObject data = new JsonObject();
            data.addProperty("name", module.getName());
            JsonObject settings = new JsonObject();
            for (String name : new String[]{"ArmorFly", "AutoMaceDive", "GrimBoost", "SafeLanding", "TerrainGuard", "TerrainLookahead", "FreeLook"}) {
                Setting<?> setting = module.getSettingByName(name);
                if (setting != null) TelemetryPrivacy.setting(settings, name, setting.getValue());
            }
            for (Setting<?> setting : module.getSettings()) {
                if (settings.size() >= 12) break;
                if (setting.getName().equals("Enabled") || setting.getName().equals("BindMode")) continue;
                TelemetryPrivacy.setting(settings, setting.getName(), setting.getValue());
            }
            data.add("settings", settings);
            result.add(data);
        }
        return result;
    }

    private static boolean relevantModule(Module module) {
        return TelemetryPrivacy.safeIdentifier(module.getName()) && (module.getCategory() == Module.Category.COMBAT
                || module.getCategory() == Module.Category.MOVEMENT || module.getCategory() == Module.Category.PLAYER
                || module.getCategory() == Module.Category.WORLD || module.getName().equals("GuangxiProbe"));
    }

    private boolean ready() {
        return canUpload() && mc.isSameThread() && mc.player != null && mc.level != null
                && sessionId != null && connection == mc.getConnection();
    }

    private boolean allow(String key, long interval) {
        long now = System.nanoTime();
        Long last = rateLimits.get(key);
        if (last != null && now - last < interval) return false;
        rateLimits.put(key, now);
        return true;
    }

    private static String kind(Entity entity) {
        return entity == null ? "unknown" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static double bounded(double value, double min, double max) { return TelemetryPrivacy.bounded(value, min, max); }
    private void enqueue(String type, JsonObject data) { enqueue(type, data, System.currentTimeMillis()); }

    private void enqueue(String type, JsonObject data, long at) {
        if (!canUpload() || sessionId == null) return;
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("t", at);
        event.add("data", data);
        queue.offer(event);
    }

    private void flush() {
        long now = System.nanoTime();
        if (!canUpload() || sessionId == null || queue.isEmpty() || (retryAfter != 0 && now - retryAfter < 0)
                || !sending.compareAndSet(false, true)) return;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("schema", 1);
        envelope.addProperty("install", installId);
        envelope.addProperty("session", sessionId);
        envelope.addProperty("server_hash", serverHash);
        envelope.addProperty("build", BuildConfig.VERSION + "-" + BuildConfig.HASH + "-"
                + BuildConfig.BUILD_TIME.replaceAll("[^A-Za-z0-9]", ""));
        envelope.addProperty("sent_at", System.currentTimeMillis());
        String body = queue.batch(envelope).toString();
        try {
            var pending = HTTP.sendAsync(HttpRequest.newBuilder(URI.create(ENDPOINT))
                            .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                            .header("X-Telemetry-Token", capability.token())
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
            request = pending;
            pending.whenComplete((response, failure) -> {
                        if (failure == null && response.statusCode() >= 200 && response.statusCode() < 300) {
                            failures = 0;
                            retryAfter = 0;
                        } else {
                            failures = Math.min(6, failures + 1);
                            long seconds = Math.min(1800, 30L << failures);
                            if (response != null && response.statusCode() == 400) {
                                // A strict receiver that does not know flight_summary rejects the
                                // whole batch; stop producing them until the next session.
                                flightSummaryBlocked = true;
                            }
                            if (response != null && response.statusCode() == 429) {
                                try {
                                    seconds = Math.max(seconds, Math.min(3600,
                                            Long.parseLong(response.headers().firstValue("Retry-After").orElse("60"))));
                                } catch (NumberFormatException ignored) {}
                            }
                            retryAfter = System.nanoTime() + seconds * SECOND;
                        }
                        sending.set(false);
                    });
        } catch (RuntimeException ignored) {
            retryAfter = System.nanoTime() + 60 * SECOND;
            sending.set(false);
        }
    }
}
