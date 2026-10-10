package probe;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.combat.*;
import dev.guizhou.features.modules.world.GuangxiMineModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.PacketSendUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.effect.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import java.nio.file.Files;
import java.util.*;

/** Normal module ticks with a player selector proxy and a server-owned damage oracle. */
public final class CrystalAutoProbe implements ClientModInitializer {
    static final BlockPos BASE = new BlockPos(-1,100,0);
    static final String[] NAMES={"normal-off","normal-on","tp-off","tp-on","normal-item-runner",
            "tp-item-runner","normal-weakness","tp-weakness","normal-offhand","tp-offhand",
            "normal-no-material","tp-no-material","normal-self-limit","tp-self-limit","mine-off","mine-on",
            "mine-min-damage","mine-break-range","mine-place-delay"};
    int scene=Integer.getInteger("crystal.start",0),stage,wait,total,uses,attacks,stops;
    volatile boolean ready,observed,mined;
    volatile float health,damage;
    volatile Throwable error;
    Zombie oracle;
    RemotePlayer target;
    GuangxiCrystalModule crystal;
    GuangxiTPModule tp;
    AutoMineModule autoMine;
    GuangxiMineModule mine;
    final List<Object> rows=new ArrayList<>();
    final Set<String> placed=new LinkedHashSet<>();
    long enabledAt, firstUseMs;
    @Override public void onInitializeClient() {
        PacketSendUtil.addSubmissionListener(p -> {
            if(stage!=2)return;
            if(p instanceof ServerboundUseItemOnPacket u){if(uses++==0)firstUseMs=System.currentTimeMillis()-enabledAt;placed.add(u.getHitResult().getBlockPos().toShortString());}
            if(p instanceof ServerboundInteractPacket)attacks++;
            if(p instanceof ServerboundPlayerActionPacket a && a.getAction()==ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)stops++;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if(!ready||oracle==null)return;
            float h=oracle.getHealth();
            if(h<health && oracle.getLastDamageSource()!=null && oracle.getLastDamageSource().is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION))damage+=health-h;
            health=h;
            var level=oracle.level();
            if(!level.getEntities((Entity)null,new AABB(-7,100,-5,7,107,5),e->e instanceof EndCrystal).isEmpty())observed=true;
            if(scene>=14 && level.getBlockState(BASE.above()).isAir())mined=true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    void set(Module m,String n,Object v){((Setting)m.getSettingByName(n)).setValue(v);}
    @SuppressWarnings({"rawtypes","unchecked"})
    void mode(Module m,String n,String v){var s=m.getSettingByName(n);set(m,n,Enum.valueOf((Class)((Enum)s.getValue()).getDeclaringClass(),v));}
    boolean tpCase(){return scene<4?scene>=2:scene<14 && (scene&1)==1;}
    boolean negative(){return scene>=10&&scene<=13||scene==16||scene==17;}
    void tick(Minecraft mc){
        if(stage==99)return;
        try{
            if(++total>5000)throw new AssertionError("timeout");
            if(error!=null)throw new AssertionError(error);
            if(mc.player==null||mc.getSingleplayerServer()==null)return;
            if(crystal==null){
                crystal=GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
                tp=GuizhouWare.moduleManager.getModuleByClass(GuangxiTPModule.class);
                autoMine=GuizhouWare.moduleManager.getModuleByClass(AutoMineModule.class);
                mine=GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
                for(var m:GuizhouWare.moduleManager.getModules())if(m.isEnabled()&&!m.getName().equals("Targets"))m.disable();
                mc.options.pauseOnLostFocus=false;mc.setScreen(null);
                set(crystal,"TpsSync",false);set(tp,"TpsSync",false);set(crystal,"Debug",true);set(crystal,"PacketLog",true);set(tp,"Debug",true);
                set(crystal,"BasePlace",false);set(tp,"BasePlace",false);
                set(autoMine,"IgnoreNakeds",false);set(autoMine,"UnderMine",false);mode(autoMine,"Extend","None");
                set(mine,"GrimDecoy",false);set(mine,"DoubleBreak",false);
            }
            if(stage==0){setup(mc);stage=1;wait=0;return;}
            if(stage==1){
                if(!ready||mc.level.dimension()!=Level.OVERWORLD||++wait<40)return;
                target=new RemotePlayer(mc.level,new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000c12345"),"CrystalFixture"));
                target.setId(900123);target.setPos(.5,101,.5);target.setHealth(20);mc.level.addEntity(target);
                if(scene==16){
                    target.getAttribute(Attributes.ARMOR).setBaseValue(20);target.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(8);
                    for(var slot:new EquipmentSlot[]{EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET})
                        target.setItemSlot(slot,mc.player.getItemBySlot(slot).copy());
                }
                boolean rotate=scene<4?(scene&1)==1:scene==15;
                set(crystal,"Rotate",rotate);set(tp,"Rotate",rotate);
                set(crystal,"MaxSelfDamage",scene==12?0.0:12.0);set(tp,"MaxSelfDamage",scene==13?0.0:12.0);
                set(crystal,"FacePlace",scene!=16);set(crystal,"MinDamage",scene==16?36.0:4.0);
                mode(crystal,"RangePreset",scene==17?"CUSTOM":"B2B2T");set(crystal,"BreakRange",scene==17?1.0:3.0);
                set(crystal,"PlaceDelay",scene==18?2000:0);
                mc.player.setYRot(-90);mc.player.setXRot(0);
                PacketSendUtil.send(new ServerboundMovePlayerPacket.Rot(-90,0,mc.player.onGround(),false));
                uses=attacks=stops=0;placed.clear();wait=0;enabledAt=System.currentTimeMillis();firstUseMs=-1;stage=2;
                if(tpCase())tp.enable();else crystal.enable();
                if(scene>=14){mine.enable();autoMine.enable();}
                return;
            }
            if(stage==2){
                target.setPos(.5,101,.5);target.setDeltaMovement(Vec3.ZERO);
                if(++wait<180&&(negative()||damage<=1))return;
                crystal.disable();tp.disable();autoMine.disable();mine.disable();
                boolean ok=negative()?uses==0&&attacks==0&&damage==0:damage>1&&(observed||attacks>0)&&(scene<14||mined&&stops>0);
                if(scene==16||scene==17)ok &= mined&&stops>0;
                if(scene==18)ok &= firstUseMs>=1950;
                var row=new LinkedHashMap<String,Object>();row.put("scene",NAMES[scene]);row.put("success",ok);row.put("targetDamage",damage);
                row.put("serverCrystalObserved",observed);row.put("mined",mined);row.put("uses",uses);row.put("attacks",attacks);row.put("stops",stops);
                row.put("positions",new ArrayList<>(placed));row.put("ticks",wait);row.put("firstUseMs",firstUseMs);row.put("driverHealth",mc.player.getHealth());rows.add(row);
                System.out.println("CRYSTAL_AUTO_CASE "+row);
                mc.level.removeEntity(target.getId(),Entity.RemovalReason.DISCARDED);
                if(++scene<Integer.getInteger("crystal.end",NAMES.length)){stage=0;return;}
                Files.writeString(mc.gameDirectory.toPath().resolve("results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));
                if(Boolean.getBoolean("crystal.expect")&&rows.stream().anyMatch(r->!((Map<?,?>)r).get("success").equals(true)))throw new AssertionError("automatic crystal case failed");
                Files.writeString(mc.gameDirectory.toPath().resolve("complete.txt"),rows.size()+" automatic crystal cases completed");stage=99;mc.stop();
            }
        }catch(Throwable ex){ex.printStackTrace();try{Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"),ex.toString());}catch(Exception ignored){}stage=99;mc.stop();}
    }
    void setup(Minecraft mc){
        ready=observed=mined=false;damage=0;health=1000;
        mc.getSingleplayerServer().execute(()->{try{
            var server=mc.getSingleplayerServer();var level=server.getLevel(Level.OVERWORLD);var p=server.getPlayerList().getPlayer(mc.player.getUUID());
            server.setDifficulty(Difficulty.NORMAL,true);server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamerule minecraft:spawn_mobs false");
            for(var e:level.getEntities(null,new AABB(-9,99,-9,9,110,9)))if(e!=p)e.discard();
            for(BlockPos pos:BlockPos.betweenClosed(-7,100,-5,7,107,5))level.setBlock(pos,pos.getY()==100?Blocks.BEDROCK.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
            // Only the two intended bases are exposed; the bedrock floor is covered with stone.
            for(BlockPos pos:BlockPos.betweenClosed(-7,100,-5,7,100,5))level.setBlock(pos,Blocks.STONE.defaultBlockState(),3);
            level.setBlock(BASE,Blocks.OBSIDIAN.defaultBlockState(),3);
            if(scene==4||scene==5){
                level.setBlock(new BlockPos(-1,100,1),Blocks.OBSIDIAN.defaultBlockState(),3);
                var drop=new ItemEntity(level,-.5,101.1,.5,new ItemStack(Items.OBSIDIAN));drop.setNoGravity(true);drop.setDeltaMovement(Vec3.ZERO);drop.setPickUpDelay(32767);level.addFreshEntity(drop);
            }
            if(scene>=14)level.setBlock(BASE.above(),Blocks.STONE.defaultBlockState(),3);
            p.setGameMode(GameType.SURVIVAL);p.removeAllEffects();p.setHealth(20);p.getFoodData().setFoodLevel(20);p.getInventory().clearContent();p.getInventory().setSelectedSlot(scene==6||scene==7?1:0);
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));p.getInventory().setItem(1,new ItemStack(Items.GOLDEN_APPLE));
            p.getInventory().setItem(2,new ItemStack(Items.NETHERITE_PICKAXE));
            if(scene==8||scene==9)p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.END_CRYSTAL,64));
            else if(scene!=10&&scene!=11)p.getInventory().setItem(3,new ItemStack(Items.END_CRYSTAL,64));
            p.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.NETHERITE_HELMET));p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.NETHERITE_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.NETHERITE_LEGGINGS));p.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.NETHERITE_BOOTS));
            var protection=level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.PROTECTION);
            for(var slot:new EquipmentSlot[]{EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET})p.getItemBySlot(slot).enchant(protection,4);
            if(scene==6||scene==7)p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,10000,0));
            p.teleportTo(level,-4.5,101,.5,Set.of(),-90,0,false);p.inventoryMenu.broadcastChanges();
            oracle=new Zombie(EntityType.ZOMBIE,level);oracle.setNoAi(true);oracle.setNoGravity(true);
            oracle.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);oracle.setHealth(1000);oracle.getAttribute(Attributes.ARMOR).setBaseValue(0);oracle.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            oracle.setPos(.5,101,.5);level.addFreshEntity(oracle);ready=true;
        }catch(Throwable ex){error=ex;}});
    }
}
