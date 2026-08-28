package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.countItems;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.localToCell;
import static com.mcbot.mcbotserver.gametest.GametestRig.positionOf;
import static com.mcbot.mcbotserver.gametest.GametestRig.reached;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;
import static com.mcbot.mcbotserver.gametest.GametestRig.submitGoto;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BotPlayerFacade;
import com.mcbot.mcbotserver.adapter.MenuOpener;
import com.mcbot.mcbotserver.adapter.RecipeCatalog;
import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.menu.MenuPlanner;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Crafting and recipe acceptance, in-engine: raw-grid table crafts
 * with vanilla onTake semantics, grid-return close paths (including
 * the phantom-compartment regressions), planner-driven full loops
 * through the actor's menu transactions, the recipe catalog lockstep
 * against the live datapack, and the three-leg Phase 3 acceptance
 * pull-craft-bank.
 *
 * <p>Contract: see boundaries.md section A, issue 0007 Phases 2-3.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotCraftingGameTests {

    private BotCraftingGameTests() {}

    /**
     * Scenario: the bot opens a crafting table via {@link MenuOpener},
     * fills the 3x3 grid with diamonds, and takes the resulting diamond
     * block. This validates the Phase 2 crafting-table disclosure end to
     * end: {@link com.mcbot.mcbotserver.adapter.BotCraftingMenu} bypasses
     * the vanilla {@code slotChangedCraftingGrid} ServerPlayer cast
     * (issue 0007 risk), {@link com.mcbot.mcbotserver.adapter.BindingMenu}
     * drives {@code clicked()} with a no-op synchronizer, and
     * {@code ResultSlot.onTake} consumes the grid materials on take.
     *
     * <p>CraftingMenu slot layout: 0=result, 1-9=crafting grid (3x3),
     * 10-36=player main, 37-45=hotbar. The bot starts with 9 diamonds in
     * hotbar slot 0 (menu slot 37); the result lands in hotbar slot 1
     * (menu slot 38).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void craftsDiamondBlockAtTable(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Place a crafting table one block south of the bot.
        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        // MenuOpener reads world-absolute positions (same as the level
        // the body lives in); convert from template-local.
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        // Give the bot 9 diamonds in hotbar slot 0.
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND, 9));
        checkEquals(9, rig.body().getInventory().container().getItem(0).getCount(), "setup: 9 diamonds in hotbar 0");

        // Create facade + opener, open the crafting table.
        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menuOpt = opener.open(tableAbs);
        check(menuOpt.isPresent(), "crafting table must open a menu");
        var menu = menuOpt.get();
        checkEquals("crafting_table", menu.snapshot().type(), "menu type must be crafting_table");

        // Fill the 3x3 crafting grid (menu slots 1-9) with one diamond
        // each. Access the CraftingContainer through slot 1's container
        // (all grid slots share the same container).
        var rawMenu = menu.rawMenu();
        CraftingContainer craftSlots = (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 9; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }
        // Trigger result recomputation. BotCraftingMenu.slotsChanged
        // bypasses the vanilla ServerPlayer cast and resolves the recipe
        // directly against the server recipe manager.
        rawMenu.slotsChanged(craftSlots);

        // Verify the result slot holds a diamond block.
        ItemStack result = rawMenu.slots.get(0).getItem();
        check(result.is(Items.DIAMOND_BLOCK), "result must be diamond_block, got " + result.getItem());
        checkEquals(1, result.getCount(), "result must be 1 diamond block");

        // Disclosure shape: roles and sourcePos are in the snapshot.
        var snap = menu.snapshot();
        checkEquals(SlotRole.RESULT, snap.slot(0).role(), "slot 0 must be the RESULT role");
        checkEquals(SlotRole.GRID, snap.slot(1).role(), "slot 1 must be the GRID role");
        checkEquals(SlotRole.HOTBAR, snap.slot(38).role(), "slot 38 must be the HOTBAR role");
        checkEquals(SlotRole.MAIN, snap.slot(10).role(), "slot 10 must be the MAIN role");
        checkEquals(
                tableAbs,
                snap.sourcePos() == null
                        ? null
                        : new BlockPos(
                                snap.sourcePos().x(),
                                snap.sourcePos().y(),
                                snap.sourcePos().z()),
                "the crafting-table menu must carry its source position");

        // Take the result: left-click (button 0) the result slot (0).
        // ResultSlot.onTake consumes the 9 grid diamonds.
        menu.click(0, 0, MenuClick.PICKUP);
        checkEquals(
                "minecraft:diamond_block",
                menu.snapshot().carried().itemId(),
                "carried must be diamond_block after take");
        checkEquals(1, menu.snapshot().carried().count(), "carried must be 1 diamond block");

        // Verify grid materials were consumed by ResultSlot.onTake.
        for (int i = 0; i < 9; i++) {
            check(craftSlots.getItem(i).isEmpty(), "crafting grid slot " + i + " must be empty after take");
        }

        // Place the diamond block into hotbar slot 1 (menu slot 38).
        menu.click(38, 0, MenuClick.PICKUP);
        check(menu.snapshot().carried().isEmpty(), "carried must be empty after placing in hotbar 1");
        ItemStack placed = rig.body().getInventory().container().getItem(1);
        check(placed.is(Items.DIAMOND_BLOCK), "diamond_block must be in hotbar 1, got " + placed.getItem());
        checkEquals(1, placed.getCount(), "hotbar 1 must hold 1 diamond block");

        // The original 9 diamonds in hotbar 0 were never touched (the
        // grid was filled directly in test setup, not via clicks).
        checkEquals(
                9,
                rig.body().getInventory().container().getItem(0).getCount(),
                "hotbar 0 must still hold 9 diamonds (setup-filled grid)");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: closing a crafting menu returns the grid materials to the
     * player inventory. Vanilla closes menus through the ServerPlayer
     * network path, which BotPlayerFacade does not have; furthermore both
     * {@code AbstractContainerMenu.removed} and
     * {@code CraftingMenu.removed -> clearContainer} gate item return on
     * {@code player instanceof ServerPlayer}. Without
     * {@link com.mcbot.mcbotserver.adapter.BindingMenu#close()}
     * the grid items would be silently lost. This test fills the grid
     * directly (no result taken), closes, and verifies all 9 diamonds are
     * returned to the inventory.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void returnsCraftingGridOnClose(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        // Give the bot 9 diamonds in hotbar 0 (separate from grid items).
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND, 9));

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menu = opener.open(tableAbs).orElseThrow();

        // Fill grid with 9 diamonds directly (not via clicks from inventory).
        var rawMenu = menu.rawMenu();
        CraftingContainer craftSlots = (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 9; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }

        // Close: must return grid materials to inventory.
        menu.close();

        // Grid must be empty after close.
        for (int i = 0; i < 9; i++) {
            check(craftSlots.getItem(i).isEmpty(), "crafting grid slot " + i + " must be empty after close");
        }

        // The 9 grid diamonds merged with the 9 setup diamonds in hotbar 0
        // (placeItemBackInInventory prefers merging before finding a free
        // slot). 9 + 9 = 18.
        checkEquals(
                18,
                rig.body().getInventory().container().getItem(0).getCount(),
                "hotbar 0 must hold 18 diamonds (9 setup + 9 returned from grid)");

        // Facade must be back on the inventory menu after close.
        check(
                facade.containerMenu == facade.facadeInventoryMenu(),
                "facade containerMenu must revert to inventory menu after close");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: closing a crafting menu must not corrupt an occupied
     * hotbar 0. The vanilla placeItemBackInInventory inherits its slot
     * search from Inventory; on the bridge that search used to read the
     * phantom items list, fall back to "slot 0 is free" unconditionally,
     * and merge the returned stacks into whatever slot 0 held — the
     * cobble would grow by the return count and the diamonds would
     * vanish. Regression for the phantom slot-search family (P0).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void returnsGridWithoutCorruptingOccupiedSlot0(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        // Slot 0 holds a different, non-full item: the corruption
        // trigger (a merge target that fails the same-item check).
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.COBBLESTONE, 32));

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menu = opener.open(tableAbs).orElseThrow();

        // Mixed grid: 5 diamonds + 4 sticks — two kinds both returning.
        var rawMenu = menu.rawMenu();
        CraftingContainer craftSlots = (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 5; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }
        for (int i = 5; i < 9; i++) {
            craftSlots.setItem(i, new ItemStack(Items.STICK, 1));
        }

        menu.close();

        checkEquals(
                32,
                rig.body().getInventory().container().getItem(0).getCount(),
                "slot 0 cobble count must be unchanged after close");
        check(
                Items.COBBLESTONE.equals(
                        rig.body().getInventory().container().getItem(0).getItem()),
                "slot 0 must still hold cobblestone, not a merged stack");
        checkEquals(5, countItems(rig, Items.DIAMOND), "all 5 grid diamonds must return to the inventory");
        checkEquals(4, countItems(rig, Items.STICK), "all 4 grid sticks must return to the inventory");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: closing a crafting menu with a completely full main
     * inventory must drop the grid materials at the body, not loop
     * forever. The vanilla loop's exit for a full inventory is
     * getFreeSlot() == -1; on the bridge that method used to read the
     * phantom items list (never full), so the loop spun forever on the
     * server tick thread. This test completing at all is the no-hang
     * assertion; the entity check pins where the items went.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void dropsGridMaterialsWhenInventoryFull(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        var level = helper.getLevel();

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        // Every main slot a full cobble stack: no merge space, no free
        // slot — the only correct outcome is the drop path.
        var container = rig.body().getInventory().container();
        for (int i = 0; i < 36; i++) {
            container.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menu = opener.open(tableAbs).orElseThrow();

        var rawMenu = menu.rawMenu();
        CraftingContainer craftSlots = (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 9; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }

        // Before the phantom fix this call never returned.
        menu.close();

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> {
                    var items = level.getEntitiesOfClass(
                            ItemEntity.class, rig.body().getBoundingBox().inflate(3.0));
                    int diamonds = items.stream()
                            .filter(e -> Items.DIAMOND.equals(e.getItem().getItem()))
                            .mapToInt(e -> e.getItem().getCount())
                            .sum();
                    check(
                            diamonds == 9,
                            "the 9 grid diamonds must drop at the body when " + "the inventory is full, saw "
                                    + diamonds);
                }))
                .thenExecuteAfter(0, () -> {
                    checkEquals(
                            36 * 64, countItems(rig, Items.COBBLESTONE), "the full cobble inventory must be untouched");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: closing the facade's inventory menu returns the 2x2
     * crafting-grid materials. Vanilla InventoryMenu.removed gates its
     * grid return on ServerPlayer, and until BotInventoryMenu existed
     * the facade's grid could not even hold materials (the vanilla
     * slotsChanged cast would throw). This test exercises both: setItem
     * into the 2x2 (no crash) and close (materials back).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void returnsInventoryMenuGridOnClose(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menu = opener.openInventory();

        // Fill the 2x2 grid (menu slots 1-4, container slots 0-3).
        var rawMenu = menu.rawMenu();
        CraftingContainer craftSlots = (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 4; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }

        menu.close();

        for (int i = 0; i < 4; i++) {
            check(craftSlots.getItem(i).isEmpty(), "inventory-menu grid slot " + i + " must be empty after close");
        }
        checkEquals(4, countItems(rig, Items.DIAMOND), "the 4 grid diamonds must return to the inventory");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a full craft through the sanctioned boundary-A
     * surface — the actor's menu transactions (ledger 29) driven by
     * the core click-sequence planner. Clicks only, no raw container
     * writes, and no flat-layout arithmetic in the scenario: grid,
     * result and source slots are addressed through the roles the
     * adapter discloses. Chain: openMenu → planGridFill → planTakeResult
     * → closeMenu.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void craftsViaMenuTransactions(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        // 9 diamonds in hotbar 0; they reach the grid through clicks.
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND, 9));

        var actor = rig.actor();
        var view = actor.openMenu(new CellPos(tableAbs.getX(), tableAbs.getY(), tableAbs.getZ()));
        check(view != null, "openMenu must succeed at the table");
        checkEquals("crafting_table", view.type(), "the opened menu must be the crafting table");
        checkEquals(
                new CellPos(tableAbs.getX(), tableAbs.getY(), tableAbs.getZ()),
                view.sourcePos(),
                "the snapshot must carry the table's position");

        // Fill all nine cells from the hotbar stack; the planner
        // resolves sources by role, so the scenario never names a
        // flat index.
        CraftingView craft = CraftingView.of(view);
        for (MenuPlanner.Step step : MenuPlanner.planGridFill(craft, "minecraft:diamond", 0, 1, 2, 3, 4, 5, 6, 7, 8)) {
            view = actor.menuClick(step.slot(), step.button(), step.kind());
        }
        check(view.carried().isEmpty(), "cursor must be spent after the fill");
        checkEquals(
                "minecraft:diamond_block",
                view.slot(craft.result().index()).item().itemId(),
                "the result slot must recompute to diamond_block");

        // Shift-click take: the product lands in the player region
        // and ResultSlot.onTake consumes the nine grid materials.
        for (MenuPlanner.Step step : MenuPlanner.planTakeResult(CraftingView.of(view))) {
            view = actor.menuClick(step.slot(), step.button(), step.kind());
        }
        check(view.carried().isEmpty(), "the product must leave the cursor via quick-move");
        actor.closeMenu();
        check(actor.menuSnapshot() == null, "no menu session may survive closeMenu");

        check(
                countItems(rig, Items.DIAMOND_BLOCK) == 1,
                "exactly one diamond_block must sit in the binding " + "container after the craft");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the Phase 2 acceptance loop verbatim — walk to a
     * crafting table, open it, place materials via planned clicks,
     * take the product, close. Every earlier menu scenario spawned
     * the bot standing next to the table; this one walks there
     * through the goto pipeline first, so the full harness-visible
     * arc (mission → arrival → transaction → product) is pinned.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void walksToTableAndCrafts(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));

        BlockPos tableLocal = new BlockPos(12, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);
        CellPos standCell = localToCell(helper, new BlockPos(11, GametestRig.WALK_Y, 8));

        // Materials ride along before the walk.
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND, 9));

        var mission = submitGoto(rig, standCell);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), standCell), "waiting for arrival beside the table")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.isActive(), "the goto mission must retire on arrival");
                    check(mission.missionSucceeded(), "the walk must be a success");

                    var actor = rig.actor();
                    var view = actor.openMenu(new CellPos(tableAbs.getX(), tableAbs.getY(), tableAbs.getZ()));
                    check(view != null, "openMenu must succeed after walking there");
                    CraftingView craft = CraftingView.of(view);
                    for (var step : MenuPlanner.planGridFill(craft, "minecraft:diamond", 0, 1, 2, 3, 4, 5, 6, 7, 8)) {
                        view = actor.menuClick(step.slot(), step.button(), step.kind());
                    }
                    checkEquals(
                            "minecraft:diamond_block",
                            view.slot(craft.result().index()).item().itemId(),
                            "result must recompute after the planned fill");
                    for (var step : MenuPlanner.planTakeResult(CraftingView.of(view))) {
                        view = actor.menuClick(step.slot(), step.button(), step.kind());
                    }
                    actor.closeMenu();

                    check(
                            countItems(rig, Items.DIAMOND_BLOCK) == 1,
                            "exactly one diamond_block must exist after " + "the loop");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the recipe catalog translates real RecipeManager
     * entries into pure descriptions — shaped only, tag-expanded,
     * pattern-relative coordinates. Pins the Phase 3 query service
     * against the live server datapack (the same lockstep discipline
     * as RuleTableGateTest: shipped data must match code
     * expectations).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void catalogsShapedRecipes(GameTestHelper helper) {
        var catalog = new RecipeCatalog(helper.getLevel());

        // 1.20.1 data: the recipe file is stick.json — singular.
        var sticksOpt = catalog.byId("minecraft:stick");
        check(sticksOpt.isPresent(), "stick must be cataloged");
        RecipeView sticks = sticksOpt.get();
        checkEquals("minecraft:stick", sticks.resultItemId(), "sticks product id");
        checkEquals(4, sticks.resultCount(), "sticks yield per craft");
        checkEquals(1, sticks.patternWidth(), "sticks is a 1-wide column");
        check(sticks.fitsInventoryGrid(), "sticks must fit the inventory menu's 2x2 grid");
        // #minecraft:planks arrives expanded to every plank kind.
        check(sticks.placements().get(0).contains("minecraft:oak_planks"), "tag expansion must list oak planks");
        check(sticks.placements().get(1).contains("minecraft:birch_planks"), "tag expansion must list birch planks");
        check(!sticks.placements().containsKey(2), "a 1x2 pattern must not grow a phantom third cell");

        var blockOpt = catalog.byId("minecraft:diamond_block");
        check(blockOpt.isPresent(), "diamond_block must be cataloged");
        RecipeView block = blockOpt.get();
        checkEquals(3, block.patternWidth(), "diamond_block width");
        check(!block.fitsInventoryGrid(), "diamond_block needs a crafting table");
        for (int pos = 0; pos < 9; pos++) {
            checkEquals(
                    List.of("minecraft:diamond"),
                    block.placements().get(pos),
                    "cell " + pos + " accepts exactly diamond");
        }

        // Shapeless translation: ingredient-index encoding, compact
        // 0..n-1, tag-expanded, 2x2-fit by the count rule.
        var stewOpt = catalog.byId("minecraft:mushroom_stew");
        check(stewOpt.isPresent(), "mushroom_stew (shapeless) must be cataloged");
        RecipeView stew = stewOpt.get();
        check(stew.shapeless(), "mushroom_stew must carry the shapeless flag");
        check(
                stew.placements().keySet().equals(java.util.Set.of(0, 1, 2)),
                "stew ingredients are compact indices 0..2, got "
                        + stew.placements().keySet());
        check(
                stew.placements().values().stream().anyMatch(ids -> ids.contains("minecraft:bowl")),
                "the bowl must be among the stew ingredients");
        check(stew.fitsInventoryGrid(), "3 ingredients fit the 2x2 under the count rule");
        var planksOpt = catalog.byId("minecraft:oak_planks");
        check(planksOpt.isPresent() && planksOpt.get().shapeless(), "oak_planks must catalog as shapeless");
        check(
                planksOpt.get().placements().get(0).stream().anyMatch(id -> id.endsWith("log")),
                "the planks ingredient must accept logs, got "
                        + planksOpt.get().placements().get(0));
        check(catalog.byId("minecraft:no_such_recipe").isEmpty(), "unknown ids resolve empty");

        var stickRecipes = catalog.byResult("minecraft:stick");
        check(
                stickRecipes.stream().anyMatch(r -> r.recipeId().equals("minecraft:stick")),
                "byResult must find the stick recipe among producers");

        helper.succeed();
    }

    /**
     * Scenario: the recipes-list wire verb's backend - {@link
     * RecipeCatalog#list} - paginates a stable, recipeId-sorted view
     * of the translatable catalog: windows are slices of one order,
     * offset clamps to [0,total], limit clamps to [1,200], and
     * shapeless recipes ride the same pages as shaped ones.
     *
     * <p>Coverage ruling (round-3 recon): the catalog reads the live
     * RecipeManager, so this contract is engine-only - no offline
     * layer-1 test can instantiate the adapter honestly.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void paginatesRecipesSorted(GameTestHelper helper) {
        var catalog = new RecipeCatalog(helper.getLevel());

        var full = catalog.list(0, 200);
        check(full.total() > 0, "the server datapack must translate at least one " + "recipe");
        checkEquals(
                (long) Math.min(full.total(), 200),
                (long) full.recipes().size(),
                "page size must be min(total, limit)");

        for (int i = 1; i < full.recipes().size(); i++) {
            check(
                    full.recipes()
                                    .get(i - 1)
                                    .recipeId()
                                    .compareTo(full.recipes().get(i).recipeId())
                            <= 0,
                    "pages must be sorted by recipeId, saw "
                            + full.recipes().get(i - 1).recipeId() + " then "
                            + full.recipes().get(i).recipeId());
        }
        check(
                full.recipes().stream().anyMatch(r -> r.recipeId().equals("minecraft:mushroom_stew")),
                "shapeless recipes must appear in pages alongside shaped ones");

        var head = catalog.list(0, 2);
        checkEquals(2, head.recipes().size(), "a small limit must truncate the page");
        checkEquals(
                (List) full.recipes().subList(0, 2),
                (List) head.recipes(),
                "windows must be stable slices of the one sorted order");

        var tail = catalog.list(full.total(), 10);
        check(tail.recipes().isEmpty(), "an offset at total must yield an empty page, not throw");

        helper.succeed();
    }

    /**
     * Scenario: shapeless translation end-to-end - the catalog emits
     * the ingredient-index encoding for {@code minecraft:oak_planks},
     * {@code planRecipe} fills the first flat grid cell with a log,
     * and the server's arrangement-agnostic matcher resolves the
     * result slot. One log becomes four planks: the offline count
     * rule meets the live RecipeManager.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void craftsShapelessPlanksFromLogs(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.OAK_LOG, 3));

        var catalog = new RecipeCatalog(helper.getLevel());
        var planksOpt = catalog.byId("minecraft:oak_planks");
        check(planksOpt.isPresent(), "oak_planks must be cataloged");
        RecipeView planks = planksOpt.get();
        check(planks.shapeless(), "oak_planks must translate as shapeless");
        checkEquals(4, planks.resultCount(), "oak_planks yields 4 per craft");

        var facade = new BotPlayerFacade(rig.body());
        var menuOpt = new MenuOpener(facade).open(tableAbs);
        check(menuOpt.isPresent(), "crafting table must open a menu");
        var menu = menuOpt.get();

        CraftingView craft = CraftingView.of(menu.snapshot());
        for (var step : MenuPlanner.planRecipe(craft, planks)) {
            menu.click(step.slot(), step.button(), step.kind());
        }
        checkEquals(
                "minecraft:oak_planks",
                menu.snapshot().slot(craft.result().index()).item().itemId(),
                "the shapeless grid must resolve planks");
        for (var step : MenuPlanner.planTakeResult(CraftingView.of(menu.snapshot()))) {
            menu.click(step.slot(), step.button(), step.kind());
        }
        checkEquals(4, countItems(rig, Items.OAK_PLANKS), "one craft must yield 4 planks");
        checkEquals(2, countItems(rig, Items.OAK_LOG), "one log must be consumed");

        helper.succeed();
    }

    /**
     * Scenario: the Phase 3 acceptance loop verbatim — given a
     * recipe id, the bot pulls materials from a chest, crafts at the
     * table, and banks the product back. Three goto legs chain in
     * one sequence; every material movement rides the planner
     * (planWithdraw → planRecipe → planTakeResult → planDeposit),
     * never raw container writes. Timeout is widened past TIMEOUT
     * because three mission arcs must fit inside one test.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 700)
    public static void pullsCraftsAndBanksByRecipeId(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 3));

        BlockPos chestLocal = new BlockPos(4, GametestRig.WALK_Y, 6);
        helper.setBlock(chestLocal, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestLocal);
        var chestEntity = helper.getLevel().getBlockEntity(chestAbs);
        check(chestEntity instanceof ChestBlockEntity, "the chest must carry a block entity to seed");
        ((ChestBlockEntity) chestEntity).setItem(0, new ItemStack(Items.OAK_PLANKS, 16));

        BlockPos tableLocal = new BlockPos(10, GametestRig.WALK_Y, 10);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        CellPos chestStand = localToCell(helper, new BlockPos(4, GametestRig.WALK_Y, 5));
        CellPos tableStand = localToCell(helper, new BlockPos(10, GametestRig.WALK_Y, 9));
        var catalog = new RecipeCatalog(helper.getLevel());
        var stickRecipe = catalog.byId("minecraft:stick").orElse(null);
        check(stickRecipe != null, "the stick recipe must be cataloged");

        var actor = rig.actor();
        GotoProcess[] mission = {null};
        var chestCell = new CellPos(chestAbs.getX(), chestAbs.getY(), chestAbs.getZ());
        var tableCell = new CellPos(tableAbs.getX(), tableAbs.getY(), tableAbs.getZ());

        helper.startSequence()
                // Leg 1: walk to the chest and pull materials.
                .thenExecute(() -> mission[0] = submitGoto(rig, chestStand))
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), chestStand), "waiting for arrival at the chest")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(mission[0].missionSucceeded(), "the walk to the chest must succeed");
                    var view = actor.openMenu(chestCell);
                    check(view != null, "the chest must open");
                    for (var step : MenuPlanner.planWithdraw(view, "minecraft:oak_planks", 2)) {
                        view = actor.menuClick(step.slot(), step.button(), step.kind());
                    }
                    actor.closeMenu();
                    check(
                            countItems(rig, Items.OAK_PLANKS) == 16,
                            "whole-stack withdrawal must land all 16 planks" + ", got "
                                    + countItems(rig, Items.OAK_PLANKS));
                })
                // Leg 2: walk to the table and craft by recipe id.
                .thenExecute(() -> mission[0] = submitGoto(rig, tableStand))
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), tableStand), "waiting for arrival at the table")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(mission[0].missionSucceeded(), "the walk to the table must succeed");
                    var view = actor.openMenu(tableCell);
                    check(view != null, "the table must open");
                    CraftingView craft = CraftingView.of(view);
                    for (var step : MenuPlanner.planRecipe(craft, stickRecipe)) {
                        view = actor.menuClick(step.slot(), step.button(), step.kind());
                    }
                    checkEquals(
                            "minecraft:stick",
                            view.slot(craft.result().index()).item().itemId(),
                            "sticks must resolve on the grid");
                    for (var step : MenuPlanner.planTakeResult(CraftingView.of(view))) {
                        view = actor.menuClick(step.slot(), step.button(), step.kind());
                    }
                    actor.closeMenu();
                    check(
                            countItems(rig, Items.STICK) == 4,
                            "exactly 4 sticks after the craft, got " + countItems(rig, Items.STICK));
                })
                // Leg 3: return to the chest and bank the product.
                .thenExecute(() -> mission[0] = submitGoto(rig, chestStand))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                reached(rig.body(), chestStand),
                                "waiting for arrival back at the chest, at="
                                        + positionOf(rig.body()) + " goal=" + chestStand
                                        + " active=" + mission[0].isActive()
                                        + " ok=" + mission[0].missionSucceeded())))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(mission[0].missionSucceeded(), "the walk back must succeed");
                    var view = actor.openMenu(chestCell);
                    check(view != null, "the chest must reopen");
                    for (var step : MenuPlanner.planDeposit(view, "minecraft:stick")) {
                        view = actor.menuClick(step.slot(), step.button(), step.kind());
                    }
                    actor.closeMenu();

                    var bank = helper.getLevel().getBlockEntity(chestAbs);
                    check(bank instanceof ChestBlockEntity, "the chest entity must persist to the end");
                    int sticksInChest = 0;
                    int planksInChest = 0;
                    var storage = (ChestBlockEntity) bank;
                    for (int i = 0; i < storage.getContainerSize(); i++) {
                        ItemStack stack = storage.getItem(i);
                        if (stack.is(Items.STICK)) {
                            sticksInChest += stack.getCount();
                        }
                        if (stack.is(Items.OAK_PLANKS)) {
                            planksInChest += stack.getCount();
                        }
                    }
                    checkEquals(4, sticksInChest, "the banked sticks must sit in the chest");
                    checkEquals(0, planksInChest, "no planks may ride back into the chest");
                    checkEquals(14, countItems(rig, Items.OAK_PLANKS), "two planks must be consumed by the craft");
                    checkEquals(0, countItems(rig, Items.STICK), "every stick must be banked");

                    rig.body().discard();
                })
                .thenSucceed();
    }
}
