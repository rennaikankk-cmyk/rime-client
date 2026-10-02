package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.input.KeyInputEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.settings.Setting;
import io.netty.channel.Channel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AutoLogModule extends Module {

    /** AutoReconnect treats a disconnect within this window of an AutoLog as intentional. */
    public static final long INTENTIONAL_WINDOW_MS = 10_000L;
    private static volatile long lastIntentionalDisconnectMs;

    private final Setting<Boolean> logOnHealth = bool("LogOnHealth", true);
    private final Setting<Integer> healthThreshold = num("HealthThreshold", 6, 1, 36);
    private final Setting<Boolean> logOnTotems = bool("LogOnTotems", true);
    private final Setting<Integer> totemThreshold = num("TotemThreshold", 1, 0, 64);
    private final Setting<Bind> disconnectBind = key("DisconnectBind", Bind.none());

    private int cachedTotems = 0;
    private volatile boolean awaitingRechamber = false;

    public AutoLogModule() {
        super("AutoLog", "Automatically disconnects based on health or totem count. AutoReconnect never "
                + "reconnects after an AutoLog disconnect.", Category.COMBAT);
        healthThreshold.setVisibility(v -> logOnHealth.getValue());
        totemThreshold.setVisibility(v -> logOnTotems.getValue());
    }

    /** True for a short window after this client disconnected on purpose (AutoLog, VisualRange). */
    public static boolean recentlyLoggedOut() {
        long at = lastIntentionalDisconnectMs;
        return at != 0 && System.currentTimeMillis() - at <= INTENTIONAL_WINDOW_MS;
    }

    @Override
    public void onEnable() {
        awaitingRechamber = false;
        if (!nullCheck()) updateTotemCount();
    }

    @Override
    public void onDisable() {
        awaitingRechamber = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;
        if (mc.player.isDeadOrDying()) { disable(); return; }
        updateTotemCount();
        if (!awaitingRechamber && isSurvival()) checkThresholds();
    }

    /**
     * Receive callbacks run on the Netty thread: only read the packet here and hand every
     * decision (world reads, disable, disconnect and its chat output) to the client thread.
     */
    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.getPacket() instanceof ClientboundEntityEventPacket pkt) {
            if (pkt.getEventId() != 35) return;
            mc.execute(() -> onTotemPopPacket(pkt));
        } else if (event.getPacket() instanceof ClientboundSetHealthPacket pkt) {
            float health = pkt.getHealth();
            mc.execute(() -> onHealthPacket(health));
        }
    }

    private void onTotemPopPacket(ClientboundEntityEventPacket pkt) {
        if (!isEnabled() || nullCheck() || !isSurvival()) return;
        if (pkt.getEntity(mc.level) != mc.player) return;
        cachedTotems--;
        if (!logOnTotems.getValue() || cachedTotems > totemThreshold.getValue()) return;

        if (awaitingRechamber) {
            awaitingRechamber = false;
            disconnect();
            return;
        }

        boolean pollOffhand = totemThreshold.getValue() > 1 && isAutoTotemEnabled();
        Channel channel = pollOffhand ? getChannel() : null;
        if (channel == null) {
            disconnect();
            return;
        }
        awaitingRechamber = true;
        pollForRechamber(channel, System.currentTimeMillis() + 500);
    }

    private void onHealthPacket(float health) {
        if (!isEnabled() || nullCheck() || !isSurvival()) return;
        if (health <= 0) { disable(); return; }
        if (logOnHealth.getValue() && health <= healthThreshold.getValue()) disconnect();
    }

    @Subscribe
    private void onKeyInput(KeyInputEvent event) {
        if (disconnectBind.getValue().isEmpty() || event.getAction() != 1) return;
        if (event.getKey() == disconnectBind.getValue().getKey()) {
            if (!nullCheck() && isSurvival()) disconnect();
        }
    }

    private void pollForRechamber(Channel channel, long deadline) {
        if (!channel.isOpen()) { awaitingRechamber = false; return; }
        channel.eventLoop().schedule(() -> mc.execute(() -> {
            if (!awaitingRechamber || nullCheck() || !isSurvival()) {
                awaitingRechamber = false;
                return;
            }
            updateTotemCount();
            boolean offhandHasTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
            boolean noTotems = cachedTotems == 0;
            boolean timedOut = System.currentTimeMillis() >= deadline;

            if (offhandHasTotem || noTotems || timedOut) {
                awaitingRechamber = false;
                if (logOnTotems.getValue() && cachedTotems <= totemThreshold.getValue()) {
                    disconnect();
                }
            } else {
                pollForRechamber(channel, deadline);
            }
        }), 10, TimeUnit.MILLISECONDS);
    }

    private void checkThresholds() {
        if (logOnHealth.getValue()) {
            float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            if (hp <= healthThreshold.getValue()) {
                disconnect();
                return;
            }
        }
        if (logOnTotems.getValue() && cachedTotems <= totemThreshold.getValue()) {
            disconnect();
        }
    }

    private void updateTotemCount() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(Items.TOTEM_OF_UNDYING)) count += offhand.getCount();
        cachedTotems = count;
    }

    private Channel getChannel() {
        if (mc.getConnection() == null) return null;
        return ((dev.guizhou.mixin.network.ConnectionAccessor) mc.getConnection().getConnection()).getChannel();
    }

    private void closeChannel() {
        Channel channel = getChannel();
        if (channel != null) channel.close();
    }

    private boolean isAutoTotemEnabled() {
        AutoTotemModule autoTotem = GuizhouWare.moduleManager.getModuleByClass(AutoTotemModule.class);
        return autoTotem != null && autoTotem.isEnabled();
    }

    private boolean isSurvival() {
        return mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SURVIVAL;
    }

    private void disconnect() {
        logOut("AutoLog Disconnect");
        disable();
    }

    /**
     * Intentional disconnect shared with VisualRange. Client thread only; marks the disconnect
     * so AutoReconnect stays away from the server that just threatened us.
     */
    public static void logOut(String title) {
        if (!mc.isSameThread()) {
            mc.execute(() -> logOut(title));
            return;
        }
        lastIntentionalDisconnectMs = System.currentTimeMillis();
        AutoLogModule module = GuizhouWare.moduleManager.getModuleByClass(AutoLogModule.class);
        if (!nullCheck() && mc.getConnection() != null) {
            BlockPos pos = mc.player.blockPosition();
            List<String> nearby = new ArrayList<>();
            for (Player player : mc.level.players()) {
                if (player != mc.player) nearby.add(player.getGameProfile().getName());
            }
            StringBuilder msg = new StringBuilder(title);
            msg.append("\nTotems: ").append(module == null ? 0 : module.cachedTotems);
            msg.append("\nCoords: ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ());
            msg.append(nearby.isEmpty() ? "\nNearby: none" : "\nNearby: " + String.join(", ", nearby));

            mc.getConnection().getConnection().disconnect(Component.literal(msg.toString()));
        } else if (module != null) {
            module.closeChannel();
        }
    }

    @Override
    public String getDisplayInfo() {
        if (nullCheck()) return null;
        return String.valueOf(cachedTotems);
    }
}
