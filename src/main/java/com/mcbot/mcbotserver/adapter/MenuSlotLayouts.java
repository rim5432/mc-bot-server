package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.menu.SlotRole;
import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;

/**
 * THE menu-kind table: one row per vanilla menu kind the bridge
 * opens, binding the engine menu class, its wire-kind aliases (the
 * snapshot {@code type()} vocabulary), and the index->role layout.
 * BindingMenu resolves slots through it and MenuVerbs resolves intent
 * roles through it, so a new kind extends THIS table in the same
 * change that adds it - there is no second place to update.
 *
 * <p>Extracted from BindingMenu so the write-half bridge stops
 * carrying layout tables (god-class paydown, 2026-08-27); promoted
 * to the kind authority 2026-08-28 when the engine-class chain and
 * the wire-kind chain had drifted into two vocabularies.
 */
final class MenuSlotLayouts {

    private MenuSlotLayouts() {}

    /** CraftingTable layout: slot 0 result, 1-9 grid, then player region. */
    public static SlotRole craftingRole(int index, int size) {
        if (index == 0) {
            return SlotRole.RESULT;
        }
        if (index <= 9) {
            return SlotRole.GRID;
        }
        return index <= size - 9 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Survival-inventory layout: 0 result, 1-4 grid, 5-8 armor,
     * last offhand; the rest is the standard main/hotbar split.
     */
    public static SlotRole inventoryRole(int index, int size) {
        if (index == 0) {
            return SlotRole.RESULT;
        }
        if (index <= 4) {
            return SlotRole.GRID;
        }
        if (index <= 8) {
            return SlotRole.ARMOR;
        }
        if (index == size - 1) {
            return SlotRole.OFFHAND;
        }
        return index <= 35 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Furnace family (furnace / blast_furnace / smoker): vanilla
     * AbstractFurnaceMenu adds slot 0 = input, 1 = fuel, 2 =
     * result, then the standard 27 main + 9 hotbar player region.
     */
    public static SlotRole furnaceRole(int index, int size) {
        if (index == 0) {
            return SlotRole.INPUT;
        }
        if (index == 1) {
            return SlotRole.FUEL;
        }
        if (index == 2) {
            return SlotRole.OUTPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Anvil layout (ItemCombinerMenu): vanilla slots 0 = left input
     * (target item), 1 = right input (material / enchantment book),
     * 2 = result, then the standard 27 main + 9 hotbar. Both inputs
     * share the INPUT role in v1 — the harness addresses the right
     * input by flat index when it needs material-specific placement.
     */
    public static SlotRole anvilRole(int index, int size) {
        if (index == 0 || index == 1) {
            return SlotRole.INPUT;
        }
        if (index == 2) {
            return SlotRole.OUTPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Brewing stand layout: vanilla BrewingStandMenu adds slots 0-2 =
     * bottles (input water bottle, output finished potion - same slot),
     * 3 = ingredient (nether wart, glowstone, redstone, ...), 4 = fuel
     * (blaze powder), then the standard 27 main + 9 hotbar.
     */
    public static SlotRole brewingRole(int index, int size) {
        if (index <= 2) {
            return SlotRole.BOTTLE;
        }
        if (index == 3) {
            return SlotRole.INGREDIENT;
        }
        if (index == 4) {
            return SlotRole.FUEL;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Stonecutter layout: vanilla StonecutterMenu adds slot 0 = input
     * (stone material), slot 1 = result (cut block), then the standard
     * 27 main + 9 hotbar. The recipe list is a UI-side selection, not
     * a slot — the harness picks the recipe by clicking the result after
     * the input is placed.
     */
    public static SlotRole stonecutterRole(int index, int size) {
        if (index == 0) {
            return SlotRole.INPUT;
        }
        if (index == 1) {
            return SlotRole.OUTPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Three-input-one-output layout shared by SmithingMenu (template,
     * base, additional -> result) and LoomMenu (banner, dye, pattern
     * -> result). Slots 0-2 = INPUT, slot 3 = OUTPUT, then the
     * standard 27 main + 9 hotbar.
     */
    public static SlotRole threeInputRole(int index, int size) {
        if (index <= 2) {
            return SlotRole.INPUT;
        }
        if (index == 3) {
            return SlotRole.OUTPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Enchanting table layout: vanilla EnchantmentMenu adds slot 0 =
     * item (enchant target), slot 1 = lapis lazuli, then the standard
     * 27 main + 9 hotbar. No output slot — the enchanted item stays in
     * slot 0 after a button click (enchantment id 0/1/2).
     */
    public static SlotRole enchantingRole(int index, int size) {
        if (index == 0 || index == 1) {
            return SlotRole.INPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Beacon layout: vanilla BeaconMenu adds slot 0 = payment (iron/
     * gold/diamond/emerald/netherite ingot), then the standard 27 main
     * + 9 hotbar. No output slot — effects are selected via the beacon
     * effect UI, not a slot transaction.
     */
    public static SlotRole beaconRole(int index, int size) {
        if (index == 0) {
            return SlotRole.INPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Horse inventory layout: vanilla HorseInventoryMenu adds the
     * horse's own slots first (slot 0 = armor, slot 1 = carpet/saddle
     * or empty, slots 2+ = chest slots for donkeys/mules/llamas with
     * chests), then the standard 27 main + 9 hotbar. The horse slot
     * count = size - 36, so chest slots run from index 2 to size-37
     * (empty for plain horses).
     */
    public static SlotRole horseRole(int index, int size) {
        if (index == 0) {
            return SlotRole.ARMOR;
        }
        if (index == 1) {
            return SlotRole.CONTAINER;
        }
        if (index <= size - 37) {
            return SlotRole.CONTAINER;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /** Container-menu fallback: leading container block, then the
     *  standard 27 main + 9 hotbar. Also the role table for kinds the
     *  table does not know yet. */
    public static SlotRole containerRole(int index, int size) {
        if (index <= size - 37) {
            return SlotRole.CONTAINER;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Lectern layout: a single book slot (index 0), no player region —
     * the lectern menu is a read-only book viewer, not an inventory.
     * The containerRole fallback would mislabel slot 0 as HOTBAR because
     * size-37 is negative, so LecternMenu needs its own row.
     */
    public static SlotRole lecternRole(int index, int size) {
        return SlotRole.CONTAINER;
    }

    /** Role table for one kind: pure index->role arithmetic. */
    @FunctionalInterface
    interface RoleTable {
        SlotRole role(int index, int size);
    }

    /**
     * One supported menu kind: the engine dispatch class, the
     * wire-kind aliases whose snapshots carry differentiated station
     * roles, and the role table. {@code wireKinds} is EMPTY for kinds
     * the harness addresses by structural role only (crafting grid,
     * own inventory) - the alias list is what makes INPUT/FUEL/OUTPUT
     * intents pass through instead of landing on CONTAINER.
     *
     * @param engineMenu menu class this row dispatches on; never null
     * @param wireKinds  snapshot type() aliases; never null
     * @param table      index->role arithmetic; never null
     */
    record KindRow(Class<?> engineMenu, List<String> wireKinds, RoleTable table) {}

    /**
     * The rows, superclass before subclass where a hierarchy exists
     * (blast furnace and smoker menus are AbstractFurnaceMenu
     * subclasses and share its row). New kinds append here.
     */
    private static final List<KindRow> KINDS = List.of(
            new KindRow(CraftingMenu.class, List.of(), MenuSlotLayouts::craftingRole),
            new KindRow(InventoryMenu.class, List.of(), MenuSlotLayouts::inventoryRole),
            new KindRow(
                    AbstractFurnaceMenu.class,
                    List.of("furnace", "blast_furnace", "smoker"),
                    MenuSlotLayouts::furnaceRole),
            new KindRow(AnvilMenu.class, List.of("anvil"), MenuSlotLayouts::anvilRole),
            new KindRow(BrewingStandMenu.class, List.of("brewing_stand"), MenuSlotLayouts::brewingRole),
            new KindRow(GrindstoneMenu.class, List.of("grindstone"), MenuSlotLayouts::anvilRole),
            new KindRow(StonecutterMenu.class, List.of("stonecutter"), MenuSlotLayouts::stonecutterRole),
            new KindRow(CartographyTableMenu.class, List.of("cartography_table"), MenuSlotLayouts::anvilRole),
            new KindRow(LecternMenu.class, List.of("lectern"), MenuSlotLayouts::lecternRole),
            new KindRow(SmithingMenu.class, List.of("smithing_table"), MenuSlotLayouts::threeInputRole),
            new KindRow(LoomMenu.class, List.of("loom"), MenuSlotLayouts::threeInputRole),
            new KindRow(EnchantmentMenu.class, List.of("enchanting_table"), MenuSlotLayouts::enchantingRole),
            new KindRow(BeaconMenu.class, List.of("beacon"), MenuSlotLayouts::beaconRole),
            new KindRow(MerchantMenu.class, List.of("merchant"), MenuSlotLayouts::anvilRole),
            new KindRow(HorseInventoryMenu.class, List.of("horse"), MenuSlotLayouts::horseRole));

    /**
     * The role of one flat slot on the given open menu: the kind's own
     * table row, or the container fallback for chest-like and unknown
     * menus.
     *
     * @param menu  the open engine menu; never null
     * @param index flat slot index within the menu
     * @param size  the menu's total slot count
     * @return the slot's role; never null
     */
    public static SlotRole roleOf(AbstractContainerMenu menu, int index, int size) {
        for (KindRow kind : KINDS) {
            if (kind.engineMenu().isInstance(menu)) {
                return kind.table().role(index, size);
            }
        }
        return containerRole(index, size);
    }

    /**
     * Whether the wire kind carries differentiated station roles
     * (INPUT/FUEL/OUTPUT/...), or whether non-CONTAINER intent roles
     * must land on the undifferentiated CONTAINER region.
     *
     * @param wireType the snapshot's type() vocabulary; never null
     * @return true when intents keep their role for this kind
     */
    public static boolean hasDifferentiatedRoles(String wireType) {
        for (KindRow kind : KINDS) {
            if (kind.wireKinds().contains(wireType)) {
                return true;
            }
        }
        return false;
    }
}
