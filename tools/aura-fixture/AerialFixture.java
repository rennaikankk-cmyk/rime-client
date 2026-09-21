package aurafixture;

import com.mojang.authlib.GameProfile;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.elytra.*;
import dev.guizhou.util.traits.Util;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;
import java.util.*;

/** Development-only packet, geometry and weapon lifecycle integration. */
public final class AerialFixture implements FabricClientGameTest, Util {
    private GuangxiElytraChaseModule air;
    private RemotePlayer target, other;
    private int checks;
    private int attackPolicy;
    private boolean rejectRotation, replaceRotation, rejectSlot, replaceSlot, rejectUse;

    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode creative @a");
            world.getClientWorld().waitForChunksRender();
            context.runOnClient(client -> {
                air=GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraChaseModule.class);
                option("AutoControlElytra",false); option("AutoWeapon",false); option("AdaptiveCombat",false);
                option("Rotate",true); option("YawStep",180f); option("OnlyWhenNoWasd",false);
                client.player.setPos(0.5,128,0.5); client.player.setYRot(0); client.player.setXRot(0);
                client.player.setOnGround(false); client.player.fallDistance=4;
                client.player.getInventory().setItem(0,new ItemStack(Items.MACE));
                client.player.getInventory().setSelectedSlot(0);
                target=target(230001,"AirTarget",new Vec3(0.5,128,2.5));
                other=target(230002,"OtherTarget",new Vec3(2.5,128,0.5));
                air.enable();
                set("trackedPlayer",client.player); set("trackedLevel",client.level); set("target",target); set("lastTarget",target);
                EVENT_BUS.register(this);
                var controller=new ElytraWeaponController();
                try {
                    PacketSendUtil.send(new ServerboundSetCarriedItemPacket(0));
                    replaceSlot=true;
                    PacketSendUtil.send(new ServerboundSetCarriedItemPacket(2));
                    check(GuizhouWare.swapManager.serverSlot()==1,"slot tracking follows actual replacement");
                    PacketSendUtil.send(new ServerboundSetCarriedItemPacket(0));
                    check(attack(),"ordinary unsupported flying target can be attacked");
                    check(feedback().pending(target.getId(),client.player.tickCount),"accepted smash arms feedback");
                    attackPolicy=1;
                    check(!attack(),"cancelled attack cannot report success");
                    check(feedback().pending(target.getId(),client.player.tickCount),"cancelled attempt preserves previous feedback");
                    PacketSendUtil.send(ServerboundInteractPacket.createInteractionPacket(target,false,InteractionHand.MAIN_HAND));
                    check(feedback().pending(target.getId(),client.player.tickCount),"nonattack interaction preserves feedback");
                    attackPolicy=2;
                    check(attack(),"matching replacement attack is accepted");
                    attackPolicy=3;
                    check(!attack(),"different target replacement is not the planned strike");
                    check(!feedback().pending(target.getId(),client.player.tickCount),"real intervening attack invalidates old attribution");
                    attackPolicy=4;
                    check(!attack(),"interaction replacement cannot become an attack");
                    rejectRotation=true;
                    check(!attack(),"cancelled aim blocks attack");
                    rejectRotation=false; replaceRotation=true;
                    check(!attack(),"wrong-angle replacement blocks attack");
                    option("Rotate",false);
                    PacketSendUtil.send(new ServerboundMovePlayerPacket.Rot(180,0,false,false));
                    check(!attack(),"manual aim facing away cannot attack");
                    PacketSendUtil.send(new ServerboundMovePlayerPacket.Rot(0,0,false,false));
                    check(attack(),"manual aim toward target can attack");
                    option("Rotate",true);
                    GuizhouWare.rotationManager.trySubmit(new RotationRequest("aerial.fixture.safety",90,90,0,RotationRequest.Mode.MOTION,true,true));
                    check(!attack(),"flight safety rotation outranks combat");
                    GuizhouWare.rotationManager.cancel("aerial.fixture.safety");
                    target.setPos(5.5,128,0.5);
                    target.getInterpolation().interpolateTo(new Vec3(0.5,128,2.5),0,0);
                    client.level.setBlock(new BlockPos(2,129,0),Blocks.STONE.defaultBlockState(),3);
                    check(attack(),"received visible position wins over occluded rendered position");
                    client.level.setBlock(new BlockPos(2,129,0),Blocks.AIR.defaultBlockState(),3);
                    client.level.setBlock(new BlockPos(0,128,1),Blocks.STONE.defaultBlockState(),3);
                    client.level.setBlock(new BlockPos(0,129,1),Blocks.STONE.defaultBlockState(),3);
                    check(!attack(),"received occluded position wins over visible rendered position");
                    client.level.setBlock(new BlockPos(0,128,1),Blocks.AIR.defaultBlockState(),3);
                    client.level.setBlock(new BlockPos(0,129,1),Blocks.AIR.defaultBlockState(),3);
                    target.getInterpolation().cancel(); target.setPos(0.5,128,2.5);
                    target.setOnGround(true); target.setDeltaMovement(Vec3.ZERO);
                    call("classifyTarget");
                    check(!(boolean)get("targetGrounded"),"unsupported ground flag cannot force ground pursuit");
                    client.level.setBlock(new BlockPos(0,129,2),Blocks.STONE.defaultBlockState(),3);
                    call("classifyTarget");
                    check(!(boolean)get("targetGrounded"),"upper body collision cannot count as foot support");
                    client.level.setBlock(new BlockPos(0,129,2),Blocks.AIR.defaultBlockState(),3);
                    target.setOnGround(false);
                    client.level.setBlock(new BlockPos(0,127,2),Blocks.STONE.defaultBlockState(),3);
                    call("classifyTarget");
                    check((boolean)get("targetGrounded"),"supported stationary target recognized without ground flag");
                    target.startFallFlying(); call("classifyTarget");
                    check(!(boolean)get("targetGrounded"),"observed glide is not mistaken for landing");
                    target.stopFallFlying();
                    client.level.setBlock(new BlockPos(0,127,2),Blocks.AIR.defaultBlockState(),3);
                    client.player.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));
                    client.player.getInventory().setItem(1,new ItemStack(Items.MACE));
                    client.player.getInventory().setSelectedSlot(1);
                    rejectSlot=true;
                    PacketSendUtil.send(new ServerboundSetCarriedItemPacket(1));
                    check(GuizhouWare.swapManager.serverSlot()==0,"cancelled slot retains last submitted selection");
                    check(!attack(),"local mace selection alone cannot attack");
                    check(!controller.update(false,true,false),"controller waits for slot submission");
                    rejectSlot=false; client.player.tickCount++;
                    check(controller.update(false,true,false),"controller retries refused selection next tick");
                    check(GuizhouWare.swapManager.serverSlot()==1,"retry synchronizes selected weapon");
                    controller.release();
                    client.player.getInventory().setItem(2,new ItemStack(Items.DIAMOND_SPEAR));
                    client.player.getInventory().setSelectedSlot(2);
                    PacketSendUtil.send(new ServerboundSetCarriedItemPacket(2));
                    rejectUse=true;
                    check(!controller.update(true,false,true),"cancelled spear use does not report readiness");
                    check(!controller.isHoldingUse() && !client.player.isUsingItem(),"cancelled spear use drops speculative local charge");
                    rejectUse=false; client.player.tickCount++;
                    check(controller.update(true,false,true) && controller.isHoldingUse(),"accepted spear use acquires charge ownership");
                    controller.release();
                    check(!client.player.isUsingItem(),"release stops only the owned spear charge");
                    long phase=(long)get("combatPhase");
                    set("combatAction",AerialCombatPolicy.Action.EVADE);
                    set("defensiveHeading",new Vec3(10,0,0));
                    call("onPacketReceive",PacketEvent.Receive.class,new PacketEvent.Receive(new ClientboundPlayerPositionPacket(1,PositionMoveRotation.of(client.player),Set.of())));
                    check((long)get("combatPhase")>phase,"setback invalidates combat phase and pending feedback");
                    check(!feedback().pending(target.getId(),client.player.tickCount),"setback clears pending hit");
                    check(get("combatAction")==AerialCombatPolicy.Action.CHASE && ((Vec3)get("defensiveHeading")).lengthSqr()==0,
                            "setback clears stale evade direction");
                    client.player.getInventory().setSelectedSlot(1);
                    PacketSendUtil.send(new ServerboundSetCarriedItemPacket(1));
                    option("YawStep",30f);
                    PacketSendUtil.send(new ServerboundMovePlayerPacket.Rot(180,0,false,false));
                    check(!attack() && GuizhouWare.rotationManager.hasRequest("ElytraChase_attack"),
                            "stepped mace aim waits before attacking");
                    set("combatPhase",(long)get("combatPhase")+1);
                    rotationTick();
                    check(!GuizhouWare.rotationManager.hasRequest("ElytraChase_attack"),
                            "phase change removes pending mace aim");
                    check(!attack() && GuizhouWare.rotationManager.hasRequest("ElytraChase_attack"),
                            "new phase can request stepped aim again");
                    client.player.getInventory().setSelectedSlot(2);
                    rotationTick();
                    check(!GuizhouWare.rotationManager.hasRequest("ElytraChase_attack"),
                            "switching to spear removes pending mace aim");
                } finally {
                    controller.release(); air.disable(); EVENT_BUS.unregister(this);
                    GuizhouWare.rotationManager.cancel("aerial.fixture.safety");
                    client.level.removeEntity(target.getId(),Entity.RemovalReason.DISCARDED);
                    client.level.removeEntity(other.getId(),Entity.RemovalReason.DISCARDED);
                }
            });
        }
        System.out.println("AERIAL_FIXTURE_PASS checks="+checks);
    }
    private boolean attack() {
        set("lastAttackTick",Integer.MIN_VALUE/2);
        mc.player.fallDistance=4;
        GuizhouWare.rotationManager.resetSilentTick();
        return (boolean)call("attack",Entity.class,target);
    }
    private CombatHitWindow feedback() { return (CombatHitWindow)get("maceFeedback"); }
    private void rotationTick() {
        try {
            var type=dev.guizhou.event.impl.entity.player.PreTickEvent.class;
            var method=GuizhouWare.rotationManager.getClass().getDeclaredMethod("onTickBoundary",type);
            method.setAccessible(true);method.invoke(GuizhouWare.rotationManager,new dev.guizhou.event.impl.entity.player.PreTickEvent());
        } catch(Exception e) { throw new RuntimeException(e); }
    }
    private RemotePlayer target(int id,String name,Vec3 pos) {
        var p=new RemotePlayer(mc.level,new GameProfile(UUID.randomUUID(),name));p.setId(id);p.setPos(pos);mc.level.addEntity(p);return p;
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private void option(String n,Object v) { ((Setting)air.getSettingByName(n)).setValue(v); }
    private Object get(String n) { try {var f=air.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(air);}catch(Exception e){throw new RuntimeException(e);} }
    private void set(String n,Object v) { try {var f=air.getClass().getDeclaredField(n);f.setAccessible(true);f.set(air,v);}catch(Exception e){throw new RuntimeException(e);} }
    private Object call(String n) { try {var m=air.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(air);}catch(Exception e){throw new RuntimeException(e);} }
    private Object call(String n,Class<?> type,Object arg) { try {var m=air.getClass().getDeclaredMethod(n,type);m.setAccessible(true);return m.invoke(air,arg);}catch(Exception e){throw new RuntimeException(e);} }
    @Subscribe(priority=-1000) private void send(PacketEvent.Send event) {
        if (event.getPacket() instanceof ServerboundSetCarriedItemPacket) {
            if (rejectSlot) event.cancel();
            else if (replaceSlot) { replaceSlot=false; PacketSendUtil.forward(event,new ServerboundSetCarriedItemPacket(1)); }
        }
        if (event.getPacket() instanceof ServerboundUseItemPacket && rejectUse) event.cancel();
        if (event.getPacket() instanceof ServerboundMovePlayerPacket m && m.hasRotation()) {
            if (rejectRotation) event.cancel();
            else if (replaceRotation) { replaceRotation=false; PacketSendUtil.forward(event,new ServerboundMovePlayerPacket.Rot(180,0,false,false)); }
        }
        if (event.getPacket() instanceof ServerboundInteractPacket && attackPolicy!=0) {
            int mode=attackPolicy;attackPolicy=0;
            if(mode==1)event.cancel();
            else PacketSendUtil.forward(event,mode==4?ServerboundInteractPacket.createInteractionPacket(target,false,InteractionHand.MAIN_HAND)
                    :ServerboundInteractPacket.createAttackPacket(mode==2?target:other,false));
        }
    }
    private void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
}
