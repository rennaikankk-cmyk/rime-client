package probe;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.combat.*;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.hud.ActiveModulesHudModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.*;

/** Exercises the actual world renderers and HUD, with stationary server-side fixture blocks. */
public final class CombatUiProbe implements ClientModInitializer {
    static final BlockPos FOOT=new BlockPos(0,101,0), CRYSTAL=new BlockPos(-3,101,0);
    static final Direction[] DIRECTIONS={Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST};
    int tick,stage,wait,orientation; volatile boolean ready,captured; Zombie target;
    final List<String> results=new ArrayList<>();
    @Override public void onInitializeClient(){ ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    static Object call(Object o,String n,Class<?>[] types,Object... args)throws Exception {
        var m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);
    }
    static void field(Object o,String n,Object value)throws Exception {
        var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,value);
    }
    void check(boolean ok,String label){if(!ok)throw new AssertionError(label);results.add(label);System.out.println("UI_CHECK "+label);}
    void tick(Minecraft mc){
        if(stage==99)return;
        try{
            if(++tick>1000)throw new AssertionError("UI probe timeout");
            if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null)return;
            var hud=GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
            var list=ActiveModulesHudModule.getInstance();
            var tp=GuizhouWare.moduleManager.getModuleByClass(GuangxiTPModule.class);
            var bed=GuizhouWare.moduleManager.getModuleByClass(GuangxiBedModule.class);
            if(stage==0){
                mc.setScreen(null);mc.options.pauseOnLostFocus=false;
                for(var m:GuizhouWare.moduleManager.getModules())if(m.isEnabled())m.disable();
                list.clear();hud.enable();
                for(var element:hud.elementList())hud.elementToggle(element).setValue(element instanceof ActiveModulesHudModule);
                // Render fixtures use real module render hooks, without enabling automatic combat actions.
                tp.enabled.setValue(true);bed.enabled.setValue(true);
                check(list.displayedModules().contains(tp)&&list.displayedModules().contains(bed),"enabled modules appear with an empty watchlist");
                tp.enabled.setValue(false);check(!list.displayedModules().contains(tp),"disabled module disappears immediately");tp.enabled.setValue(true);
                hud.activeModulesSource.setValue(ActiveModulesHudModule.Source.WATCHLIST);
                check(list.renderedLineCount()==0,"explicit empty watchlist stays empty");list.add(tp.getName());
                check(list.displayedModules().equals(List.of(tp)),"manual watchlist still works");list.clear();
                hud.activeModulesSource.setValue(ActiveModulesHudModule.Source.ENABLED);
                check(tp.getSettings().stream().anyMatch(s->s.getName().equals("RenderDamage")),"TP crystal exposes RenderDamage");
                call(tp,"ensureAttackSession",new Class[]{});
                call(tp,"noteRender",new Class[]{BlockPos.class,float.class},CRYSTAL,12f);
                field(tp,"renderStartMs",System.currentTimeMillis()-1000);
                check(!(boolean)call(tp,"hasRender",new Class[]{}),"expired damage snapshot is cleared");
                setup(mc);stage=1;return;
            }
            if(!ready||++wait<35)return;
            var live=mc.level.getEntity(target.getId());if(live==null)return;
            Direction facing=DIRECTIONS[orientation];BlockPos head=FOOT.relative(facing);
            field(bed,"renderPos",FOOT);field(bed,"renderHead",head);field(bed,"renderStartMs",System.currentTimeMillis());
            float damage=(float)call(tp,"estimateDamage",new Class[]{net.minecraft.world.entity.LivingEntity.class,Vec3.class},live,new Vec3(CRYSTAL.getX()+.5,101,.5));
            call(tp,"noteRender",new Class[]{BlockPos.class,float.class},CRYSTAL,damage);
            if(stage==1){
                var footState=mc.level.getBlockState(FOOT);var headState=mc.level.getBlockState(head);
                if(!footState.is(Blocks.RED_BED)||!headState.is(Blocks.RED_BED))return;
                AABB actual=footState.getShape(mc.level,FOOT).bounds().move(FOOT)
                        .minmax(headState.getShape(mc.level,head).bounds().move(head));
                AABB drawn=(AABB)call(bed,"renderBox",new Class[]{});
                check(actual.equals(drawn),"bed render bounds match vanilla shape: "+facing);
                check(damage>0&&Float.isFinite(damage),"TP displayed damage uses current explosion calculation: "+damage);
                mc.gui.getChat().clearMessages(true);
                stage=2;wait=0;return;
            }
            if(stage==2&&wait>50){
                mc.gui.getChat().clearMessages(true);
                check(list.wasRendered()&&list.boundW()>0&&list.boundH()>0,"active list has visible render bounds: "+facing);
                captured=false;Screenshot.grab(mc.gameDirectory,"combat-ui-"+facing.getName()+".png",mc.getMainRenderTarget(),1,msg->captured=true);
                stage=3;return;
            }
            if(stage==3&&captured){
                check(Files.exists(mc.gameDirectory.toPath().resolve("screenshots/combat-ui-"+facing.getName()+".png")),"screenshot saved: "+facing);
                if(++orientation==DIRECTIONS.length){Files.write(mc.gameDirectory.toPath().resolve("ui-complete.txt"),results);stage=99;mc.stop();return;}
                setupBed(mc);stage=1;wait=0;
            }
        }catch(Throwable error){error.printStackTrace();try{Files.writeString(mc.gameDirectory.toPath().resolve("ui-failure.txt"),error.toString());}catch(Exception ignored){}stage=99;mc.stop();}
    }
    void setup(Minecraft mc){
        mc.getSingleplayerServer().execute(()->{
            var server=mc.getSingleplayerServer();var p=server.getPlayerList().getPlayer(mc.player.getUUID());var level=p.level();
            level.setDayTime(6000);
            p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();
            for(int x=-8;x<=8;x++)for(int z=-8;z<=9;z++){level.setBlock(new BlockPos(x,100,z),Blocks.STONE.defaultBlockState(),3);for(int y=101;y<110;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);}
            level.setBlock(CRYSTAL.below(),Blocks.OBSIDIAN.defaultBlockState(),3);
            p.teleportTo(level,4.5,104,7.5,Set.of(),150,24,false);
            target=new Zombie(EntityType.ZOMBIE,level);target.setNoAi(true);target.setPersistenceRequired();
            target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_HELMET));
            target.setPos(-.5,101,.5);level.addFreshEntity(target);
            ready=true;
        });
        setupBed(mc);
    }
    void setupBed(Minecraft mc){
        Direction facing=DIRECTIONS[orientation];
        mc.getSingleplayerServer().execute(()->{
            var level=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()).level();
            for(Direction d:DIRECTIONS)level.setBlock(FOOT.relative(d),Blocks.AIR.defaultBlockState(),3);
            level.setBlock(FOOT,Blocks.AIR.defaultBlockState(),3);
            var state=Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,facing);
            level.setBlock(FOOT,state.setValue(BedBlock.PART,BedPart.FOOT),2);
            level.setBlock(FOOT.relative(facing),state.setValue(BedBlock.PART,BedPart.HEAD),3);
        });
    }
}
