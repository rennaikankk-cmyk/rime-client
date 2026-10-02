package probe;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.combat.AutoTotemModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import java.nio.file.Files;
import java.util.*;

/** Actual native server inventory/use checks; this source set never enters the production jar. */
public final class OffhandProbe implements ClientModInitializer {
    final String[] cases={"normal","enchanted","normal-fallback","enchanted-fallback","fallback-disabled",
            "pickaxe-disabled","pickaxe-allowed","release-key","low-health"};
    final List<Map<String,Object>> results=new ArrayList<>();
    volatile boolean ready,serverUsing; volatile int apples; volatile boolean serverTotem; volatile Throwable serverError;
    AutoTotemModule module; int ticks,index=-1,phase,age; boolean mealObserved,released;
    @Override public void onInitializeClient(){
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(!ready)return;
            var players=server.getPlayerList().getPlayers();if(players.size()!=1)return;
            var player=players.getFirst();serverUsing=player.isUsingItem()&&player.getUsedItemHand()==InteractionHand.OFF_HAND;
            apples=player.getInventory().countItem(Items.GOLDEN_APPLE)+player.getInventory().countItem(Items.ENCHANTED_GOLDEN_APPLE);
            serverTotem=player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        });
    }
    void check(boolean value,String name){if(!value)throw new AssertionError(cases[index]+": "+name);}
    void next(Minecraft mc)throws Exception{
        mc.options.keyUse.setDown(false);module.disable();mc.gameMode.releaseUsingItem(mc.player);
        if(++index==cases.length){Files.writeString(mc.gameDirectory.toPath().resolve("complete.txt"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(results));phase=99;mc.stop();return;}
        ready=false;serverUsing=false;age=0;phase=0;mealObserved=false;released=false;
        String name=cases[index];
        FlatBaseProbe.setting(module,"Lethal",false);FlatBaseProbe.setting(module,"Health",16.0);
        FlatBaseProbe.setting(module,"SafeHealth",14.0);FlatBaseProbe.setting(module,"Delay",3);
        FlatBaseProbe.setting(module,"GappleOnUse",true);
        FlatBaseProbe.setting(module,"Crapple",Set.of("normal","normal-fallback","pickaxe-allowed","pickaxe-disabled","release-key","low-health").contains(name));
        FlatBaseProbe.setting(module,"GappleFallback",!name.equals("fallback-disabled"));
        FlatBaseProbe.setting(module,"GapPickaxe",name.equals("pickaxe-allowed"));
        mc.getSingleplayerServer().execute(()->{try{
            var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            mc.getSingleplayerServer().getCommands().performPrefixedCommand(mc.getSingleplayerServer().createCommandSourceStack(),"gamerule naturalRegeneration false");
            player.setGameMode(GameType.SURVIVAL);player.getAbilities().invulnerable=true;player.onUpdateAbilities();
            player.removeAllEffects();player.setAbsorptionAmount(0);
            player.setHealth(name.equals("low-health")?10:20);player.getFoodData().setFoodLevel(20);
            player.getInventory().clearContent();player.getInventory().selected=0;
            player.getInventory().setItem(0,new ItemStack(name.startsWith("pickaxe")?Items.DIAMOND_PICKAXE:Items.DIAMOND_SWORD));
            boolean onlyNormal=name.equals("normal")||name.equals("enchanted-fallback")||name.equals("fallback-disabled");
            boolean onlyEnchanted=name.equals("enchanted")||name.equals("normal-fallback");
            if(!onlyEnchanted)player.getInventory().setItem(1,new ItemStack(Items.GOLDEN_APPLE,3));
            if(!onlyNormal)player.getInventory().setItem(2,new ItemStack(Items.ENCHANTED_GOLDEN_APPLE,3));
            player.getInventory().setItem(3,new ItemStack(Items.TOTEM_OF_UNDYING,2));
            player.getInventory().offhand.set(0,new ItemStack(Items.TOTEM_OF_UNDYING));
            player.inventoryMenu.broadcastChanges();ready=true;
        }catch(Throwable e){serverError=e;}});
    }
    void tick(Minecraft mc){if(phase==99)return;try{
        if(++ticks>1300)throw new AssertionError("native offhand timeout");
        if(serverError!=null)throw new AssertionError("server fixture",serverError);
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null)return;
        if(module==null){for(var m:GuizhouWare.moduleManager.getModules())if(m.isEnabled())m.disable();mc.setScreen(null);mc.options.pauseOnLostFocus=false;
            module=GuizhouWare.moduleManager.getModuleByClass(AutoTotemModule.class);next(mc);return;}
        if(!ready)return;
        String name=cases[index];age++;
        if(phase==0){if(age<35||!mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING))return;
            mc.player.getInventory().selected=0;mc.gameMode.ensureHasSentCarriedItem();
            check(mc.player.getMainHandItem().is(name.startsWith("pickaxe")?Items.DIAMOND_PICKAXE:Items.DIAMOND_SWORD),"staged native client weapon selected");
            if(name.equals("low-health"))check(mc.player.getHealth()==10&&mc.player.getAbsorptionAmount()==0,"low-health fixture remains at staged health");
            module.enable();mc.options.keyUse.setDown(true);
            var method=Minecraft.class.getDeclaredMethod("startUseItem");method.setAccessible(true);method.invoke(mc);
            phase=1;age=0;return;}
        boolean denied=Set.of("fallback-disabled","pickaxe-disabled","low-health").contains(name);
        if(denied&&age>=45){check(!mealObserved&&!serverUsing&&serverTotem,"denied use preserves server totem");finish(mc,"server denied unsafe/unsupported use");return;}
        if(serverUsing&&!released){mealObserved=true;
            var expected=name.equals("enchanted")||name.equals("normal-fallback")?Items.ENCHANTED_GOLDEN_APPLE:Items.GOLDEN_APPLE;
            check(mc.player.getUseItem().is(expected),"correct apple selected on actual offhand use");
            if(name.equals("release-key")&&age>=12&&!released){mc.options.keyUse.setDown(false);released=true;age=0;}
        }
        if(name.equals("release-key")&&released&&age>15){check(!serverUsing&&serverTotem,"key release restores server totem");finish(mc,"meal interrupted and server totem restored");return;}
        int initial=Set.of("normal","enchanted","normal-fallback","enchanted-fallback","fallback-disabled").contains(name)?3:6;
        if(!denied&&!name.equals("release-key")&&apples<initial){check(mealObserved,"observed real offhand channel");finish(mc,"server consumed selected apple");return;}
        check(age<150,"use failed to complete");
    }catch(Throwable e){e.printStackTrace();try{Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"),e.toString());}catch(Exception ignored){}phase=99;mc.options.keyUse.setDown(false);if(module!=null)module.disable();mc.stop();}}
    void finish(Minecraft mc,String evidence)throws Exception{results.add(Map.of("case",cases[index],"passed",true,"evidence",evidence));System.out.println("NATIVE_OFFHAND_PASSED "+cases[index]);next(mc);}
}
