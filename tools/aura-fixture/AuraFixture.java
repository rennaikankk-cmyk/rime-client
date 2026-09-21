package aurafixture;

import com.mojang.authlib.GameProfile;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.combat.AutoSwordModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.*;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.combat.*;
import dev.guizhou.util.combat.AuraPolicy.*;
import dev.guizhou.util.traits.Util;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.lang.reflect.*;
import java.util.function.Consumer;

/** Development-only integration fixture. It is never included in a distributable JAR. */
public final class AuraFixture implements FabricClientGameTest, Util {
    private int checks, attacks, movements, outboundSeen;
    private int lastTarget;
    private boolean rejectAttack, rejectSlot, rejectOutbound;
    private AutoSwordModule aura;
    private final Consumer<Packet<?>> observe = p -> {
        if (p instanceof ServerboundMovePlayerPacket m && m.hasPosition()) movements++;
        if (p instanceof ServerboundInteractPacket a) a.dispatch(new ServerboundInteractPacket.Handler() {
            public void onInteraction(InteractionHand h) {}
            public void onInteraction(InteractionHand h,Vec3 v) {}
            public void onAttack() { attacks++; lastTarget = a.entityId; }
        });
    };
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode creative @a");
            world.getClientWorld().waitForChunksRender();
            context.runOnClient(client -> {
                check(HeartbeatManager.authorized(),"explicit development build is authorized");
                aura = GuizhouWare.moduleManager.getModuleByClass(AutoSwordModule.class);
                for (var module : GuizhouWare.moduleManager.getModules()) if (module != aura && module.isEnabled() && module.getName().equals("GuangxiCrystal")) module.disable();
                client.player.setPos(0.5,128,0.5);
                client.player.setYRot(0); client.player.setXRot(0);
                client.player.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));
                client.player.getInventory().setItem(1,new ItemStack(Items.DIAMOND_AXE));
                client.player.getInventory().setSelectedSlot(0);
                client.player.setOnGround(true);
                RemotePlayer a = target(220001,"First",new Vec3(0.5,128,2.5));
                RemotePlayer b = target(220002,"Second",new Vec3(2.5,128,0.5));
                EVENT_BUS.register(this);
                PacketSendUtil.addSubmissionListener(observe);
                try {
                    option("PauseScreen",false); option("CrystalPriority",false); option("TPS",AutoSwordModule.TpsMode.NONE);
                    option("WeaponMode",WeaponMode.REQUIRE); option("Mode",TargetMode.MULTI);
                    option("MaxTargets",2); option("Rotate",true); option("TP",false);
                    option("WallsRange",0.0); option("Range",3.0); option("CustomDelay",false);
                    option("CritMode",AutoSwordModule.CritMode.OFF); option("Weapon",Weapon.ALL);
                    option("SwapAction",SwapAction.SILENT); option("SwitchDelay",0); option("YawStep",180f);
                    aura.enable();
                    call("session");
                    check(aura.getDisplayName().equals("Aura"),"canonical module name");
                    correction();
                    check(!(boolean)field("tpBlocked"),"ordinary correction without recent TP does not disable TP");
                    ready(); tick();
                    check(attacks==1 && lastTarget==a.getId(),"first multi target attacked");
                    check(((AuraPolicy.Clock)field("clock")).elapsed()==0,"accepted attack resets clock");
                    ready(); tick();
                    check(attacks==2 && lastTarget==b.getId(),"next cooldown rotates to second target");
                    rejectAttack=true;
                    ready(); tick();
                    check(attacks==2 && ((AuraPolicy.Clock)field("clock")).elapsed()>=100,"cancelled attack leaves cooldown ready");
                    rejectAttack=false;
                    option("Rotate",false);
                    PacketSendUtil.send(new ServerboundMovePlayerPacket.Rot(180,0,false,false));
                    ready(); tick();
                    check(attacks==2,"Rotate off cannot hit behind submitted view");
                    option("Rotate",true);
                    option("WeaponMode",WeaponMode.SWAP); option("Weapon",Weapon.AXE);
                    rejectSlot=true;
                    ready(); tick();
                    check(attacks==2,"cancelled weapon selection prevents attack");
                    rejectSlot=false;
                    ready(); tick();
                    check(attacks==3 && client.player.getInventory().getSelectedSlot()==0,"silent axe attack restores hand");
                    check((int)field("submittedSlot")==0,"silent selection restoration actually submitted");
                    option("WeaponMode",WeaponMode.REQUIRE); option("Weapon",Weapon.ALL);
                    client.level.removeEntity(b.getId(),Entity.RemovalReason.DISCARDED);
                    a.setPos(0.5,128,8.5);
                    ready(); tick();
                    check(attacks==3,"remote target out of ordinary reach");
                    option("TP",true);
                    int before = movements;
                    ready(); tick();
                    check(attacks==4,"TP attack after full route");
                    check(movements>=before+4,"TP sends outbound and return segments");
                    check(((Vec3)field("submittedPosition")).distanceToSqr(client.player.position())<1e-8,"TP returns submitted position");
                    check(field("pendingReturn")==null,"successful return clears pending cleanup");
                    rejectOutbound=true;
                    ready(); tick();
                    check(attacks==4,"cancelled outbound prevents TP attack");
                    check(field("pendingReturn")==null,"partial outbound still restores origin");
                    rejectOutbound=false;
                    correction();
                    check((boolean)field("tpBlocked"),"correction after recent TP blocks further TP");
                    ready(); tick();
                    check(attacks==4,"setback prevents another distant attack");
                    option("TP",false);
                    a.setPos(0.5,128,2.5);
                    client.level.setBlock(new BlockPos(0,128,1),Blocks.STONE.defaultBlockState(),3);
                    ready(); tick();
                    check(attacks==5,"partially exposed target uses visible upper aim point");
                    client.level.setBlock(new BlockPos(0,129,1),Blocks.STONE.defaultBlockState(),3);
                    ready(); tick();
                    check(attacks==5,"full-height wall occludes normal attack");
                    option("WallsRange",3.0);
                    ready(); tick();
                    check(attacks==6,"explicit wall range permits close wall attack");
                    client.level.setBlock(new BlockPos(0,129,1),Blocks.AIR.defaultBlockState(),3);
                    client.level.setBlock(new BlockPos(0,128,1),Blocks.AIR.defaultBlockState(),3);
                    double swordSpeed = AuraWeaponAttributes.value(client.player,new ItemStack(Items.DIAMOND_SWORD),Attributes.ATTACK_SPEED);
                    double axeSpeed = AuraWeaponAttributes.value(client.player,new ItemStack(Items.DIAMOND_AXE),Attributes.ATTACK_SPEED);
                    check(Math.abs((swordSpeed-axeSpeed)-0.6)<1e-6,"replacement weapon speed reflects real item attributes");
                    client.player.getInventory().setItem(2,new ItemStack(Items.MACE));
                    client.player.getInventory().setSelectedSlot(2);
                    client.player.fallDistance=4; client.player.setOnGround(false);
                    option("Rotate",false);
                    PacketSendUtil.send(new ServerboundMovePlayerPacket.Rot(180,0,false,false));
                    ready();
                    check(!aura.isMaceAttackReady(),"mace cannot claim crystal priority while facing away with Rotate off");
                    option("Rotate",true);
                    check(aura.isMaceAttackReady(),"ready reachable mace can claim attack priority");
                    option("SwitchDelay",3);
                    check(!aura.isMaceAttackReady(),"unsettled weapon plan cannot claim mace priority");
                    aura.disable();
                    check(!GuizhouWare.rotationManager.hasRequest("Aura"),"disable clears rotation ownership");
                } finally {
                    aura.disable(); EVENT_BUS.unregister(this); PacketSendUtil.removeSubmissionListener(observe);
                    client.level.removeEntity(a.getId(),Entity.RemovalReason.DISCARDED);
                    client.level.removeEntity(b.getId(),Entity.RemovalReason.DISCARDED);
                }
            });
        }
        System.out.println("AURA_FIXTURE_PASS checks="+checks+" attacks="+attacks+" movementPackets="+movements);
    }
    private RemotePlayer target(int id,String name,Vec3 pos) {
        RemotePlayer p = new RemotePlayer(mc.level,new GameProfile(UUID.randomUUID(),name));
        p.setId(id); p.setPos(pos); mc.level.addEntity(p); return p;
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private void option(String name,Object value) { ((Setting)aura.getSettingByName(name)).setValue(value); }
    private Object field(String name) { try { Field f=AutoSwordModule.class.getDeclaredField(name); f.setAccessible(true); return f.get(aura); } catch(Exception e){throw new RuntimeException(e);} }
    private Object call(String name) { try { Method m=AutoSwordModule.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(aura); } catch(Exception e){throw new RuntimeException(e);} }
    private void correction() {
        try {
            Method method=AutoSwordModule.class.getDeclaredMethod("onReceive",PacketEvent.Receive.class); method.setAccessible(true);
            method.invoke(aura,new PacketEvent.Receive(new ClientboundPlayerPositionPacket(1,net.minecraft.world.entity.PositionMoveRotation.of(mc.player),Set.of())));
        } catch(Exception e){throw new RuntimeException(e);}
    }
    private void ready() { ((AuraPolicy.Clock)field("clock")).reset(mc.player.tickCount,100); }
    private void tick() {
        GuizhouWare.rotationManager.resetSilentTick();
        try { Method m=AutoSwordModule.class.getDeclaredMethod("onPreTick",PreTickEvent.class);m.setAccessible(true);m.invoke(aura,new PreTickEvent()); }
        catch(Exception e){throw new RuntimeException(e);}
    }
    @Subscribe(priority=-1000) private void send(PacketEvent.Send event) {
        if (rejectAttack && event.getPacket() instanceof ServerboundInteractPacket) event.cancel();
        if (rejectSlot && event.getPacket() instanceof ServerboundSetCarriedItemPacket) event.cancel();
        if (rejectOutbound && event.getPacket() instanceof ServerboundMovePlayerPacket m && m.hasPosition()
                && new Vec3(m.getX(0),m.getY(0),m.getZ(0)).distanceToSqr(mc.player.position())>0.01 && ++outboundSeen==2) event.cancel();
    }
    private void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message+" [attacks="+attacks+", last="+lastTarget+"]"); }
}
