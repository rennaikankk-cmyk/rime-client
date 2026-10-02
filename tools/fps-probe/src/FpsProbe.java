package probe;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.combat.GuangxiCrystalModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.util.elytra.FlightCollisionSnapshot;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Isolated real-world workload; identical inputs before/after, no server combat or win-rate claim. */
public final class FpsProbe implements ClientModInitializer {
 boolean queued; volatile boolean ready; int ticks; int samples; long crystalNs,pathNs,captureNs;
 final List<String> results=new ArrayList<>();
 Method ensure,damage,safe; Field path;
 @Override public void onInitializeClient() {
  try {
   ensure=method("ensureSelfCache"); damage=method("calcDamageSelf",Vec3.class,BlockPos.class);
   safe=method("selfDamageOk",Vec3.class,BlockPos.class,double.class,float.class);
   path=GuangxiCrystalModule.class.getDeclaredField("selfPath");path.setAccessible(true);
  } catch(Exception e){throw new RuntimeException(e);}
  ClientTickEvents.END_CLIENT_TICK.register(mc->{try{tick(mc);}catch(Exception e){throw new RuntimeException(e);}});
 }
 static Method method(String name,Class<?>... types)throws Exception {
  var m=GuangxiCrystalModule.class.getDeclaredMethod(name,types);m.setAccessible(true);return m;
 }
 void tick(Minecraft mc)throws Exception {
  if(mc.player==null||mc.getSingleplayerServer()==null)return;
  mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);
  if(!queued){queued=true;mc.setScreen(null);mc.getSingleplayerServer().execute(()->{
   var s=mc.getSingleplayerServer();var p=s.getPlayerList().getPlayer(mc.player.getUUID());var l=p.level();
   s.setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
   s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),"gamerule minecraft:spawn_mobs false");
   p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();p.getInventory().clearContent();
   for(int x=-14;x<=14;x++)for(int z=-14;z<=14;z++)for(int y=115;y<=137;y++)
    l.setBlock(new BlockPos(x,y,z),(y==119||x==6&&y<125?Blocks.OBSIDIAN:Blocks.AIR).defaultBlockState(),3);
   p.teleportTo(l,.5,121,.5,Set.of(),0,0,false);ready=true;
  });return;}
  if(!ready||++ticks<100)return;
  if(ticks==100)ClientDiagnostics.start(60);
  var crystal=GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
  var flight=GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
  Vec3 origin=mc.player.position();
  List<Vec3> forecast=new ArrayList<>();for(int i=0;i<25;i++)forecast.add(origin.add(0,0,i*.25));
  long start=System.nanoTime();StringBuilder check=new StringBuilder();
  for(int i=0;i<12;i++){
   Vec3 center=origin.add((i%4)-2,-1,(i/4)+2);
   ensure.invoke(crystal);path.set(crystal,forecast);
   check.append(safe.invoke(crystal,center,null,4d,20f)).append(':');
   ensure.invoke(crystal);path.set(crystal,forecast);
   check.append(damage.invoke(crystal,center,null)).append(';');
  }
  long ct=System.nanoTime()-start;start=System.nanoTime();
  for(int i=0;i<64;i++)check.append(flight.clearNavigationPath(origin,origin.add((i%8)-4,1,-12+i/8))?'1':'0');
  long pt=System.nanoTime()-start;start=System.nanoTime();
  var snapshot=FlightCollisionSnapshot.capture(mc.player,mc.level,12);
  long st=System.nanoTime()-start;
  if(ticks>=200){crystalNs+=ct;pathNs+=pt;captureNs+=st;samples++;results.add(check.toString());}
  if(ticks>=299){ClientDiagnostics.stop();
   verify(mc,crystal,flight);
   var dir=mc.gameDirectory.toPath();
   Files.writeString(dir.resolve("benchmark.json"),"{\"samples\":"+samples+",\"crystal_ns\":"+crystalNs+",\"path_ns\":"+pathNs+",\"capture_ns\":"+captureNs+"}");
   Files.write(dir.resolve("decisions.txt"),results);ClientDiagnostics.export(dir.resolve("diagnostics.json"));
   System.out.println("FPS_PROBE_PASS samples="+samples);mc.stop();
  }
 }
 int checks;
 void require(boolean value){checks++;if(!value)throw new AssertionError("FPS equivalence check "+checks);}
 void verify(Minecraft mc,GuangxiCrystalModule crystal,GuangxiElytraModule flight)throws Exception {
  Vec3 saved=mc.player.position();Random random=new Random(129130);
  var at=Arrays.stream(GuangxiCrystalModule.class.getDeclaredMethods()).filter(m->m.getName().equals("selfDamageAt")).findFirst().orElseThrow();at.setAccessible(true);
  var cache=GuangxiCrystalModule.class.getDeclaredField("selfCache");cache.setAccessible(true);
  for(int scene=0;scene<4;scene++) {
   mc.player.setPos(scene%2==0?.5:5.3,scene<2?121:132,.5);
   Vec3 origin=mc.player.position();
   var old=BaselineCollisionSnapshot.capture(mc.player,mc.level,3);
   var now=FlightCollisionSnapshot.capture(mc.player,mc.level,3);
   require(old!=null&&now!=null);
   for(int i=0;i<400;i++) {
    Vec3 from=origin.add(random.nextDouble()*5-2.5,random.nextDouble()*4-2,random.nextDouble()*5-2.5);
    Vec3 to=origin.add(random.nextDouble()*5-2.5,random.nextDouble()*4-2,random.nextDouble()*5-2.5);
    require(old.clear(from,to)==now.clear(from,to));
    require(legacyClear(mc,from,to)==flight.clearNavigationPath(from,to));
   }
   for(int i=0;i<30;i++) {
    List<Vec3> poses=new ArrayList<>();for(int j=0;j<25;j++)poses.add(origin.add(j*.17,0,j*.08));
    Vec3 center=origin.add(random.nextDouble()*10-5,random.nextDouble()*3-2,random.nextDouble()*10-5);
    ensure.invoke(crystal);path.set(crystal,poses);Object sc=cache.get(crystal);float exact=0;
    for(Vec3 pose:poses)exact=Math.max(exact,(float)at.invoke(crystal,sc,pose,mc.player.getBoundingBox().move(pose.subtract(origin)),center,null));
    for(double maximum:new double[]{0,4,36}) {
     ensure.invoke(crystal);path.set(crystal,poses);
     require((boolean)safe.invoke(crystal,center,null,maximum,20f)==dev.guizhou.util.combat.CrystalSelectionPolicy.isSelfDamageSafe(exact,maximum,20f,1.5));
     // Same pass after a partial rejection must still give the complete maximum.
     require(Float.compare((float)damage.invoke(crystal,center,null),exact)==0);
    }
   }
  }
  mc.player.setPos(saved);Files.writeString(mc.gameDirectory.toPath().resolve("equivalence.json"),"{\"checks\":"+checks+",\"passed\":true}");
 }
 static boolean legacyClear(Minecraft mc,Vec3 from,Vec3 to) {
  Vec3 delta=to.subtract(from);double length=delta.length();if(length>96)return false;
  int steps=Math.max(1,(int)Math.ceil(length/.5));Vec3 step=delta.scale(1d/steps);
  var body=mc.player.getBoundingBox().inflate(.06,.02,.06).move(from.subtract(mc.player.position()));
  for(int i=0;i<steps;i++) {
   var swept=body.expandTowards(step);
   if(!mc.level.hasChunkAt(BlockPos.containing(swept.minX,swept.minY,swept.minZ))||!mc.level.hasChunkAt(BlockPos.containing(swept.maxX,swept.maxY,swept.maxZ))
       ||!mc.level.getWorldBorder().isWithinBounds(swept)||mc.level.getBlockCollisions(mc.player,swept).iterator().hasNext())return false;
   body=body.move(step);
  }
  return true;
 }
}
