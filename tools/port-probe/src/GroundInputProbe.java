package probe;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.util.*;
import static dev.guizhou.util.traits.Util.EVENT_BUS;

/** Tests the real legacy KeyboardInput hook with an injected navigation request, not cave pathfinding. */
public final class GroundInputProbe implements ClientModInitializer {
    private int stage,ticks,wait,checks;
    private double startZ,stopZ;
    private volatile boolean ready;
    private volatile Throwable failure;
    private GuangxiElytraModule flight;
    private final List<String> rows=new ArrayList<>(List.of("case,result"));
    public void onInitializeClient(){EVENT_BUS.register(this);ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
    private void check(boolean result,String label){if(!result)throw new AssertionError(label);checks++;rows.add(label+",passed");}
    private void set(String name,Object value)throws Exception{var f=flight.getClass().getDeclaredField(name);f.setAccessible(true);f.set(flight,value);}
    @Subscribe(priority=-200)
    private void inputRequest(PreTickEvent event){
        if(stage<2||stage>5)return;
        try {var mc=Minecraft.getInstance();set("groundDirection",new Vec3(0,0,5));set("groundDirectionTick",mc.player.tickCount);}
        catch(Throwable e){failure=e;}
    }
    private void tick(Minecraft mc){
        if(stage==99)return;
        if(++ticks>1200){finish(mc,new AssertionError("Timeout stage="+stage));return;}
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null)return;
        try{
            if(failure!=null)throw new AssertionError(failure);
            if(stage==0){
                mc.setScreen(null);mc.options.pauseOnLostFocus=false;
                for(var m:GuizhouWare.moduleManager.getModules())if(m.isEnabled())m.disable();
                check(dev.guizhou.manager.HeartbeatManager.authorized(),"development authorization");
                flight=GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
                mc.getSingleplayerServer().execute(()->{try{
                    var server=mc.getSingleplayerServer();var p=server.getPlayerList().getPlayer(mc.player.getUUID());var level=p.serverLevel();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamerule doMobSpawning false");
                    for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++)for(int y=98;y<=106;y++)
                        level.setBlock(new BlockPos(x,y,z),(y<=100||y==103?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
                    for(int z=-12;z<=12;z++)for(int y=101;y<=102;y++)level.setBlock(new BlockPos(-2,y,z),Blocks.STONE.defaultBlockState(),3);
                    for(int z=-1;z<=1;z++)for(int y=98;y<=100;y++)level.setBlock(new BlockPos(4,y,z),Blocks.AIR.defaultBlockState(),3);
                    p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.teleportTo(level,.5,101,.5,Set.of(),0,0);ready=true;
                }catch(Throwable e){failure=e;}});
                stage=1;return;
            }
            if(stage==1){
                if(!ready||++wait<50)return;
                check(mc.player.onGround(),"standing under two-block roof");
                FlatBaseProbe.setting(flight,"TerrainGuard",true);FlatBaseProbe.setting(flight,"CaveEscape",true);flight.enable();
                var clear=flight.getClass().getDeclaredMethod("clearGroundPath",Vec3.class,Vec3.class);clear.setAccessible(true);
                Vec3 origin=new Vec3(.5,101,.5);
                check((boolean)clear.invoke(flight,origin,origin.add(0,0,5)),"standing passage clears actual world");
                check(!(boolean)clear.invoke(flight,origin,origin.add(-5,0,0)),"standing wall rejects route");
                check(!(boolean)clear.invoke(flight,origin,origin.add(6,0,0)),"missing floor rejects route");
                startZ=mc.player.getZ();wait=0;stage=2;return;
            }
            check(mc.player.input instanceof net.minecraft.client.player.KeyboardInput,"native keyboard object retained tick "+stage+"-"+wait);
            if(stage==2&&++wait>=25){check(mc.player.getZ()>startZ+1,"automatic ordinary walking moves actual player");startZ=mc.player.getZ();mc.options.keyDown.setDown(true);stage=3;wait=0;}
            else if(stage==3&&++wait>=15){check(mc.player.getZ()<startZ-.3,"manual backward input overrides navigation");mc.options.keyDown.setDown(false);startZ=mc.player.getZ();stage=4;wait=0;}
            else if(stage==4&&++wait>=15){check(mc.player.getZ()>startZ+.3,"release of manual key resumes navigation");flight.disable();stage=5;wait=0;}
            else if(stage==5){if(++wait==10)stopZ=mc.player.getZ();if(wait>=20){check(Math.abs(mc.player.getZ()-stopZ)<.05,"disable cannot latch walking keys");finish(mc,null);}}
        }catch(Throwable e){finish(mc,e);}
    }
    private void finish(Minecraft mc,Throwable error){
        stage=99;mc.options.keyDown.setDown(false);if(flight!=null&&flight.isEnabled())flight.disable();EVENT_BUS.unregister(this);
        try{Files.write(mc.gameDirectory.toPath().resolve("ground-input.csv"),rows);
            Files.writeString(mc.gameDirectory.toPath().resolve(error==null?"complete.txt":"failure.txt"),error==null?"ground-input checks="+checks:error.toString());
            if(error!=null)error.printStackTrace();else System.out.println("GROUND_INPUT_PROBE_PASSED "+checks);
        }catch(Exception e){e.printStackTrace();}mc.stop();
    }
}
