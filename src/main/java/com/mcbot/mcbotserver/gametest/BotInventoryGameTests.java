package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.countItems;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BindingMenu;
import com.mcbot.mcbotserver.adapter.BotPlayerFacade;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Inventory and world-container menu acceptance, in-engine: item
 * drop/place intents, the binding container's removeItem path,
 * reach refusal, keep-open enforcement, double-chest merge, chest
 * round trips through the actor's transactions, armor placement
 * (head-feet translation), and menu-displacement terminality.
 * Crafting-grid scenarios live in {@link BotCraftingGameTests}.
 *
 * <p>Contract: see boundaries.md section A (menu transactions,
 * ledger 29) and issue 0007.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotInventoryGameTests {

    private BotInventoryGameTests() {}

    /**
     * Scenario: the DropSelected intent on the INTERACT channel spawns
     * the selected hotbar item as a world item entity and clears the
     * source slot. Full-stack drop (vanilla Q).
     *
     * <p>Why a gametest and not an offline assertion: the drop mutates
     * world state (spawns an ItemEntity) and the inventory container —
     * both need a real engine. The offline gate (DropSelectedIntentTest)
     * covers intent shape and channel validation; this pins the full
     * adapter chain: claim → BindingActor INTERACT dispatch → rising-edge
     * gate → BotBodyEntity.dropSelectedItem → spawnAtLocation + slot clear.
     *
     * <p>Rising-edge: the claim is submitted once before the sequence;
     * the first driveTick flushes the actor and fires the drop. The
     * claim expires at flush, so subsequent ticks do not re-drop even
     * though driveUntil keeps driving.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void dropsSelectedItem(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        var level = helper.getLevel();

        // Put 16 diamonds in hotbar slot 0 (the default selected slot).
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND, 16));
        checkEquals(16, rig.body().getInventory().container().getItem(0).getCount(), "setup: 16 diamonds in slot 0");

        // Submit the drop claim. Priority 200 beats any reflex or
        // behavior that might contest INTERACT (none should in an idle
        // body, but the high priority is defensive).
        rig.actor().submit(new Claim(Channel.INTERACT, 200, "gt-drop", new Intent.DropSelected(true)));

        helper.startSequence()
                // First driveTick fires the drop; subsequent ticks let the
                // ItemEntity appear in getEntitiesOfClass (spawnAtLocation
                // registers it immediately, but the entity tick may take one
                // cycle to be visible).
                .thenWaitUntil(driveUntil(rig, () -> {
                    var items = level.getEntitiesOfClass(
                            ItemEntity.class, rig.body().getBoundingBox().inflate(3.0));
                    check(!items.isEmpty(), "an ItemEntity must spawn after DropSelected");
                }))
                .thenExecuteAfter(0, () -> {
                    var items = level.getEntitiesOfClass(
                            ItemEntity.class, rig.body().getBoundingBox().inflate(3.0));
                    ItemEntity dropped = items.get(0);
                    check(
                            Items.DIAMOND.equals(dropped.getItem().getItem()),
                            "the dropped item must be a diamond, got "
                                    + dropped.getItem().getItem());
                    checkEquals(16, dropped.getItem().getCount(), "full-stack drop must spawn all 16 diamonds");
                    check(
                            rig.body().getInventory().container().getItem(0).isEmpty(),
                            "the source slot must be empty after a full-stack drop");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the InteractBlock intent on the INTERACT channel places
     * the selected hotbar block against the clicked face and shrinks the
     * source stack by one. Phase 1 scope: default-state block placement
     * only (no direction-aware states, no use-block / menu opening) —
     * see InteractBlockExecutor Javadoc for the deferred items.
     *
     * <p>Setup: a dirt block sits one cell south of the bot at foot
     * level; the bot holds 16 stone in hotbar slot 0. The claim targets
     * the dirt block's UP face, so the stone lands on top of the dirt
     * (target.relative(UP)). The bot is within bare-hand reach (≈1.4
     * blocks from eye to dirt center).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void placesBlockOnInteract(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        // Target: dirt one cell south at foot level. Click its UP face
        // to place on top of it.
        BlockPos target = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(target, Blocks.DIRT);
        BlockPos placePos = target.above();
        // Template-local coords must read through the helper - a raw
        // level read would interpret them as absolute world position
        // and answer with whatever sits outside the test box.
        check(helper.getBlockState(placePos).isAir(), "setup: placement cell must be air before the test");

        // Give the bot 16 stone in the selected hotbar slot (slot 0).
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.STONE, 16));
        checkEquals(16, rig.body().getInventory().container().getItem(0).getCount(), "setup: 16 stone in slot 0");

        // Intents carry WORLD-absolute cells - the adapter reads them
        // straight out of the level, and its reach gate measures real
        // distances. The rig works in template-local coords, so convert
        // before submitting. hitPos: center of the UP face, absolute;
        // carried for Phase 2 direction-aware placement, unused by the
        // Phase 1 executor.
        BlockPos absTarget = helper.absolutePos(target);
        var hit = new com.mcbot.mcbotserver.api.types.Vec3(
                absTarget.getX() + 0.5, absTarget.getY() + 1.0, absTarget.getZ() + 0.5);

        // Submit the place claim and drive one tick so the actor flushes
        // and the rising-edge gate fires.
        rig.actor()
                .submit(new Claim(
                        Channel.INTERACT,
                        200,
                        "gt-place",
                        new Intent.InteractBlock(
                                GametestRig.cellOf(absTarget), com.mcbot.mcbotserver.api.types.Direction.UP, hit)));
        driveOnly(rig).run();

        helper.startSequence()
                // The block placement is synchronous inside the tick (setBlock
                // is immediate), but the item shrink and the level state may
                // take one cycle to settle in getBlockState.
                .thenWaitUntil(driveUntil(rig, () -> {
                    check(
                            helper.getBlockState(placePos).is(Blocks.STONE),
                            "a stone block must be placed above the dirt target, " + "got "
                                    + helper.getBlockState(placePos));
                }))
                .thenExecuteAfter(0, () -> {
                    checkEquals(
                            15,
                            rig.body().getInventory().container().getItem(0).getCount(),
                            "the source stack must shrink by one after placement");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: clicking a backpack slot in the inventory menu picks up
     * the item via {@code container.removeItem} — the path that was
     * broken by BridgeInventory's phantom super-constructed compartments
     * (review P1-1 on commit 3d53037). The inherited
     * {@code Inventory.removeItem} operated on the empty phantom lists
     * and returned EMPTY; every backpack click would silently pick up
     * nothing. This test forces that exact code path:
     * {@code doClick PICKUP} → {@code Slot.tryRemove} →
     * {@code Slot.remove} → {@code BridgeInventory.removeItem}.
     *
     * <p>InventoryMenu slot layout: 0=craft result, 1-4=craft grid,
     * 5-8=armor, 9-35=main backpack, 36-44=hotbar, 45=offhand.
     * BindingInventory index 9 (first backpack slot) = InventoryMenu
     * slot 9; BindingInventory index 0 (hotbar 0) = menu slot 36.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void picksUpFromBackpackSlot(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Put 8 diamonds in the first backpack slot (BindingInventory
        // index 9 = InventoryMenu slot 9). Hotbar stays empty.
        final int BACKPACK_MENU_SLOT = 9;
        final int HOTBAR0_MENU_SLOT = 36;
        rig.body().getInventory().container().setItem(9, new ItemStack(Items.DIAMOND, 8));
        checkEquals(
                8, rig.body().getInventory().container().getItem(9).getCount(), "setup: 8 diamonds in backpack slot 9");
        check(rig.body().getInventory().container().getItem(0).isEmpty(), "setup: hotbar 0 must be empty");

        // Open the inventory menu through the facade.
        var facade = new BotPlayerFacade(rig.body());
        facade.syncPosition();
        var menu = new BindingMenu(facade.facadeInventoryMenu(), facade, "inventory", null);

        // Pick up: left-click the backpack slot. This goes through
        // Slot.tryRemove → Slot.remove → container.removeItem — the
        // phantom-compartment path that was broken before the override.
        menu.click(BACKPACK_MENU_SLOT, 0, MenuClick.PICKUP);
        checkEquals(
                "minecraft:diamond",
                menu.snapshot().carried().itemId(),
                "after pickup: carried must be diamonds (removeItem must "
                        + "read the binding container, not the phantom super list)");
        checkEquals(8, menu.snapshot().carried().count(), "after pickup: carried must be all 8 diamonds");
        check(
                rig.body().getInventory().container().getItem(9).isEmpty(),
                "after pickup: backpack slot 9 must be empty (removeItem "
                        + "must have removed from the binding container)");

        // Place: left-click hotbar 0. This goes through setByPlayer →
        // setItem (the path that always worked).
        menu.click(HOTBAR0_MENU_SLOT, 0, MenuClick.PICKUP);
        check(menu.snapshot().carried().isEmpty(), "after place: carried must be empty");
        checkEquals(
                8,
                rig.body().getInventory().container().getItem(0).getCount(),
                "after place: hotbar 0 must hold 8 diamonds");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: {@link com.mcbot.mcbotserver.adapter.MenuOpener#open}
     * rejects a target beyond the 4.5 block interaction reach. Without
     * the reach gate the bot could open a menu on any loaded chunk
     * regardless of distance. The bot is at (7, WALK_Y, 7); a chest at
     * z=15 is 8 blocks away — well beyond reach. The opener must return
     * empty.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void rejectsMenuBeyondReach(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Place a chest 8 blocks south of the bot (z=15, bot at z=7).
        BlockPos chestLocal = new BlockPos(7, GametestRig.WALK_Y, 15);
        helper.setBlock(chestLocal, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestLocal);

        var facade = new BotPlayerFacade(rig.body());
        var opener = new com.mcbot.mcbotserver.adapter.MenuOpener(facade);
        var menuOpt = opener.open(chestAbs);

        check(
                menuOpt.isEmpty(),
                "menu opener must return empty for a chest 8 blocks away " + "(beyond 4.5 block reach)");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: clicks on a world menu fail loudly once the bot is out
     * of the vanilla keep-open range. Vanilla enforces stillValid every
     * tick via the ServerPlayer; the facade is never ticked, so
     * BindingMenu re-runs the predicate per click against the synced
     * position. close() must keep working (returns items, lowers the
     * lid) regardless of distance.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void clickThrowsWhenBotWalksAway(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos chestLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(chestLocal, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestLocal);

        var facade = new BotPlayerFacade(rig.body());
        var opener = new com.mcbot.mcbotserver.adapter.MenuOpener(facade);
        var menu = opener.open(chestAbs).orElseThrow();

        // ~9.2 blocks from the chest center: beyond the 8-block
        // keep-open range (BLOCK_REACH 4.5 + 3.5), well inside the
        // structure.
        rig.body().setPos(1.5, GametestRig.WALK_Y, 1.5);

        boolean threw = false;
        try {
            menu.click(0, 0, MenuClick.PICKUP);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check(
                threw,
                "clicking a chest menu from beyond keep-open range " + "must throw, not silently act on the container");

        // close is the terminal cleanup path and stays usable.
        menu.close();

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: opening a second menu closes the first — vanilla
     * parity (ServerPlayer.openMenu closes the previous container). The
     * old binding must return its crafting-grid materials, become
     * terminal (clicks throw), and the new menu must take over.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void openingNewMenuClosesPrevious(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos chestLocal = new BlockPos(6, GametestRig.WALK_Y, 8);
        helper.setBlock(chestLocal, Blocks.CHEST);
        BlockPos tableAbs = helper.absolutePos(tableLocal);
        BlockPos chestAbs = helper.absolutePos(chestLocal);

        var facade = new BotPlayerFacade(rig.body());
        var opener = new com.mcbot.mcbotserver.adapter.MenuOpener(facade);
        var tableMenu = opener.open(tableAbs).orElseThrow();

        // 9 diamonds into the table grid.
        var rawMenu = tableMenu.rawMenu();
        net.minecraft.world.inventory.CraftingContainer craftSlots =
                (net.minecraft.world.inventory.CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 9; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }

        var chestMenu = opener.open(chestAbs).orElseThrow();
        checkEquals("chest", chestMenu.snapshot().type(), "the second open must yield the chest menu");

        checkEquals(9, countItems(rig, Items.DIAMOND), "opening the chest must return the table-grid diamonds");
        for (int i = 0; i < 9; i++) {
            check(
                    craftSlots.getItem(i).isEmpty(),
                    "table grid slot " + i + " must be empty after the " + "menu was closed by the next open");
        }

        boolean threw = false;
        try {
            tableMenu.click(1, 0, MenuClick.PICKUP);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check(threw, "clicking a binding displaced by a newer menu " + "must throw (terminal state)");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a double chest opens as the full vanilla 54-slot
     * CompoundContainer merge (ChestBlock.getContainer, the same
     * helper the vanilla open path uses). The old behavior — one
     * half, 27 of 54 slots, no error — was the silent half-open the
     * review flagged.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void opensDoubleChestFullWidth(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // A west-east pair facing north: LEFT at x=6 connects east.
        BlockState left = Blocks.CHEST
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState right = Blocks.CHEST
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT);
        BlockPos leftLocal = new BlockPos(6, GametestRig.WALK_Y, 8);
        BlockPos rightLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(leftLocal, left);
        helper.setBlock(rightLocal, right);

        // Setup guard: neighbor updates must not have collapsed the
        // pair back to singles — otherwise the test proves nothing.
        check(
                helper.getLevel().getBlockState(helper.absolutePos(leftLocal)).getValue(ChestBlock.TYPE)
                        != ChestType.SINGLE,
                "setup: the left half must stay a double-chest half");

        var actor = rig.actor().menuTransactions();
        BlockPos leftAbs = helper.absolutePos(leftLocal);
        var view = actor.openMenu(GametestRig.cellOf(leftAbs));
        check(view != null, "opening either half of a double chest must succeed");
        checkEquals(54, containerSlotsOf(view), "a double chest must expose all 54 container slots");
        checkEquals(SlotRole.CONTAINER, view.slot(53).role(), "slot 53 (last chest slot) must be CONTAINER");
        checkEquals(SlotRole.MAIN, view.slot(54).role(), "slot 54 (first player slot) must be MAIN");
        actor.closeMenu();

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the deposit/retrieve round trip through the actor's
     * menu transactions — QUICK_MOVE a stack into the chest, close,
     * reopen (state survived the close), PICKUP it back out. Proves
     * the chest container is the real block entity, not a menu-side
     * copy, and that close/reopen is lossless.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void storesAndRetrievesFromChest(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos chestLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(chestLocal, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND, 32));

        var actor = rig.actor().menuTransactions();
        var view = actor.openMenu(GametestRig.cellOf(chestAbs));
        check(view != null, "the chest must open");
        checkEquals(27, containerSlotsOf(view), "a single chest exposes 27 container slots");

        // Role-based addressing: hotbar 0 is the FIRST HOTBAR-role
        // slot - its flat index differs per menu kind (54 here, 37 in
        // a crafting table). The role lookup is exactly the knowledge
        // the harness must never re-derive.
        int hotbar0 = firstSlotWithRole(view, SlotRole.HOTBAR);
        view = actor.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        checkEquals(32, view.slot(0).item().count(), "the chest's first slot must hold the 32 diamonds");
        check(view.slot(hotbar0).isEmpty(), "hotbar 0 must be empty after the quick-move");
        actor.closeMenu();

        // Reopen: the deposit survived the close (real block entity).
        view = actor.openMenu(GametestRig.cellOf(chestAbs));
        checkEquals(32, view.slot(0).item().count(), "the deposit must survive close and reopen");
        hotbar0 = firstSlotWithRole(view, SlotRole.HOTBAR);
        view = actor.menuClick(0, 0, MenuClick.PICKUP);
        checkEquals(32, view.carried().count(), "the pickup must lift all 32 diamonds");
        view = actor.menuClick(hotbar0, 0, MenuClick.PICKUP);
        check(view.carried().isEmpty(), "the stack must land back in hotbar 0");
        actor.closeMenu();

        checkEquals(
                32,
                rig.body().getInventory().container().getItem(0).getCount(),
                "hotbar 0 must hold the 32 diamonds again");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: armor placed through the inventory menu lands in the
     * correct equipment storage. Vanilla flat armor indices run
     * feet..head while the binding runs head..feet; before the bridge
     * translation, a helmet clicked into the menu's head slot
     * (containerSlot 39) silently landed in the feet storage slot.
     * Helmet goes to head storage (36), boots to feet storage (39),
     * and InventoryView reports them at armor[0]/armor[3].
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void equipsArmorThroughMenuClicks(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        var container = rig.body().getInventory().container();
        container.setItem(0, new ItemStack(Items.DIAMOND_HELMET));
        container.setItem(1, new ItemStack(Items.DIAMOND_BOOTS));

        var facade = new BotPlayerFacade(rig.body());
        var opener = new com.mcbot.mcbotserver.adapter.MenuOpener(facade);
        var menu = opener.openInventory();

        // Inventory-menu layout: armor slots 5-8 (5=head, 8=feet),
        // hotbar slots 36-44 (hotbar 0 = menu 36, hotbar 1 = 37).
        menu.click(36, 0, MenuClick.PICKUP);
        checkEquals(
                "minecraft:diamond_helmet",
                menu.snapshot().carried().itemId(),
                "pickup must lift the helmet from hotbar 0");
        menu.click(5, 0, MenuClick.PICKUP);
        check(menu.snapshot().carried().isEmpty(), "the helmet must land on the head armor slot");
        menu.click(37, 0, MenuClick.PICKUP);
        menu.click(8, 0, MenuClick.PICKUP);
        check(menu.snapshot().carried().isEmpty(), "the boots must land on the feet armor slot");

        check(
                Items.DIAMOND_HELMET.equals(container.getItem(36).getItem()),
                "head storage (binding 36) must hold the helmet");
        check(
                Items.DIAMOND_BOOTS.equals(container.getItem(39).getItem()),
                "feet storage (binding 39) must hold the boots");

        var view = rig.body().getInventory().snapshot();
        checkEquals(
                "minecraft:diamond_helmet",
                view.armor().get(0).itemId(),
                "InventoryView armor[0] (head) must report the helmet");
        checkEquals(
                "minecraft:diamond_boots",
                view.armor().get(3).itemId(),
                "InventoryView armor[3] (feet) must report the boots");

        menu.close();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the wear planner (Stage 3 Phase 4 wear slice) picks
     * the best piece per armor slot over the REAL registry
     * classification and executes through the menu transactions. Iron
     * helmet pre-worn, diamond helmet and boots in the hotbar: the
     * strictly better helmet swaps in (the iron one returns to its
     * source slot), the boots fill the empty feet slot, and nothing
     * stays on the cursor.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void wearsBestArmorThroughPlanner(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        var container = rig.body().getInventory().container();
        // Binding flat storage: 0..8 hotbar, 36 head armor. Iron on
        // the head, diamond helmet (defense 3 > iron 2) and boots one
        // slot deeper in the hotbar.
        container.setItem(36, new ItemStack(Items.IRON_HELMET));
        container.setItem(0, new ItemStack(Items.DIAMOND_HELMET));
        container.setItem(2, new ItemStack(Items.DIAMOND_BOOTS));

        var facade = new BotPlayerFacade(rig.body());
        var opener = new com.mcbot.mcbotserver.adapter.MenuOpener(facade);
        var menu = opener.openInventory();
        var preSnap = menu.snapshot();
        var catalog = new com.mcbot.mcbotserver.adapter.VanillaArmorCatalog();
        var steps = com.mcbot.mcbotserver.core.menu.MenuPlanner.planWear(preSnap, catalog);
        for (var step : steps) {
            menu.click(step.slot(), step.button(), step.kind());
        }
        check(menu.snapshot().carried().isEmpty(), "wear must leave nothing on the cursor");

        var view = rig.body().getInventory().snapshot();
        checkEquals(
                "minecraft:diamond_helmet",
                view.armor().get(0).itemId(),
                "the diamond helmet must replace the worn iron one");
        checkEquals("minecraft:diamond_boots", view.armor().get(3).itemId(), "the boots must fill the empty feet slot");
        check(
                Items.IRON_HELMET.equals(container.getItem(0).getItem()),
                "the displaced iron helmet must return to its source slot");

        menu.close();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: ground-item pickup (issue 0010 section 7 dependency,
     * Phase 4 acquisition ground work): an ItemEntity spawned on the
     * body rides the touch hook into the binding container; a fully
     * taken stack discards the entity.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void picksUpGroundItemsByTouch(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        var body = rig.body();
        var at = helper.absolutePos(new BlockPos(7, GametestRig.WALK_Y, 7));
        var drop = new ItemEntity(
                body.level(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, new ItemStack(Items.BREAD, 3));
        body.level().addFreshEntity(drop);

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!drop.isAlive(), "waiting for the pickup")))
                .thenExecuteAfter(0, () -> {
                    checkEquals(3, countItems(rig, Items.BREAD), "the whole dropped stack must land in the container");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /** Count of CONTAINER-role slots in a menu snapshot (the opened
     * world container's width, independent of the player region). */
    private static int containerSlotsOf(MenuView view) {
        int total = 0;
        for (var slot : view.slots()) {
            if (slot.role() == SlotRole.CONTAINER) {
                total++;
            }
        }
        return total;
    }

    /**
     * Scenario: the anvil rename loop end to end — an item QUICK_MOVE'd
     * into the input slot, the rename text set, the result slot taken;
     * the body's XP pays the 1-level rename cost and the renamed item
     * lands in the bot inventory. This is the engine verification the
     * eatsWhenHungry lesson demands: the anvil's mayPickup gate reads
     * the facade's experienceLevel field, which only syncExperience
     * keeps truthful.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void anvilRenamesAndChargesLevels(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        BlockPos anvilLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(anvilLocal, Blocks.ANVIL);
        BlockPos anvilAbs = helper.absolutePos(anvilLocal);

        rig.body().giveExperienceLevels(5);
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_SWORD));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(anvilAbs));
        check(view != null, "opening the anvil must succeed");
        checkEquals("anvil", view.type(), "the opened menu must be the anvil");

        int hotbar0 = firstSlotWithRole(view, SlotRole.HOTBAR);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        check(!view.slot(0).isEmpty(), "the sword must land in the anvil input slot");
        tx.setAnvilName("Botblade");

        // The take targets the OUTPUT slot (2) — AnvilMenu's mayPickup
        // gate reads the synced experienceLevel, and the take fires
        // onTake -> giveExperienceLevels(-1) through the facade.
        view = tx.menuClick(2, 0, MenuClick.PICKUP);
        check(view.carried() != null && !view.carried().isEmpty(), "the renamed result must be takeable");
        check(view.slot(0).isEmpty(), "the take must consume the input sword");
        tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        tx.closeMenu();

        checkEquals(4, rig.body().getExperienceLevel(), "the 1-level rename cost must be paid from the body's XP");
        checkEquals(
                "Botblade",
                rig.body().getInventory().container().getItem(0).getHoverName().getString(),
                "the renamed sword must carry the set name");
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the enchanting table through clickMenuButton — sword +
     * lapis deposited, body levels sufficient, option 0 clicked; the
     * enchant consumes one lapis and levels through the facade
     * delegation. The enchantment power needs bookshelves (with none
     * the menu offers no enchantment list at all), hence the shelves.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void enchantsThroughMenuButton(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.ENCHANTING_TABLE);
        helper.setBlock(new BlockPos(9, GametestRig.WALK_Y + 1, 8), Blocks.BOOKSHELF);
        helper.setBlock(new BlockPos(8, GametestRig.WALK_Y + 1, 9), Blocks.BOOKSHELF);

        rig.body().giveExperienceLevels(30);
        var container = rig.body().getInventory().container();
        container.setItem(0, new ItemStack(Items.IRON_SWORD));
        container.setItem(1, new ItemStack(Items.LAPIS_LAZULI, 8));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(helper.absolutePos(tableLocal)));
        check(view != null, "opening the enchanting table must succeed");

        int hotbar0 = firstSlotWithRole(view, SlotRole.HOTBAR);
        int hotbar1 = hotbar0 + 1;
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar1, 0, MenuClick.QUICK_MOVE);
        check(!view.slot(1).isEmpty(), "the lapis must land in the enchanting fuel slot");

        tx.menuButtonClick(0);
        var after = tx.menuSnapshot();
        checkEquals(7, after.slot(1).item().count(), "option 0 consumes exactly one lapis");
        check(rig.body().getExperienceLevel() < 30, "the enchant must charge levels through the facade delegation");
        // The enchanted sword is proven by the consumed inputs: a failed
        // clickMenuButton consumes nothing.
        view = tx.menuClick(0, 0, MenuClick.QUICK_MOVE);
        checkEquals(1, countItems(rig, Items.IRON_SWORD), "the sword must come back to the inventory");
        tx.closeMenu();

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: XP persistence across the menu open/close cycle — the
     * syncExperience round trip must not create or destroy body XP
     * (the facade fields are write-only mirrors, never a second
     * source of truth).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void xpSurvivesMenuCycles(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        BlockPos chestLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(chestLocal, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestLocal);

        rig.body().giveExperienceLevels(7);
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND));
        var tx = rig.actor().menuTransactions();

        for (int cycle = 0; cycle < 3; cycle++) {
            var view = tx.openMenu(GametestRig.cellOf(chestAbs));
            check(view != null, "opening the chest must succeed on cycle " + cycle);
            int hotbar0 = firstSlotWithRole(view, SlotRole.HOTBAR);
            tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
            tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
            tx.closeMenu();
            checkEquals(
                    7, rig.body().getExperienceLevel(), "menu cycle " + cycle + " must not create or destroy body XP");
        }
        checkEquals(1, countItems(rig, Items.DIAMOND), "the diamond round trip must be lossless");
        rig.body().discard();
        helper.succeed();
    }

    /** First flat slot index carrying the given role (e.g. the first
     * HOTBAR slot). Role-based addressing - flat layouts differ per
     * menu kind. */
    private static int firstSlotWithRole(MenuView view, SlotRole role) {
        for (var slot : view.slots()) {
            if (slot.role() == role) {
                return slot.index();
            }
        }
        throw new IllegalStateException("no slot with role " + role);
    }
}
