package probe;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.combat.*;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.AnchorDamageUtil;
import dev.guizhou.util.combat.AnchorSequence;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Isolated real Minecraft server oracle. No production target filter or authorization is patched. */
public final class BlastProbe implements ClientModInitializer {
    static final BlockPos HEAD=new BlockPos(0,101,0), FOOT=HEAD.north();
    static final Vec3 SOURCE=Vec3.atCenterOf(HEAD);
    static final String[] SCENES={"open","stone-wall","obsidian-wall","slab","armor","resistance"};
    int tick,stage,wait,trial,checks,chain,step,oracleCases,packetChains;
    int inventoryStage, inventoryWait;
    int rotationStage;
    volatile int inventoryRevision;
    ItemStack acknowledgedMain, acknowledgedOff;
    int shieldStage, shieldWait;
    float uncoveredSelf, uncoveredTarget;
    volatile BlockPos shieldPosition;
    volatile boolean serverShieldSeen;
    final Vec3 shieldSource=new Vec3(4.5,101,.5);
    final dev.guizhou.util.combat.BlastShieldController shield=new dev.guizhou.util.combat.BlastShieldController();
    volatile boolean ready;
    volatile Throwable failure;
    volatile Zombie target;
    volatile EndCrystal crystal;
    volatile double actual=Double.NaN;
    volatile float health=1000;
    volatile Vec3 serverPos;
    volatile float serverYaw,serverPitch;
    float expected,second,restoreYaw,restorePitch;
    Vec3 restorePos;
    List<String> rows=new ArrayList<>();
    @Override public void onInitializeClient(){
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server->{
            if(target!=null)health=target.getHealth();
            if(shieldPosition!=null)serverShieldSeen=server.overworld().getBlockState(shieldPosition).is(Blocks.OBSIDIAN);
            var p=server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if(p!=null){serverPos=p.position();serverYaw=p.getYRot();serverPitch=p.getXRot();}
        });
    }
    static Object call(Object o,String n,Class<?>[] ts,Object... as)throws Exception {var m=o.getClass().getDeclaredMethod(n,ts);m.setAccessible(true);return m.invoke(o,as);}
    static void field(Object o,String n,Object v)throws Exception {var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    @SuppressWarnings({"rawtypes","unchecked"}) static void setting(Module m,String n,Object v){for(Setting s:m.getSettings())if(s.getName().equals(n)){if(s.getValue() instanceof Enum e&&v instanceof String t)v=Enum.valueOf(e.getDeclaringClass(),t);s.setValue(v);return;}throw new IllegalArgumentException(n);}
    void check(boolean v,String n){checks++;if(!v)throw new AssertionError(n);System.out.println("BLAST_CHECK "+n);}
    /** Real server packets: equal stacks preserve use identity but still advance menu revision. */
    boolean checkInventoryAcknowledgements(Minecraft mc) {
        if (inventoryStage == 0) {
            mc.setScreen(null);
            for(var module:GuizhouWare.moduleManager.getModules())if(module.isEnabled())module.disable();
            mc.player.getInventory().setSelectedSlot(0);
            mc.getSingleplayerServer().execute(()->{
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                player.getInventory().setSelectedSlot(0);
                player.getInventory().setItem(0,new ItemStack(Items.APPLE,4));
                player.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.GOLDEN_APPLE,4));
                player.inventoryMenu.broadcastFullState();
            });
            inventoryStage=1; inventoryWait=0; return true;
        }
        if (++inventoryWait < 15) return true;
        inventoryWait=0;
        if (inventoryStage == 1) {
            acknowledgedMain=mc.player.getMainHandItem();acknowledgedOff=mc.player.getOffhandItem();
            check(acknowledgedMain.is(Items.APPLE)&&acknowledgedOff.is(Items.GOLDEN_APPLE),"server supplies inventory acknowledgment fixture");
            mc.getSingleplayerServer().execute(()->{
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(0,
                        player.inventoryMenu.incrementStateId(),36,player.getMainHandItem().copy()));
                inventoryRevision=player.inventoryMenu.incrementStateId();
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(0,
                        inventoryRevision,45,player.getOffhandItem().copy()));
            });
            inventoryStage=2;return true;
        }
        if(inventoryStage==2){
            check(mc.player.getMainHandItem()==acknowledgedMain&&mc.player.getOffhandItem()==acknowledgedOff,
                    "identical server acknowledgments preserve both hand identities");
            check(mc.player.inventoryMenu.getStateId()==inventoryRevision,"identical contents still apply the authoritative menu revision");
            mc.getSingleplayerServer().execute(()->{
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                player.getMainHandItem().setCount(3);player.getOffhandItem().setCount(2);
                player.inventoryMenu.broadcastChanges();inventoryRevision=player.inventoryMenu.getStateId();
            });
            inventoryStage=3;return true;
        }
        check(mc.player.getMainHandItem().getCount()==3&&mc.player.getOffhandItem().getCount()==2,
                "changed food counts apply on both hands");
        check(mc.player.inventoryMenu.getStateId()==inventoryRevision,"changed contents retain server revision");
        inventoryStage=4;return false;
    }

    boolean checkShieldConfirmation(Minecraft mc) throws Exception {
        if(shieldStage==0){
            ready=false;
            mc.getSingleplayerServer().execute(()->{try{
                var server=mc.getSingleplayerServer();var level=server.overworld();
                var player=server.getPlayerList().getPlayer(mc.player.getUUID());
                server.setDifficulty(Difficulty.NORMAL,true);level.setDayTime(18000);
                for(int x=-5;x<=8;x++)for(int z=-3;z<=3;z++){
                    level.setBlock(new BlockPos(x,100,z),Blocks.OBSIDIAN.defaultBlockState(),3);
                    for(int y=101;y<=105;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                }
                player.removeAllEffects();player.setGameMode(GameType.SURVIVAL);player.getAbilities().flying=false;player.onUpdateAbilities();
                player.getInventory().clearContent();player.getInventory().setItem(0,new ItemStack(Items.OBSIDIAN,64));
                player.getInventory().setSelectedSlot(0);player.inventoryMenu.broadcastFullState();
                player.teleportTo(level,.5,101,.5,Set.of(),0,0,false);
                target=new Zombie(EntityType.ZOMBIE,level);target.setNoAi(true);target.setNoGravity(true);target.setSilent(true);
                target.setPersistenceRequired();target.setPos(5.5,101,.5);level.addFreshEntity(target);ready=true;
            }catch(Throwable t){failure=t;}});
            shieldStage=1;shieldWait=0;return true;
        }
        if(!ready||++shieldWait<40||!(mc.level.getEntity(target.getId()) instanceof LivingEntity live))return true;
        var calculation=new AnchorDamageUtil.Calculation();
        var targets=List.of(AnchorDamageUtil.Target.of(live));
        if(shieldStage==1){
            uncoveredSelf=calculation.damage(AnchorDamageUtil.Target.of(mc.player),shieldSource,12,null,null,null);
            uncoveredTarget=calculation.damage(targets.getFirst(),shieldSource,12,null,null,null);
            check(uncoveredSelf>1&&uncoveredTarget>1,"shield fixture has measurable self and target exposure");
            check(!shield.beforeBlast(shieldSource,null,12,targets,5,false,"BlastProbe"),"shield submits placement and defers the explosive");
            var pending=shield.getClass().getDeclaredField("pending");pending.setAccessible(true);shieldPosition=(BlockPos)pending.get(shield);
            check(shieldPosition!=null,"shield is pending, not assumed confirmed after sending");
            shieldStage=2;shieldWait=0;return true;
        }
        check(serverShieldSeen,"real server accepted cover placement");
        check(mc.level.getBlockState(shieldPosition).is(Blocks.OBSIDIAN),"client observed cover block acknowledgment");
        check(shield.beforeBlast(shieldSource,null,12,targets,5,false,"BlastProbe"),"acknowledged cover releases the explosive gate");
        check(calculation.damage(AnchorDamageUtil.Target.of(mc.player),shieldSource,12,null,null,null)<uncoveredSelf*.7,
                "observed cover reduces self damage by at least thirty percent");
        check(calculation.damage(targets.getFirst(),shieldSource,12,null,null,null)>=uncoveredTarget*.9,
                "observed cover retains target exposure");
        shieldStage=3;return false;
    }

    /** Actual dispatch, cancellation, and replacement through the production event chain. */
    void checkRotationAcknowledgements(Minecraft mc) {
        var rotation=GuizhouWare.rotationManager;
        check(rotation.look(44,-65),"initial silent look submitted");
        check(Math.abs(rotation.getServerPitch()+65)<.01,"silent look cache follows accepted packet");
        check(dev.guizhou.util.PacketSendUtil.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                10,0,mc.player.onGround(),mc.player.horizontalCollision)),"ordinary camera restoration submitted");
        check(Math.abs(rotation.getServerPitch())<.01,"ordinary movement replaces stale silent pitch");
        check(rotation.look(44,-65)&&Math.abs(rotation.getServerPitch()+65)<.01,
                "repeated silent aim is sent again after ordinary camera restoration");
        var policy=new RotationProbePolicy();
        dev.guizhou.util.traits.Util.EVENT_BUS.register(policy,"BlastProbeRotationPolicy");
        try {
            policy.block=true;
            check(!rotation.look(80,-35),"cancelled look reports rejection");
            check(Math.abs(rotation.getServerPitch()+65)<.01&&Math.abs(rotation.getServerYaw()-44)<.01,
                    "cancelled look preserves accepted rotation");
            policy.block=false;policy.replace=true;
            check(rotation.look(80,-35),"accepted forwarded rotation reports submission");
            check(Math.abs(rotation.getServerPitch()-15)<.01&&Math.abs(rotation.getServerYaw()+30)<.01,
                    "replacement angles survive original look completion");
        } finally { dev.guizhou.util.traits.Util.EVENT_BUS.unregister(policy); }
        rotation.look(mc.player.getYRot(),mc.player.getXRot());
        rotationStage=1;
    }
    static final class RotationProbePolicy {
        boolean block,replace,forwarding;
        @dev.guizhou.event.system.Subscribe(priority=1000)
        void send(dev.guizhou.event.impl.network.PacketEvent.Send event) {
            if(forwarding||!(event.getPacket() instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot move))return;
            if(block){event.cancel();return;}
            if(replace){
                forwarding=true;
                try {dev.guizhou.util.PacketSendUtil.forward(event,new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                        -30,15,move.isOnGround(),false));}
                finally {forwarding=false;}
            }
        }
    }

    void tick(Minecraft mc){
        if(stage==99)return;
        if(++tick>5000){fail(mc,new AssertionError("timeout stage="+stage+" trial="+trial+" chain="+chain+" step="+step));return;}
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null)return;
        mc.options.pauseOnLostFocus=false;
        try{
            if(failure!=null)throw new AssertionError(failure);
            if (inventoryStage < 4 && checkInventoryAcknowledgements(mc)) return;
            if(rotationStage==0)checkRotationAcknowledgements(mc);
            if (shieldStage < 3 && checkShieldConfirmation(mc)) return;
            var tp=GuizhouWare.moduleManager.getModuleByClass(GuangxiTPModule.class);
            var piston=GuizhouWare.moduleManager.getModuleByClass(GuangxiPistonModule.class);
            var bed=GuizhouWare.moduleManager.getModuleByClass(GuangxiBedModule.class);
            var anchor=GuizhouWare.moduleManager.getModuleByClass(AutoAnchorModule.class);
            if(stage==0){mc.setScreen(null);for(var m:GuizhouWare.moduleManager.getModules())if(m.isEnabled())m.disable();
                check(dev.guizhou.manager.HeartbeatManager.authorized(),"explicit development authorization");
                setting(tp,"Rotate",false);setting(tp,"1.12",false);setting(piston,"BlockDestruction",true);setting(bed,"Rotate",false);setting(anchor,"Rotate",false);
                rows.add("family,scene,predicted,server_damage,error");
                int start=Integer.getInteger("blast.startChain",-1);
                if(start>=0){chain=start;setup(mc,true);stage=3;}else{setup(mc,false);stage=1;}return;}
            if(stage==1){
                if(ready&&wait==10&&trial%6==5)mc.getSingleplayerServer().execute(()->{
                    // Vanilla does not reveal another mob's effects. Explicit oracle metadata for this reduction case.
                    var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    p.connection.send(new net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket(target.getId(),
                            target.getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE),false));
                });
                if(!ready||++wait<35||mc.level.getEntity(target.getId())==null)return;
                var live=(LivingEntity)mc.level.getEntity(target.getId());int family=trial/6;
                if(trial==0){
                    setting(anchor,"MaxSelfDamage",36.0);
                    var oldMotion=mc.player.getDeltaMovement();
                    mc.player.setDeltaMovement(new Vec3(8,0,0));
                    var self=AnchorDamageUtil.Target.of(mc.player);
                    Vec3 along=mc.player.position().add(10,0,0);
                    float now=AnchorDamageUtil.damage(self,along,BlockPos.containing(along));
                    float path=(float)call(anchor,"selfDamage",new Class[]{AnchorDamageUtil.Target.class,Vec3.class,BlockPos.class},self,along,BlockPos.containing(along));
                    check(path>now+5,"anchor samples dangerous intermediate flight positions current="+now+" path="+path);
                    mc.player.setDeltaMovement(oldMotion);anchor.onDisable();setting(anchor,"MaxSelfDamage",4.0);
                    check(!(boolean)call(tp,"safeSelf",new Class[]{Vec3.class,BlockPos.class},mc.player.position(),null),"TP rejects lethal current blast");
                    var remote=new EndCrystal(mc.level,10.5,101,.5);
                    check(call(tp,"breakFeet",new Class[]{EndCrystal.class},remote)==null,"TP rejects unarmored lethal virtual stands even with distant real position");
                    float plain=(float)call(piston,"calcDamage",new Class[]{LivingEntity.class,Vec3.class,BlockPos.class},live,SOURCE,null);
                    var armor=live.getAttribute(Attributes.ARMOR);double previous=armor.getBaseValue();
                    armor.setBaseValue(8);
                    float armored=(float)call(piston,"calcDamage",new Class[]{LivingEntity.class,Vec3.class,BlockPos.class},live,SOURCE,null);
                    check(armored<plain,"piston observes changed armor without a new planning pass");
                    armor.setBaseValue(previous);
                }
                if(family==0){expected=(float)call(tp,"estimateDamage",new Class[]{LivingEntity.class,Vec3.class},live,SOURCE);
                    second=(float)call(piston,"calcDamage",new Class[]{LivingEntity.class,Vec3.class,BlockPos.class},live,SOURCE,null);
                    check(Math.abs(expected-second)<.0001,"TP/piston exact geometry parity "+SCENES[trial%6]);
                }else expected=AnchorDamageUtil.damage(AnchorDamageUtil.Target.of(live),SOURCE,HEAD,family==2?FOOT:null);
                var server=mc.getSingleplayerServer();server.execute(()->{try{
                    var level=(net.minecraft.server.level.ServerLevel)target.level();var p=server.getPlayerList().getPlayer(mc.player.getUUID());
                    target.invulnerableTime=0;target.setHealth(1000);
                    if(family==0){var c=new EndCrystal(level,SOURCE.x,SOURCE.y,SOURCE.z);level.addFreshEntity(c);c.hurtServer(level,target.damageSources().generic(),1);}
                    else level.getBlockState(HEAD).useWithoutItem(level,p,new BlockHitResult(SOURCE,Direction.UP,HEAD,false));
                    actual=1000-target.getHealth();
                }catch(Throwable t){failure=t;}});stage=2;return;}
            if(stage==2){if(!Double.isFinite(actual))return;double error=Math.abs(expected-actual);
                String family=new String[]{"tp-and-piston-crystal","anchor","bed"}[trial/6];
                rows.add(String.format(Locale.ROOT,"%s,%s,%.6f,%.6f,%.6f",family,SCENES[trial%6],expected,actual,error));
                check(error<.02,"server damage "+family+" "+SCENES[trial%6]+" predicted="+expected+" actual="+actual);
                oracleCases++;if(++trial<18){setup(mc,false);stage=1;return;}
                chain=0;setup(mc,true);stage=3;return;}
            if(stage==3){if(!ready||++wait<40||mc.level.getEntity(target.getId())==null)return;
                check(mc.player.hasEffect(net.minecraft.world.effect.MobEffects.RESISTANCE),"chain fixture resistance synchronized");
                mc.player.setYRot(0);mc.player.setXRot(0);
                dev.guizhou.util.PacketSendUtil.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(0,0,mc.player.onGround(),mc.player.horizontalCollision));
                System.out.println("BLAST_CHAIN_START chain="+chain+" pos="+mc.player.position()+" yaw="+GuizhouWare.rotationManager.getServerYaw()+" bed="+mc.player.getInventory().getItem(3)+" selected="+mc.player.getInventory().getSelectedSlot());
                restorePos=mc.player.position();restoreYaw=GuizhouWare.rotationManager.getServerYaw();restorePitch=GuizhouWare.rotationManager.getServerPitch();
                setting(tp,"Rotate",true);setting(bed,"Rotate",chain==2);step=0;wait=0;stage=4;
            }
            if(stage==4){
                if(++wait>180)throw new AssertionError("packet chain timeout "+chain+" step="+step+" targetHealth="+health+" crystalRemoved="+(crystal==null?"n/a":crystal.isRemoved()));
                if(chain==0){
                    var state=mc.level.getBlockState(HEAD);
                    if(step==0){if((boolean)call(anchor,"perform",new Class[]{AnchorSequence.Action.class,BlockPos.class},AnchorSequence.Action.PLACE,HEAD)){step=1;check(true,"anchor placement sent");}}
                    else if(step==1&&state.is(Blocks.RESPAWN_ANCHOR)){if((boolean)call(anchor,"perform",new Class[]{AnchorSequence.Action.class,BlockPos.class},AnchorSequence.Action.CHARGE,HEAD)){step=2;check(true,"anchor charge sent after observed placement");}}
                    else if(step==2&&state.is(Blocks.RESPAWN_ANCHOR)&&state.getValue(RespawnAnchorBlock.CHARGE)>0){if((boolean)call(anchor,"perform",new Class[]{AnchorSequence.Action.class,BlockPos.class},AnchorSequence.Action.DETONATE,HEAD)){step=3;check(true,"anchor detonation sent after observed charge");}}
                }else if(chain==1||chain==2){
                    setting(bed,"TpMode",chain==2);field(bed,"pendingFoot",FOOT);field(bed,"pendingHead",HEAD);field(bed,"pendingTarget",mc.player);field(bed,"desiredYaw",0f);
                    if(step==0&&(boolean)call(bed,"placeBed",new Class[0])){step=1;check(true,"bed placement sent tp="+(chain==2));}
                    else if(step==1&&mc.level.getBlockState(HEAD).is(Blocks.RED_BED)&&mc.level.getBlockState(FOOT).is(Blocks.RED_BED)){
                        if((boolean)call(bed,"detonate",new Class[0])){step=2;check(true,"observed bed pair detonated tp="+(chain==2));}}
                }else if(chain==3){
                    if(step==0&&(boolean)call(tp,"tpPlace",new Class[]{BlockPos.class},HEAD.below())){step=1;check(true,"TP crystal placement sent");}
                    else if(step==1){for(var e:mc.level.getEntities(null,new AABB(HEAD).inflate(2)))if(e instanceof EndCrystal c&&(boolean)call(tp,"tpBreak",new Class[]{EndCrystal.class},c)){step=2;check(true,"TP crystal attack sent");break;}}
                }else{
                    if(step==0){mc.getSingleplayerServer().execute(()->target.level().setBlock(new BlockPos(2,101,1),Blocks.REDSTONE_BLOCK.defaultBlockState(),3));step=1;}
                    else if(step==1&&crystal!=null&&mc.level.getEntity(crystal.getId()) instanceof EndCrystal c&&c.getX()<.25){
                        check(true,"real server piston moved crystal before attack");
                        if((boolean)call(piston,"breakCrystal",new Class[]{EndCrystal.class},c)){step=2;check(true,"piston module attack sent");}}
                }
                if(health<999&&step>=2){stage=5;wait=0;}
            }
            if(stage==5){if(++wait<8)return;
                check(health<999,"real packet-chain explosion damage chain="+chain);
                if(chain==2||chain==3){check(serverPos.distanceTo(restorePos)<.02,"TP restored server position chain="+chain+" expected="+restorePos+" actual="+serverPos);
                    check(Math.abs(net.minecraft.util.Mth.wrapDegrees(serverYaw-restoreYaw))<.01&&Math.abs(serverPitch-restorePitch)<.01,"TP restored original server angles chain="+chain);}
                rows.add("packet-chain,"+chain+",,"+(1000-health)+",0");
                packetChains++;if(++chain<5){setup(mc,true);stage=3;return;}
                Files.write(mc.gameDirectory.toPath().resolve("damage.csv"),rows);Files.writeString(mc.gameDirectory.toPath().resolve("complete.txt"),"checks="+checks+"\nreal explosion oracle cases="+oracleCases+"\nreal module packet chains="+packetChains+"\n");
                System.out.println("BLAST_PROBE_PASSED checks="+checks);stage=99;mc.stop();
            }
        }catch(Throwable t){fail(mc,t);}
    }
    void setup(Minecraft mc,boolean packet){
        ready=false;actual=Double.NaN;wait=0;int index=trial%6;int family=trial/6;int mode=chain;
        var server=mc.getSingleplayerServer();server.execute(()->{try{
            var p=server.getPlayerList().getPlayer(mc.player.getUUID());
            boolean nether=packet?(mode==1||mode==2):family==2;
            var level=server.getLevel(nether?Level.NETHER:Level.OVERWORLD);
            server.setDifficulty(Difficulty.NORMAL,true);level.setDayTime(18000);
            var old=new ArrayList<Entity>();for(var e:level.getAllEntities())if(e instanceof net.minecraft.world.entity.Mob||e instanceof EndCrystal)old.add(e);old.forEach(Entity::discard);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamerule minecraft:spawn_mobs false");
            for(int x=-8;x<=13;x++)for(int z=-7;z<=7;z++){level.setBlock(new BlockPos(x,100,z),Blocks.OBSIDIAN.defaultBlockState(),3);for(int y=101;y<=108;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);}
            if(!packet&&(index==1||index==2))for(int z=-4;z<=4;z++)for(int y=101;y<=105;y++)level.setBlock(new BlockPos(2,y,z),(index==1?Blocks.STONE:Blocks.OBSIDIAN).defaultBlockState(),3);
            if(!packet&&index==3)for(int z=-4;z<=4;z++)level.setBlock(new BlockPos(2,101,z),Blocks.STONE_SLAB.defaultBlockState(),3);
            p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();p.removeAllEffects();p.getInventory().clearContent();
            p.teleportTo(level,packet&&mode==3?-5.5:packet&&mode==2?-7.5:-2.5,101,-2.5,Set.of(),0,0,false);
            if(packet){
                p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE,1200,4));
                Item[] items={Items.DIAMOND_SWORD,Items.RESPAWN_ANCHOR,Items.GLOWSTONE,Items.RED_BED,Items.END_CRYSTAL};
                for(int i=0;i<items.length;i++)p.getInventory().setItem(i,new ItemStack(items[i],i==0?1:64));p.getInventory().setSelectedSlot(0);p.inventoryMenu.broadcastChanges();
            }else if(family==1)level.setBlock(HEAD,Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE,1),3);
            else if(family==2){level.setBlock(FOOT,Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT),3);level.setBlock(HEAD,Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.HEAD),3);}
            target=new Zombie(EntityType.ZOMBIE,level);target.setNoAi(true);target.setNoGravity(true);target.setSilent(true);target.setPersistenceRequired();target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);target.getAttribute(Attributes.ARMOR).setBaseValue(0);target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);target.setHealth(1000);target.setPos(packet&&mode==4?-4.5:4.5,101,.5);
            if(!packet&&index==4)target.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.NETHERITE_CHESTPLATE));
            if(!packet&&index==5)target.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE,1200,1));
            level.addFreshEntity(target);health=1000;
            if(packet&&mode==4){level.setBlock(new BlockPos(2,101,0),Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.WEST),3);crystal=new EndCrystal(level,.5,101,.5);level.addFreshEntity(crystal);}
            ready=true;
        }catch(Throwable t){failure=t;}});
    }
    void fail(Minecraft mc,Throwable t){t.printStackTrace();stage=99;try{Files.write(mc.gameDirectory.toPath().resolve("damage.csv"),rows);Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"),t.toString());}catch(Exception ignored){}mc.stop();}
}
