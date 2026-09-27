package probe;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.combat.GuangxiCrystalModule;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.combat.CrystalMotionPrediction;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Real 1.21.11 integrated-server explosions. Runs only in an explicit development launch. */
public final class CrystalProbe implements ClientModInitializer {
    private static final String[] CASES = {"open", "stone-wall", "obsidian-wall", "slab", "armor", "removed-wall"};
    private static final Vec3 SOURCE = new Vec3(.5, 101, .5);
    private int tick, stage, wait, trial;
    private volatile int ready = -1;
    private volatile double actual = Double.NaN;
    private volatile Throwable serverFailure;
    private volatile Zombie target;
    private float predicted, legacy;
    private int checks;
    private volatile float serverHealth;
    private float actionStartHealth;
    private int placements, attacks;
    private final List<String> rows = new ArrayList<>();

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (target != null) serverHealth = target.getHealth();
        });
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setting(Module m, String name, Object value) {
        for (Setting s : m.getSettings()) if (s.getName().equals(name)) {
            if (s.getValue() instanceof Enum e && value instanceof String text) value = Enum.valueOf(e.getDeclaringClass(), text);
            s.setValue(value); return;
        }
        throw new IllegalArgumentException(name);
    }
    private static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = object.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(object, args);
    }
    private static Object field(Object object, String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static void setField(Object object, String name, Object value) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    private void check(boolean value, String name) {
        checks++; if (!value) throw new AssertionError(name);
        System.out.println("CRYSTAL_CHECK " + name);
    }
    private Object targetCache(Minecraft mc, GuangxiCrystalModule c) throws Exception {
        List<?> targets = (List<?>)call(c,"collectTargets",new Class<?>[]{TargetsModule.class,double.class},null,20.0);
        for (Object cache : targets) if (((net.minecraft.world.entity.Entity)field(cache,"entity")).getId()==target.getId()) return cache;
        throw new IllegalStateException("Target not synchronized");
    }
    private void tick(Minecraft mc) {
        if (++tick > 2400) { fail(mc,new AssertionError("timeout stage="+stage)); return; }
        if (mc.player==null || mc.level==null || mc.getSingleplayerServer()==null || stage==99) return;
        mc.options.pauseOnLostFocus=false;
        try {
            if (serverFailure!=null) throw new AssertionError("server fixture failed",serverFailure);
            var c=GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
            if (stage==0) {
                mc.setScreen(null);
                for(var mod:GuizhouWare.moduleManager.getModules()) if(mod.isEnabled())mod.disable();
                // This isolated development run has no launcher telemetry capability.
                check(dev.guizhou.manager.HeartbeatManager.authorized(),"development authorization");
                setting(c,"Logic","DEFAULT");setting(c,"Predict",false);setting(c,"Rotate",false);
                setting(c,"BlockDestruction",true);
                rows.add("case,predicted,legacy_geometry,server_damage,error");
                setup(mc);stage=1;wait=0;return;
            }
            if (stage==1) {
                if(ready!=trial || ++wait<50 || mc.level.getEntity(target.getId())==null)return;
                call(c,"ensureSession",new Class<?>[0]);call(c,"ensureSelfCache",new Class<?>[0]);
                Object cache=targetCache(mc,c);
                predicted=(float)call(c,"calcDamage",new Class<?>[]{cache.getClass(),Vec3.class},cache,SOURCE);
                setting(c,"Logic","RUSHER");call(c,"ensureSelfCache",new Class<?>[0]);
                legacy=(float)call(c,"calcDamage",new Class<?>[]{cache.getClass(),Vec3.class},cache,SOURCE);
                setting(c,"Logic","DEFAULT");
                var server=mc.getSingleplayerServer();
                server.execute(()->{
                    try {
                        target.invulnerableTime=0; target.setHealth(1000);
                        var crystal=new EndCrystal(target.level(),SOURCE.x,SOURCE.y,SOURCE.z);
                        target.level().addFreshEntity(crystal);
                        crystal.hurtServer((net.minecraft.server.level.ServerLevel)target.level(),target.damageSources().generic(),1);
                        actual=1000-target.getHealth();
                    }catch(Throwable e){serverFailure=e;}
                });
                stage=2;return;
            }
            if(stage==2) {
                if(!Double.isFinite(actual))return;
                double error=Math.abs(predicted-actual);
                String row=String.format(Locale.ROOT,"%s,%.6f,%.6f,%.6f,%.6f",CASES[trial],predicted,legacy,actual,error);
                rows.add(row);System.out.println("CRYSTAL_DAMAGE "+row);
                check(error<.02,"server damage matches "+CASES[trial]);
                if(++trial<CASES.length){setup(mc);stage=1;wait=0;return;}
                extraChecks(mc,c);
                mc.getSingleplayerServer().execute(()->{
                    var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    p.getInventory().setItem(0,new ItemStack(Items.END_CRYSTAL,64));
                    p.getInventory().setSelectedSlot(0);
                    p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE,1200,4));
                    p.inventoryMenu.broadcastChanges();
                });
                setting(c,"Predict",false);setting(c,"SwitchPause",0);setting(c,"TpsSync",false);
                stage=3;wait=0;return;
            }
            if(stage==3) {
                if(++wait<30)return;
                call(c,"ensureSelfCache",new Class<?>[0]);call(c,"refreshEntitySnapshot",new Class<?>[0]);
                Object cache=targetCache(mc,c);
                call(c,"findBestPlace",new Class<?>[]{List.class},List.of(cache));
                List<?> warmCandidates=(List<?>)field(c,"candidates");
                check(warmCandidates.size()>1,"multiple current candidates for lock regression");
                BlockPos stale=(BlockPos)field(warmCandidates.getLast(),"airPos");
                setting(c,"Logic","RUSHER");setting(c,"PlaceTimeout",1000);
                setField(field(c,"placeRank"),"hint",stale.asLong());
                setField(c,"lastPlaceAir",stale);
                ((it.unimi.dsi.fastutil.longs.Long2LongMap)field(c,"crystalPlaces")).put(stale.asLong(),System.currentTimeMillis());
                Object oldPick=call(c,"findBestPlace",new Class<?>[]{List.class},List.of(cache));
                System.out.println("CRYSTAL_LOCK stale="+stale+" legacy="+oldPick+" first="+field(((List<?>)field(c,"candidates")).getFirst(),"airPos"));
                check(oldPick==null,"retained legacy sorting reproduces stale hint lockout");
                setting(c,"Logic","DEFAULT");
                Object picked=call(c,"findBestPlace",new Class<?>[]{List.class},List.of(cache));
                check(picked!=null,"stale low bound hint cannot lock out stronger current placement");
                List<?> candidates=(List<?>)field(c,"candidates");
                for(int i=1;i<candidates.size();i++)check((float)field(candidates.get(i-1),"bound")>=(float)field(candidates.get(i),"bound"),"current bounds remain sorted");
                ((it.unimi.dsi.fastutil.longs.Long2LongMap)field(c,"crystalPlaces")).clear();
                setField(c,"lastPlaceAir",null);
                c.enable();actionStartHealth=serverHealth;stage=4;wait=0;
            }
            if(stage==4) {
                if(serverHealth<actionStartHealth-.1f) {
                    c.disable();
                    check(placements>0 && attacks>0,"selected placement and attack packets cause server damage");
                    rows.add("packet-chain,"+placements+","+attacks+","+(actionStartHealth-serverHealth)+",0");
                    Files.write(mc.gameDirectory.toPath().resolve("damage.csv"),rows);
                    Files.writeString(mc.gameDirectory.toPath().resolve("complete.txt"),"checks="+checks+"\nreal integrated-server explosion cases="+CASES.length+"\nplacements="+placements+" attacks="+attacks+" damage="+(actionStartHealth-serverHealth)+"\n");
                    System.out.println("CRYSTAL_PROBE_PASSED checks="+checks+" placements="+placements+" attacks="+attacks+" damage="+(actionStartHealth-serverHealth));
                    stage=99;mc.stop();return;
                }
                if(++wait>120)throw new AssertionError("No packet-chain damage placements="+placements+" attacks="+attacks);
                call(c,"ensureSelfCache",new Class<?>[0]);call(c,"refreshEntitySnapshot",new Class<?>[0]);
                // The fixture supplies a server-owned zombie so no second authenticated player is required.
                // Production's player-only target filter remains unchanged.
                List<?> caches=List.of(targetCache(mc,c));
                Object crystal=call(c,"findBestCrystal",new Class<?>[]{List.class},caches);
                if(crystal!=null && (boolean)call(c,"breakCrystal",new Class<?>[]{EndCrystal.class},crystal))attacks++;
                Object placement=call(c,"findBestPlace",new Class<?>[]{List.class},caches);
                if(placement!=null && (boolean)call(c,"doPlace",new Class<?>[]{placement.getClass()},placement))placements++;
            }
        } catch(Throwable e) { fail(mc,e); }
    }
    private void setup(Minecraft mc) {
        actual=Double.NaN;ready=-1;
        int index=trial;
        mc.getSingleplayerServer().execute(()->{
            try {
                var server=mc.getSingleplayerServer();var p=server.getPlayerList().getPlayer(mc.player.getUUID());var level=p.level();
                server.setDifficulty(Difficulty.NORMAL,true);level.setDayTime(18000);
                var entities=new ArrayList<net.minecraft.world.entity.Entity>();
                for(var e:level.getAllEntities())if(e instanceof net.minecraft.world.entity.Mob || e instanceof EndCrystal)entities.add(e);
                entities.forEach(net.minecraft.world.entity.Entity::discard);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamerule minecraft:spawn_mobs false");
                for(int x=-5;x<=12;x++)for(int z=-6;z<=6;z++) {
                    level.setBlock(new BlockPos(x,100,z),Blocks.OBSIDIAN.defaultBlockState(),3);
                    for(int y=101;y<=106;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                }
                if(index==1||index==2)for(int z=-4;z<=4;z++)for(int y=101;y<=104;y++)
                    level.setBlock(new BlockPos(2,y,z),(index==1?Blocks.STONE:Blocks.OBSIDIAN).defaultBlockState(),3);
                if(index==3)for(int z=-4;z<=4;z++)level.setBlock(new BlockPos(2,101,z),Blocks.STONE_SLAB.defaultBlockState(),3);
                p.setGameMode(GameType.CREATIVE);p.teleportTo(level,.5,101,-3.5,Set.of(),0,0,false);
                target=new Zombie(EntityType.ZOMBIE,level);target.setNoAi(true);target.setSilent(true);target.setNoGravity(true);target.setPersistenceRequired();
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
                target.getAttribute(Attributes.ARMOR).setBaseValue(0);
                target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
                target.setHealth(1000);target.setPos(4.5,101,.5);
                if(index==4)target.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.NETHERITE_CHESTPLATE));
                level.addFreshEntity(target);ready=index;
            }catch(Throwable e){serverFailure=e;}
        });
    }
    private void extraChecks(Minecraft mc, GuangxiCrystalModule c) throws Exception {
        var start=mc.player.position();
        var oldMotion=mc.player.getDeltaMovement();
        setting(c,"Predict",true);setting(c,"AdaptivePredict",true);setting(c,"SelfPredict",0);
        mc.player.setDeltaMovement(new Vec3(8,0,0));
        call(c,"ensureSelfCache",new Class<?>[0]);
        List<?> path=(List<?>)field(c,"selfPath");
        check(path.size()>2,"fast flight auto self path with SelfPredict=0");
        Vec3 source=start.add(4,0,0);
        float route=(float)call(c,"calcDamageSelf",new Class<?>[]{Vec3.class,BlockPos.class},source,null);
        Object sc=field(c,"selfCache");
        float endpoint=(float)call(c,"selfDamageAt",new Class<?>[]{sc.getClass(),Vec3.class,AABB.class,Vec3.class,BlockPos.class},
                sc,start,mc.player.getBoundingBox(),source,null);
        check(route>endpoint+5,"intermediate self damage exceeds start-only estimate");
        setting(c,"Logic","RUSHER");call(c,"ensureSelfCache",new Class<?>[0]);
        check(((List<?>)field(c,"selfPath")).size()==1,"Rusher zero self prediction unchanged");
        setting(c,"Logic","DEFAULT");mc.player.setDeltaMovement(oldMotion);
        AABB flying=new AABB(-.3,0,-.3,.3,.6,.3);
        AABB wall=new AABB(4.01,-1,-1,4.02,4,1);
        var collision=CrystalMotionPrediction.trace(Vec3.ZERO,flying,new Vec3(14,0,0),2,false,b->!b.intersects(wall));
        check(collision.getLast().x<4,"high speed sweep stops before thin wall");
    }
    private void fail(Minecraft mc,Throwable e) {
        e.printStackTrace();stage=99;
        try {Files.write(mc.gameDirectory.toPath().resolve("damage.csv"),rows);Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"),e.toString());}catch(Exception ignored){}
        mc.stop();
    }
}
