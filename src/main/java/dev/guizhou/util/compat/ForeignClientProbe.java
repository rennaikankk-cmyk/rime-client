package dev.guizhou.util.compat;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Optional, read-only public API adapters. No foreign settings or packets are changed. */
public final class ForeignClientProbe {
    public enum Domain { FLIGHT, ARMOR, ROCKET, COMBAT, OFFHAND, FOOD }
    public record Snapshot(Map<Domain, String> owners) {
        public Snapshot { owners = Map.copyOf(owners); }
        public boolean blocks(Domain domain) { return owners.containsKey(domain); }
        public String reason(Domain domain) { return owners.get(domain); }
    }
    @FunctionalInterface public interface Classes { Class<?> find(String name) throws ClassNotFoundException; }
    private final Classes classes;
    public ForeignClientProbe(Classes classes) { this.classes = classes; }

    public Snapshot read(Set<String> mods) {
        var owners = new EnumMap<Domain, String>(Domain.class);
        if (mods.contains("slimefunhelper") || mods.contains("rennaikanksmp")) {
            try { slimefun(owners); }
            catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                unknown(owners, "SlimefunHelper API unavailable");
            }
        }
        if (mods.contains("rusherhack")) {
            try { rusher(owners); }
            catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                unknown(owners, "RusherHack API unavailable");
            }
        }
        return new Snapshot(owners);
    }

    private void slimefun(Map<Domain, String> owners) throws ReflectiveOperationException {
        Class<?> extraType = classes.find("me.matl114.hacks.modules.move.ElytraExtra");
        Object extra = extraType.getField("INSTANCE").get(null);
        if (extra == null) throw new IllegalStateException("SlimefunHelper initializing");
        if (flag(extra, "armorFly")) put(owners, "SlimefunHelper ArmorFly", Domain.ARMOR);
        if (flag(extra, "autoRocket")) put(owners, "SlimefunHelper AutoRocket", Domain.ROCKET);
        if (flag(extra, "autoSwitch")) put(owners, "SlimefunHelper AutoSwitch", Domain.ARMOR);
        if (flag(extra, "enableUnbreakableElytra")) put(owners, "SlimefunHelper UnbreakableElytra", Domain.ARMOR);
        if (flag(extra, "rocketBoost")) put(owners, "SlimefunHelper RocketBoost", Domain.FLIGHT, Domain.COMBAT);
        Object groups = classes.find("me.matl114.hacks.modules.HackModules").getMethod("getModuleGroups").invoke(null);
        if (!(groups instanceof Iterable<?> iterable)) throw new IllegalStateException("Missing module groups");
        Class<?> moduleType = classes.find("me.matl114.hacks.api.BaseModule");
        Method active = moduleType.getMethod("isActive"), name = moduleType.getMethod("getName");
        Method modules = classes.find("me.matl114.hacks.api.ModuleManager").getMethod("getModules");
        for (Object group : iterable) {
            Object result = modules.invoke(group);
            if (!(result instanceof Iterable<?> entries)) throw new IllegalStateException("Missing modules");
            for (Object module : entries) if (Boolean.TRUE.equals(active.invoke(module)))
                classify(owners, "SlimefunHelper", String.valueOf(name.invoke(module)));
        }
    }

    private boolean flag(Object module, String name) throws ReflectiveOperationException {
        Object ref = module.getClass().getField(name).get(module);
        Object result = ref.getClass().getMethod("get").invoke(ref);
        if (!(result instanceof Boolean)) throw new IllegalStateException("Not a boolean flag");
        return Boolean.TRUE.equals(result);
    }

    private void rusher(Map<Domain, String> owners) throws ReflectiveOperationException {
        Object manager = classes.find("org.rusherhack.client.api.RusherHackAPI").getMethod("getModuleManager").invoke(null);
        Object result = classes.find("org.rusherhack.core.feature.IFeatureManager").getMethod("getFeatures").invoke(manager);
        if (!(result instanceof Iterable<?> entries)) throw new IllegalStateException("Missing RusherHack modules");
        Class<?> toggle = classes.find("org.rusherhack.core.interfaces.IToggleable");
        Method active = toggle.getMethod("isToggled");
        Method name = classes.find("org.rusherhack.core.interfaces.INamed").getMethod("getName");
        for (Object module : entries) if (toggle.isInstance(module) && Boolean.TRUE.equals(active.invoke(module)))
            classify(owners, "RusherHack", String.valueOf(name.invoke(module)));
    }

    static void classify(Map<Domain, String> owners, String client, String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "");
        String owner = client + " " + name;
        switch (key) {
            case "elytraflight", "elytrafly", "elytracontrol", "elytragrimaccelerate", "elytragrimacc", "elytrajump", "flight", "fly" ->
                    put(owners, owner, Domain.FLIGHT, Domain.ARMOR, Domain.ROCKET, Domain.COMBAT);
            case "elytrabot", "elytrabotv2" -> put(owners, owner, Domain.FLIGHT, Domain.ARMOR, Domain.ROCKET, Domain.COMBAT);
            case "armorfly", "autoarmor", "elytraswap", "cheststealer", "inventorytweaks", "inventorymanager" -> put(owners, owner, Domain.ARMOR);
            case "autorocket", "autofirework" -> put(owners, owner, Domain.ROCKET);
            case "autototem", "offhand" -> put(owners, owner, Domain.OFFHAND);
            case "autoeat", "autogapple" -> put(owners, owner, Domain.FOOD);
            case "killaura", "aura", "attackaura", "anchoraura", "autocrystal", "crystalaura", "maceaura", "spearaura" -> put(owners, owner, Domain.COMBAT);
            default -> { }
        }
    }
    private static void put(Map<Domain, String> owners, String name, Domain... domains) {
        for (Domain domain : domains) owners.putIfAbsent(domain, name);
    }
    private static void unknown(Map<Domain, String> owners, String reason) {
        put(owners, reason, Domain.values());
    }
}
