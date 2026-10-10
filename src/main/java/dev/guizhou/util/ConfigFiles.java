package dev.guizhou.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** First-run defaults and crash-resistant single-file configuration persistence. */
public final class ConfigFiles {
    private ConfigFiles() {}

    public static JsonElement read(Path file) throws IOException {
        try {
            JsonElement value = JsonParser.parseString(Files.readString(file));
            if (value.isJsonNull()) throw new IOException("Empty or null configuration: " + file.getFileName());
            return value;
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    public static void write(Path file, String json) throws IOException {
        Path destination = file.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, json);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
