package dev.guizhou.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import dev.guizhou.features.Feature;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.settings.EnumConverter;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.traits.Jsonable;
import dev.guizhou.util.ConfigFiles;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Vector2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;

public class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ConfigManager");
    private static final Path CONFIG_ROOT = FabricLoader.getInstance().getGameDir().resolve("guizhouware");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<Jsonable> jsonables = new LinkedList<>();

    public void addConfig(Jsonable jsonable) {
        jsonables.add(jsonable);
    }

    public void load() {
        mkdirs();
        for (Jsonable jsonable : jsonables) {
            try {
                JsonElement read = ConfigFiles.read(CONFIG_ROOT.resolve(jsonable.getFileName()));
                if (read != null) jsonable.fromJson(read);
            } catch (Throwable e) {
                LOGGER.error("Failed to load", e);
            }
        }
        // Snapshot after every part loaded: later parts (modules) can adjust earlier ones.
        for (Jsonable jsonable : jsonables) rememberLoaded(jsonable);
        dirtySince = 0;
    }

    public void save() {
        mkdirs();
        long generation = generations.incrementAndGet();
        for (Jsonable jsonable : jsonables) {
            try {
                JsonElement json = jsonable.toJson();
                String text = GSON.toJson(json);
                synchronized (writeLock) {
                    ConfigFiles.write(CONFIG_ROOT.resolve(jsonable.getFileName()), text);
                    written.put(jsonable.getFileName(), text);
                    writtenGeneration = Math.max(writtenGeneration, generation);
                }
            } catch (Throwable e) {
                LOGGER.error("Failed to write to file", e);
            }
        }
    }

    // ---------------------------------------------------------- autosave

    private static final long AUTOSAVE_DEBOUNCE_MS = 5_000L;
    private static final long AUTOSAVE_INTERVAL_MS = 60_000L;
    private final Object writeLock = new Object();
    private final java.util.Map<String, String> written = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GuizhouWare-ConfigWriter");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.atomic.AtomicLong generations = new java.util.concurrent.atomic.AtomicLong();
    /** Newest snapshot on disk; guarded by writeLock so a queued autosave never overwrites a newer save(). */
    private long writtenGeneration;
    private volatile long dirtySince;
    private long lastSnapshot = System.currentTimeMillis();

    /** Any setting, bind or toggle change; the next {@link #autosaveTick()} after the debounce writes it. */
    public void markDirty() {
        if (dirtySince == 0) dirtySince = System.currentTimeMillis();
    }

    /**
     * Client thread only. Serializes on this thread (modules are not thread-safe), then writes the
     * files whose text changed on a background thread with the same atomic replace as {@link #save()}.
     * The periodic pass also catches changes that post no event (friends, enemies, kits, waypoints).
     */
    public void autosaveTick() {
        long now = System.currentTimeMillis();
        boolean debounced = dirtySince != 0 && now - dirtySince >= AUTOSAVE_DEBOUNCE_MS;
        if (!debounced && now - lastSnapshot < AUTOSAVE_INTERVAL_MS) return;
        dirtySince = 0;
        lastSnapshot = now;
        java.util.Map<String, String> changed = new java.util.LinkedHashMap<>();
        for (Jsonable jsonable : jsonables) {
            try {
                String text = GSON.toJson(jsonable.toJson());
                if (!text.equals(written.get(jsonable.getFileName()))) changed.put(jsonable.getFileName(), text);
            } catch (Throwable e) {
                LOGGER.error("Failed to serialize {} for autosave", jsonable.getFileName(), e);
            }
        }
        if (changed.isEmpty()) return;
        long generation = generations.incrementAndGet();
        writer.execute(() -> {
            try {
                mkdirs();
            } catch (Throwable e) {
                LOGGER.error("Autosave could not create the config directory", e);
                return;
            }
            for (var entry : changed.entrySet()) {
                try {
                    synchronized (writeLock) {
                        if (generation < writtenGeneration) return;
                        ConfigFiles.write(CONFIG_ROOT.resolve(entry.getKey()), entry.getValue());
                        written.put(entry.getKey(), entry.getValue());
                        writtenGeneration = generation;
                    }
                } catch (Throwable e) {
                    LOGGER.error("Autosave failed for {}", entry.getKey(), e);
                }
            }
        });
    }

    /** Remembers what load() read so the first autosave only writes files that really changed. */
    private void rememberLoaded(Jsonable jsonable) {
        try {
            written.put(jsonable.getFileName(), GSON.toJson(jsonable.toJson()));
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------- named presets

    private static final Path CONFIGS_DIR = CONFIG_ROOT.resolve("configs");
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]+");

    /** Saves every registered config file into {@code configs/<name>/}. */
    public boolean saveNamed(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) return false;
        try {
            Path dir = CONFIGS_DIR.resolve(name);
            Files.createDirectories(dir);
            for (Jsonable jsonable : jsonables) {
                JsonElement json = jsonable.toJson();
                ConfigFiles.write(dir.resolve(jsonable.getFileName()), GSON.toJson(json));
            }
            return true;
        } catch (Throwable e) {
            LOGGER.error("Failed to save config '{}'", name, e);
            return false;
        }
    }

    /** Loads every registered config file from {@code configs/<name>/} into the live modules. */
    public boolean loadNamed(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) return false;
        Path dir = CONFIGS_DIR.resolve(name);
        if (!Files.isDirectory(dir)) return false;
        boolean complete = true;
        for (Jsonable jsonable : jsonables) {
            try {
                Path file = dir.resolve(jsonable.getFileName());
                JsonElement read = ConfigFiles.read(file);
                if (read != null) jsonable.fromJson(read);
            } catch (Throwable e) {
                complete = false;
                LOGGER.error("Failed to load config '{}' part '{}'", name, jsonable.getFileName(), e);
            }
        }
        return complete;
    }

    public List<String> listNamed() {
        List<String> out = new java.util.ArrayList<>();
        try (var stream = Files.list(CONFIGS_DIR)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(out::add);
        } catch (Throwable ignored) {
        }
        return out;
    }

    public boolean deleteNamed(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) return false;
        Path dir = CONFIGS_DIR.resolve(name);
        if (!Files.isDirectory(dir)) return false;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable e) {
            LOGGER.error("Failed to delete config '{}'", name, e);
            return false;
        }
        return true;
    }

    private void mkdirs() {
        if (!CONFIG_ROOT.toFile().exists()) {
            boolean success = CONFIG_ROOT.toFile().mkdirs();
            if (!success) {
                throw new RuntimeException("Failed to create needed directories!");
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void setValueFromJson(Feature feature, Setting setting, JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        switch (setting.getType()) {
            case "Boolean" -> setting.setValue(element.getAsBoolean());
            case "Double" -> setting.setValue(element.getAsDouble());
            case "Float" -> setting.setValue(element.getAsFloat());
            case "Integer" -> setting.setValue(element.getAsInt());
            case "String" -> setting.setValue(element.getAsString());
            case "Bind" -> setting.setValue(new Bind(element.getAsInt()));
            case "Color" -> {
                try {
                    String colorStr = element.getAsString();
                    String[] parts = colorStr.split(",");
                    if (parts.length == 4) {
                        int r = Integer.parseInt(parts[0]);
                        int g = Integer.parseInt(parts[1]);
                        int b = Integer.parseInt(parts[2]);
                        int a = Integer.parseInt(parts[3]);
                        setting.setValue(new Color(r, g, b, a));
                    }
                } catch (Exception exception) {
                    LOGGER.error("Error parsing color for: {} : {}", feature.getName(), setting.getName());
                }
            }
            case "Pos" -> {
                try {
                    String posStr = element.getAsString();
                    String[] parts = posStr.split(",");
                    if (parts.length == 2) {
                        float x = Float.parseFloat(parts[0]);
                        float y = Float.parseFloat(parts[1]);
                        setting.setValue(new Vector2f(x, y));
                    }
                } catch (Exception exception) {
                    LOGGER.error("Error parsing position for: {} : {}", feature.getName(), setting.getName());
                }
            }
            case "Enum" -> {
                try {
                    EnumConverter converter = new EnumConverter(setting.getValue().getClass());
                    Enum value = converter.doBackward(element);
                    if (value != null) setting.setValue(value);
                } catch (Exception exception) {
                    LOGGER.error("Error parsing enum for {}.{}: {}", feature.getName(), setting.getName(), exception);
                }
            }
            default -> LOGGER.error("Unknown Setting type for: {} : {}", feature.getName(), setting.getName());
        }
    }
}
