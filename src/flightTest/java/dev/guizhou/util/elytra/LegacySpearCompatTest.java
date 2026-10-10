package dev.guizhou.util.elytra;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.KineticWeapon;

public final class LegacySpearCompatTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Item[] swords = {Items.WOODEN_SWORD, Items.STONE_SWORD, Items.STONE_SWORD,
                Items.IRON_SWORD, Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD};
        Item[] spears = {Items.WOODEN_SPEAR, Items.STONE_SPEAR, Items.COPPER_SPEAR,
                Items.IRON_SPEAR, Items.GOLDEN_SPEAR, Items.DIAMOND_SPEAR, Items.NETHERITE_SPEAR};
        for (int i = 0; i < swords.length; i++) {
            ItemStack stack = marked(swords[i], 1296 + i);
            var before = stack.getComponentsPatch();
            var nativeComponents = spears[i].components();
            var profile = LegacySpearCompat.profile(stack, true);
            check(profile != null && profile.kinetic().equals(nativeComponents.get(DataComponents.KINETIC_WEAPON))
                    && profile.range().equals(nativeComponents.get(DataComponents.ATTACK_RANGE))
                    && profile.effects().equals(nativeComponents.get(DataComponents.USE_EFFECTS)), "material " + i);
            check(!stack.has(DataComponents.KINETIC_WEAPON) && stack.getComponentsPatch().equals(before), "no inventory mutation");
            check(LegacySpearCompat.profile(stack, false) == null, "actual Via connection required");
        }
        ItemStack renamed = new ItemStack(Items.NETHERITE_SWORD);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("1.21.11 Netherite Spear"));
        check(LegacySpearCompat.profile(renamed, true) == null, "renamed sword is not a spear");
        check(LegacySpearCompat.profile(marked(Items.DIAMOND_SWORD, 1302), true) == null, "material mismatch");
        check(LegacySpearCompat.profile(marked(Items.NETHERITE_SWORD, 1303), true) == null, "unknown ID");
        check(LegacySpearCompat.profile(marked(Items.NETHERITE_AXE, 1302), true) == null, "wrong fallback");
        check(LegacySpearCompat.profile(ItemStack.EMPTY, true) == null, "empty");
        check(LegacySpearCompat.profile(new ItemStack(Items.NETHERITE_SPEAR), true) == null, "native spear untouched");

        // Real protocol-765 data has a later-stage VV sword ID plus the authoritative spear marker.
        CompoundTag actual = new CompoundTag(); actual.putInt(LegacySpearCompat.BACKUP + "|id", 1302);
        CompoundTag hashes = new CompoundTag(); hashes.putInt("id", 883);
        hashes.putString("backup_tag", "VB|Protocol1_21_5To1_21_4"); actual.put("VV|original_hashes", hashes);
        ItemStack converted = new ItemStack(Items.NETHERITE_SWORD); converted.set(DataComponents.CUSTOM_DATA, CustomData.of(actual));
        check(LegacySpearCompat.profile(converted, true) != null, "later-stage sword hashes cannot hide spear marker");
        hashes.putInt("id", 1302); actual.remove(LegacySpearCompat.BACKUP + "|id");
        actual.put("VV|original_hashes", hashes); converted.set(DataComponents.CUSTOM_DATA, CustomData.of(actual));
        check(LegacySpearCompat.profile(converted, true) == null, "IDs from other registry stages are not spear proof");
        hashes.putString("backup_tag", LegacySpearCompat.BACKUP); actual.put("VV|original_hashes", hashes);
        converted.set(DataComponents.CUSTOM_DATA, CustomData.of(actual));
        check(LegacySpearCompat.profile(converted, true) != null, "explicit spear-stage hashes");

        CompoundTag tag = new CompoundTag(); tag.putInt(LegacySpearCompat.BACKUP + "|id", 1302);
        var nativeWeapon = Items.NETHERITE_SPEAR.components().get(DataComponents.KINETIC_WEAPON);
        CompoundTag weapon = (CompoundTag) KineticWeapon.CODEC.encodeStart(NbtOps.INSTANCE,
                new KineticWeapon(7, 13, nativeWeapon.dismountConditions(), nativeWeapon.knockbackConditions(),
                        nativeWeapon.damageConditions(), nativeWeapon.forwardMovement(), nativeWeapon.damageMultiplier(),
                        java.util.Optional.empty(), java.util.Optional.empty())).result().orElseThrow();
        CompoundTag backup = new CompoundTag(); backup.put("kinetic_weapon", weapon); tag.put(LegacySpearCompat.BACKUP, backup);
        converted.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        check(LegacySpearCompat.profile(converted, true).kinetic().delayTicks() == 13, "server override preserved");
        backup.put("kinetic_weapon", IntTag.valueOf(1)); tag.put(LegacySpearCompat.BACKUP, backup);
        converted.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        check(LegacySpearCompat.profile(converted, true) == null, "malformed override rejected");
        check(DualViewSupport.legacySpearFamily("1.20.3-1.20.4"), "GZ-compatible protocol family");
        check(DualViewSupport.legacySpearFamily("1.21.9-1.21.10"), "last pre-spear protocol family");
        check(!DualViewSupport.legacySpearFamily("1.21.11") && !DualViewSupport.legacySpearFamily("1.20.4-rc1")
                && !DualViewSupport.legacySpearFamily("1.20.4-1.19.4"), "native and unknown families rejected");
        System.out.println("LegacySpearCompatTest: all checks passed");
    }
    private static ItemStack marked(Item item, int id) {
        CompoundTag tag = new CompoundTag(); tag.putInt(LegacySpearCompat.BACKUP + "|id", id);
        ItemStack result = new ItemStack(item); result.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); return result;
    }
    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
