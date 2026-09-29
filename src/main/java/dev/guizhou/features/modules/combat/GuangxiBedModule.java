package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.AnchorDamageUtil;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.combat.BedCandidateWatch;
import dev.guizhou.util.combat.BedSequence;
import dev.guizhou.util.combat.BlastStageDiagnostics;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.player.EatUtil;
import dev.guizhou.mixin.client.ClientLevelAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import java.util.HashMap;
import java.util.Map;
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
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

/** Bed aura with state-confirmed placement/detonation and power-five blast estimates. */
public class GuangxiBedModule extends Module {

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private final Setting<Double>  bedRange  = num("Range", 5.0, 1.0, 6.0);
    private final Setting<Double>  minDamage = num("MinDamage", 12.0, 0.0, 36.0);
    private final Setting<Double>  maxSelf   = num("MaxSelfDamage", 12.0, 0.0, 36.0);
    private final Setting<Integer> cooldown  = num("Cooldown", 500, 0, 5000);
    private final Setting<Boolean> rotate    = bool("Rotate", true);
    private final Setting<Integer> confirmTicks = num("ConfirmTicks", 8, 2, 40);
    private final Setting<Integer> retries = num("Retries", 2, 1, 5);
    private final Setting<Boolean> assumeBestArmor = bool("AssumeBestArmor", false);
    /** Patience for a candidate whose actions keep failing locally (never submitted). */
    private final Setting<Integer> localTimeout = num("LocalTimeoutTicks", 40, 10, 200);
    private final Setting<Boolean> debug = bool("Debug", false);

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
    private final Setting<Color>   renderColor = color("Color", 224, 138, 108, 60);

    private final Timer timer = new Timer();

    private BlockPos pendingFoot = null;
    private BlockPos pendingHead = null;
    private float desiredYaw = 0f;
    private Player pendingTarget;
    private final BedSequence sequence = new BedSequence();
    private final BedCandidateWatch candidateWatch = new BedCandidateWatch();
    private final BlastStageDiagnostics stageDiagnostics = new BlastStageDiagnostics("GuangxiBed");

    /** Per-second scan funnel counters, printed when Debug is on. */
    private int sTargets, sDimRej, sCellRej, sRangeRej, sDmgRej, sSelfRej, sTpFeetRej;
    private long sWindow;

    private void dbgFlush() {
        long now = System.currentTimeMillis();
        if (sWindow == 0) { sWindow = now; return; }
        if (now - sWindow < 1000) return;
        if (debug.getValue() && (sTargets > 0 || sDimRej > 0 || sCellRej > 0 || sRangeRej > 0
                || sDmgRej > 0 || sSelfRej > 0 || sTpFeetRej > 0)) {
            GuizhouWare.LOGGER.info(
                    "[BedDiag] scan: targets={} dim={} cell={} range={} dmg={} self={} tpFeet={}",
                    sTargets, sDimRej, sCellRej, sRangeRej, sDmgRej, sSelfRej, sTpFeetRej);
        }
        sTargets = sDimRej = sCellRej = sRangeRej = sDmgRej = sSelfRej = sTpFeetRej = 0;
        sWindow = now;
    }
    private ClientLevel sessionLevel;
    private LocalPlayer sessionPlayer;
    private BlockPos renderHead;

    private BlockPos renderPos = null;
    private long renderStartMs = 0L;

    public GuangxiBedModule() {
        super("GuangxiBed", "Nether/End bed aura with confirmed placement, safe damage checks and automatic crafting.", Category.COMBAT);
        tpRange.setVisibility(v -> tpMode.getValue());
        bedReserve.setVisibility(v -> autoCraft.getValue());
        refillSlot.setVisibility(v -> refill.getValue());
    }

    @Override
    public void onEnable() { resetSession(); }

    @Override
    public void onDisable() { resetSession(); }

    private void resetSession() {
        clearPending();
        candidateWatch.reset();
        renderPos = null;
        renderHead = null;
        craftPhase = 0;
        craftTimeoutTick = 0;
        craftContainerId = -1;
        sessionLevel = mc.level;
        sessionPlayer = mc.player;
        timer.reset();
    }

    private void clearPending() {
        stageDiagnostics.reset();
        pendingFoot = null;
        pendingHead = null;
        pendingTarget = null;
        sequence.reset();
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) { dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce(); return; }
        dbgFlush();
        if (sessionLevel != mc.level || sessionPlayer != mc.player) resetSession();
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        if (craftPhase != 0) { tickAutoCraft(); return; }
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) return;
        if (mc.player.isShiftKeyDown() || EatUtil.shouldDefer()) return;
        if (refill.getValue()) tickRefill();
        if (tpsSync.getValue() && GuizhouWare.tpsCounterService.isRubberbandRecent()) return;
        if (pendingFoot != null) { tickPending(); return; }
        if (!timer.passedMs(bedCooldownMs())) return;
        if (autoCraft.getValue() && tickAutoCraft()) return;

        long timer = ClientDiagnostics.startTimer();
        BedSpot spot;
        try { spot = findBestSpot(tpMode.getValue() || rotate.getValue()
                ? null : facingFromYaw(GuizhouWare.rotationManager.getServerYaw())); }
        finally { ClientDiagnostics.endTimer("combat.GuangxiBed.scan", timer); }
        if (spot == null) return;
        pendingFoot = spot.foot();
        pendingHead = spot.head();
        pendingTarget = spot.target();
        desiredYaw = cardinalYaw(spot.dir());
        candidateWatch.purge(mc.player.tickCount);
        candidateWatch.start(mc.player.tickCount, localTimeout.getValue());
        tickPending(spot);
    }

    private void tickPending() { tickPending(null); }

    private void tickPending(BedSpot fresh) {
        boolean existing = matchingBed();
        BedSequence.Action action = sequence.next(existing,
                mc.level.getBlockState(pendingHead).getBlock() instanceof BedBlock,
                existing || spotStillValid(pendingFoot, pendingHead), mc.player.tickCount,
                confirmTicks.getValue(), retries.getValue());
        stageDiagnostics.decision(action.name(), mc.player.tickCount, confirmTicks.getValue());
        if (action == BedSequence.Action.WAIT) return;
        // BedSequence only bounds actions that were submitted; a candidate whose local actions
        // keep failing never reaches it. Drop it after LocalTimeoutTicks and avoid the spot
        // briefly, so the scan re-picks somewhere usable instead of waiting forever.
        if (candidateWatch.expired(mc.player.tickCount)) {
            stageDiagnostics.blocked("localTimeout");
            candidateWatch.giveUp(pendingFoot.asLong(), mc.player.tickCount, localTimeout.getValue() + 20);
            clearPending();
            timer.reset();
            return;
        }
        if (action == BedSequence.Action.COMPLETE || action == BedSequence.Action.ABORT) {
            clearPending();
            timer.reset();
            return;
        }
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        if (pendingTarget == null || pendingTarget.isRemoved() || pendingTarget.isDeadOrDying()
                || targets != null && !targets.isValidPlayerTarget(pendingTarget)
                || !mc.level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pendingHead).explodes()
                || !(fresh == null ? safeDamage(pendingTarget, pendingFoot, pendingHead)
                        : fresh.damage() > 0f && fresh.damage() >= minDamage.getValue() && safeSelfDamage(fresh.selfDamage()))) {
            stageDiagnostics.blocked("targetOrSafety");
            clearPending();
            return;
        }
        if (action == BedSequence.Action.DETONATE) {
            if (detonate()) {
                sequence.sent(action, mc.player.tickCount);
                candidateWatch.progress(mc.player.tickCount);
                stageDiagnostics.submitted(action.name(), mc.player.tickCount);
            } else if (tpMode.getValue() && safeTpFeet(pendingFoot, pendingHead) == null) {
                stageDiagnostics.blocked("noTpFeet");
            } else stageDiagnostics.blocked("actionDeferred");
        } else {
            if (!hasBed()) { stageDiagnostics.blocked("material"); clearPending(); return; }
            if (placeBed()) {
                sequence.sent(action, mc.player.tickCount);
                candidateWatch.progress(mc.player.tickCount);
                stageDiagnostics.submitted(action.name(), mc.player.tickCount);
            } else stageDiagnostics.blocked("actionDeferred");
        }
    }

    private boolean matchingBed() {
        BlockState foot = mc.level.getBlockState(pendingFoot);
        BlockState head = mc.level.getBlockState(pendingHead);
        return foot.getBlock() instanceof BedBlock && head.getBlock() == foot.getBlock()
                && foot.getValue(BedBlock.PART) == BedPart.FOOT && head.getValue(BedBlock.PART) == BedPart.HEAD
                && foot.getValue(BedBlock.FACING) == head.getValue(BedBlock.FACING)
                && pendingFoot.relative(foot.getValue(BedBlock.FACING)).equals(pendingHead);
    }

    private boolean hasBed() {
        if (mc.player.getMainHandItem().is(ItemTags.BEDS) || mc.player.getOffhandItem().is(ItemTags.BEDS)) return true;
        if (autoSwap.getValue() == AutoSwapMode.NONE) return false;
        return InventoryUtil.find(stack -> stack.is(ItemTags.BEDS), bedSwapMode().scope()).found();
    }

    private SwapMode bedSwapMode() {
        return autoSwap.getValue() == AutoSwapMode.INVENTORY ? SwapMode.ALTSILENT : SwapMode.SILENT;
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
    private int craftContainerId = -1;

    /** @return true when this tick was consumed by the crafting flow. */
    private boolean tickAutoCraft() {
        switch (craftPhase) {
            case 1 -> {
                if (mc.screen instanceof CraftingScreen
                        && mc.player.containerMenu instanceof CraftingMenu menu) {
                    craftContainerId = menu.containerId;
                    craftPhase = craftBed(menu) ? 2 : 0;
                    craftTimeoutTick = mc.player.tickCount + 10;
                } else if (mc.player.tickCount > craftTimeoutTick) {
                    craftPhase = 0;
                }
                return true;
            }
            case 2 -> {
                if (!(mc.screen instanceof CraftingScreen) || mc.player.containerMenu.containerId != craftContainerId) craftPhase = 0;
                else if (mc.player.tickCount > craftTimeoutTick) {
                    mc.player.closeContainer();
                    craftPhase = 0;
                }
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
        if (!GuizhouWare.placementManager.sendUseItemOnDirect(tableHit, InteractionHand.MAIN_HAND,
                mc.player.getEyePosition(), 4.0, 4.0)) return false;
        craftPhase = 1;
        craftTimeoutTick = mc.player.tickCount + 40;
        return true;
    }

    private boolean craftBed(CraftingMenu menu) {
        if (!menu.getCarried().isEmpty()) return false;
        for (int slot = 1; slot <= 9; slot++) {
            if (!menu.getSlot(slot).getItem().isEmpty()) return false;
        }
        int woolItems = findMaterialSlot(s -> s.is(ItemTags.WOOL), 3);
        int plankItems = findMaterialSlot(s -> s.is(ItemTags.PLANKS), 3);
        int woolSlot = woolItems == -1 ? -1 : menuSlotFor(menu, woolItems);
        int plankSlot = plankItems == -1 ? -1 : menuSlotFor(menu, plankItems);
        if (woolSlot == -1 || plankSlot == -1) {
            mc.player.closeContainer();
            return false;
        }

        int cursorOrigin = -1;
        try {
            cursorOrigin = woolSlot;
            click(menu, woolSlot, ClickType.PICKUP);
            clickOne(menu, 1);
            clickOne(menu, 2);
            clickOne(menu, 3);
            click(menu, woolSlot, ClickType.PICKUP);
            cursorOrigin = -1;

            cursorOrigin = plankSlot;
            click(menu, plankSlot, ClickType.PICKUP);
            clickOne(menu, 4);
            clickOne(menu, 5);
            clickOne(menu, 6);
            click(menu, plankSlot, ClickType.PICKUP);
            cursorOrigin = -1;

            click(menu, 0, ClickType.QUICK_MOVE);
            return true;
        } finally {
            // A thrown/interrupted click must not leave the remaining material on the cursor.
            // Only touch the menu this attempt owns, never a screen opened by the user later.
            if (mc.player.containerMenu == menu) {
                try {
                    if (cursorOrigin >= 0 && !menu.getCarried().isEmpty())
                        click(menu, cursorOrigin, ClickType.PICKUP);
                } finally {
                    mc.player.closeContainer();
                }
            }
        }
    }

    private void clickOne(CraftingMenu menu, int slot) {
        // Right-click deposits one item; left-click would move the whole stack into the first cell.
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 1, ClickType.PICKUP, mc.player);
    }

    private void click(CraftingMenu menu, int slot, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, type, mc.player);
    }

    private int countBeds() {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(ItemTags.BEDS)) n += s.getCount();
        }
        if (mc.player.getOffhandItem().is(ItemTags.BEDS)) n += mc.player.getOffhandItem().getCount();
        return n;
    }

    /** Inventory slot index of a stack matching the filter, or -1. */
    private int findMaterialSlot(java.util.function.Predicate<ItemStack> filter, int minCount) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
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
                    if (mc.level.getBlockState(cursor).is(Blocks.CRAFTING_TABLE)
                            && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(cursor)) <= 16.0) {
                        BlockHitResult hit = GuizhouWare.placementManager.prepareInteractionHit(cursor, null,
                                mc.player.getEyePosition(), 4.0, 4.0, false);
                        if (hit != null) return hit;
                    }
                }
            }
        }
        return null;
    }

    private record BedSpot(BlockPos foot, BlockPos head, Direction dir, Vec3 center, float damage, float selfDamage, Player target) {}

    private BedSpot findBestSpot(Direction restrict) {
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        double range = tpMode.getValue() ? tpRange.getValue() : bedRange.getValue() + AnchorDamageUtil.DIAMETER;
        Map<Long, Boolean> validCells = new HashMap<>();
        Map<Long, Float> selfDamage = new HashMap<>();
        AnchorDamageUtil.Target self = AnchorDamageUtil.Target.of(mc.player);
        boolean canPlace = hasBed();
        int scanTick = mc.player.tickCount;
        BedSpot best = null;
        for (Player target : mc.level.players()) {
            if (target == mc.player || target.isDeadOrDying()) continue;
            if (targets != null && !targets.isValidPlayerTarget(target)) continue;
            if (mc.player.distanceToSqr(target) > range * range) continue;
            sTargets++;
            AnchorDamageUtil.Target damageTarget = AnchorDamageUtil.Target.of(target, assumeBestArmor.getValue());
            BlockPos feet = target.blockPosition();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos foot = feet.offset(dx, 0, dz);
                    if (candidateWatch.avoided(foot.asLong(), scanTick)) { sCellRej++; continue; }
                    for (Direction dir : HORIZONTAL) {
                        BlockPos head = foot.relative(dir);
                        if (!mc.level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, head).explodes()) { sDimRej++; continue; }
                        BlockState footState = mc.level.getBlockState(foot);
                        BlockState headState = mc.level.getBlockState(head);
                        boolean existing = footState.getBlock() instanceof BedBlock
                                && footState.getValue(BedBlock.PART) == BedPart.FOOT
                                && footState.getValue(BedBlock.FACING) == dir
                                && headState.getBlock() == footState.getBlock()
                                && headState.getValue(BedBlock.PART) == BedPart.HEAD
                                && headState.getValue(BedBlock.FACING) == dir;
                        if (!existing && (!canPlace || restrict != null && dir != restrict)) { sCellRej++; continue; }
                        if (!existing && (!validCells.computeIfAbsent(foot.asLong(), k -> cellStillValid(foot))
                                || !validCells.computeIfAbsent(head.asLong(), k -> cellStillValid(head)))) { sCellRej++; continue; }
                        // Vanilla redirects the foot interaction to the head before exploding.
                        Vec3 center = Vec3.atCenterOf(head);
                        if (!tpMode.getValue() && (mc.player.getEyePosition().distanceToSqr(center) > bedRange.getValue() * bedRange.getValue()
                                || !existing && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(foot)) > bedRange.getValue() * bedRange.getValue())) { sRangeRej++; continue; }
                        float upper = AnchorDamageUtil.maxDamage(damageTarget, center);
                        if (upper < minDamage.getValue() || best != null && upper <= best.damage()) { sDmgRej++; continue; }
                        float damage = AnchorDamageUtil.damage(damageTarget, center, foot, head);
                        if (damage <= 0f || damage < minDamage.getValue() || best != null && damage <= best.damage()) { sDmgRej++; continue; }
                        // Empty candidates share the same terrain at a head; existing bed pairs need both halves removed.
                        float local = existing ? AnchorDamageUtil.damage(self, center, foot, head)
                                : selfDamage.computeIfAbsent(head.asLong(), k -> AnchorDamageUtil.damage(self, center, foot, head));
                        if (!safeSelfDamage(local)) { sSelfRej++; continue; }
                        if (tpMode.getValue() && safeTpFeet(foot, head) == null) { sTpFeetRej++; continue; }
                        best = new BedSpot(foot, head, dir, center, damage, local, target);
                    }
                }
            }
        }
        return best;
    }

    private boolean safeDamage(Player target, BlockPos foot, BlockPos head) {
        Vec3 center = Vec3.atCenterOf(head);
        float damage = AnchorDamageUtil.damage(AnchorDamageUtil.Target.of(target, assumeBestArmor.getValue()), center, foot, head);
        return damage > 0f && damage >= minDamage.getValue()
                && safeSelfDamage(AnchorDamageUtil.damage(AnchorDamageUtil.Target.of(mc.player), center, foot, head));
    }

    private boolean safeSelfDamage(float damage) {
        return Float.isFinite(damage) && damage >= 0 && damage <= maxSelf.getValue()
                && (damage <= 0f || damage + 1.5f < mc.player.getHealth() + mc.player.getAbsorptionAmount());
    }

    private boolean cellStillValid(BlockPos cell) {
        if (mc.level.isOutsideBuildHeight(cell) || !mc.level.getWorldBorder().isWithinBounds(cell)) return false;
        if (!mc.level.getBlockState(cell).isAir()) return false;
        BlockPos below = cell.below();
        BlockState belowState = mc.level.getBlockState(below);
        if (belowState.isAir() || belowState.getCollisionShape(mc.level, below).isEmpty()) return false;
        // Bed collision height is 9/16; a player standing above the bed need not block it.
        AABB bedBox = new AABB(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1,
                cell.getY() + 9.0 / 16.0, cell.getZ() + 1);
        for (Entity e : mc.level.getEntities(null, bedBox)) {
            if (e.blocksBuilding && !e.isRemoved() && !e.isSpectator()) return false;
        }
        return true;
    }

    private boolean spotStillValid(BlockPos foot, BlockPos head) {
        return cellStillValid(foot) && cellStillValid(head);
    }

    private boolean placeBed() {
        BlockPos floor = pendingFoot.below();
        BlockHitResult placeHit = GuizhouWare.placementManager.prepareInteractionHit(floor, Direction.UP,
                interactionEye(), interactionRange(), interactionRange(), false);
        if (placeHit == null) return false;
        if (!prepareAction(placeHit, true)) return false;
        AutoTotemModule.holdForCombat(AutoTotemModule.BED);
        boolean mainBed = mc.player.getMainHandItem().is(ItemTags.BEDS);
        boolean offBed = mc.player.getOffhandItem().is(ItemTags.BEDS);
        if (mainBed || offBed) {
            boolean sent = sendAtSpot(placeHit, offBed && !mainBed ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
            if (sent) noteRender();
            return sent;
        }
        if (autoSwap.getValue() == AutoSwapMode.NONE) return false;
        var result = InventoryUtil.find(stack -> stack.is(ItemTags.BEDS), bedSwapMode().scope());
        if (!result.found()) return false;
        boolean[] sent = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(result, bedSwapMode(), SwapPriority.CRYSTAL,
                () -> sent[0] = sendAtSpot(placeHit, result.hand(), true));
        if (swapped && sent[0]) noteRender();
        return swapped && sent[0];
    }

    private Vec3 tpDetonationFeet;

    private Vec3 safeTpFeet(BlockPos foot, BlockPos head) {
        Vec3 center = Vec3.atCenterOf(head);
        var self = AnchorDamageUtil.Target.of(mc.player);
        return dev.guizhou.util.combat.TeleportBlastPosition.find(center, new AABB(head), 6.0,
                feet -> safeSelfDamage(AnchorDamageUtil.damage(self.at(feet), center, foot, head)));
    }

    private boolean detonate() {
        if (tpMode.getValue()) {
            tpDetonationFeet = safeTpFeet(pendingFoot, pendingHead);
            if (tpDetonationFeet == null) return false;
        }
        try { return detonateAtSpot(); }
        finally { tpDetonationFeet = null; }
    }

    private boolean detonateAtSpot() {
        BlockHitResult hit = GuizhouWare.placementManager.prepareInteractionHit(pendingHead, null,
                interactionEye(), interactionRange(), interactionRange(), false);
        if (hit == null) return false;
        if (!prepareAction(hit, false)) return false;
        boolean sent = sendAtSpot(hit, InteractionHand.MAIN_HAND, false);
        if (sent) noteRender();
        return sent;
    }

    private boolean prepareAction(BlockHitResult hit, boolean placing) {
        if (tpMode.getValue()) return mc.player.distanceToSqr(pendingTarget) <= tpRange.getValue() * tpRange.getValue();
        if (mc.player.getEyePosition().distanceToSqr(hit.getLocation()) > bedRange.getValue() * bedRange.getValue()) return false;
        if (!rotate.getValue()) return !placing || facingFromYaw(GuizhouWare.rotationManager.getServerYaw()) == facingFromYaw(desiredYaw);
        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), hit.getLocation());
        return GuizhouWare.rotationManager.trySubmit(new RotationRequest("GuangxiBed", 70,
                placing ? desiredYaw : angles[0], angles[1], RotationRequest.Mode.SILENT));
    }

    private Vec3 interactionFeet() {
        if (tpDetonationFeet != null) return tpDetonationFeet;
        return new Vec3(pendingFoot.getX() + 0.5, pendingFoot.getY() + 2.0, pendingFoot.getZ() + 0.5);
    }

    private Vec3 interactionEye() {
        return tpMode.getValue() ? interactionFeet().add(0, mc.player.getEyeHeight(), 0) : mc.player.getEyePosition();
    }

    private double interactionRange() { return tpMode.getValue() ? 6.0 : bedRange.getValue(); }

    private boolean sendAtSpot(BlockHitResult hit, InteractionHand hand, boolean placing) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        java.util.function.BooleanSupplier action = () -> GuizhouWare.placementManager.sendUseItemOnDirect(
                hit, hand, interactionEye(), interactionRange(), interactionRange());
        if (!tpMode.getValue()) return action.getAsBoolean();
        Vec3 feet = interactionFeet();
        if (!placing && !safeSelfDamage(AnchorDamageUtil.damage(AnchorDamageUtil.Target.of(mc.player).at(feet),
                Vec3.atCenterOf(pendingHead), pendingFoot, pendingHead))) return false;
        var connection = mc.player.connection;
        var restore = new ServerboundMovePlayerPacket.PosRot(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                GuizhouWare.rotationManager.getServerYaw(), GuizhouWare.rotationManager.getServerPitch(),
                mc.player.onGround(), mc.player.horizontalCollision);
        float[] look = rotate.getValue() ? MathUtil.calcAngle(interactionEye(), hit.getLocation())
                : new float[]{GuizhouWare.rotationManager.getServerYaw(), GuizhouWare.rotationManager.getServerPitch()};
        return dev.guizhou.util.InteractionTransaction.run(
                () -> sendFakeMove(interactionFeet(), placing ? desiredYaw : look[0], look[1]),
                action, () -> connection.send(restore));
    }

    private void noteRender() {
        renderPos = pendingFoot;
        renderHead = pendingHead;
        renderStartMs = System.currentTimeMillis();
    }

    private boolean sendFakeMove(Vec3 pos, float yaw, float pitch) {
        return PacketSendUtil.send(new ServerboundMovePlayerPacket.PosRot(
                pos.x, pos.y, pos.z, yaw, pitch, false, false));
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

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || renderPos == null || nullCheck()) return;
        if (System.currentTimeMillis() - renderStartMs > 1000L) {
            renderPos = null;
            return;
        }
        AABB box = renderBox();
        RenderUtil.drawBoxFilled(event.getMatrix(), box, renderColor.getValue());
        RenderUtil.drawBox(event.getMatrix(), box, renderColor.getValue(), 1.5f);
    }

    private AABB renderBox() {
        // A bed is two horizontal cells but only 9/16 block tall.
        return new AABB(renderPos.getX(), renderPos.getY(), renderPos.getZ(),
                renderPos.getX() + 1.0, renderPos.getY() + 9.0 / 16.0, renderPos.getZ() + 1.0).expandTowards(
                renderHead != null ? renderHead.getX() - renderPos.getX() : 0, 0,
                renderHead != null ? renderHead.getZ() - renderPos.getZ() : 0);
    }
}
