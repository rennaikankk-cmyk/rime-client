package dev.guizhou.util;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ArmorFlyConfigMigrationTest {
    public static void main(String[] args) {
        for (boolean active : new boolean[]{false,true}) for (boolean legacy : new boolean[]{false,true}) for (boolean standalone : new boolean[]{false,true}) {
            JsonObject original = json("{\"GuangxiElytra\":{\"ArmorFly\":\""+legacy+"\",\"Enabled\":\""+active+"\"},\"ArmorFly\":{\"Enabled\":\""+standalone+"\",\"AutoRocket\":\"false\"}}");
            JsonObject result = ArmorFlyConfigMigration.migrate(original);
            check(result.getAsJsonObject("ArmorFly").get("Enabled").getAsBoolean()==(legacy&&active||standalone),"Preserve active old ownership");
            check(result.getAsJsonObject("GuangxiElytraChase").get("AutoArmorFly").getAsBoolean()==legacy,"Preserve latent chase armor preference");
            check(!result.getAsJsonObject("GuangxiElytra").has("ArmorFly"),"Remove old key");
            check(!result.getAsJsonObject("ArmorFly").get("AutoRocket").getAsBoolean(),"Preserve independent options");
            check(original.getAsJsonObject("GuangxiElytra").has("ArmorFly"),"Input unchanged");
            check(result.equals(ArmorFlyConfigMigration.migrate(result)),"Idempotent migration");
        }
        check(!ArmorFlyConfigMigration.migrate(json("{\"GuangxiElytra\":{\"ArmorFly\":false}}")).has("ArmorFly"),"Partial false preset must not disable existing owner");
        check(ArmorFlyConfigMigration.migrate(json("{\"GuangxiElytra\":{\"ArmorFly\":true}}")).getAsJsonObject("ArmorFly").get("Enabled").getAsBoolean(),"Old partial true preset activates standalone");
        for (String invalid : new String[]{"null","{}","[]","\"bogus\""})
            check(!ArmorFlyConfigMigration.migrate(json("{\"GuangxiElytra\":{\"ArmorFly\":"+invalid+"}}")).has("ArmorFly"),"Invalid legacy value ignored");
        JsonObject modern=json("{\"ArmorFly\":{\"Enabled\":false},\"GuangxiElytra\":{\"Enabled\":true}}");
        check(modern.equals(ArmorFlyConfigMigration.migrate(modern)),"Modern disabled module remains disabled");
        System.out.println("ArmorFlyConfigMigrationTest passed: 55 checks");
    }
    private static JsonObject json(String s) {return JsonParser.parseString(s).getAsJsonObject();}
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
