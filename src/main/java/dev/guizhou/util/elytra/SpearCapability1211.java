package dev.guizhou.util.elytra;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** 1.21.11 server spears translated for a 1.21.1 client, including external ViaProxy.
 * Keys and defaults were checked against the Via writers and the native item registry.
 * No item display name or unqualified numeric id establishes this capability. */
public final class SpearCapability1211 {
    public static final String VB_TAG = "VB|Protocol1_21_11To1_21_9";
    public static final String VB_ID = VB_TAG + "|id";
    public static final String VB_BACKUP = VB_TAG + "|backup";
    public static final String VV_HASHES = "VV|original_hashes";
    public static final int SPEAR_ID_MIN = 1296, SPEAR_ID_MAX = 1302;
    private static final int[] DELAY = {15,14,13,12,14,10,8};
    private static final int[] DAMAGE = {300,275,250,225,275,200,175};
    private static final int[] KNOCKBACK = {200,180,165,135,170,130,110};
    private static final int[] DISMOUNT = {100,90,80,50,70,60,50};
    private static final float[] DISMOUNT_SPEED = {14,10,9,8,10,7.5f,7};
    private SpearCapability1211() {}

    public record Condition(int maxDurationTicks, float minSpeed, float minRelativeSpeed) {
        public boolean test(int activeTicks, double speed, double relativeSpeed) {
            return activeTicks >= 0 && activeTicks <= maxDurationTicks
                    && Double.isFinite(speed) && Double.isFinite(relativeSpeed)
                    && speed >= minSpeed && relativeSpeed >= minRelativeSpeed;
        }
    }

    public record Spec(int itemId, int delayTicks, int activeDurationTicks, int contactCooldownTicks,
                       double minRange, double maxRange, double minCreativeRange, double maxCreativeRange,
                       double hitboxMargin, double mobFactor,
                       float minSpeedPerSecond, float minRelativeSpeedPerSecond,
                       Condition damage, Condition knockback, Condition dismount) {
        public boolean threatens(int useTicks, double speed, double relativeSpeed) {
            if (useTicks < delayTicks) return false;
            int active = useTicks - delayTicks;
            return damage != null && damage.test(active,speed,relativeSpeed)
                    || knockback != null && knockback.test(active,speed,relativeSpeed)
                    || dismount != null && dismount.test(active,speed,relativeSpeed);
        }
    }

    public static Spec detect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        try {
            CompoundTag root = custom.copyTag();
            int id;
            if (root.contains(VB_ID)) id = integer(root, VB_ID, -1);
            else {
                if (!root.contains(VV_HASHES, CompoundTag.TAG_COMPOUND)) return null;
                CompoundTag hashes = root.getCompound(VV_HASHES);
                if (!VB_TAG.equals(hashes.getString("backup_tag"))) return null;
                id = integer(hashes,"id",-1);
            }
            if (id < SPEAR_ID_MIN || id > SPEAR_ID_MAX) return null;
            if (root.contains(VB_BACKUP) && !root.contains(VB_BACKUP, CompoundTag.TAG_COMPOUND)) return null;
            CompoundTag backup = root.getCompound(VB_BACKUP);
            int i=id-SPEAR_ID_MIN, delay=DELAY[i], contact=10;
            Condition damage=new Condition(DAMAGE[i],0,4.6f);
            Condition knockback=new Condition(KNOCKBACK[i],5.1f,0);
            Condition dismount=new Condition(DISMOUNT[i],DISMOUNT_SPEED[i],0);
            if (backup.contains("kinetic_weapon")) {
                if (!backup.contains("kinetic_weapon",CompoundTag.TAG_COMPOUND)) return null;
                CompoundTag kinetic=backup.getCompound("kinetic_weapon");
                delay=integer(kinetic,"delay_ticks",delay);
                contact=integer(kinetic,"contact_cooldown_ticks",contact);
                // Explicit components replace defaults; absent optional effects stay absent.
                damage=condition(kinetic,"damage_conditions");
                knockback=condition(kinetic,"knockback_conditions");
                dismount=condition(kinetic,"dismount_conditions");
            }
            double min=2,max=4.5,creativeMin=2,creativeMax=6.5,margin=.125,mob=.5;
            if (backup.contains("attack_range")) {
                if (!backup.contains("attack_range",CompoundTag.TAG_COMPOUND)) return null;
                CompoundTag range=backup.getCompound("attack_range");
                min=number(range,"min_range",min);max=number(range,"max_range",max);
                creativeMin=number(range,"min_creative_reach",creativeMin);
                creativeMax=number(range,"max_creative_reach",creativeMax);
                margin=number(range,"hitbox_margin",margin);mob=number(range,"mob_factor",mob);
            }
            if (max<min || creativeMax<creativeMin) return null;
            return new Spec(id,delay,damage==null?-1:damage.maxDurationTicks(),contact,
                    min,max,creativeMin,creativeMax,margin,mob,
                    damage==null?0:damage.minSpeed(),damage==null?0:damage.minRelativeSpeed(),
                    damage,knockback,dismount);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static Condition condition(CompoundTag parent,String key) {
        if (!parent.contains(key)) return null;
        if (!parent.contains(key,CompoundTag.TAG_COMPOUND)) throw new IllegalArgumentException(key);
        CompoundTag tag=parent.getCompound(key);
        return new Condition(integer(tag,"max_duration_ticks",0),
                speed(tag,"min_speed"),speed(tag,"min_relative_speed"));
    }
    private static float speed(CompoundTag tag,String key) {
        if (!tag.contains(key)) return 0;
        if (!tag.contains(key,CompoundTag.TAG_ANY_NUMERIC)) throw new IllegalArgumentException(key);
        float value=tag.getFloat(key);
        if (!Float.isFinite(value)) throw new IllegalArgumentException(key);
        return value; // Native condition codecs accept signed float thresholds.
    }
    private static int integer(CompoundTag tag,String key,int fallback) {
        if (!tag.contains(key)) return fallback;
        if (!tag.contains(key,CompoundTag.TAG_ANY_NUMERIC)) throw new IllegalArgumentException(key);
        double value=tag.getDouble(key);
        if (!Double.isFinite(value) || value<0 || value>Integer.MAX_VALUE || value!=Math.rint(value))
            throw new IllegalArgumentException(key);
        return (int)value;
    }
    private static double number(CompoundTag tag,String key,double fallback) {
        if (!tag.contains(key)) return fallback;
        if (!tag.contains(key,CompoundTag.TAG_ANY_NUMERIC)) throw new IllegalArgumentException(key);
        double value=tag.getDouble(key);
        if (!Double.isFinite(value) || value<0 || value>Float.MAX_VALUE) throw new IllegalArgumentException(key);
        return value;
    }
    /** Native held use continues beyond the damage window until explicitly released. */
    public static int heldUseDuration(ItemStack stack) { return detect(stack)==null?0:72000; }
    public static boolean isStabSupported() { return false; }
}
