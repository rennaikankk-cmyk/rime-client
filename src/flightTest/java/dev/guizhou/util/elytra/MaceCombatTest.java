package dev.guizhou.util.elytra;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class MaceCombatTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        check(MaceCombat.isMace(new ItemStack(Items.MACE), false), "Native mace");
        ItemStack axe = new ItemStack(Items.NETHERITE_AXE);
        check(!MaceCombat.isMace(axe, true), "Ordinary axe");
        axe.set(DataComponents.CUSTOM_NAME, Component.translatable("vb.item.mace"));
        check(!MaceCombat.isMace(axe, true), "Renaming cannot change weapon identity");
        CompoundTag tag = new CompoundTag();
        tag.putInt("VB|Protocol1_20_5To1_20_3|id", 1093);
        axe.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        check(MaceCombat.isMace(axe, true), "Recorded ViaBackwards mace marker");
        check(!MaceCombat.isMace(axe, false), "Marker requires Via legacy protocol");
        ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
        sword.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        check(!MaceCombat.isMace(sword, true), "Unexpected fallback item rejected");
        tag.putInt("VB|Protocol1_20_5To1_20_3|id", 1092);
        axe.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        check(!MaceCombat.isMace(axe, true), "Different original item rejected");
        check(!MaceCombat.isMace(ItemStack.EMPTY, true), "Empty item");
        System.out.println("MaceCombatTest passed: 8 checks");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
