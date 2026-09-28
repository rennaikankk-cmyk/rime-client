package probe;

import com.mojang.authlib.GameProfile;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.features.modules.render.TrajectoriesModule;
import dev.guizhou.util.combat.ProjectilePrediction;
import dev.guizhou.util.elytra.PearlLandingThreat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.util.*;

/** Isolated integration fixture; synthetic enemy observations are not a PvP benchmark. */
public final class DefenseProbe {
    private boolean queued, done;
    private volatile boolean prepared;
    private volatile Throwable failure;
    private int wait, checks;
    public void init() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private void tick(Minecraft mc) {
        if (done || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        mc.options.pauseOnLostFocus = false;
        if (!queued) {
            queued = true; mc.setScreen(null);
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var server = mc.getSingleplayerServer();
                    var p = server.getPlayerList().getPlayer(mc.player.getUUID());
                    var level = p.level();
                    for (int x=-16;x<=24;x++) for(int z=-16;z<=16;z++) {
                        level.setBlock(new BlockPos(x,100,z),Blocks.STONE.defaultBlockState(),3);
                        for(int y=101;y<=108;y++) level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                    }
                    p.teleportTo(level,.5,101,.5,Set.of(),0,0,false);
                    var pearl = new ThrownEnderpearl(EntityType.ENDER_PEARL,level);
                    pearl.setPos(10.5,104,.5); pearl.setDeltaMovement(.2,-.1,0);
                    var predicted = ProjectilePrediction.simulate(pearl,30);
                    check(predicted.landed(),"native floor prediction lands");
                    for(int i=1;i<predicted.points().size();i++) {
                        pearl.tick();
                        double[] v = predicted.points().get(i);
                        check(pearl.position().distanceTo(new Vec3(v[0],v[1],v[2]))<1e-4,"native pearl step "+i);
                    }
                    check(pearl.isRemoved(),"native pearl actually hit floor");
                    prepared = true;
                } catch(Throwable e) { failure=e; prepared=true; }
            });
            return;
        }
        if (!prepared || ++wait<40) return;
        done=true;
        try {
            if(failure!=null)throw new AssertionError("native physics fixture",failure);
            runClientChecks(mc);
            Files.writeString(mc.gameDirectory.toPath().resolve("defense-complete.txt"),"checks="+checks+"\n");
            System.out.println("DEFENSE_PROBE_PASS checks="+checks);
        } catch(Throwable e) {
            e.printStackTrace();
            try { Files.writeString(mc.gameDirectory.toPath().resolve("defense-failure.txt"),e.toString()); }
            catch(Exception ignored) {}
        } finally { mc.stop(); }
    }
    @SuppressWarnings("unchecked")
    private void runClientChecks(Minecraft mc) throws Exception {
        var chase=GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraChaseModule.class);
        var flight=GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        var render=GuizhouWare.moduleManager.getModuleByClass(TrajectoriesModule.class);
        render.disable(); flight.disable(); mc.setScreen(null);
        mc.player.setPos(.5,101,.5); mc.player.setDeltaMovement(Vec3.ZERO);
        var owner=new RemotePlayer(mc.level,new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000321"),"DefenseFixture"));
        owner.setId(900001); owner.setPos(20.5,101,.5); mc.level.addEntity(owner);
        CombatProbe.setting(chase,"Mode","SPEAR");
        CombatProbe.setting(chase,"AutoControlElytra",false);
        CombatProbe.setting(chase,"AutoWeapon",false);
        CombatProbe.setting(chase,"AutoSpearUse",false);
        chase.enable(); call(chase,"onPreTick",new Class<?>[]{PreTickEvent.class},new PreTickEvent());
        set(chase,"target",owner);
        var pearl=new ThrownEnderpearl(EntityType.ENDER_PEARL,mc.level);
        pearl.setId(900002); pearl.setOwner(owner);
        pearl.setPos(2.5,102,.5); pearl.setDeltaMovement(0,-.8,0); mc.level.addEntity(pearl);
        List<PearlLandingThreat.Threat> hazards=(List<PearlLandingThreat.Threat>)call(chase,"pearlThreats");
        check(!hazards.isEmpty() && hazards.getFirst().urgent(),"enemy pearl arrives near self");
        check(!render.isEnabled(),"trajectory module remains disabled");
        check((boolean)call(chase,"updateDefense"),"pearl activates live combat defense");
        Vec3 first=(Vec3)CombatProbe.field(chase,"defensiveHeading");
        check(first.lengthSqr()>0,"live escape heading");
        CombatProbe.setting(chase,"AvoidObstacles",false);
        var flightIntent=new dev.guizhou.util.elytra.FlightVelocity(new Vec3(0,0,5),10);
        GuangxiElytraChaseModule.onElytra(flightIntent);
        check(flightIntent.isDirectional(),"escape takes priority over coupled cruise direction");
        set(chase,"defensiveHeading",Vec3.ZERO);
        GuangxiElytraChaseModule.onElytra(flightIntent);
        check(flightIntent.isBraking(),"blocked escape brakes instead of idle hovering");
        set(chase,"defensiveHeading",first);
        CombatProbe.setting(chase,"AvoidObstacles",true);
        owner.setPos(-20.5,101,.5); mc.player.tickCount++;
        call(chase,"updateDefense");
        check(first.distanceTo((Vec3)CombatProbe.field(chase,"defensiveHeading"))<1e-8,"owner motion cannot rotate committed escape");
        pearl.setOwner(null); call(chase,"updateDefense");
        check(first.distanceTo((Vec3)CombatProbe.field(chase,"defensiveHeading"))<1e-8,"policy clear-delay is not a new threat event");
        pearl.setOwner(owner);
        BlockPos block=BlockPos.containing(mc.player.position().add(first.normalize()));
        mc.level.setBlock(block,Blocks.STONE.defaultBlockState(),3);
        mc.level.setBlock(block.above(),Blocks.STONE.defaultBlockState(),3);
        mc.player.tickCount++; call(chase,"updateDefense");
        check(first.distanceTo((Vec3)CombatProbe.field(chase,"defensiveHeading"))>1e-6,"obstacle interrupts lock");
        mc.level.setBlock(block,Blocks.AIR.defaultBlockState(),3);
        mc.level.setBlock(block.above(),Blocks.AIR.defaultBlockState(),3);
        pearl.setOwner(mc.player);
        check(((List<?>)call(chase,"pearlThreats")).isEmpty(),"own pearl excluded");
        pearl.setOwner(null);
        check(((List<?>)call(chase,"pearlThreats")).isEmpty(),"unknown owner excluded");
        pearl.setOwner(owner); mc.player.setDeltaMovement(new Vec3(-5,0,0));
        check(((List<?>)call(chase,"pearlThreats")).isEmpty(),"already escaping arrival");
        mc.player.setDeltaMovement(Vec3.ZERO);
        GuizhouWare.friendManager.addFriend(owner.getGameProfile().name(),Long.MAX_VALUE,false);
        check(((List<?>)call(chase,"pearlThreats")).isEmpty(),"friend pearl excluded without sending messages");
        GuizhouWare.friendManager.removeFriend(owner.getGameProfile().name());
        CombatProbe.setting(chase,"PearlDefense",false);
        ((dev.guizhou.util.elytra.AerialCombatPolicy)CombatProbe.field(chase,"tactics")).reset();
        check(!(boolean)call(chase,"updateDefense"),"pearl defense toggle restores pursuit");
        CombatProbe.setting(chase,"PearlDefense",true);
        check((boolean)call(chase,"updateDefense"),"pearl defense re-enable observes current arrival");
        pearl.setPos(.5,102,-1); pearl.setDeltaMovement(0,0,1);
        var hit=ProjectilePrediction.simulate(pearl,3);
        check(hit.landed() && hit.end()[1]>101.2,"local player participates in collisions");
        pearl.setPos(2.5,102,.5); pearl.setDeltaMovement(0,-.8,0);
        long[] durations=new long[200];
        for(int i=0;i<durations.length;i++) { long start=System.nanoTime(); call(chase,"pearlThreats"); durations[i]=System.nanoTime()-start; }
        Arrays.sort(durations);
        System.out.println("DEFENSE_SAMPLE_US p50="+durations[100]/1000+" p95="+durations[190]/1000+" max="+durations[199]/1000);
        chase.disable();
        check(((Vec3)CombatProbe.field(chase,"defensiveHeading")).lengthSqr()==0,"disable clears heading");
        pearl.discard(); owner.discard();
    }
    private void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
    private static Object call(Object o,String name) throws Exception { return call(o,name,new Class<?>[0]); }
    private static Object call(Object o,String name,Class<?>[] types,Object...args) throws Exception {
        var m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);
    }
    private static void set(Object o,String name,Object value)throws Exception {
        var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
}
