package dev.guizhou.util.elytra;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class FlightProfileMigrationTest {
    private static int checks;
    public static void main(String[] args) {
        JsonObject old = json("{\"MotionMode\":\"VOID\",\"Speed\":3.5,\"Enabled\":true,\"Keybind\":42}");
        JsonObject migrated = FlightProfileMigration.migrate(old).getAsJsonObject();
        check(migrated.get("Mode").getAsString().equals("NoAC"), "legacy direct movement remains NoAC");
        check(migrated.get("SpeedBps").getAsDouble()==70, "blocks/tick migrate to blocks/second");
        check(migrated.get("ClimbBps").getAsDouble()==70, "legacy climb inherits its original speed");
        check(!old.has("Mode"), "migration does not mutate saved input");
        check(migrated.get("Enabled").getAsBoolean() && migrated.get("Keybind").getAsInt()==42, "state and binding preserved");
        check(FlightProfileMigration.migrate(migrated).equals(migrated), "repeated loading is idempotent");
        for (String legacy : new String[]{"FIREWORKS", "INVALID"}) {
            check(FlightProfileMigration.migrate(json("{\"MotionMode\":\""+legacy+"\",\"GrimBoost\":true}"))
                    .getAsJsonObject().get("Mode").getAsString().equals("Grim"), "legacy accelerated settings select controlled Grim profile");
        }
        JsonObject explicit = FlightProfileMigration.migrate(json("{\"Mode\":\"NoAC\",\"MotionMode\":\"FIREWORKS\",\"SpeedBps\":60,\"ClimbBps\":80}" )).getAsJsonObject();
        check(explicit.get("Mode").getAsString().equals("NoAC") && explicit.get("ClimbBps").getAsDouble()==80, "explicit profile and separate climb win");
        for (String bad : new String[]{"null", "{}", "[]", "\"NaN\"", "1e300", "-1e300"}) {
            JsonObject value = FlightProfileMigration.migrate(json("{\"SpeedBps\":"+bad+",\"ClimbBps\":"+bad+"}")).getAsJsonObject();
            for(String key:new String[]{"SpeedBps","ClimbBps"}) {
                double speed=value.get(key).getAsDouble();
                check(Double.isFinite(speed)&&speed>=1&&speed<=2000,"untrusted speed is finite and bounded");
            }
        }
        check(FlightProfileMigration.migrate(null).isJsonObject(),"missing profile is safe");
        check(!FlightProfileMigration.migrate(json("{\"FreeLook\":true}")).getAsJsonObject().has("Mode"),
                "partial presets must not reset the selected mode");
        check(FlightProfileMigration.migrate(json("{\"MotionMode\":\"FIREWORKS\",\"AutoRescale\":false,\"Speed\":6}"))
                .getAsJsonObject().get("SpeedBps").getAsDouble()==120,"old unrestricted rockets profile preserves direct speed");
        check(FlightProfileMigration.migrate(json("{\"MotionMode\":\"FIREWORKS\",\"AutoRescale\":false,\"CoupledCruise\":true}"))
                .getAsJsonObject().get("Mode").getAsString().equals("Grim"),"cruise takes precedence over unused rescale switch");
        System.out.println("FlightProfileMigrationTest passed: "+checks+" checks");
    }
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
