package probe;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.modules.combat.GuangxiCrystalModule;
import dev.guizhou.features.settings.Setting;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;

/** Flat-ground base placement and real server packet chain; development fixture only. */
public final class FlatBaseProbe implements ClientModInitializer {
    private int ticks, stage, wait, checks, places, attacks;
    private volatile boolean ready, baseObserved;
    private volatile Throwable serverFailure;
    private volatile Zombie target;
    private volatile float health;
    private BlockPos chosen;
    private float predicted;
    private final List<String> rows = new ArrayList<>(List.of("case,value"));

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (target != null) {
                health = target.getHealth();
                if (chosen != null) baseObserved = target.level().getBlockState(chosen).is(Blocks.OBSIDIAN);
            }
        });
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void setting(Module module,String name,Object value) {
        for (Setting s:module.getSettings()) if(s.getName().equals(name)) {
            if(s.getValue() instanceof Enum e && value instanceof String text)value=Enum.valueOf(e.getDeclaringClass(),text);
            s.setValue(value);return;
        }
        throw new IllegalArgumentException(name);
    }
    private static Object call(Object o,String name,Class<?>[] types,Object... args)throws Exception {
        Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);
    }
    private static Object field(Object o,String name)throws Exception {
        Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private void check(boolean pass,String name) {
        checks++;if(!pass)throw new AssertionError(name);rows.add(name+",passed");System.out.println("FLAT_BASE_CHECK "+name);
    }
    private List<?> caches(GuangxiCrystalModule c)throws Exception {
        List<?> all=(List<?>)call(c,"collectTargets",new Class<?>[]{TargetsModule.class,double.class},null,20.0);
        for(Object cache:all)if(((net.minecraft.world.entity.Entity)field(cache,"entity")).getId()==target.getId())return List.of(cache);
        throw new AssertionError("Target cache not synchronized");
    }
    private Object pick(Minecraft mc,GuangxiCrystalModule c)throws Exception {
        call(c,"ensureSelfCache",new Class<?>[0]);call(c,"refreshEntitySnapshot",new Class<?>[0]);
        return call(c,"findBestBasePlace",new Class<?>[]{LivingEntity.class,List.class},mc.level.getEntity(target.getId()),caches(c));
    }
    private void tick(Minecraft mc) {
        if(stage==99)return;
        if(++ticks>1400){finish(mc,new AssertionError("Timeout stage="+stage));return;}
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null)return;
        try {
            if(serverFailure!=null)throw new AssertionError(serverFailure);
            var c=GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
            if(stage==0) {
                mc.setScreen(null);mc.options.pauseOnLostFocus=false;
                for(var m:GuizhouWare.moduleManager.getModules())if(m.isEnabled())m.disable();
                check(dev.guizhou.manager.HeartbeatManager.authorized(),"development authorization");
                setting(c,"Logic","DEFAULT");setting(c,"Predict",false);setting(c,"Rotate",false);
                setting(c,"SwitchPause",0);setting(c,"TpsSync",false);
                setup(mc);stage=1;return;
            }
            if(stage==1) {
                if(!ready||++wait<50||mc.level.getEntity(target.getId())==null)return;
                call(c,"ensureSession",new Class<?>[0]);
                check(Math.abs(mc.player.getY()-target.getY())<.01,"player and target stand on same grass plane");
                check(mc.player.distanceToSqr(mc.level.getEntity(target.getId()))>=35.9,"target six blocks away");
                setting(c,"Logic","RUSHER");check(pick(mc,c)==null,"Rusher original flat-ground search unchanged");
                setting(c,"Logic","DEFAULT");
                Object candidate=pick(mc,c);
                check(candidate!=null,"DEFAULT finds flat-ground obsidian base");
                chosen=((BlockPos)field(candidate,"base")).immutable();predicted=(float)field(candidate,"damage");
                check(chosen.getY()==101,"base lies above grass at target feet height");
                check(mc.level.getBlockState(chosen.below()).is(Blocks.GRASS_BLOCK),"base has actual grass support");
                check(dev.guizhou.util.PlaceUtil.canPlace(chosen,5.154),"base reachable and entity collision free");
                check(predicted>=5,"candidate meets default base damage threshold");
                setting(c,"BasePlaceMinDamage",36.0);
                check(pick(mc,c)==null,"higher minimum damage still rejects weak bases");
                setting(c,"BasePlaceMinDamage",5.0);
                setting(c,"RangePreset","CUSTOM");setting(c,"PlaceRange",1.0);
                check(pick(mc,c)==null,"out-of-range bases remain rejected");
                setting(c,"RangePreset","B2B2T");
                candidate=pick(mc,c);check(candidate!=null,"candidate returns with normal range");
                chosen=((BlockPos)field(candidate,"base")).immutable();predicted=(float)field(candidate,"damage");
                System.out.println("BASE_DIAG base="+chosen+" obs="+dev.guizhou.util.inventory.InventoryUtil.find(Items.OBSIDIAN,dev.guizhou.util.inventory.InventoryUtil.HOTBAR_SCOPE)
                    +" hit="+GuizhouWare.placementManager.preparePlaceHit(chosen,null,true,6,6,mc.player.getEyePosition())
                    +" defer="+dev.guizhou.util.player.EatUtil.shouldDefer()+" screen="+mc.screen+" main="+mc.player.getMainHandItem());
                check((boolean)call(c,"doBasePlace",new Class<?>[]{candidate.getClass()},candidate),"real obsidian placement submitted");
                stage=2;wait=0;return;
            }
            if(stage==2) {
                if(++wait>120)throw new AssertionError("Server did not accept base");
                if(!baseObserved||!mc.level.getBlockState(chosen).is(Blocks.OBSIDIAN))return;
                check(true,"server and client confirmed obsidian base");
                c.enable();stage=3;wait=0;
            }
            if(stage==3) {
                if(health<999.9f) {
                    c.disable();check(places>0&&attacks>0,"selected crystal place and attack caused server damage");
                    rows.add("base,"+chosen.toShortString());rows.add("predicted,"+predicted);rows.add("server_damage,"+(1000-health));
                    finish(mc,null);return;
                }
                if(++wait>160)throw new AssertionError("No damage places="+places+" attacks="+attacks);
                call(c,"ensureSelfCache",new Class<?>[0]);call(c,"refreshEntitySnapshot",new Class<?>[0]);
                List<?> targets=caches(c);
                Object crystal=call(c,"findBestCrystal",new Class<?>[]{List.class},targets);
                if(crystal!=null&&(boolean)call(c,"breakCrystal",new Class<?>[]{EndCrystal.class},crystal))attacks++;
                Object place=call(c,"findBestPlace",new Class<?>[]{List.class},targets);
                if(place!=null&&(boolean)call(c,"doPlace",new Class<?>[]{place.getClass()},place))places++;
            }
        }catch(Throwable e){finish(mc,e);}
    }
    private void setup(Minecraft mc) {
        mc.getSingleplayerServer().execute(()->{
            try {
                var server=mc.getSingleplayerServer();var p=server.getPlayerList().getPlayer(mc.player.getUUID());var level=p.serverLevel();
                server.setDifficulty(Difficulty.NORMAL,true);level.setDayTime(18000);
                var entities=new ArrayList<net.minecraft.world.entity.Entity>();
                for(var e:level.getAllEntities())if(e instanceof net.minecraft.world.entity.Mob||e instanceof EndCrystal)entities.add(e);
                entities.forEach(net.minecraft.world.entity.Entity::discard);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamerule doMobSpawning false");
                for(int x=-8;x<=15;x++)for(int z=-8;z<=8;z++)for(int y=94;y<=107;y++)
                    level.setBlock(new BlockPos(x,y,z),(y<100?Blocks.DIRT:y==100?Blocks.GRASS_BLOCK:Blocks.AIR).defaultBlockState(),3);
                p.setGameMode(GameType.CREATIVE);p.teleportTo(level,.5,101,.5,Set.of(),-90,0);
                p.getInventory().clearContent();p.getInventory().setItem(0,new ItemStack(Items.END_CRYSTAL,64));
                p.getInventory().setItem(1,new ItemStack(Items.OBSIDIAN,64));p.getInventory().selected = 1;
                p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,2400,4));
                p.inventoryMenu.broadcastChanges();
                target=new Zombie(EntityType.ZOMBIE,level);target.setNoAi(true);target.setSilent(true);target.setNoGravity(true);target.setPersistenceRequired();
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);target.getAttribute(Attributes.ARMOR).setBaseValue(0);
                target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);target.setHealth(1000);target.setPos(6.5,101,.5);
                level.addFreshEntity(target);health=1000;ready=true;
            }catch(Throwable e){serverFailure=e;}
        });
    }
    private void finish(Minecraft mc,Throwable error) {
        stage=99;
        try {
            Files.write(mc.gameDirectory.toPath().resolve("damage.csv"),rows);
            if(error!=null){error.printStackTrace();Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"),error.toString());}
            else {Files.writeString(mc.gameDirectory.toPath().resolve("complete.txt"),"flat-base checks="+checks+" places="+places+" attacks="+attacks+"\n");System.out.println("FLAT_BASE_PROBE_PASSED "+checks);}
        }catch(Exception e){e.printStackTrace();}
        mc.stop();
    }
}
