package dev.guizhou.features.modules.render;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.inventory.ItemUseTracker;
import dev.guizhou.util.lang.ClientLanguage;
import dev.guizhou.util.render.WorldLabel;
import dev.guizhou.util.world.ContainerHistoryStore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers what containers you actually opened, per server and dimension, and marks shulker
 * boxes you placed yourself. Ported from SlimefunHelper ChestHistory: the record is a snapshot
 * from the last time the container was observed, never live contents, and containers that were
 * never opened are never guessed at. Kit resupply reads this store, and resupply interference
 * skips your own boxes with it.
 *
 * <p>Open screens are associated with a world position through the block the player just used;
 * screens that appear without a fresh interaction (server GUIs) are not recorded.
 */
public class ChestHistoryModule extends Module {

    private static ChestHistoryModule instance;
    private static final ContainerHistoryStore STORE = new ContainerHistoryStore();

    /** A screen opened this long after a block use is no longer trusted to belong to it. */
    private static final long OPEN_ASSOCIATION_MS = 1500;
    private static final long SELF_PLACE_TIMEOUT_MS = 20000;
    private static final int REFRESH_TICKS = 10;
    private static final int MAINTAIN_TICKS = 40;

    private final Setting<Boolean> showLabels = bool("ShowLabels", true);
    private final Setting<Double> range = num("Range", 32.0, 8.0, 64.0);
    private final Setting<Integer> expireMinutes = num("ExpireMinutes", 30, 5, 240);
    private final Setting<Boolean> trackSelfPlaced = bool("TrackSelfPlaced", true);
    private final Setting<String> ignoreTitles = str("IgnoreTitles", "菜单");

    private BlockPos pendingOpenPos;
    private long pendingOpenAtMs;
    private int trackedContainerId = -1;
    private BlockPos trackedPos;
    private BlockPos trackedPair;
    private int refreshTimer;
    private int maintainTimer;

    public ChestHistoryModule() {
        super("ChestHistory", "Remembers the contents of containers you opened and marks shulker boxes you "
                + "placed yourself. Labels are last-observation only; contents of unopened containers are "
                + "never guessed.", Category.RENDER);
        instance = this;
        PacketSendUtil.addSubmissionListener(this::onUseSubmitted);
    }

    /** The shared history store; survives module toggles, clears on server change and expiry. */
    public static ContainerHistoryStore store() {
        return STORE;
    }

    public static boolean selected() {
        return instance != null && instance.isEnabled();
    }

    @Override
    public void onDisable() {
        pendingOpenPos = null;
        trackedContainerId = -1;
        trackedPos = null;
        trackedPair = null;
    }

    /** Player-initiated block uses: candidates for the next opened screen and for self placements. */
    private void onUseSubmitted(Packet<?> packet) {
        // The packet listener bypasses the event bus, so disabled state is checked here, first.
        if (!isEnabled() || mc.player == null || !mc.isSameThread()) return;
        if (!(packet instanceof ServerboundUseItemOnPacket use)) return;
        if (ItemUseTracker.moduleUseInFlight()) return; // module actions are not player opens
        BlockHitResult hit = use.getHitResult();
        pendingOpenPos = hit.getBlockPos().immutable();
        pendingOpenAtMs = System.currentTimeMillis();
        if (trackSelfPlaced.getValue()) {
            ItemStack held = mc.player.getItemInHand(use.getHand());
            if (held.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock) {
                STORE.proposeSelfPlacement(pendingOpenPos.relative(hit.getDirection()).immutable(),
                        block.getBlock(), pendingOpenAtMs);
            }
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;
        if (!HeartbeatManager.authorized()) {
            HeartbeatManager.notifyDeniedOnce();
            return;
        }
        long now = System.currentTimeMillis();
        String scope = (mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer")
                + "#" + mc.level.dimension().identifier();
        STORE.rescope(scope);
        maintain(now);
        trackOpenScreen(now);
        verifySelfPlacements();
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        STORE.clear();
        trackedContainerId = -1;
        trackedPos = null;
        trackedPair = null;
        pendingOpenPos = null;
    }

    private void trackOpenScreen(long now) {
        boolean containerScreen = mc.screen instanceof AbstractContainerScreen<?>
                && !(mc.screen instanceof InventoryScreen)
                && !(mc.screen instanceof CreativeModeInventoryScreen);
        if (!containerScreen) {
            trackedContainerId = -1;
            trackedPos = null;
            trackedPair = null;
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.screen;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null) return;
        if (menu.containerId != trackedContainerId) {
            trackedContainerId = menu.containerId;
            refreshTimer = 0;
            resolveAssociation(now);
        } else if (++refreshTimer < REFRESH_TICKS) {
            return;
        }
        refreshTimer = 0;
        capture(screen, menu, now);
    }

    /** Binds the freshly opened screen to the block the player just used, if it is still fresh. */
    private void resolveAssociation(long now) {
        trackedPos = null;
        trackedPair = null;
        if (pendingOpenPos == null || now - pendingOpenAtMs > OPEN_ASSOCIATION_MS) return;
        BlockState state = mc.level.getBlockState(pendingOpenPos);
        if (state.getBlock() instanceof ChestBlock chest
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            trackedPair = pendingOpenPos.relative(ChestBlock.getConnectedDirection(state));
        }
        trackedPos = pendingOpenPos;
        pendingOpenPos = null; // consumed: another screen needs a fresh interaction
    }

    private void capture(AbstractContainerScreen<?> screen, AbstractContainerMenu menu, long now) {
        int containerCount = Math.max(0, menu.slots.size() - 36);
        if (containerCount <= 0) return;
        Component title = screen.getTitle();
        if (ignoredTitle(title == null ? "" : title.getString())) return;
        List<ItemStack> slots = new ArrayList<>(containerCount);
        for (int i = 0; i < containerCount; i++) slots.add(menu.getSlot(i).getItem().copy());
        if (isEnderChest(title)) {
            STORE.recordEnderChest(slots, now);
            return;
        }
        if (trackedPos == null) return; // a server GUI without a fresh block interaction: not recorded
        BlockState state = mc.level.getBlockState(trackedPos);
        STORE.recordOpened(trackedPos, state.getBlock(), title == null ? "" : title.getString(),
                slots, trackedPair, now);
    }

    private boolean isEnderChest(Component title) {
        if (title == null) return false;
        if (title.getContents() instanceof TranslatableContents contents
                && "container.enderchest".equals(contents.getKey())) return true;
        String plain = title.getString();
        return plain.equalsIgnoreCase("Ender Chest") || plain.equalsIgnoreCase("末影箱");
    }

    private boolean ignoredTitle(String title) {
        String raw = ignoreTitles.getValue();
        if (raw == null || raw.isBlank()) return false;
        for (String part : raw.split(",")) {
            String needle = part.trim();
            if (!needle.isEmpty() && title.contains(needle)) return true;
        }
        return false;
    }

    /** Drops entries whose block changed, expired entries, and stale placement predictions. */
    private void maintain(long now) {
        if (++maintainTimer < MAINTAIN_TICKS) return;
        maintainTimer = 0;
        STORE.expire(now, expireMinutes.getValue() * 60_000L);
        STORE.expirePendingPlacements(now, SELF_PLACE_TIMEOUT_MS);
        List<BlockPos> doomed = new ArrayList<>();
        for (var entry : STORE.entries()) {
            BlockPos pos = entry.getKey();
            ContainerHistoryStore.Entry record = entry.getValue();
            if (!mc.level.hasChunkAt(pos)) continue;
            if (record.doubleChest()) {
                // both halves must still be chests of the recorded kind
                if (!(mc.level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
                    doomed.add(pos);
                } else if (mc.level.hasChunkAt(record.pairPos())
                        && !(mc.level.getBlockState(record.pairPos()).getBlock() instanceof ChestBlock)) {
                    doomed.add(pos);
                }
                continue;
            }
            if (STORE.invalidate(pos, mc.level.getBlockState(pos).getBlock())) continue;
        }
        for (BlockPos pos : doomed) STORE.remove(pos);
    }

    /** A predicted shulker placement counts once its block really appeared there. */
    private void verifySelfPlacements() {
        for (ContainerHistoryStore.PendingPlacement pending : STORE.pending()) {
            if (!mc.level.hasChunkAt(pending.pos())) continue;
            if (mc.level.getBlockState(pending.pos()).getBlock() == pending.expected()) {
                STORE.confirmSelfPlacement(pending.pos());
            }
        }
    }

    @Subscribe
    public void onRender2D(Render2DEvent event) {
        if (!showLabels.getValue() || nullCheck()) return;
        long now = System.currentTimeMillis();
        double maxSq = range.getValue() * range.getValue();
        for (var entry : STORE.entries()) {
            BlockPos pos = entry.getKey();
            ContainerHistoryStore.Entry record = entry.getValue();
            double dx = pos.getX() + 0.5 - mc.player.getX();
            double dy = pos.getY() + 0.5 - mc.player.getY();
            double dz = pos.getZ() + 0.5 - mc.player.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxSq) continue;
            String text = ClientLanguage.text("上次观察 ") + ageLabel(now - record.seenAtMs())
                    + " · " + record.itemCount() + ClientLanguage.text(" 件")
                    + (record.selfPlaced() ? ClientLanguage.text(" · 自放") : "");
            WorldLabel.draw(event.getContext(), pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    Math.sqrt(distSq), text, 0xFFFFFFFF, 0xA0000000, 1.0f, 0.6f);
        }
    }

    private static String ageLabel(long ageMs) {
        long seconds = Math.max(1, ageMs / 1000);
        if (seconds < 60) return seconds + ClientLanguage.text("秒前");
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + ClientLanguage.text("分前");
        return minutes / 60 + ClientLanguage.text("时前");
    }

    @Override
    public String getDisplayInfo() {
        return String.valueOf(STORE.size());
    }
}
