package dev.guizhou.util.elytra;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

public final class MappedCombatItemsTest {
    private static int checks;
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        check(MappedCombatItems.isMace(new ItemStack(Items.MACE)), "native mace");
        ItemStack axe = new ItemStack(Items.NETHERITE_AXE);
        axe.set(DataComponents.CUSTOM_NAME, Component.literal("1.21 Mace"));
        check(!MappedCombatItems.isMace(axe), "display name alone is not an identity");
        CompoundTag mace = new CompoundTag();
        mace.putInt(MappedCombatItems.MACE_ID, 1093);
        axe.set(DataComponents.CUSTOM_DATA, CustomData.of(mace));
        check(MappedCombatItems.isMace(axe), "captured Via 1.20.4 mace identity");
        mace.putInt(MappedCombatItems.MACE_ID, 1092);
        axe.set(DataComponents.CUSTOM_DATA, CustomData.of(mace));
        check(!MappedCombatItems.isMace(axe), "neighboring item is not a mace");
        mace.putString(MappedCombatItems.MACE_ID, "1093");
        axe.set(DataComponents.CUSTOM_DATA, CustomData.of(mace));
        check(!MappedCombatItems.isMace(axe), "malformed numeric identity");
        System.out.println("MappedCombatItemsTest passed: " + checks + " checks");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
