package probe;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.features.settings.Setting;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
public final class CombatProbe implements ClientModInitializer {
 int ticks; volatile boolean prepared; boolean queued, started; int running; Zombie target; float previousHealth=1000; int damageEvents; double totalDamage; PrintWriter trace;
 static final String MODE=System.getProperty("probe.mode","SPEAR");
 static final boolean ARMOR=Boolean.getBoolean("probe.armor");
 static final boolean AIR=Boolean.getBoolean("probe.air");
 static final boolean HIGH=Boolean.getBoolean("probe.high");
 int serverTicks;
 @Override public void onInitializeClient() {
  ClientTickEvents.END_CLIENT_TICK.register(this::tick);
  ServerTickEvents.END_SERVER_TICK.register(server -> {
   if(target!=null && AIR && prepared && serverTicks%10==0) {
    var p=server.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
    var k=p.getUseItem().get(net.minecraft.core.component.DataComponents.KINETIC_WEAPON);
    var range=p.getUseItem().get(net.minecraft.core.component.DataComponents.ATTACK_RANGE);
    if(k!=null && range!=null) {
     double speed=p.getLookAngle().dot(net.minecraft.world.item.component.KineticWeapon.getMotion(p));
     double rel=Math.max(0,speed-p.getLookAngle().dot(net.minecraft.world.item.component.KineticWeapon.getMotion(target)));
     var hits=net.minecraft.world.entity.projectile.ProjectileUtil.getHitEntitiesAlong(p,range,e->e==target,net.minecraft.world.level.ClipContext.Block.COLLIDER).right();
     System.out.println("PROBE_SERVER ticks="+serverTicks+" dist="+p.distanceTo(target)+" use="+p.getTicksUsingItem()+" delay="+k.delayTicks()+" speed="+speed+" valid="+(k.damageConditions().isPresent()&&k.damageConditions().get().test(p.getTicksUsingItem()-k.delayTicks(),speed,rel,1))+" hits="+hits.map(java.util.Collection::size).orElse(0));
    }
   }
   if(target!=null && AIR && prepared) {
    double t=++serverTicks;
    target.setPos(0.5+12*Math.sin(t*0.1),125+5*Math.sin(t*0.035),0.5+12*Math.cos(t*0.1));
   }
   if(target!=null && target.getHealth()<previousHealth) {
    float damage=previousHealth-target.getHealth(); damageEvents++; totalDamage+=damage;
    var source=target.getLastDamageSource();
    System.out.println("PROBE_DAMAGE mode="+MODE+" damage="+damage+" events="+damageEvents+" source="+(source==null?"unknown":source.type().msgId()));
    target.setHealth(1000);
   }
   if(target!=null) previousHealth=target.getHealth();
  });
 }
 @SuppressWarnings({"unchecked","rawtypes"}) static void setting(Module m,String name,Object v) {
  for(Setting s:m.getSettings()) if(s.getName().equals(name)) { if(s.getValue() instanceof Enum e && v instanceof String text) v=Enum.valueOf(e.getDeclaringClass(),text); s.setValue(v); return; }
  throw new IllegalArgumentException("Missing setting "+name);
 }
 static Object field(Object o,String name) {
  if(o==null)return null;
  for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name); f.setAccessible(true);return f.get(o);}catch(Exception e){} return "?";
 }
 void tick(Minecraft mc) {
  ticks++;
  if(mc.player==null || mc.level==null || mc.getSingleplayerServer()==null) return;
  mc.options.pauseOnLostFocus=false;
  if(!queued) {
   queued=true; mc.setScreen(null); GuizhouWare.telemetryManager.setActive(false);
   mc.getSingleplayerServer().execute(()->setup(mc));
   return;
  }
  if(!prepared) return;
  if(!started) {
   if(++running<50)return;
   mc.setScreen(null);
   try {trace=new PrintWriter(Files.newBufferedWriter(mc.gameDirectory.toPath().resolve("combat-trace.csv")));}catch(IOException e){throw new RuntimeException(e);}
   trace.println("tick,x,y,z,vy,gliding,fall,using,useTicks,chest,state,target,boost,rocketBlock");
   var flight=GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
   var chase=GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraChaseModule.class);
   setting(flight,"ArmorFly",ARMOR); setting(flight,"Speed",3.1); setting(flight,"MotionMode","FIREWORKS");
   if(!MODE.equals("ARMOR")) setting(chase,"Mode",MODE); setting(chase,"PlayerOnly",false); setting(chase,"AdaptiveCombat",false);
   setting(chase,"AutoMaceDive",true); setting(chase,"MaceUseExtraAttack",false);
   setting(chase,"MaceHeight",10.0); setting(chase,"SpearRange",11.0);
   setting(GuizhouWare.moduleManager.getModuleByClass(dev.guizhou.features.modules.client.TargetsModule.class),"Hostiles",true);
   if(MODE.equals("ARMOR")) {
    GuizhouWare.moduleManager.getModuleByClass(dev.guizhou.features.modules.movement.ArmorFlyModule.class).enable();
    mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());p.teleportTo(p.level(),0.5,135,8.5,Set.of(),180,0,false);});
   } else {
    if(HIGH) mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());p.teleportTo(p.level(),0.5,129,8.5,Set.of(),180,0,false);});
    chase.enable();
   }
   started=true; running=0;
   System.out.println("PROBE_START mode="+MODE+" armor="+ARMOR);
  }
  if(!started)return;
  running++;
  if(MODE.equals("ARMOR") && running==10 && mc.player.tryToStartFallFlying()) {
   mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc.player,net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
  }
  var flight=GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
  var chase=GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraChaseModule.class);
  Object selected=field(chase,"target");
  if(!MODE.equals("ARMOR") && selected instanceof net.minecraft.world.entity.Entity entity
      && !entity.getUUID().equals(target.getUUID())) {
   throw new AssertionError("Probe selected an entity other than the measured target: "+entity);
  }
  if(MODE.equals("ARMOR")) {
   var armorModule=GuizhouWare.moduleManager.getModuleByClass(dev.guizhou.features.modules.movement.ArmorFlyModule.class);
   if(running==200)flight.enableByChase();
   if(running==220)flight.releaseByChase();
   if(running==225) {
    if(!armorModule.isEnabled()||flight.isEnabled()||!flight.isArmorSwapActive())throw new AssertionError("Independent armor lost after borrowed flight release");
    System.out.println("PROBE_ARMOR ownership_survived=true");
   }
   if(running==400)armorModule.disable();
   if(running==410) {
    if(flight.isArmorSwapActive()||!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA))throw new AssertionError("Independent armor failed equipment recovery");
    System.out.println("PROBE_ARMOR recovered=true");
   }
  }
  if(running%5==0) {
   var p=mc.player; Object behavior=field(chase,"behavior"); if(MODE.equals("MXS"))behavior=field(behavior,"active");
   trace.printf(Locale.ROOT,"%d,%.3f,%.3f,%.3f,%.3f,%s,%.3f,%s,%d,%s,%s,%s,%s,%s%n",running,p.getX(),p.getY(),p.getZ(),p.getDeltaMovement().y,p.isFallFlying(),p.fallDistance,p.isUsingItem(),p.getTicksUsingItem(),p.getItemBySlot(EquipmentSlot.CHEST).getItem(),field(behavior,"state"),field(chase,"target")!=null,flight.isBoostAttached(),field(flight,"lastRocketBlock")); trace.flush(); if(running%20==0) System.out.println("PROBE_STATE tick="+running+" pos="+p.position()+" target="+field(chase,"target")+" heading="+field(behavior,"heading")+" safe="+field(flight,"safetySteering")+" landing="+field(flight,"landingDanger")+" chargeRoom="+field(behavior,"makingChargeRoom")+" held="+p.getMainHandItem()+" use="+p.getTicksUsingItem()+" rot="+GuizhouWare.rotationManager.getRotationPitch()+","+GuizhouWare.rotationManager.getRotationYaw());
  }
  if(running>=800) {
   chase.disable(); flight.disable(); trace.close();
   System.out.println("PROBE_DONE mode="+MODE+" damageEvents="+damageEvents+" damage="+totalDamage);
   mc.stop();
  }
 }
 void setup(Minecraft mc) {
  var server=mc.getSingleplayerServer(); var p=server.getPlayerList().getPlayer(mc.player.getUUID()); var level=p.level(); server.setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
  // Each run copies a saved world, which may contain the previous probe target.
  // Remove loaded mobs from the copy so target selection and damage refer to the same entity.
  var previousMobs=new ArrayList<net.minecraft.world.entity.Mob>();
  for(var entity:level.getAllEntities()) if(entity instanceof net.minecraft.world.entity.Mob mob) previousMobs.add(mob);
  previousMobs.forEach(net.minecraft.world.entity.Entity::discard);
  server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamerule minecraft:spawn_mobs false");
  for(int x=-24;x<=24;x++)for(int z=-24;z<=24;z++) {
   level.setBlock(new BlockPos(x,100,z),Blocks.STONE.defaultBlockState(),3);
   for(int y=101;y<=116;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
  }
  p.setGameMode(GameType.SURVIVAL); p.getAbilities().invulnerable=true; p.onUpdateAbilities();
  p.getInventory().clearContent();
  p.getInventory().setItem(0,new ItemStack(Items.MACE)); p.getInventory().setItem(1,new ItemStack(Items.NETHERITE_SPEAR));
  p.getInventory().setItem(2,new ItemStack(Items.NETHERITE_CHESTPLATE));
  p.getInventory().setItem(3,new ItemStack(Items.FIREWORK_ROCKET,64));
  p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.ELYTRA)); p.inventoryMenu.broadcastChanges();
  p.teleportTo(level,0.5,106,8.5,Set.of(),180,0,false);
  target=new Zombie(EntityType.ZOMBIE,level); target.setNoAi(true); target.setSilent(true); target.setPersistenceRequired();
  target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(1000);
  target.setNoGravity(AIR); target.setPos(0.5,AIR?125:101,0.5); target.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.NETHERITE_HELMET)); level.addFreshEntity(target);
  prepared=true;
 }
}
