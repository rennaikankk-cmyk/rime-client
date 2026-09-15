package dev.guizhou.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.guizhou.features.Feature;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.settings.EnumConverter;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.traits.Jsonable;
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
    private static final Path OYVEY_PATH = FabricLoader.getInstance().getGameDir().resolve("guizhouware");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<Jsonable> jsonables = new LinkedList<>();

    public void addConfig(Jsonable jsonable) {
        jsonables.add(jsonable);
    }

    public void load() {
        mkdirs();
        for (Jsonable jsonable : jsonables) {
            try {
                String read = Files.readString(OYVEY_PATH.resolve(jsonable.getFileName()));
                jsonable.fromJson(JsonParser.parseString(read));
            } catch (Throwable e) {
                LOGGER.error("Failed to load", e);
            }
        }
    }

    public void save() {
        mkdirs();
        for (Jsonable jsonable : jsonables) {
            try {
                JsonElement json = jsonable.toJson();
                Files.writeString(OYVEY_PATH.resolve(jsonable.getFileName()), GSON.toJson(json));
            } catch (Throwable e) {
                LOGGER.error("Failed to write to file", e);
            }
        }
    }

    // ---------------------------------------------------------- named presets

    private static final Path CONFIGS_DIR = OYVEY_PATH.resolve("configs");
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
                Files.writeString(dir.resolve(jsonable.getFileName()), GSON.toJson(json));
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
        for (Jsonable jsonable : jsonables) {
            try {
                Path file = dir.resolve(jsonable.getFileName());
                if (!Files.exists(file)) continue;
                jsonable.fromJson(JsonParser.parseString(Files.readString(file)));
            } catch (Throwable e) {
                LOGGER.error("Failed to load config '{}' part '{}'", name, jsonable.getFileName(), e);
            }
        }
        return true;
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
        if (!OYVEY_PATH.toFile().exists()) {
            boolean success = OYVEY_PATH.toFile().mkdirs();
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
            case "String" -> setting.setValue(element.getAsString().replace("_", " "));
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
