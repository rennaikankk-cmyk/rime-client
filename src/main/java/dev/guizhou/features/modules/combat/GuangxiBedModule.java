package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.DamageUtil;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import dev.guizhou.util.models.Timer;
import dev.guizhou.util.player.ChatUtil;
import dev.guizhou.util.render.RenderUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

/**
 * Bed aura for the Nether/End: silently swaps to a bed, places it next to the target
 * (orientation pinned via a silent rotation, since beds align to the player's facing),
 * then detonates it with a second use packet in the same tick — the server processes the
 * packets in order, so the bed already exists when the detonate click arrives.
 */
public class GuangxiBedModule extends Module {

    private static final double BED_RADIUS = 10.0; // explosion power 5 -> damage radius 2*power
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private final Setting<Double>  bedRange  = num("Range", 5.0, 1.0, 6.0);
    private final Setting<Double>  minDamage = num("MinDamage", 12.0, 0.0, 36.0);
    private final Setting<Double>  maxSelf   = num("MaxSelfDamage", 12.0, 0.0, 36.0);
    private final Setting<Integer> cooldown  = num("Cooldown", 500, 0, 5000);
    private final Setting<Boolean> rotate    = bool("Rotate", true);

    private enum AutoSwapMode { NONE, SILENT, INVENTORY }
    private final Setting<AutoSwapMode> autoSwap = mode("AutoSwap", AutoSwapMode.SILENT);

    /** Long-range mode: desync move sandwich carries the fake position AND the bed-axis yaw,
     *  so no rotation manager involvement is needed at all. */
    private final Setting<Boolean> tpMode  = bool("TpMode", false);
    private final Setting<Double>  tpRange = num("TpRange", 200.0, 16.0, 512.0);

    /** Replenishes beds by packet-crafting at a nearby crafting table when the count drops. */
    private final Setting<Boolean> autoCraft = bool("AutoCraft", false);
    private final Setting<Integer> bedReserve = num("BedReserve", 2, 1, 36);

    /** Swaps a bed from the main inventory into the chosen hotbar slot when it runs dry (Mio refill). */
    private final Setting<Boolean>  refill     = bool("Refill", false);
    private final Setting<Integer>  refillSlot = num("RefillSlot", 1, 1, 9);

    /** Stretches the cooldown by measured TPS and pauses placements right after a rubberband. */
    private final Setting<Boolean> tpsSync = bool("TpsSync", true);

    private final Setting<Boolean> render      = bool("Render", true);
    private final Setting<Color>   renderColor = color("Color", 255, 60, 60, 60);

    private final Timer timer = new Timer();

    private BlockPos pendingFoot = null;
    private BlockPos pendingHead = null;
    private float desiredYaw = 0f;

    private BlockPos renderPos = null;
    private long renderStartMs = 0L;

    public GuangxiBedModule() {
        super("GuangxiBed", "Nether/End bed aura: silent bed swap, place on the target, instant detonate.", Category.COMBAT);
        tpRange.setVisibility(v -> tpMode.getValue());
        bedReserve.setVisibility(v -> autoCraft.getValue());
        refillSlot.setVisibility(v -> refill.getValue());
    }

    @Override
    public void onDisable() {
        pendingFoot = null;
        pendingHead = null;
        renderPos = null;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        if (mc.level.dimension() != Level.NETHER && mc.level.dimension() != Level.END) {
            pendingFoot = null;
            pendingHead = null;
            return;
        }
        if (refill.getValue()) tickRefill();
        if (tpsSync.getValue() && GuizhouWare.tpsCounterService.isRubberbandRecent()) return;
        if (!timer.passedMs(bedCooldownMs())) return;

        if (autoCraft.getValue() && tickAutoCraft()) return;

        boolean mainBed = mc.player.getMainHandItem().is(ItemTags.BEDS);
        boolean offBed = mc.player.getOffhandItem().is(ItemTags.BEDS);
        if (!mainBed && !offBed && autoSwap.getValue() != AutoSwapMode.NONE) {
            var scope = autoSwap.getValue() == AutoSwapMode.INVENTORY
                    ? InventoryUtil.FULL_SCOPE : InventoryUtil.HOTBAR_SCOPE;
            if (!InventoryUtil.find(stack -> stack.is(ItemTags.BEDS), scope).found()) {
                ChatUtil.sendPersistent("guangxibed:status", Component.literal(
                        "GuangxiBed: no bed in " + (autoSwap.getValue() == AutoSwapMode.INVENTORY ? "inventory" : "hotbar")));
                return;
            }
        }

        Player target = findTarget();
        if (target == null) return;

        boolean useTp = tpMode.getValue();
        BedSpot spot = findSpot(target,
                useTp || rotate.getValue() ? null : facingFromYaw(GuizhouWare.rotationManager.getServerYaw()));
        if (spot == null) return;

        pendingFoot = spot.foot();
        pendingHead = spot.head();
        desiredYaw = cardinalYaw(spot.dir());

        // TpMode: the axis yaw rides inside the fake move packet — skip the rotation manager.
        if (!useTp && rotate.getValue()) {
            float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), spot.center());
            GuizhouWare.rotationManager.submit(new RotationRequest(
                    "GuangxiBed", 70, desiredYaw, angles[1], RotationRequest.Mode.SILENT));
            // A SILENT rotation performs synchronously (move packet + server yaw update now),
            // so the alignment check below usually passes on this very tick — no extra tick.
            if (Math.abs(Mth.wrapDegrees(GuizhouWare.rotationManager.getServerYaw() - desiredYaw)) > 40f) {
                return; // rotation lost the tick's budget — pending survives, retried next tick
            }
        }
        execute();
    }

    // ------------------------------------------------------------ auto craft

    private long bedCooldownMs() {
        if (!tpsSync.getValue()) return cooldown.getValue();
        return (long) (cooldown.getValue() * GuizhouWare.tpsCounterService.delayScale());
    }

    /** Keeps the configured hotbar slot holding a bed, pulling from the main inventory. */
    private void tickRefill() {
        int hotbarIdx = refillSlot.getValue() - 1;
        var inv = mc.player.getInventory();
        if (inv.getItem(hotbarIdx).is(ItemTags.BEDS)) return;
        for (int i = 9; i < 36; i++) {
            if (!inv.getItem(i).is(ItemTags.BEDS)) continue;
            // Player inventory menu: main inventory keeps its indices, button = hotbar slot.
            mc.gameMode.handleInventoryMouseClick(
                    mc.player.inventoryMenu.containerId, i, hotbarIdx, ClickType.SWAP, mc.player);
            return;
        }
    }

    private int craftPhase = 0; // 0 idle, 1 waiting for the table screen, 2 closing
    private int craftTimeoutTick = 0;

    /** @return true when this tick was consumed by the crafting flow. */
    private boolean tickAutoCraft() {
        switch (craftPhase) {
            case 1 -> {
                if (mc.screen instanceof CraftingScreen
                        && mc.player.containerMenu instanceof CraftingMenu menu) {
                    craftBed(menu);
                    craftPhase = 2;
                    craftTimeoutTick = mc.player.tickCount + 10;
                } else if (mc.player.tickCount > craftTimeoutTick) {
                    craftPhase = 0;
                }
                return true;
            }
            case 2 -> {
                if (!(mc.screen instanceof CraftingScreen)) craftPhase = 0;
                return true;
            }
            default -> { }
        }

        if (countBeds() >= bedReserve.getValue()) return false;

        if (findMaterialSlot(s -> s.is(ItemTags.WOOL), 3) == -1) {
            ChatUtil.sendPersistent("guangxibed:craft", Component.literal("GuangxiBed: need a wool stack of 3+"));
            return false;
        }
        if (findMaterialSlot(s -> s.is(ItemTags.PLANKS), 3) == -1) {
            ChatUtil.sendPersistent("guangxibed:craft", Component.literal("GuangxiBed: need a plank stack of 3+"));
            return false;
        }
        BlockHitResult tableHit = findCraftingTable();
        if (tableHit == null) {
            ChatUtil.sendPersistent("guangxibed:craft", Component.literal("GuangxiBed: no crafting table within 4 blocks"));
            return false;
        }

        // Opens the table (screen flashes ~2 frames); the clicks land next tick.
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, tableHit);
        craftPhase = 1;
        craftTimeoutTick = mc.player.tickCount + 40;
        return true;
    }

    private void craftBed(CraftingMenu menu) {
        int woolItems = findMaterialSlot(s -> s.is(ItemTags.WOOL), 3);
        int plankItems = findMaterialSlot(s -> s.is(ItemTags.PLANKS), 3);
        int woolSlot = woolItems == -1 ? -1 : menuSlotFor(menu, woolItems);
        int plankSlot = plankItems == -1 ? -1 : menuSlotFor(menu, plankItems);
        if (woolSlot == -1 || plankSlot == -1) {
            mc.player.closeContainer();
            return;
        }

        click(menu, woolSlot, ClickType.PICKUP);   // cursor: wool stack
        click(menu, 1, ClickType.PICKUP);
        click(menu, 2, ClickType.PICKUP);
        click(menu, 3, ClickType.PICKUP);          // one wool each into the top grid row
        click(menu, woolSlot, ClickType.PICKUP);   // return remainder

        click(menu, plankSlot, ClickType.PICKUP);  // cursor: planks
        click(menu, 4, ClickType.PICKUP);
        click(menu, 5, ClickType.PICKUP);
        click(menu, 6, ClickType.PICKUP);          // one plank each into the middle row
        click(menu, plankSlot, ClickType.PICKUP);  // return remainder

        click(menu, 0, ClickType.QUICK_MOVE);      // craft bed -> inventory
        mc.player.closeContainer();
    }

    private void click(CraftingMenu menu, int slot, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, type, mc.player);
    }

    private int countBeds() {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(ItemTags.BEDS)) n += s.getCount();
        }
        if (mc.player.getOffhandItem().is(ItemTags.BEDS)) n += mc.player.getOffhandItem().getCount();
        return n;
    }

    /** Inventory slot index of a stack matching the filter, or -1. */
    private int findMaterialSlot(java.util.function.Predicate<ItemStack> filter, int minCount) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (filter.test(s) && s.getCount() >= minCount) return i;
        }
        return -1;
    }

    /** Menu-slot index for an Inventory.items index, resolved through the live menu. */
    private int menuSlotFor(CraftingMenu menu, int itemsIndex) {
        for (net.minecraft.world.inventory.Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == itemsIndex) {
                return slot.index;
            }
        }
        return -1;
    }

    private BlockHitResult findCraftingTable() {
        BlockPos p = mc.player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    cursor.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    if (mc.level.getBlockState(cursor).is(Blocks.CRAFTING_TABLE)) {
                        return new BlockHitResult(Vec3.atCenterOf(cursor), Direction.UP, cursor.immutable(), false);
                    }
                }
            }
        }
        return null;
    }

    private Player findTarget() {
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        double range = tpMode.getValue() ? tpRange.getValue() : bedRange.getValue() + 2.0;
        double rangeSq = range * range;
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(range))) {
            if (!(e instanceof Player p) || p.isDeadOrDying()) continue;
            if (targets != null && !targets.isValidPlayerTarget(p)) continue;
            double d = mc.player.distanceToSqr(p);
            if (d > rangeSq) continue;
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private record BedSpot(BlockPos foot, BlockPos head, Direction dir, Vec3 center, float damage) {}

    /** Two horizontally adjacent air cells at the target's feet level with solid floor under both.
     *  {@code restrict} limits the bed axis (used when rotations are off). */
    private BedSpot findSpot(Player target, Direction restrict) {
        BlockPos feet = target.blockPosition();
        BedSpot best = null;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                for (Direction dir : HORIZONTAL) {
                    if (restrict != null && dir != restrict) continue;
                    BlockPos other = cell.relative(dir);
                    if (!cellStillValid(cell) || !cellStillValid(other)) continue;

                    Vec3 center = new Vec3(
                            (cell.getX() + other.getX()) / 2.0 + 0.5,
                            cell.getY() + 0.5,
                            (cell.getZ() + other.getZ()) / 2.0 + 0.5);
                    // The spot must be within our own placement reach (local mode only —
                    // TpMode reaches it through the desync fake position).
                    if (!tpMode.getValue()
                            && mc.player.getEyePosition().distanceTo(center) > bedRange.getValue()) continue;

                    float dmg = estimateDamage(target, center);
                    if (dmg < minDamage.getValue()) continue;
                    if (DamageUtil.crystalMaxSelfDamage(center) > maxSelf.getValue()) continue;

                    if (best == null || dmg > best.damage()) {
                        // Vanilla orients the bed to the player's facing: foot at the clicked
                        // cell, head one further in the facing direction.
                        best = new BedSpot(cell, other, dir, center, dmg);
                    }
                }
            }
        }
        return best;
    }

    private boolean cellStillValid(BlockPos cell) {
        if (!mc.level.getBlockState(cell).isAir()) return false;
        BlockPos below = cell.below();
        BlockState belowState = mc.level.getBlockState(below);
        if (belowState.isAir() || belowState.getCollisionShape(mc.level, below).isEmpty()) return false;
        // A live crystal occupying the cell makes the server reject the bed placement —
        // skip it (players standing in the cell are fine; beds place through them).
        AABB bedBox = new AABB(cell);
        for (Entity e : mc.level.getEntities(null, bedBox)) {
            if (e instanceof EndCrystal) return false;
        }
        return true;
    }

    private boolean spotStillValid(BlockPos foot, BlockPos head) {
        return cellStillValid(foot) && cellStillValid(head);
    }

    private void execute() {
        BlockPos floor = pendingFoot.below();
        Vec3 floorTop = new Vec3(floor.getX() + 0.5, floor.getY() + 0.95, floor.getZ() + 0.5); // 0.95: cursor 严格在面内（网易拒 1.0 边界）
        BlockHitResult placeHit = new BlockHitResult(floorTop, Direction.UP, floor, false);

        Vec3 bedCenter = new Vec3(pendingFoot.getX() + 0.5, pendingFoot.getY() + 0.5, pendingFoot.getZ() + 0.5);
        BlockHitResult boomHit = new BlockHitResult(bedCenter, Direction.UP, pendingFoot, false);

        boolean mainBed = mc.player.getMainHandItem().is(ItemTags.BEDS);
        boolean offBed = mc.player.getOffhandItem().is(ItemTags.BEDS);
        InteractionHand hand = offBed && !mainBed ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;

        Runnable fire = () -> {
            if (tpMode.getValue()) {
                Vec3 fake = new Vec3(floor.getX() + 0.5, floor.getY() + 4.0, floor.getZ() + 0.5);
                sendFakeMove(fake, desiredYaw);
                GuizhouWare.placementManager.sendUseItemOnDirect(placeHit, hand);
                // Same-tick detonate: packet order guarantees the bed exists server-side here.
                GuizhouWare.placementManager.sendUseItemOnDirect(boomHit, hand);
                sendRealMove();
            } else {
                GuizhouWare.placementManager.sendUseItemOnDirect(placeHit, hand);
                GuizhouWare.placementManager.sendUseItemOnDirect(boomHit, hand);
            }
        };

        boolean fired;
        if (mainBed || offBed) {
            fire.run(); // already holding a bed: zero swap packets
            fired = true;
        } else if (autoSwap.getValue() == AutoSwapMode.NONE) {
            fired = false;
        } else if (autoSwap.getValue() == AutoSwapMode.INVENTORY) {
            fired = GuizhouWare.swapManager.withSwap(
                    stack -> stack.is(ItemTags.BEDS), SwapMode.ALTSILENT, SwapPriority.CRYSTAL, fire);
        } else {
            fired = GuizhouWare.swapManager.withSwap(
                    stack -> stack.is(ItemTags.BEDS), SwapMode.SILENT, SwapPriority.CRYSTAL, fire);
        }

        if (fired) {
            timer.reset();
            renderPos = pendingFoot;
            renderStartMs = System.currentTimeMillis();
        }
        pendingFoot = null;
        pendingHead = null;
    }

    private void sendFakeMove(Vec3 pos, float yaw) {
        mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                pos.x, pos.y, pos.z, yaw,
                GuizhouWare.rotationManager.getServerPitch(),
                false, false));
    }

    private void sendRealMove() {
        mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                GuizhouWare.rotationManager.getServerYaw(),
                GuizhouWare.rotationManager.getServerPitch(),
                mc.player.onGround(), mc.player.horizontalCollision));
    }

    /** Vanilla bed explosion damage (power 5) with armor reduction; prot skipped on purpose. */
    private float estimateDamage(LivingEntity target, Vec3 center) {
        double dist = target.position().distanceTo(center);
        if (dist > BED_RADIUS) return 0f;
        double impact = 1.0 - dist / BED_RADIUS;
        float dmg = (float) ((impact * impact + impact) / 2.0 * 7.0 * BED_RADIUS + 1.0);
        float armor = (float) target.getAttributeValue(Attributes.ARMOR);
        float toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float i = 2.0f + toughness / 4.0f;
        float j = Mth.clamp(armor - dmg / i, armor * 0.2f, 20.0f);
        return dmg * (1.0f - j / 25.0f);
    }

    private static float cardinalYaw(Direction dir) {
        return switch (dir) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }

    private static Direction facingFromYaw(float yaw) {
        float snapped = Math.round(Mth.wrapDegrees(yaw) / 90f) * 90f;
        if (snapped > 135f || snapped < -135f) return Direction.NORTH;
        if (snapped < -45f) return Direction.EAST;
        if (snapped > 45f) return Direction.WEST;
        return Direction.SOUTH;
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || renderPos == null || nullCheck()) return;
        if (System.currentTimeMillis() - renderStartMs > 1000L) {
            renderPos = null;
            return;
        }
        AABB box = new AABB(renderPos).expandTowards(
                pendingHead != null ? pendingHead.getX() - renderPos.getX() : 0, 0,
                pendingHead != null ? pendingHead.getZ() - renderPos.getZ() : 0);
        RenderUtil.drawBoxFilled(event.getMatrix(), box, renderColor.getValue());
        RenderUtil.drawBox(event.getMatrix(), box, renderColor.getValue(), 1.5f);
    }
}
