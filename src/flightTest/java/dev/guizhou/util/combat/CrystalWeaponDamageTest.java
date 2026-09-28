package dev.guizhou.util.combat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class CrystalWeaponDamageTest {
    private static int checks;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        run();
    }

    public static void run() {
        checks = 0;
        ItemStack empty = ItemStack.EMPTY;
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemStack axe = new ItemStack(Items.DIAMOND_AXE);
        AttributeInstance damage = new AttributeInstance(Attributes.ATTACK_DAMAGE, ignored -> {});
        damage.setBaseValue(1);
        check(CrystalWeaponDamage.canDamage(damage, empty, empty), "bare hand without weakness");
        effect(damage, false, 0);
        check(!CrystalWeaponDamage.canDamage(damage, empty, empty), "weakness I blocks bare hand");
        check(!CrystalWeaponDamage.canDamage(damage, empty, new ItemStack(Items.END_CRYSTAL)), "crystal cannot clear weakness");
        check(CrystalWeaponDamage.canDamage(damage, empty, sword), "sword clears weakness I");
        check(CrystalWeaponDamage.canDamage(damage, empty, axe), "axe is also valid");
        check(damage.getValue() == 0 && damage.getModifiers().size() == 1, "estimates do not mutate live attributes");
        effect(damage, false, 1);
        check(!CrystalWeaponDamage.canDamage(damage, empty, sword), "weakness II defeats diamond sword");
        check(CrystalWeaponDamage.canDamage(damage, empty, axe), "weakness II still allows diamond axe");
        effect(damage, true, 0);
        check(CrystalWeaponDamage.canDamage(damage, empty, sword), "strength included with weapon damage");
        check(!CrystalWeaponDamage.canDamage(damage, empty, empty), "strength I does not clear weakness II barehanded");
        sword.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)) damage.addOrUpdateTransientModifier(modifier);
        });
        check(!CrystalWeaponDamage.canDamage(damage, empty, empty), "stale sword attribute after swap is removed");
        check(CrystalWeaponDamage.canDamage(damage, sword, sword), "held sword is not double counted");
        effect(damage, false, 2);
        check(!CrystalWeaponDamage.canDamage(damage, sword, sword), "strong weakness is not bypassed by double counting");
        check(!CrystalWeaponDamage.canDamage(null, empty, sword), "missing attribute refuses attack");
        System.out.println("CrystalWeaponDamageTest: " + checks + " checks passed");
    }

    private static void effect(AttributeInstance damage, boolean strength, int amplifier) {
        (strength ? MobEffects.DAMAGE_BOOST : MobEffects.WEAKNESS).value().createModifiers(amplifier, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)) damage.addOrUpdateTransientModifier(modifier);
        });
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
