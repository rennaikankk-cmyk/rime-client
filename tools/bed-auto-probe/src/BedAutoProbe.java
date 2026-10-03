package probe;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.combat.GuangxiBedModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.PacketSendUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.core.Direction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.util.*;

/** Full automatic target scan/tick flow; never injects pending positions or calls private actions. */
public final class BedAutoProbe implements ClientModInitializer {
    int scene=Integer.getInteger("bed.start",0), stage, wait, total, uses;
    volatile boolean ready, observedBed;
    volatile float health;
    volatile float explosionDamage;
    volatile Throwable error;
    Zombie serverTarget;
    RemotePlayer target;
    final List<Object> rows = new ArrayList<>();
    final List<Object> interactions = new ArrayList<>();
    int sneakPackets;
    final String[] scenes = {"flat", "unsupported-head", "side-support", "chest-support"};
    GuangxiBedModule bed;
    @Override public void onInitializeClient() {
        PacketSendUtil.addSubmissionListener(p -> {
            if (stage != 2) return;
            if (p instanceof net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket input && input.getAction()==net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY) sneakPackets++;
            if (p instanceof ServerboundUseItemOnPacket use) {
                uses++;
                var hit=use.getHitResult(); var level=Minecraft.getInstance().level;
                interactions.add(Map.of("block",level.getBlockState(hit.getBlockPos()).toString(),
                        "pos",hit.getBlockPos().toShortString(),"face",hit.getDirection().name(),"hand",use.getHand().name()));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!ready || serverTarget == null) return;
            float current=serverTarget.getHealth();
            if(current<health && serverTarget.getLastDamageSource()!=null
                    && serverTarget.getLastDamageSource().is(net.minecraft.world.damagesource.DamageTypes.BAD_RESPAWN_POINT))
                explosionDamage+=health-current;
            health = current;
            var level = serverTarget.level();
            if(level.dimension()==Level.END) {
                for (Entity entity : ((net.minecraft.server.level.ServerLevel)level).getEntities(EntityType.ENDER_DRAGON, e -> true))
                    entity.discard();
            }
            for (BlockPos p : BlockPos.betweenClosed(-3,100,-3,3,103,3))
                if (level.getBlockState(p).getBlock() instanceof BedBlock) observedBed = true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    void setting(String name, Object value) { ((Setting)bed.getSettingByName(name)).setValue(value); }
    void tick(Minecraft mc) {
        if (stage == 99) return;
        try {
            if (++total > 3000) throw new AssertionError("fixture timeout");
            if (error != null) throw new AssertionError(error);
            if (mc.player == null || mc.getSingleplayerServer() == null) return;
            mc.options.pauseOnLostFocus = false;
            if (bed == null) {
                bed = GuizhouWare.moduleManager.getModuleByClass(GuangxiBedModule.class);
                for (var m : GuizhouWare.moduleManager.getModules())
                    if (m.isEnabled() && !m.getName().equals("Targets")) m.disable();
                setting("Debug",true); setting("TpsSync",false);
                mc.setScreen(null);
            }
            if (stage == 0) { setup(mc); stage=1; wait=0; return; }
            if (stage == 1) {
                if (!ready || mc.level.dimension() != dimension()) return;
                ++wait;
                if(scene==9 && wait==30) mc.getSingleplayerServer().execute(() -> {
                    // The first dragon-fight tick creates an exit fountain at the new arena's
                    // surface. Rebuild AFTER that initialization, before enabling the module.
                    var level=mc.getSingleplayerServer().getLevel(Level.END);
                    System.out.println("BED_FIXTURE_END_INITIAL_CENTER " + level.getBlockState(new BlockPos(0,101,0)));
                    for (BlockPos pos : BlockPos.betweenClosed(-7,100,-5,6,107,5))
                        level.setBlock(pos,pos.getY()==100 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(),3);
                    serverTarget.setHealth(1000); health=1000; explosionDamage=0;
                });
                if(wait<40) return;
                target = new RemotePlayer(mc.level,new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000bed123"),"BedFixture"));
                target.setId(900123); target.setPos(.5,101,.5); target.setHealth(20);
                if (scene != 14) mc.level.addEntity(target);
                mc.player.setYRot(-90); mc.player.setXRot(0);
                PacketSendUtil.send(new ServerboundMovePlayerPacket.Rot(-90,0,mc.player.onGround()));
                setting("Rotate",(scene & 1)==1);
                setting("VerticalScan",scene==6 || scene==7 ? 0 : 1);
                setting("MaxSelfDamage",scene==11 ? 0.0 : 12.0);
                setting("Range",scene==16 ? 4.0 : 5.0);
                setEnum("AutoSwap",scene==13 ? "INVENTORY" : "SILENT");
                bed.enable(); uses=0; sneakPackets=0; interactions.clear(); wait=0; stage=2; return;
            }
            if (stage == 2) {
                // Keep the selector proxy aligned with the stationary server-owned damage oracle.
                target.setPos(.5,101,.5); target.setDeltaMovement(Vec3.ZERO);
                if (++wait < 140 && (negative() || explosionDamage <= 1)) return;
                bed.disable();
                boolean success=negative() ? !observedBed && uses==0 : observedBed && explosionDamage > 1;
                var row=new LinkedHashMap<String,Object>();
                row.put("scene",name()); row.put("rotate",(scene&1)==1); row.put("placedOnServer",observedBed);
                row.put("targetDamage",explosionDamage); row.put("usePackets",uses); row.put("ticks",wait); row.put("success",success);
                row.put("dimension",mc.level.dimension().location().toString());
                row.put("driverPosition",mc.player.position().toString());
                row.put("driverHealth",mc.player.getHealth());
                row.put("driverFloor",mc.level.getBlockState(new BlockPos(-4,100,0)).toString());
                row.put("sneakPackets",sneakPackets); row.put("interactions",new ArrayList<>(interactions)); rows.add(row);
                System.out.println("BED_AUTO_CASE " + rows.getLast());
                mc.level.removeEntity(target.getId(),Entity.RemovalReason.DISCARDED); target=null;
                if (++scene < Integer.getInteger("bed.end",Boolean.getBoolean("bed.extended") ? 17 : 8)) { stage=0; return; }
                Files.writeString(mc.gameDirectory.toPath().resolve("results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));
                if (Boolean.getBoolean("bed.expect") && rows.stream().anyMatch(r -> !((Map<?,?>)r).get("success").equals(true)))
                    throw new AssertionError("One or more automatic placement/detonation scenes failed");
                Files.writeString(mc.gameDirectory.toPath().resolve("complete.txt"),rows.size()+" automatic scenarios finished");
                stage=99; mc.stop();
            }
        } catch(Throwable ex) {
            ex.printStackTrace();
            try { Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"),ex.toString()); } catch(Exception ignored) {}
            stage=99; mc.stop();
        }
    }
    boolean negative() { return scene==8 || scene==10 || scene==11 || scene==14 || scene==15; }
    net.minecraft.resources.ResourceKey<Level> dimension() { return scene==8 ? Level.OVERWORLD : scene==9 ? Level.END : Level.NETHER; }
    String name() { return scene<8 ? scenes[scene/2] : switch(scene) {
        case 8 -> "overworld-blocked"; case 9 -> "end"; case 10 -> "no-material"; case 11 -> "self-damage-blocked";
        case 12 -> "offhand"; case 13 -> "inventory-swap"; case 14 -> "no-target"; case 15 -> "occupied"; default -> "foot-only-reach"; }; }
    @SuppressWarnings({"rawtypes","unchecked"})
    void setEnum(String name,String value) {
        var s=bed.getSettingByName(name); setting(name,Enum.valueOf((Class)((Enum)s.getValue()).getDeclaringClass(),value));
    }
    void setup(Minecraft mc) {
        ready=false; observedBed=false; health=1000; explosionDamage=0; final int geometry=scene<8 ? scene/2 : 0;
        mc.getSingleplayerServer().execute(() -> { try {
            var server=mc.getSingleplayerServer(); var level=server.getLevel(dimension());
            var p=server.getPlayerList().getPlayer(mc.player.getUUID());
            if (serverTarget != null) serverTarget.discard();
            server.setDifficulty(Difficulty.NORMAL,true);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamerule doMobSpawning false");
            for (BlockPos pos : BlockPos.betweenClosed(-7,100,-5,6,107,5))
                level.setBlock(pos,geometry==0 && pos.getY()==100 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(),3);
            for(int x=-4;x<=-3;x++) level.setBlock(new BlockPos(x,100,0),Blocks.OBSIDIAN.defaultBlockState(),3);
            if(geometry==3) level.setBlock(new BlockPos(-5,100,0),Blocks.OBSIDIAN.defaultBlockState(),3);
            level.setBlock(new BlockPos(0,100,0),Blocks.OBSIDIAN.defaultBlockState(),3);
            if(geometry==1 || geometry==3) level.setBlock(new BlockPos(-1,101,0),
                    (geometry==3 ? Blocks.CHEST : Blocks.OBSIDIAN).defaultBlockState(),3);
            if(geometry==2) level.setBlock(new BlockPos(-2,102,0),Blocks.OBSIDIAN.defaultBlockState(),3);
            if(scene==15) for(BlockPos pos:BlockPos.betweenClosed(-3,101,-3,3,103,3))
                if(pos.getX()!=0 || pos.getZ()!=0) level.setBlock(pos,Blocks.OBSIDIAN.defaultBlockState(),3);
            if(scene==16) {
                level.setBlock(new BlockPos(0,102,0),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.EAST).setValue(BedBlock.PART,BedPart.FOOT),3);
                level.setBlock(new BlockPos(1,102,0),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.EAST).setValue(BedBlock.PART,BedPart.HEAD),3);
            }
            p.setGameMode(GameType.SURVIVAL); p.removeAllEffects(); p.setHealth(20); p.getFoodData().setFoodLevel(20);
            p.getInventory().clearContent(); p.getInventory().selected=0;
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));
            if(scene==12) p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.RED_BED,64));
            else if(scene!=10 && scene!=16) p.getInventory().setItem(scene==13 ? 10 : 3,new ItemStack(Items.RED_BED,64));
            p.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.NETHERITE_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.NETHERITE_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.NETHERITE_LEGGINGS));
            p.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.NETHERITE_BOOTS));
            p.teleportTo(level,geometry==3 ? -4.5 : -3.5,101,.5,Set.of(),-90,0); p.inventoryMenu.broadcastChanges();
            serverTarget=new Zombie(EntityType.ZOMBIE,level);serverTarget.setNoAi(true);serverTarget.setNoGravity(true);
            serverTarget.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);serverTarget.setHealth(1000);
            serverTarget.getAttribute(Attributes.ARMOR).setBaseValue(0);serverTarget.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            serverTarget.setPos(.5,101,.5);level.addFreshEntity(serverTarget);ready=true;
        } catch(Throwable ex) { error=ex; } });
    }
}
