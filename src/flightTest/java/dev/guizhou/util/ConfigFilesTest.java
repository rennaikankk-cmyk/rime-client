package dev.guizhou.util;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class ConfigFilesTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("guizhouware-config-test-");
        try {
            Path config = dir.resolve("new-profile/config.json");
            check(ConfigFiles.read(config) == null, "A first launch retains defaults without an exception");
            ConfigFiles.write(config, "{\"Items\":\"diamond_spear\",\"备注\":\"重锤\"}");
            var parsed = ConfigFiles.read(config).getAsJsonObject();
            check(parsed.get("Items").getAsString().equals("diamond_spear"), "Item identifiers survive persistence");
            check(parsed.get("备注").getAsString().equals("重锤"), "UTF-8 round trip");
            ConfigFiles.write(config, "{\"value\":2}");
            check(ConfigFiles.read(config).getAsJsonObject().get("value").getAsInt() == 2, "Existing file is replaced");
            try (var files = Files.list(config.getParent())) {
                check(files.count() == 1, "Atomic save leaves no temporary files");
            }
            Files.writeString(config, "{broken");
            try { ConfigFiles.read(config); throw new AssertionError("Malformed file must report failure"); }
            catch (JsonParseException expected) { }
            check(Files.readString(config).equals("{broken"), "Failed reads never overwrite the original");
            Files.writeString(config, "null");
            try { ConfigFiles.read(config); throw new AssertionError("Null file must report failure"); }
            catch (IOException expected) { }
            Path blocked = dir.resolve("directory.json");
            Files.createDirectory(blocked);
            Files.writeString(blocked.resolve("keep"), "original");
            try { ConfigFiles.write(blocked, "{}"); throw new AssertionError("Directory replacement must fail"); }
            catch (IOException expected) { }
            check(Files.readString(blocked.resolve("keep")).equals("original"), "Failed replacement preserves existing data");
            try (var files = Files.list(dir)) {
                check(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Failed save cleans its own temporary file");
            }
            System.out.println("ConfigFilesTest passed: first launch, atomic replacement, UTF-8 and failure preservation");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
