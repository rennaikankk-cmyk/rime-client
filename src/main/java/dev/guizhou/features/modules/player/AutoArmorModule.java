package dev.guizhou.features.modules.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.movement.ArmorFlyModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.EnchantmentUtil;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.player.ArmorScore;
import dev.guizhou.util.player.ScreenGate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Equips the best armor from the inventory and swaps out pieces about to break. One swap per
 * delay window; the chest slot is left alone while gliding, wearing an elytra (optional) or
 * while ArmorFly/ElytraFlight own it.
 */
public class AutoArmorModule extends Module {
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final Setting<Integer> delay = num("DelayTicks", 3, 1, 20);
    private final Setting<Double> lowDurability = num("LowDurability", 0.10, 0.0, 0.5);
    private final Setting<Boolean> preferBlast = bool("PreferBlastLeggings", true);
    private final Setting<Boolean> keepElytra = bool("KeepElytra", true);
    private final Setting<Boolean> pauseMoving = bool("PauseWhileMoving", false);

    private int cooldown;

    public AutoArmorModule() {
        super("AutoArmor", "Wears the best armor in your inventory and replaces pieces below the durability line. "
                + "Never touches the chest slot while gliding.", Category.PLAYER);
    }

    @Override
    public void onDisable() {
        resetState();
    }

    @Override
    public void onTick() {
        if (!HeartbeatManager.authorized()) { HeartbeatManager.notifyDeniedOnce(); resetState(); return; }
        if (nullCheck() || mc.gameMode == null || mc.player.isCreative()) return;
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (!ScreenGate.allowsInventoryUpkeep(true)) return;
        if (pauseMoving.getValue() && (mc.player.input.keyPresses.forward() || mc.player.input.keyPresses.backward()
                || mc.player.input.keyPresses.left() || mc.player.input.keyPresses.right() || mc.player.input.keyPresses.jump())) return;
        for (EquipmentSlot slot : SLOTS) {
            if (slot == EquipmentSlot.CHEST && chestLocked()) continue;
            ItemStack worn = mc.player.getItemBySlot(slot);
            ArmorScore.Piece current = piece(worn, slot);
            int best = -1;
            ArmorScore.Piece bestPiece = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 36; i++) {
                ArmorScore.Piece candidate = piece(mc.player.getInventory().getItem(i), slot);
                if (candidate == null || candidate.binding() || ArmorScore.low(candidate, lowDurability.getValue())) continue;
                double score = ArmorScore.score(candidate, preferBlast(slot));
                if (score > bestScore) {
                    best = i;
                    bestPiece = candidate;
                    bestScore = score;
                }
            }
            if (best < 0 || !ArmorScore.shouldReplace(worn.isEmpty() ? null : current, bestPiece,
                    preferBlast(slot), lowDurability.getValue())) continue;
            equip(slot, best, worn.isEmpty());
            cooldown = delay.getValue();
            return;
        }
    }

    private boolean preferBlast(EquipmentSlot slot) {
        return preferBlast.getValue() && slot == EquipmentSlot.LEGS;
    }

    private boolean chestLocked() {
        if (mc.player.isFallFlying()) return true;
        if (keepElytra.getValue() && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) return true;
        return enabled(ArmorFlyModule.class) || enabled(GuangxiElytraModule.class);
    }

    private static boolean enabled(Class<? extends Module> type) {
        Module module = GuizhouWare.moduleManager.getModuleByClass(type);
        return module != null && module.isEnabled();
    }

    /** Only real armor for {@code slot}: elytras, pumpkins and heads have no armor value and are skipped. */
    private ArmorScore.Piece piece(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return null;
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null || equippable.slot() != slot) return null;
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double armor = modifiers.compute(Attributes.ARMOR, 0.0, slot);
        if (armor <= 0) return null;
        double toughness = modifiers.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot);
        int max = stack.isDamageableItem() ? stack.getMaxDamage() : 0;
        return new ArmorScore.Piece(armor, toughness,
                EnchantmentUtil.getLevel(Enchantments.PROTECTION, stack),
                EnchantmentUtil.getLevel(Enchantments.BLAST_PROTECTION, stack),
                EnchantmentUtil.getLevel(Enchantments.UNBREAKING, stack),
                max - stack.getDamageValue(), max,
                EnchantmentUtil.getLevel(Enchantments.BINDING_CURSE, stack) > 0);
    }

    /** Inventory-menu clicks: shift-click into an empty slot, number-key swap from the hotbar, else pick/place. */
    private void equip(EquipmentSlot slot, int inventoryIndex, boolean slotEmpty) {
        if (!HeartbeatManager.authorized()) return;
        int armorSlot = switch (slot) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            default -> 8;
        };
        int menuSlot = inventoryIndex < 9 ? inventoryIndex + 36 : inventoryIndex;
        if (slotEmpty) {
            InventoryUtil.click(menuSlot, 0, ClickType.QUICK_MOVE);
        } else if (inventoryIndex < 9) {
            InventoryUtil.click(armorSlot, inventoryIndex, ClickType.SWAP);
        } else {
            InventoryUtil.click(menuSlot, 0, ClickType.PICKUP);
            InventoryUtil.click(armorSlot, 0, ClickType.PICKUP);
            InventoryUtil.click(menuSlot, 0, ClickType.PICKUP);
        }
    }

    private void resetState() {
        cooldown = 0;
    }
}
