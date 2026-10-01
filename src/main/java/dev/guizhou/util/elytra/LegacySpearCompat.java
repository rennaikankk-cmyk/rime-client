package dev.guizhou.util.elytra;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.item.component.UseEffects;

import java.util.IdentityHashMap;

/** Read-only view of a spear preserved by ViaBackwards; never changes inventory hashes. */
public final class LegacySpearCompat {
    static final String BACKUP = "VB|Protocol1_21_11To1_21_9";
    private static final IdentityHashMap<CustomData, Cached> CACHE = new IdentityHashMap<>();

    public record Profile(KineticWeapon kinetic, AttackRange range, UseEffects effects) {}
    private record Cached(Item item, Profile profile) {}

    private LegacySpearCompat() {}

    public static Profile profile(ItemStack stack) {
        return profile(stack, DualViewSupport.usesLegacySpearData());
    }

    static synchronized Profile profile(ItemStack stack, boolean legacyVia) {
        if (!legacyVia || stack == null || stack.isEmpty()
                || stack.has(DataComponents.KINETIC_WEAPON)) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        Cached cached = CACHE.get(data);
        if (cached != null && cached.item() == stack.getItem()) return cached.profile();
        Profile result = read(stack, data.copyTag());
        if (CACHE.size() >= 64) CACHE.clear();
        CACHE.put(data, new Cached(stack.getItem(), result));
        return result;
    }

    private static Profile read(ItemStack stack, CompoundTag tag) {
        // Prefer the explicit original ID: later conversion stages overwrite VV hashes
        // with a sword ID (883 in the measured protocol-765 inventory).
        int id = tag.getInt(BACKUP + "|id").orElse(-1);
        if (id == -1) {
            CompoundTag hashes = tag.getCompound("VV|original_hashes").orElse(null);
            if (hashes != null && hashes.getString("backup_tag").orElse("").equals(BACKUP))
                id = hashes.getInt("id").orElse(-1);
        }
        Item nativeItem = switch (id) {
            case 1296 -> stack.is(Items.WOODEN_SWORD) ? Items.WOODEN_SPEAR : null;
            case 1297 -> stack.is(Items.STONE_SWORD) ? Items.STONE_SPEAR : null;
            case 1298 -> stack.is(Items.COPPER_SWORD) || stack.is(Items.STONE_SWORD) ? Items.COPPER_SPEAR : null;
            case 1299 -> stack.is(Items.IRON_SWORD) ? Items.IRON_SPEAR : null;
            case 1300 -> stack.is(Items.GOLDEN_SWORD) ? Items.GOLDEN_SPEAR : null;
            case 1301 -> stack.is(Items.DIAMOND_SWORD) ? Items.DIAMOND_SPEAR : null;
            case 1302 -> stack.is(Items.NETHERITE_SWORD) ? Items.NETHERITE_SPEAR : null;
            default -> null;
        };
        if (nativeItem == null) return null;
        var defaults = nativeItem.components();
        KineticWeapon kinetic = defaults.get(DataComponents.KINETIC_WEAPON);
        AttackRange range = defaults.get(DataComponents.ATTACK_RANGE);
        UseEffects effects = defaults.get(DataComponents.USE_EFFECTS);
        CompoundTag backup = tag.getCompound(BACKUP).orElse(null);
        if (backup != null) {
            // Explicit component overrides are authoritative when the conversion
            // retained them. A malformed override must not silently become a default.
            if (backup.contains("kinetic_weapon")) {
                CompoundTag saved = backup.getCompound("kinetic_weapon").map(CompoundTag::copy).orElse(null);
                if (saved == null) return null;
                // Via sound IDs need registry translation, unrelated to damage timing.
                saved.remove("sound"); saved.remove("hit_sound");
                kinetic = KineticWeapon.CODEC.parse(NbtOps.INSTANCE, saved).result().orElse(null);
            }
            if (backup.contains("attack_range")) {
                CompoundTag saved = backup.getCompound("attack_range").map(CompoundTag::copy).orElse(null);
                if (saved == null) return null;
                if (saved.contains("min_range")) saved.put("min_reach", saved.get("min_range"));
                if (saved.contains("max_range")) saved.put("max_reach", saved.get("max_range"));
                range = AttackRange.CODEC.parse(NbtOps.INSTANCE, saved).result().orElse(null);
            }
            if (backup.contains("use_effects"))
                effects = UseEffects.CODEC.parse(NbtOps.INSTANCE, backup.get("use_effects")).result().orElse(null);
        }
        return kinetic == null || range == null || effects == null ? null : new Profile(kinetic, range, effects);
    }
}
