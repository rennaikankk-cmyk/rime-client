package dev.guizhou.util.elytra;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Verifies the 1.21.1 spear adapter against the actual Via writer shapes
 * (VB root id/backup compound; VV original_hashes with exact backup_tag provenance).
 */
public final class SpearCapabilityTest {
    public static void main(String[] args) {
        initializeRegistries();

        // 1. Verified VB id alone -> native default table for diamond (1301).
        ItemStack diamond = stack(Items.DIAMOND_SWORD, 1301, null);
        SpearCapability1211.Spec spec = SpearCapability1211.detect(diamond);
        expect(spec != null, "VB id on a sword identifies a translated spear");
        expect(spec.delayTicks() == 10, "diamond native delay is 10");
        expect(spec.activeDurationTicks() == 200, "diamond native active window is 200");
        expect(SpearCombat.damageDurationTicks(diamond) == 210,
                "public duration API is delay + active window");
        expect(spec.maxRange() == 4.5, "diamond native max range is 4.5");
        expect(spec.minRelativeSpeedPerSecond() == 4.6f, "native relative-speed floor is 4.6/s");
        expect(SpearCapability1211.isStabSupported() == false, "stab stays unsupported");

        // 2. Server overrides inside the VB backup supersede the defaults.
        CompoundTag backup = new CompoundTag();
        CompoundTag kinetic = new CompoundTag();
        kinetic.putInt("delay_ticks", 5);
        kinetic.putInt("contact_cooldown_ticks", 7);
        CompoundTag conditions = new CompoundTag();
        conditions.putInt("max_duration_ticks", 100);
        conditions.putFloat("min_speed", 2.5f);
        conditions.putFloat("min_relative_speed", 6.0f);
        kinetic.put("damage_conditions", conditions);
        backup.put("kinetic_weapon", kinetic);
        CompoundTag range = new CompoundTag();
        range.putFloat("min_range", 1.5f);
        range.putFloat("max_range", 6.0f);
        range.putFloat("min_creative_reach", 1.5f);
        range.putFloat("max_creative_reach", 8.0f);
        range.putFloat("hitbox_margin", 0.25f);
        backup.put("attack_range", range);
        ItemStack overridden = stack(Items.NETHERITE_SWORD, 1302, backup);
        SpearCapability1211.Spec custom = SpearCapability1211.detect(overridden);
        expect(custom != null, "override stack still detects");
        expect(custom.delayTicks() == 5, "server delay override wins");
        expect(custom.contactCooldownTicks() == 7, "server contact-cooldown override wins");
        expect(custom.activeDurationTicks() == 100, "server active-window override wins");
        expect(custom.minSpeedPerSecond() == 2.5f, "server absolute speed floor wins");
        expect(custom.minRelativeSpeedPerSecond() == 6.0f, "server relative speed floor wins");
        expect(custom.maxRange() == 6.0, "server max-range override wins (float field)");
        expect(custom.hitboxMargin() == 0.25, "server margin override wins");
        expect(SpearCombat.chargeTicks(overridden) == 5, "chargeTicks reads the override");

        // 3. VV original_hashes accepted only with the exact ViaBackwards backup_tag.
        expect(stackWithHashes(1296, SpearCapability1211.VB_TAG) != null
                        && stackWithHashes(1296, SpearCapability1211.VB_TAG).delayTicks() == 15,
                "VV id with verified backup_tag uses the wood defaults");
        expect(stackWithHashes(1296, "VB|Protocol1_21_9To1_21_5") == null,
                "foreign backup_tag is rejected");
        expect(stackWithHashes(1296, null) == null, "VV id without backup_tag is rejected");

        // 4. Range and material gates.
        expect(SpearCapability1211.detect(stack(Items.DIAMOND_SWORD, 1295, null)) == null,
                "id below the verified range is rejected");
        expect(SpearCapability1211.detect(stack(Items.DIAMOND_SWORD, 1303, null)) == null,
                "id above the verified range is rejected");
        // note: no item-tag gate — the closed VB id range is the provenance; a stick
        // carrying a verified VB id would be a genuine translated-spear anomaly.
        expect(SpearCapability1211.detect(ItemStack.EMPTY) == null, "empty stack is not a spear");
        expect(SpearCapability1211.detect(new ItemStack(Items.DIAMOND_SWORD)) == null,
                "plain sword without Via metadata is not a spear");

        System.out.println("SpearCapabilityTest: all checks passed");
    }

    /** 1.21.1 keeps bootstrap on the server main class; touching it forces the
     *  static item registry population this offline test needs. */
    private static void initializeRegistries() {
        // Bootstrap needs the shared game version before datafixers can build.
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            Class<?> server = Class.forName("net.minecraft.server.Bootstrap");
            server.getMethod("bootStrap").invoke(null);
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("Cannot bootstrap registries", missing);
        }
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int vbId, CompoundTag backup) {
        ItemStack stack = new ItemStack(item);
        CompoundTag root = new CompoundTag();
        root.putInt(SpearCapability1211.VB_ID, vbId);
        if (backup != null) root.put(SpearCapability1211.VB_BACKUP, backup);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return stack;
    }

    private static SpearCapability1211.Spec stackWithHashes(int id, String backupTag) {
        ItemStack stack = new ItemStack(Items.WOODEN_SWORD);
        CompoundTag root = new CompoundTag();
        CompoundTag hashes = new CompoundTag();
        hashes.putInt("id", id);
        if (backupTag != null) hashes.putString("backup_tag", backupTag);
        root.put(SpearCapability1211.VV_HASHES, hashes);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return SpearCapability1211.detect(stack);
    }

    private static void expect(boolean actual, String message) {
        if (!actual) throw new AssertionError(message);
    }
}
