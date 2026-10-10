package dev.guizhou.features.modules.qinghua;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.BlockUtil;
import dev.guizhou.features.modules.qinghua.util.MSTimer;
import dev.guizhou.features.settings.Setting;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of eternal AntiRegear — mine/drop shulker logic unchanged.
 */
public class AntiRegearModule extends Module {
    public static AntiRegearModule INSTANCE;

    private static final long SAFE_TTL_MS = 250L;
    private static final long USER_OPEN_TTL_MS = 2500L;
    private final Long2LongOpenHashMap safeUntil = new Long2LongOpenHashMap();

    public enum Mode { MINE, DROP }

    private final Setting<Mode> mode = mode("Mode", Mode.MINE)
            .described("MINE: packet mine the shulker.  DROP: open and drop items.");
    private final Setting<Double> safeRange = num("SafeRange", 2.0, 0.0, 8.0)
            .described("Blocks within this range are considered your own.");
    private final Setting<Double> range = num("Range", 5.0, 0.0, 8.0)
            .described("Max distance to search for enemy shulkers.");
    private final Setting<Boolean> checkSelf = bool("CheckSelf", true)
            .described("Skip shulkers you recently placed yourself.");
    private final Setting<Integer> dropDelay = num("DropDelay", 40, 0, 200)
            .described("Ticks between each drop operation (DROP mode).")
            .setVisibility(v -> mode.getValue() == Mode.DROP);
    private final Setting<Integer> packetAmount = num("Packets", 1, 1, 27)
            .described("Items dropped per operation (DROP mode).")
            .setVisibility(v -> mode.getValue() == Mode.DROP);

    private final MSTimer timer = new MSTimer();
    private BlockPos openPos = null;
    private int currentWindowId = -1;
    private int currentSlot = 0;
    private final List<BlockPos> completed = new ArrayList<>();
    private final List<ItemStack> containerItems = new ArrayList<>();
    public final List<BlockPos> safe = new ArrayList<>();

    public AntiRegearModule() {
        super("AntiRegear", "your home's shulker box killer....", Category.WORLD);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.openPos = null;
        this.currentWindowId = -1;
        this.currentSlot = 0;
        this.completed.clear();
        this.safeUntil.clear();
        this.containerItems.clear();
        this.timer.reset();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;
        this.cleanupSafe();
        if (this.mode.getValue() == Mode.DROP) {
            this.tickDropMode();
        } else {
            this.tickMineMode();
        }
    }

    private void tickMineMode() {
        ShulkerBoxBlockEntity target = this.getBlock();
        if (target != null && mc.gameMode != null) {
            mc.gameMode.startDestroyBlock(target.getBlockPos(), BlockUtil.getClickSide(target.getBlockPos()));
        }
    }

    private void tickDropMode() {
        if (this.shouldPauseForUserOpen()) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            this.resetDropState();
            return;
        }
        if (this.openPos == null && this.currentWindowId == -1) {
            ShulkerBoxBlockEntity target = this.getBlock();
            if (target != null) {
                this.openPos = target.getBlockPos();
                BlockUtil.clickBlock(this.openPos, BlockUtil.getClickSide(this.openPos), false, false);
                this.timer.reset();
            }
        } else {
            this.tickDrop();
        }
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.getPacket() instanceof ClientboundOpenScreenPacket packet) {
            if (this.mode.getValue() == Mode.DROP && this.openPos != null) {
                event.cancel();
                this.currentWindowId = packet.getContainerId();
                this.currentSlot = 0;
                this.containerItems.clear();
                this.timer.reset();
            }
        } else if (event.getPacket() instanceof ClientboundContainerSetContentPacket packet
                && this.mode.getValue() == Mode.DROP
                && this.currentWindowId != -1
                && packet.containerId() == this.currentWindowId) {
            event.cancel();
            this.containerItems.clear();
            List<ItemStack> stacks = packet.items();
            for (int i = 0; i < stacks.size() && i < 27; i++) {
                this.containerItems.add(stacks.get(i));
            }
            this.timer.reset();
        }
    }

    private boolean shouldPauseForUserOpen() {
        if (!mc.options.keyUse.isDown()) return false;
        if (!(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) return false;
        BlockPos pos = bhr.getBlockPos();
        if (!(mc.level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock)) return false;
        this.safeUntil.put(pos.asLong(), System.currentTimeMillis() + USER_OPEN_TTL_MS);
        if (this.openPos != null && this.openPos.equals(pos)) {
            this.resetDropState();
        }
        return true;
    }

    private void resetDropState() {
        this.openPos = null;
        this.currentWindowId = -1;
        this.currentSlot = 0;
        this.containerItems.clear();
        this.timer.reset();
    }

    private void tickDrop() {
        if (this.openPos == null) {
            this.currentWindowId = -1;
            this.currentSlot = 0;
            this.containerItems.clear();
            return;
        }
        if (!(mc.level.getBlockState(this.openPos).getBlock() instanceof ShulkerBoxBlock)) {
            this.resetDropState();
            return;
        }
        if (this.currentWindowId == -1) {
            if (this.timer.hasPassed(1500L)) {
                this.openPos = null;
                this.timer.reset();
            }
            return;
        }
        if (this.containerItems.isEmpty()) {
            if (this.timer.hasPassed(1500L) && mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundContainerClosePacket(this.currentWindowId));
                this.openPos = null;
                this.currentWindowId = -1;
                this.currentSlot = 0;
                this.timer.reset();
            }
            return;
        }
        if (!this.timer.hasPassTime(this.dropDelay.getValue())) return;
        if (mc.getConnection() == null) return;

        for (int i = 0; i < this.packetAmount.getValue(); i++) {
            if (this.currentSlot > 26) {
                mc.getConnection().send(new ServerboundContainerClosePacket(this.currentWindowId));
                this.completed.add(this.openPos);
                this.openPos = null;
                this.currentWindowId = -1;
                this.currentSlot = 0;
                this.containerItems.clear();
                break;
            }
            // 1.21.11 click packet uses HashedStack; keep throw-slot intent via gameMode.
            if (mc.gameMode != null) {
                mc.gameMode.handleInventoryMouseClick(
                        this.currentWindowId, this.currentSlot, 1, ClickType.THROW, mc.player);
            }
            this.currentSlot++;
        }
        this.timer.reset();
    }

    private ShulkerBoxBlockEntity getBlock() {
        long now = System.currentTimeMillis();
        for (BlockEntity entity : BlockUtil.getTileEntities()) {
            if (!(entity instanceof ShulkerBoxBlockEntity shulker)) continue;
            BlockPos pos = shulker.getBlockPos();
            double distance = Mth.sqrt((float) mc.player.distanceToSqr(pos.getCenter()));
            if (distance > this.range.getValue()) continue;
            if (distance <= this.safeRange.getValue()) continue;
            if (this.checkSelf.getValue() && this.safeUntil.getOrDefault(pos.asLong(), 0L) > now) continue;
            if (this.completed.contains(pos) || this.safe.contains(pos)) continue;
            return shulker;
        }
        return null;
    }

    public void markSafe(BlockPos pos) {
        this.safeUntil.put(pos.asLong(), System.currentTimeMillis() + SAFE_TTL_MS);
    }

    private void cleanupSafe() {
        long now = System.currentTimeMillis();
        for (long posLong : this.safeUntil.keySet().toLongArray()) {
            if (this.safeUntil.get(posLong) <= now
                    || !(mc.level.getBlockState(BlockPos.of(posLong)).getBlock() instanceof ShulkerBoxBlock)) {
                this.safeUntil.remove(posLong);
            }
        }
    }
}
