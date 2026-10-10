package dev.guizhou.features.modules.qinghua;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.DeathEvent;
import dev.guizhou.event.impl.entity.TotemPopEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.NumberListUtil;
import dev.guizhou.features.settings.Setting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Port of eternal AutoEZ — death/pop message logic unchanged.
 * StringList → newline-delimited Setting&lt;String&gt;.
 */
public class AutoEZModule extends Module {
    public enum Mode { File, Normal }

    private final Setting<Mode> mode = mode("Mode", Mode.File).described("File.");
    private final Setting<Boolean> antiSpam = bool("AntiAntiSpam", true)
            .described("Append random alphanumeric characters to avoid spam detection.");
    private final Setting<Integer> antiSpamLength = num("AntiSpamLength", 5, 1, 20)
            .described("Number of random characters to append (1-20).")
            .setVisibility(v -> antiSpam.getValue());
    private final Setting<Integer> range = num("Range", 10, 0, 500)
            .described("Only send messages for players within this range (blocks).");
    private final Setting<Boolean> targetFriend = bool("TargetFriend", false)
            .described("Also send messages when friends die.");
    private final Setting<String> normalMessages = str("NormalMessages", "EZ %player%!")
            .described("Messages used in Normal mode (newline / comma separated).")
            .setVisibility(v -> mode.getValue() == Mode.Normal);

    private final List<String> fileMessages = new ArrayList<>();
    private final Map<UUID, Integer> totemPops = new HashMap<>();
    private final Random random = new Random();
    private static final String ANTI_SPAM_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    public AutoEZModule() {
        super("AutoEZ", "ez.", Category.CLIENT);
    }

    @Override
    public void onEnable() {
        if (this.mode.getValue() == Mode.File) loadFileMessages();
    }

    @Subscribe
    private void onDeath(DeathEvent event) {
        if (mc.player == null || event.getEntity() == null) return;
        LivingEntity entity = event.getEntity();
        if (entity == mc.player) return;
        if (!(entity instanceof Player player)) return;
        if (!targetFriend.getValue() && GuizhouWare.friendManager != null && GuizhouWare.friendManager.isFriend(player)) return;
        if (mc.player.distanceTo(player) > range.getValue()) return;
        int pops = totemPops.getOrDefault(player.getUUID(), 0);
        totemPops.remove(player.getUUID());
        sendEz(player, pops);
    }

    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        if (event.getEntity() instanceof Player player) {
            totemPops.merge(player.getUUID(), 1, Integer::sum);
        }
    }

    private void sendEz(Player player, int pops) {
        List<String> messages;
        if (mode.getValue() == Mode.File && !fileMessages.isEmpty()) {
            messages = fileMessages;
        } else {
            messages = NumberListUtil.parseStrings(normalMessages.getValue());
        }
        if (messages.isEmpty()) return;
        String msg = messages.get(random.nextInt(messages.size()));
        msg = msg.replace("%player%", player.getName().getString())
                .replace("%pops%", String.valueOf(pops));
        if (antiSpam.getValue()) {
            StringBuilder sb = new StringBuilder(msg).append(' ');
            int len = antiSpamLength.getValue();
            for (int i = 0; i < len; i++) {
                sb.append(ANTI_SPAM_CHARS.charAt(random.nextInt(ANTI_SPAM_CHARS.length())));
            }
            msg = sb.toString();
        }
        if (mc.getConnection() != null) mc.getConnection().sendChat(msg);
    }

    private void loadFileMessages() {
        fileMessages.clear();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("qinghua").resolve("autoez.txt");
        try {
            if (Files.exists(path)) {
                fileMessages.addAll(Files.readAllLines(path));
                fileMessages.removeIf(String::isBlank);
            }
        } catch (IOException ignored) {
        }
    }
}
