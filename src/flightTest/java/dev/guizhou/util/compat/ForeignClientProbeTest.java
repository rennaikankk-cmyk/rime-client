package dev.guizhou.util.compat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.guizhou.util.compat.ForeignClientProbe.Domain.*;

public final class ForeignClientProbeTest {
    private static int checks;
    public static final class Flag { public boolean value; public Boolean get() { return value; } }
    public static final class Extra { public static Extra INSTANCE = new Extra(); public Flag armorFly = new Flag(), autoRocket = new Flag(), autoSwitch = new Flag(), enableUnbreakableElytra = new Flag(), rocketBoost = new Flag(); }
    public static class Base { final String name; boolean active; Base(String name) { this.name=name; } public String getName(){return name;} public boolean isActive(){return active;} }
    public static final class Groups { public static List<Manager> getModuleGroups(){return List.of(new Manager());} }
    public static final class Manager { public List<Base> getModules(){return modules;} }
    static List<Base> modules = List.of();
    public interface Named { String getName(); }
    public interface Toggle { boolean isToggled(); }
    public interface Features { List<Feature> getFeatures(); }
    public static final class Feature implements Named, Toggle {
        final String name; boolean active; Feature(String name, boolean active){this.name=name;this.active=active;}
        public String getName(){return name;} public boolean isToggled(){return active;}
        public void setToggled(boolean state){throw new AssertionError("Foreign state must not be changed");}
    }
    public static final class API { public static Features getModuleManager(){return () -> features;} }
    static List<Feature> features=List.of();
    private static Class<?> lookup(String name) throws ClassNotFoundException {
        return switch(name) {
            case "me.matl114.hacks.modules.move.ElytraExtra" -> Extra.class;
            case "me.matl114.hacks.modules.HackModules" -> Groups.class;
            case "me.matl114.hacks.api.ModuleManager" -> Manager.class;
            case "me.matl114.hacks.api.BaseModule" -> Base.class;
            case "org.rusherhack.client.api.RusherHackAPI" -> API.class;
            case "org.rusherhack.core.feature.IFeatureManager" -> Features.class;
            case "org.rusherhack.core.interfaces.INamed" -> Named.class;
            case "org.rusherhack.core.interfaces.IToggleable" -> Toggle.class;
            default -> throw new ClassNotFoundException(name);
        };
    }
    public static void main(String[] args) {
        var probe=new ForeignClientProbe(ForeignClientProbeTest::lookup);
        check(probe.read(Set.of()).owners().isEmpty(),"No optional clients requires no API");
        check(probe.read(Set.of("slimefunhelper","rusherhack")).owners().isEmpty(),"Installed inactive clients do not block");
        Extra.INSTANCE.armorFly.value=true;
        var state=probe.read(Set.of("slimefunhelper"));
        check(state.blocks(ARMOR)&&!state.blocks(OFFHAND),"SLM armor is scoped");
        Extra.INSTANCE.autoRocket.value=true;
        check(probe.read(Set.of("slimefunhelper")).blocks(ROCKET),"SLM rocket flag");
        Base flight=new Base("ElytraFlight"),totem=new Base("AutoTotem");
        flight.active=true;totem.active=true;modules=List.of(flight,totem);
        state=probe.read(Set.of("rennaikanksmp"));
        check(state.blocks(FLIGHT)&&state.blocks(COMBAT)&&state.blocks(OFFHAND),"Fork active flight and totem detected");
        flight.active=false;totem.active=false;Extra.INSTANCE.armorFly.value=false;Extra.INSTANCE.autoRocket.value=false;
        check(probe.read(Set.of("slimefunhelper")).owners().isEmpty(),"Foreign disable releases domains");
        Extra.INSTANCE.autoSwitch.value = true;
        check(probe.read(Set.of("slimefunhelper")).blocks(ARMOR), "SLM auto switch also owns equipment");
        Extra.INSTANCE.autoSwitch.value = false;
        Extra.INSTANCE.enableUnbreakableElytra.value = true;
        check(probe.read(Set.of("slimefunhelper")).blocks(ARMOR), "SLM durability swaps also own equipment");
        Extra.INSTANCE.enableUnbreakableElytra.value = false;
        Extra.INSTANCE.rocketBoost.value = true;
        check(probe.read(Set.of("slimefunhelper")).blocks(FLIGHT), "SLM boost owns motion");
        Extra.INSTANCE.rocketBoost.value = false;
        features=List.of(new Feature("ElytraFly",true),new Feature("AutoTotem",true),new Feature("AutoEat",true),new Feature("CrystalAura",true),new Feature("InvHelper",true));
        state=probe.read(Set.of("rusherhack"));
        for(var domain:ForeignClientProbe.Domain.values())check(state.blocks(domain),"Rusher public toggle: "+domain);
        for(var feature:features)feature.active=false;
        check(probe.read(Set.of("rusherhack")).owners().isEmpty(),"Rusher toggles are read afresh");
        Extra.INSTANCE=null;
        check(probe.read(Set.of("slimefunhelper")).owners().size()==7,"Initialization failure yields safely");
        Extra.INSTANCE=new Extra();
        check(probe.read(Set.of("slimefunhelper")).owners().isEmpty(),"Late initialization recovers");
        var unsupported=new ForeignClientProbe(name->{throw new ClassNotFoundException(name);});
        check(unsupported.read(Set.of()).owners().isEmpty(),"Absent classes are optional");
        check(unsupported.read(Set.of("rusherhack")).owners().size()==7,"Detected incompatible API yields with a reason");
        Extra.INSTANCE.armorFly.value=true;features=List.of(new Feature("AutoEat",true));
        state=probe.read(Set.of("slimefunhelper","rusherhack"));
        check(state.blocks(ARMOR)&&state.blocks(FOOD)&&!state.blocks(FLIGHT),"Both clients combine independent owners");
        var owners=new java.util.EnumMap<ForeignClientProbe.Domain,String>(ForeignClientProbe.Domain.class);
        ForeignClientProbe.classify(owners,"RusherHack","Nametags");
        check(owners.isEmpty(),"Visual modules never block gameplay");
        ForeignClientProbe.classify(owners,"SlimefunHelper","ElytraGrimAcc");
        check(owners.containsKey(FLIGHT), "Official upstream accelerated-flight module name");
        owners.clear();
        ForeignClientProbe.classify(owners,"SlimefunHelper","AttackAura");
        check(owners.containsKey(COMBAT) && !owners.containsKey(ARMOR), "Official upstream combat module name");
        owners.clear();
        ForeignClientProbe.classify(owners,"SlimefunHelper","InvHelper");
        check(owners.containsKey(INVENTORY) && !owners.containsKey(FLIGHT) && !owners.containsKey(OFFHAND),
                "Upstream InvHelper owns the inventory domain only");
        ForeignClientProbe.classify(owners,"SlimefunHelper","KitReplenish");
        check(owners.containsKey(INVENTORY), "Upstream KitReplenish owns the inventory domain");
        System.out.println("ForeignClientProbeTest passed: "+checks+" checks");
    }
    private static void check(boolean condition,String reason){checks++;if(!condition)throw new AssertionError(reason);}
}
