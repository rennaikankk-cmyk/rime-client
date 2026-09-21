package dev.guizhou.util.combat;

import dev.guizhou.util.EnchantmentUtil;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.traits.Util;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Map;
import java.util.WeakHashMap;

/** Main-thread cache shared by combat modules. Never hand an Entry or a live entity to a worker. */
public final class CombatSnapshotService implements Util {
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Map<LivingEntity, Entry> ENTRIES = new WeakHashMap<>();
    private static Object level, player;
    private static long revision, identities;

    private CombatSnapshotService() {}

    public static CombatantSnapshot get(LivingEntity entity) {
        if (!mc.isSameThread()) throw new IllegalStateException("Combat snapshot capture requires the client thread");
        if (level != mc.level || player != mc.player) {
            ENTRIES.clear();
            level = mc.level;
            player = mc.player;
            revision++;
        }
        var p = entity.position();
        var b = entity.getBoundingBox();
        var effect = entity.getEffect(MobEffects.DAMAGE_RESISTANCE);
        Key key = new Key(entity.getId(), p.x, p.y, p.z, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ,
                entity.getHealth(), entity.getAbsorptionAmount(),
                (float)entity.getAttributeValue(Attributes.ARMOR),
                (float)entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                effect == null ? -1 : effect.getAmplifier(), entity.isAlive() && !entity.isRemoved());
        Entry cached = ENTRIES.get(entity);
        if (cached != null && cached.identity.get() != entity) cached = null;
        boolean equipmentSame = cached != null && sameEquipment(entity, cached.equipment);
        if (cached != null && cached.key.equals(key) && equipmentSame) {
            ClientDiagnostics.count("combat.snapshot.hit", 1);
            return cached.snapshot;
        }
        int protection = equipmentSame ? cached.snapshot.protection() : 0;
        int assumed = equipmentSame ? cached.snapshot.assumedProtection() : 0;
        ItemStack[] equipment = equipmentSame ? cached.equipment : new ItemStack[ARMOR.length];
        if (equipmentSame) ClientDiagnostics.count("combat.snapshot.profileReuse", 1);
        else for (int i = 0; i < ARMOR.length; i++) {
            ItemStack stack = entity.getItemBySlot(ARMOR[i]);
            equipment[i] = stack.copy();
            if (stack.isEmpty()) continue;
            int points = EnchantmentUtil.getLevel(Enchantments.PROTECTION, stack)
                    + 2 * EnchantmentUtil.getLevel(Enchantments.BLAST_PROTECTION, stack);
            protection += points;
            assumed += points == 0 ? 8 : points;
        }
        CombatantSnapshot value = new CombatantSnapshot(key.id, cached == null ? ++identities : cached.snapshot.identity(), ++revision, key.x, key.y, key.z,
                key.minX, key.minY, key.minZ, key.maxX, key.maxY, key.maxZ, key.health, key.absorption,
                key.armor, key.toughness, ExplosionDamageModel.resistanceMultiplier(key.resistance),
                Math.min(20, protection), Math.min(20, assumed), key.alive);
        // A hard bound also protects long sessions when removed entities are retained by other modules.
        if (ENTRIES.size() >= 256) ENTRIES.clear();
        ENTRIES.put(entity, new Entry(new java.lang.ref.WeakReference<>(entity), key, equipment, value));
        ClientDiagnostics.count("combat.snapshot.miss", 1);
        return value;
    }

    private static boolean sameEquipment(LivingEntity entity, ItemStack[] before) {
        for (int i = 0; i < ARMOR.length; i++) {
            if (!ItemStack.matches(before[i], entity.getItemBySlot(ARMOR[i]))) return false;
        }
        return true;
    }

    private record Key(int id, double x, double y, double z,
                       double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                       float health, float absorption, float armor, float toughness, int resistance, boolean alive) {}
    private record Entry(java.lang.ref.WeakReference<LivingEntity> identity, Key key, ItemStack[] equipment, CombatantSnapshot snapshot) {}
}
