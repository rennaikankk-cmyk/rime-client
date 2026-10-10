package dev.guizhou.features.modules.qinghua;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.BlockUtil;
import dev.guizhou.features.modules.qinghua.util.MSTimer;
import dev.guizhou.features.modules.qinghua.util.PredictUtility;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.inventory.InventoryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/**
 * Port of eternal AnchorTrap — place/trap path unchanged.
 * InvUtils → InventoryUtil; Friends → friendManager; render omitted (no Meteor ShapeMode).
 */
public class AnchorTrapModule extends Module {
    public static AnchorTrapModule INSTANCE;

    private final Setting<Integer> delay = num("Delay", 0, 0, 500)
            .described("Delay between placements in ms");
    private final MSTimer timer = new MSTimer();
    private final Setting<Double> placeRange = num("PlaceRange", 4.0, 1.0, 6.0)
            .described("Max placement reach");
    private final Setting<Integer> blocksPer = num("BlocksPer", 1, 1, 8)
            .described("Blocks placed per tick");
    public final Setting<Double> predictTicks = num("PredictTicks", 2.0, 0.0, 20.0)
            .described("Prediction ticks for target position");
    private final Setting<Double> range = num("Range", 5.0, 1.0, 8.0)
            .described("Target search range");
    private final Setting<Boolean> targetFriend = bool("TargetFriend", false)
            .described("Target friends");
    private final Setting<Boolean> airPlace = bool("AirPlace", false)
            .described("Place blocks in air without needing a supporting block");
    private final Setting<Boolean> extend = bool("Extend", true)
            .described("Extend trap to adjacent blocks");
    private final Setting<Boolean> head = bool("Head", true).described("Trap the head");
    private final Setting<Boolean> headExtend = bool("HeadExtend", false)
            .described("Extend head trap to surrounding blocks");
    private final Setting<Boolean> legs = bool("Legs", false).described("Trap the legs");
    private final Setting<Boolean> down = bool("Down", false).described("Place block below target");
    private final Setting<Boolean> breakCrystal = bool("Break", true)
            .described("Break crystals before placing");
    private final Setting<Boolean> usingPause = bool("UsingPause", true)
            .described("Pause while using items");

    final ArrayList<BlockPos> trapList = new ArrayList<>();
    final ArrayList<BlockPos> placeList = new ArrayList<>();
    public Player target;
    int progress = 0;

    public AnchorTrapModule() {
        super("AnchorTrap", "anchortrap.", Category.COMBAT);
        INSTANCE = this;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        this.trapList.clear();
        this.placeList.clear();
        this.progress = 0;
        this.target = null;
        if (mc.player == null || mc.level == null) return;
        if (this.usingPause.getValue() && mc.player.isUsingItem()) return;
        if (!this.timer.hasPassed(this.delay.getValue().intValue())) return;
        this.target = this.getNearestTarget();
        if (this.target != null) this.trapTarget(this.target);
    }

    private Player getNearestTarget() {
        double r = this.range.getValue();
        double closestDist = Double.MAX_VALUE;
        Player closest = null;
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (player.isDeadOrDying() || !player.isAlive() || player.getHealth() <= 0.0F) continue;
            if (!this.targetFriend.getValue()
                    && GuizhouWare.friendManager != null
                    && GuizhouWare.friendManager.isFriend(player)) {
                continue;
            }
            double dist = mc.player.distanceTo(player);
            if (dist <= r && dist < closestDist) {
                closestDist = dist;
                closest = player;
            }
        }
        return closest;
    }

    private void trapTarget(Player target) {
        Vec3 playerPos = this.predictTicks.getValue() > 0.0
                ? PredictUtility.predictPosition(target, this.predictTicks.getValue().intValue())
                : target.position();
        this.doTrap(target, BlockPos.containing(playerPos), false);
    }

    private void doTrap(Player player, BlockPos pos, boolean isExtended) {
        if (pos == null) return;
        if (this.trapList.contains(pos)) return;
        this.trapList.add(pos);
        int headOffset = player.isVisuallyCrawling() ? 1 : 2;
        if (this.legs.getValue()) {
            for (Direction i : Direction.values()) {
                if (i != Direction.DOWN && i != Direction.UP) {
                    this.tryPlaceBlock(pos.relative(i));
                }
            }
        }
        if (this.headExtend.getValue() && !isExtended) {
            int[] offsets = new int[]{-1, 0, 1};
            for (int x : offsets) {
                for (int z : offsets) {
                    if (x != 0 || z != 0) {
                        BlockPos offsetPos = pos.offset(x, 0, z);
                        if (this.checkEntity(offsetPos)) {
                            this.tryPlaceBlock(offsetPos.above(headOffset));
                        }
                    }
                }
            }
        }
        if (!isExtended && this.head.getValue() && BlockUtil.clientCanPlace(pos.above(headOffset), this.breakCrystal.getValue())) {
            this.tryPlaceBlock(pos.above(headOffset));
        }
        if (!isExtended && this.down.getValue()) {
            this.tryPlaceBlock(pos.below());
        }
        if (this.extend.getValue()) {
            for (int x : new int[]{1, 0, -1}) {
                for (int zx : new int[]{1, 0, -1}) {
                    BlockPos offsetPos = pos.offset(x, 0, zx);
                    if (this.checkEntity(offsetPos)) {
                        this.doTrap(player, offsetPos, true);
                    }
                }
            }
        }
    }

    private boolean checkEntity(BlockPos pos) {
        if (mc.player.getBoundingBox().intersects(new AABB(pos))) return false;
        for (Player entity : mc.level.players()) {
            if (entity.getBoundingBox().intersects(new AABB(pos)) && entity.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private void tryPlaceBlock(BlockPos pos) {
        if (this.placeList.contains(pos)) return;
        if (this.airPlace.getValue()) {
            if (!BlockUtil.canReplace(pos)) return;
            if (BlockUtil.hasEntity(new AABB(pos), this.breakCrystal.getValue())) return;
        } else if (!BlockUtil.canPlace(pos, 6.0, this.breakCrystal.getValue())) {
            return;
        }
        if (this.progress >= this.blocksPer.getValue()) return;
        if (Mth.sqrt((float) mc.player.getEyePosition().distanceToSqr(pos.getCenter())) > this.placeRange.getValue()) {
            return;
        }
        int old = mc.player.getInventory().getSelectedSlot();
        int block = this.getAnchor();
        if (block == -1) return;
        this.placeList.add(pos);
        this.attackCrystal(pos);
        this.doSwap(block);
        if (this.airPlace.getValue()) {
            BlockUtil.airPlaceBlock(pos, false);
        } else {
            BlockUtil.placeBlock(pos, false);
        }
        this.doSwap(old);
        this.timer.reset();
        this.progress++;
    }

    private void attackCrystal(BlockPos pos) {
        if (!this.breakCrystal.getValue() || mc.getConnection() == null) return;
        AABB box = new AABB(pos);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof EndCrystal
                    && entity.getBoundingBox().intersects(box)
                    && entity.isAlive()
                    && (!this.usingPause.getValue() || !mc.player.isUsingItem())) {
                mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(entity, mc.player.isShiftKeyDown()));
                mc.player.swing(InteractionHand.MAIN_HAND);
                break;
            }
        }
    }

    private void doSwap(int slot) {
        if (slot >= 0 && slot <= 8) InventoryUtil.swap(slot);
    }

    private int getAnchor() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.RESPAWN_ANCHOR)) return i;
        }
        return -1;
    }

    @Override
    public String getDisplayInfo() {
        return this.target != null ? this.target.getName().getString() : null;
    }
}
