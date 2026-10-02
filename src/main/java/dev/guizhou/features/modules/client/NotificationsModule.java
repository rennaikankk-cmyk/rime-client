package dev.guizhou.features.modules.client;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.ClientEvent;
import dev.guizhou.event.impl.entity.TotemPopEvent;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.hud.NotifierHudModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.TextUtil;
import dev.guizhou.util.player.ChatUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NotificationsModule extends Module {
    private static final String MODULE_FORMAT = "%s %s %s";

    private static final int INFO_COLOR = 0xFFFF55;

    public Setting<Boolean> moduleToggle = bool("Module Toggle", true);
    public Setting<Boolean> deaths = bool("Deaths", true);
    public Setting<Boolean> totemPops = bool("TotemPops", true);
    public Setting<Boolean> gear = bool("Gear", false);
    public Setting<Boolean> sound = bool("Sound", false).described("爆图腾、阵亡和补给不足时播放提示音，受界面音效音量控制。");

    private final Set<UUID> lastTickAlive = ConcurrentHashMap.newKeySet();

    private record GearItem(Item item, String label, Setting<Integer> threshold) {}

    private final GearItem[] gearItems = {
            new GearItem(Items.OBSIDIAN, "obsidian", gearThreshold("Obsidian", 128, 512)),
            new GearItem(Items.EXPERIENCE_BOTTLE, "XP bottles", gearThreshold("XP Bottles", 128, 512)),
            new GearItem(Items.END_CRYSTAL, "crystals", gearThreshold("Crystals", 128, 512)),
            new GearItem(Items.ENDER_PEARL, "pearls", gearThreshold("Pearls", 32, 256)),
    };

    private Setting<Integer> gearThreshold(String label, int def, int max) {
        Setting<Integer> s = num(label + " Threshold", def, 0, max);
        s.setVisibility(v -> gear.getValue());
        return s;
    }

    private final Set<Item> gearWarned = ConcurrentHashMap.newKeySet();

    public NotificationsModule() {
        super("Notifications", "Displays notifications for various client events", Category.CLIENT);
    }

    @Subscribe
    public void onClient(ClientEvent event) {
        if (!moduleToggle.getValue()
                || event.getType() != ClientEvent.Type.TOGGLE_MODULE
                || event.getFeature() instanceof ClickGuiModule) {
            return;
        }

        boolean moduleState = event.getFeature().isEnabled();
        String id = event.getFeature().getName();
        String name = event.getFeature() instanceof Module module ? module.getDisplayName() : id;
        ChatFeed.moduleToggled(id, name, moduleState);
        NotifierHudModule.push("module:" + id, NotifierHudModule.Icon.INFO,
                name + (moduleState ? dev.guizhou.util.lang.ClientLanguage.text(" 已开启") : dev.guizhou.util.lang.ClientLanguage.text(" 已关闭")));
    }

    @Subscribe(priority = 100)
    public void onPreTick(PreTickEvent event) {
        if (!deaths.getValue()) return;
        if (mc.level == null || mc.player == null) return;

        Set<UUID> currentlyAlive = new HashSet<>();
        for (Player player : mc.level.players()) {
            UUID id = player.getUUID();
            if (player.isDeadOrDying()) {
                if (lastTickAlive.contains(id)) {
                    int pops = GuizhouWare.playerInfoManager.getTotemPops(id);
                    String name = player.getGameProfile().name();
                    Component message = Component.empty()
                            .append(Component.literal(name + dev.guizhou.util.lang.ClientLanguage.text(" 阵亡，此前爆了 ")))
                            .append(Component.literal(String.valueOf(pops)).withColor(ChatPalette.WARNING))
                            .append(Component.literal(dev.guizhou.util.lang.ClientLanguage.text(" 个图腾")));
                    ChatFeed.send(ChatTone.ERROR, "☠", message);
                    if (sound.getValue()) dev.guizhou.util.render.UiSounds.error();
                    NotifierHudModule.push("death:"+id, NotifierHudModule.Icon.SKULL, message);
                }
            } else {
                currentlyAlive.add(id);
            }
        }
        lastTickAlive.clear();
        lastTickAlive.addAll(currentlyAlive);
    }

    @Subscribe(priority = -100)
    public void onTotemPop(TotemPopEvent event) {
        if (!totemPops.getValue()) return;
        if (mc.player == null) return;

        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Player player)) return;

        UUID id = player.getUUID();
        int pops = GuizhouWare.playerInfoManager.getTotemPops(id);
        boolean self = player == mc.player;
        if (sound.getValue()) {
            if (self) dev.guizhou.util.render.UiSounds.error();
            else dev.guizhou.util.render.UiSounds.success();
        }
        String subject = self ? dev.guizhou.util.lang.ClientLanguage.text("你") : player.getGameProfile().name();

        Component body = Component.empty()
                .append(Component.literal(subject + dev.guizhou.util.lang.ClientLanguage.text(" 已爆 ")))
                .append(Component.literal(String.valueOf(pops)).withColor(ChatPalette.WARNING))
                .append(Component.literal(dev.guizhou.util.lang.ClientLanguage.text(" 个图腾")));

        ChatFeed.replace("pops:" + id, ChatTone.WARN, "✦", body);

        NotifierHudModule.push(
                "pops:" + id,
                NotifierHudModule.Icon.INFO,
                body);
    }

    @Subscribe
    public void onGearTick(PreTickEvent event) {
        if (!gear.getValue()) return;
        if (mc.player == null) return;

        for (GearItem gi : gearItems) {
            int count = countItem(gi.item());
            int threshold = gi.threshold().getValue();

            if (count >= threshold) {
                gearWarned.remove(gi.item());
                continue;
            }

            if (!gearWarned.add(gi.item())) continue;
            if (sound.getValue()) dev.guizhou.util.render.UiSounds.error();

            Component body = Component.empty()
                    .append(Component.literal("Low on " + gi.label() + ": "))
                    .append(Component.literal(String.valueOf(count)).withColor(INFO_COLOR))
                    .append(Component.literal(" left"));
            NotifierHudModule.push("gear:" + gi.label(), NotifierHudModule.Icon.INFO, body);
        }
    }

    private int countItem(Item item) {
        int total = 0;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) total += s.getCount();
        }
        return total;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        lastTickAlive.clear();
        gearWarned.clear();
    }
}
