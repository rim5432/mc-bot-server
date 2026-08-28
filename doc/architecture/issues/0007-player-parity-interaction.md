---
title: Player parity - inventory, menus, crafting, and the interaction surface
last_verified: 2026-08-29
covers:
  - doc/architecture/function-map.md
  - src/main/java/com/mcbot/mcbotserver/adapter/BindingActor.java
  - src/main/java/com/mcbot/mcbotserver/adapter/entity/BotBodyEntity.java
status: open (Stage 3 review agenda; unlocked by the pre-Stage-3 survival gate)
related:
  - doc/architecture/boundaries.md
  - doc/decisions/0002-capability-model-task-arbiter.md
  - doc/decisions/0004-tick-pipeline-actor-channels.md
  - doc/architecture/issues/archive/0004-movement-primitive-vocabulary.md
  - doc/architecture/issues/archive/0005-player-feel-motion-layer.md
---

# Issue 0007: Player parity - inventory, menus, crafting, interaction

## 1. Scope

The user directive for Stage 3: make the bot equivalent to a player
at the information-interaction level - inventory, container menus,
the crafting-table disclosure, block/entity interaction - but only
after the pre-Stage-3 survival gate closes (a body that cannot
survive a night does not need a crafting table yet). This issue
reviews the function-map rows it would flip. Already flipped by
Phases 1-2: inventory management and crafting are now SHIPPED
rows in the map's "Manipulate the world" section. Still pending
on the map: the tool-loadout DEFERRED row, the "Take orders"
harvest-and-place GAP, and the survival "automatic eating" GAP.

Initial analysis 2026-08-24; reviewed and corrected the same day
(menu transaction semantics, interaction-surface precision, core
menu-state constraint). Vanilla-API claims marked VERIFIED were
checked against `D:/mc-decompiled/forge-1.20.1-47.4.10/`; the rest
are pickup-time verification items.

## 2. Diagnosis: what the player has that the bot does not

| Dimension | Player | Bot today |
|---|---|---|
| Sense (WorldView) | blocks, entities, inventory, menu slots, craft result, XP, hunger, effects | blocks, entities, collision shapes, block traits |
| Act (Actor/Intent) | move/look, left-click (attack / dig), right-click (interact / use), open menu, menu click, drop, offhand swap | Move, Rotate, Use(boolean) as melee-only, SelectSlot |
| Inventory | 36 + 4 armor + 1 offhand, NBT persistence | `selectedSlot` is a bare int; no items exist |
| Menus / containers | `AbstractContainerMenu` family, open/close lifecycle | none |
| Crafting | `CraftingMenu` + `RecipeManager` grid matching | none |
| Block interaction | useItemOn / destroyBlock with reach checks | none (Use resolves entity melee only) |

## 3. The player interaction surface (decompiled tree)

Key facts the design leans on:

- `Inventory` compartments: 36 items + 4 armor + 1 offhand,
  `getContainerSize() = 41`; `add` auto-stacks, `getDestroySpeed`
  gives tool acceleration, NBT save/load.
- `AbstractContainerMenu`: unified slot numbering (container slots
  + player inventory + hotbar), `carried` ghost stack,
  `clicked(slotId, button, ClickType, Player)` dispatcher with
  PICKUP / QUICK_MOVE / SWAP / CLONE / THROW / QUICK_CRAFT /
  PICKUP_ALL (CLONE is creative-only - `instabuild` gated - and
  QUICK_CRAFT is a 3-phase drag-allocation protocol a bot never
  needs: PICKUP + QUICK_MOVE cover all automation), per-subclass
  `quickMoveStack`, `removed` for close-time cleanup (a crafting
  grid returns its contents). VERIFIED:
  `setSynchronizer(ContainerSynchronizer)` exists - a no-op
  synchronizer is a legal seam, so menus can run without a
  network connection.
- `InventoryMenu`: the player inventory screen carries an
  always-available 2x2 crafting grid - no table needed. Crafting
  parity therefore has two tiers: 2x2 baseline (planks, sticks,
  torches), 3x3 table as the extension.
- `CraftingMenu`: `TransientCraftingContainer` 3x3 + result slot;
  `slotChangedCraftingGrid` matches via
  `RecipeManager.getRecipeFor` and writes the result slot.
  VERIFIED (Forge-patched): `stillValid(ContainerLevelAccess,
  Player, Block)` checks block identity and
  `distanceToSqr <= (BLOCK_REACH + 3.5)^2`. The declared
  `reachOld = 64.0` in the same patch is dead code (single
  occurrence, never read) - there is no runtime fallback, the
  attribute check is the only path. Reach semantics matter for
  how far the bot may stand from an open menu.
- Survival-mode digging is continuous, not single-shot:
  `ServerPlayerGameMode` accumulates destroy progress per tick
  while the button holds on the same block+face (tool speed,
  tier, hardness). A rising-edge-only press intent cannot mine -
  the interaction surface needs hold semantics plus an
  adapter-side mining-progress state machine.
- `ServerPlayer` menu lifecycle: `containerMenu` (current, default
  `inventoryMenu`), `openMenu` (close previous, allocate
  containerId, `provider.createMenu`, open screen, init), 
  `closeContainer` (close packet, `removed`, revert to
  inventoryMenu).
- Interaction entry points: `interactOn` (right-click entity),
  `attack` (left-click entity), `useItem` (held item use),
  `ServerGameMode.useItemOn` (right-click block),
  `destroyBlock` (left-click block), `mayInteract` (permission +
  distance).
- `Inventory`'s constructor wants a `Player`; `Player` is an
  abstract class, not an interface. `SimpleContainer` needs no
  player but loses `getDestroySpeed` and armor semantics.

## 4. Two architecture paths

- **Path A - extend the mob carrier.** Keep
  `BotBodyEntity extends PathfinderMob`; add inventory / menu /
  crafting as subsystems bridged in the adapter. Menus are driven
  directly (`menu.clicked(...)` with a no-op synchronizer); recipe
  matching calls `level.getRecipeManager()`. The open problem is
  the `Player` parameter that vanilla interaction surfaces demand -
  answered by a minimal `BotPlayerFacade extends Player`
  (delegate position/level/inventory/alive/reach; everything else
  throws), NOT by making the bot a real player.
- **Path B - FakeServerPlayer carrier.** A `ServerPlayer` subclass
  with a `FakeConnection` (Carpet-style) gets the whole player
  machine for free - but reopens the Stage 1 carrier ratification,
  forces chunk-ticket and death/respawn handling, and replaces the
  `zza/xxa` physics drive with packet-driven movement. That is an
  entity-layer rewrite, not an extension.

Recommendation: Path A, staged delivery. Path B stays the recorded
fallback if a v1+ requirement (advancements, XP, enchanting UI
state) turns out to be facade-hostile.

## 5. Phased plan (mirrored in workplan Stage 3)

1. **Phase 1 (L)** inventory sense + basic interaction - api
   `ItemView` / `InventoryView`, `SimpleContainer`-backed
   `BindingInventory`, `Intent.InteractBlock` carrying the clicked
   `face` and `hitPos` (placement position depends on the face;
   api-layer pure-Java `Direction`, adapter maps to engine
   `Direction`), hold-to-mine with the adapter-side mining state
   machine, `DropSelected`.
   Carve-out 2026-08-25 (issue 0009): the DIG half of this phase
   shipped early on the survival track - the suffocation escape
   motivated the INTERACT channel, `Intent.Dig` and the
   adapter-side executor (ledger 25), all inventory-free. Phase 1
   keeps the use/place shapes, the face semantics, the inventory
   reads and `DropSelected`; its hold-to-mine item is now "grow the
   executor's main-hand tool supplier", not "build the machine".
   Progress 2026-08-25 (inventory sense half landed in parallel
   with the survival gate): api `ItemView` (record: id + count +
   nbtDigest, `EMPTY` constant) and `InventoryView` (record: 36
   main + selectedSlot + 4 armor + offhand, `empty()` factory,
   `getSelected` / `hasItem` / `countOf` / `hotbar` / `backpack`,
   defensive-copy immutability); `WorldView.getInventory()` default
   method (returns empty); adapter `BindingInventory`
   (`SimpleContainer`-backed, 41 slots, `snapshot()` produces
   `InventoryView`); wired into `BotBodyEntity` (owns the
   container), `BindingActor` (SLOT claim mirrors selectedSlot to
   the binding), `BindingWorldView` (supplier-based override),
   `BotAssembly` (state snapshot reads real item counts),
   `MockWorldView` (test scriptable inventory). Offline gate:
   `InventorySenseTest` (26 assertions across validation, queries,
   immutability). Remaining in Phase 1: main-hand tool supplier on
   DigExecutor (dig currently bare-hand; tool speed/tier not yet
   wired).
   Progress 2026-08-25 (DropSelected landed): `Intent.DropSelected`
   (record: `fullStack` boolean — Q vs Ctrl-Q distinction) rides the
   INTERACT channel alongside Dig (per-channel arbitration means they
   cannot both win a tick); `BindingActor` fires on the rising edge
   (same shape USE uses for melee swings — a held claim across ticks
   does not drop twice); `BotBodyEntity.dropSelectedItem(boolean)`
   spawns via `Entity.spawnAtLocation` (0.5f y-offset) and clears or
   shrinks the source slot. Offline gate: `DropSelectedIntentTest`
   (10 assertions: record shape, INTERACT-accepts, other-channels-reject,
   Dig coexistence, null/blank rejection). In-engine gate:
   `dropsSelectedItem` gametest (16 diamonds in slot 0 → claim →
   ItemEntity spawns with count 16 → slot empty).
   Progress 2026-08-25 (InteractBlock place landed): `Intent.InteractBlock`
   (record: `CellPos target` + `Direction face` + `Vec3 hitPos`) rides
   the INTERACT channel alongside Dig and DropSelected (three variants,
   one winner per tick); api-layer `Direction` enum (6 values in MC
   ordinal order, `dx/dy/dz` + `relative(CellPos)`); `BindingActor`
   fires on the rising edge (same one-shot pattern as DropSelected);
   `InteractBlockExecutor` bypasses `ServerPlayerGameMode.useItemOn`
   (which requires a ServerPlayer — Phase 2 facade territory) and
   places directly via `level.setBlock` + item shrink, exactly the way
   DigExecutor bypasses destroyBlock. Phase 1 scope: default-state block
   placement only (BlockItem, air target cell, 4.5 block reach);
   direction-aware states (stairs, pistons), use-block (chest/button),
   and replaceable-cell placement (water, tall grass) are deferred to
   Phase 2 — they need `Block.getStateForPlacement(BlockPlaceContext)`
   or `BlockState.use(Level, Player, ...)`, both Player-typed. Offline
   gate: `InteractBlockIntentTest` (21 assertions: Direction enum shape
   + unit vectors + relative, InteractBlock record + null validation,
   channel validation, INTERACT three-variant coexistence). In-engine
   gate: `placesBlockOnInteract` gametest (dirt target south of bot,
   16 stone in slot 0 → claim UP face → stone block appears above dirt
   → stack shrinks to 15).
   Progress 2026-08-27 (Phase 2 use chain landed): the executor runs
   the vanilla non-sneak right-click order through `BotPlayerFacade` -
   `BlockState.use` first, then the held stack's `useOn` when the block
   passed - so direction-aware placement, replaceable cells, and
   use-block interactions (doors, buttons, levers, hoe, flint) now ride
   `BlockItem.place`'s own state machine; the `setBlock` replica and
   its manual survival/obstruction/sound/shrink steps retired. Two
   recorded deviations: the Forge RightClickBlock event is not
   consulted, and `MenuProvider` blocks stay menu-verb territory (a
   second open path would fight the MenuOpener's transaction state).
   New sync verb `/bot use x y z face` (0013 write-path table row);
   the executor's consumed verdict is the receipt - a pressed button
   springs back, so no post-state read exists. `placesBlockOnInteract`
   keeps its premise (vanilla place still shrinks and fills the UP
   cell); the use-block scenarios ride the staged gametest batch.
   Progress 2026-08-25 (DigExecutor tool supplier landed): the dig
   executor now reads the selected hotbar ItemStack every tick and
   computes `toolSpeed = stack.getDestroySpeed(state)` and
   `hasCorrectTool = !requiresCorrectToolForDrops || stack.isCorrectToolForDrops(state)`,
   passing both to `DigPacing.perTickProgress`. `DigPacing` signature
   grew from `(destroySpeed, requiresCorrectTool, onGround)` to
   `(destroySpeed, toolSpeed, hasCorrectTool, onGround)` — the
   semantic flip from "does the block require a tool" to "does the
   current tool satisfy the requirement" is what makes correct-tool
   drops work. The break call changed from `destroyBlock(pos, true)`
   to `destroyBlock(pos, hasCorrectTool)`, matching vanilla
   `ServerPlayerGameMode.destroyBlock` — a hand-mined stone block
   breaks but yields no cobblestone. Offline gate: `DigPacingTest`
   extended with iron-pickaxe (~8 ticks on stone vs 150 bare-hand),
   wooden-pickaxe (~23 ticks), correct-tool divisor (30 vs 100), and
   low-tier-tool-fast-but-no-drops cases. Phase 1 is now fully
   complete: inventory sense + DropSelected + InteractBlock(place) +
   DIG(with tool supplier) + acceptance criterion (dig + read inventory
   + drop) all met.
   Review round 2026-08-25 (formula audit + fixes): the DigPacing
   arithmetic was verified point-by-point against the decompiled tree
   — `Inventory.getDestroySpeed` returns the held item's speed with no
   +1 base, the 30/100 divisor matches `getDestroyProgress`, and the
   airborne /5 matches `Player.getDigSpeed`; test numbers match wiki
   breaking times (stone: 150 hand ticks, 23 wooden, 8 iron). The
   drop-path deviations are now inventoried in DigExecutor's Javadoc:
   loot context receives `ItemStack.EMPTY` as the tool (match_tool-
   gated drops behave bare-hand), no XP pop, no tool durability, no
   mining exhaustion (an issue 0010 modeling concern), no underwater
   /5. InteractBlockExecutor gained vanilla `BlockItem.canPlace` parity
   guards (`canSurvive` + `isUnobstructed` with
   `CollisionContext.empty()`) — no self-entombment, no non-surviving
   placements. Bot drops carry the player's 40-tick pickup delay
   instead of `spawnAtLocation`'s default 10. The Phase 2 facade getter
   stayed out of the body so this batch lands self-contained; the
   in-flight menu-stack WIP is preserved untracked alongside a scratch
   copy of its excised gametest.
2. **Phase 2 (L)** menu system + crafting-table disclosure - api
   `MenuView` / `CraftingView` (2x2 `InventoryMenu` baseline, 3x3
   table extension), core as a click-sequence PLANNER over the
   read-only view (core holds no menu state - see risk 2),
   `MenuOpener` per block kind, `BotPlayerFacade`.
   Progress 2026-08-25 (Phase 2 baseline + crafting table landed):
   `BotPlayerFacade` extends `Player` (2 abstract methods:
   isSpectator/isCreative false), delegates position/level/UUID to
   the body, overrides `getInventory()` to return `BridgeInventory`,
   creates its own `InventoryMenu` (super's inventoryMenu is final
   and backed by the empty default inventory — ignored, closeContainer
   overridden). `BridgeInventory` extends `Inventory`, delegates all
   41 slot reads/writes to the body's `BindingInventory` (layout
   matches exactly: 36 main + 4 armor + 1 offhand). `BindingMenu`
   wraps `AbstractContainerMenu` with a no-op `ContainerSynchronizer`
   (4 methods: sendInitialData/sendSlotChange/sendCarriedChange/
   sendDataChange), provides snapshot() → MenuView and click() →
   menu.clicked(). api types: `MenuView` (type + containerSize +
   immutable SlotView list) and `SlotView` (index + ItemView).
   `BotCraftingMenu` extends `CraftingMenu` and overrides
   `slotsChanged` to bypass the vanilla `slotChangedCraftingGrid`
   ServerPlayer cast (which would ClassCastException on the facade):
   it reads the CraftingContainer/ResultContainer through the public
   slots list, queries the server RecipeManager directly, and writes
   the result — no network packet, no ServerPlayer. `MenuOpener`
   opens crafting_table (→ BotCraftingMenu) and chest (→ ChestMenu
   backed by ChestBlockEntity) and returns BindingMenu. In-engine
   gate: `craftsDiamondBlockAtTable` gametest (9 diamonds in 3x3
   grid → slotsChanged → diamond_block result → click take →
   ResultSlot.onTake consumes all 9 grid materials → place result in
   hotbar 1). 24/24 gametests pass. Remaining in Phase 2: core
   click-sequence PLANNER (places materials via clicks instead of
   direct container writes), CraftingView api type.
   Post-review hardening 2026-08-25 (e5545f6, 28229e0; 33/33
   gametests): the P2 design review found the phantom slot-search
   family unsealed on `BridgeInventory` — vanilla
   `placeItemBackInInventory` searched the phantom `items` list, so
   `close()` corrupted an occupied slot 0 (wrong-stack merge without
   an identity check) and hung the tick thread when slot 0 was full
   (drop branch unreachable). `getFreeSlot` /
   `getSlotWithRemainingSpace` / `add` now delegate to the binding
   container. Lifecycle contract: the facade owns its open
   `BindingMenu` (as ServerPlayer owns containerMenu) and
   `MenuOpener` closes it before opening a new one; close is
   terminal + idempotent, and every click re-runs the menu's own
   `stillValid` predicate against the synced position (vanilla does
   this per tick via ServerPlayer; the facade is never ticked).
   Distance policy recorded: open gate 4.5 blocks (project
   interaction reach), keep-open gate vanilla 8.0 (BLOCK_REACH 4.5 +
   3.5; the facade carries default player attributes). The facade's
   inventory menu is `BotInventoryMenu` — vanilla
   `InventoryMenu.slotsChanged` routes through the same ServerPlayer
   cast as the 3x3, so the 2x2 grid used to CCE on any material;
   result recomputation is shared via
   `BotCraftingMenu.recomputeResult`, and close() returns 2x2
   materials like the 3x3. Double chests are explicitly rejected
   (vanilla merges halves into CompoundContainer; binding one half
   silently exposed 27 of 54 slots).
   Vocabulary + surface round 2026-08-26 (b24b9d2, c936ba1,
   ca69864, f413ad8; 36/36 gametests; user rulings):
   - Armor order ruling AMENDED - pulled forward from Phase 4 and
     landed with the SlotRole slice: bridge-side translation
     (binding = 75 - vanillaFlat, armor only) in the four flat-index
     accessors. Reason: SlotRole makes armor a first-class
     addressable surface, so the reversal became reachable, not
     remote. Pinned by equipsArmorThroughMenuClicks.
   - Disclosure shape executed: MenuView carries carried (the §6.5
     ruling) + sourcePos (null for own inventory); SlotView carries
     SlotRole; the vanilla flat-layout knowledge lives in exactly
     one adapter table (BindingMenu.roleOf).
   - api.menu.MenuClick (PICKUP/QUICK_MOVE/THROW; CLONE and
     QUICK_CRAFT deliberately excluded) - the engine ClickType no
     longer appears in any public signature, so a core planner can
     exist without MC imports.
   - §6.2 menu transaction surface RESOLVED by user ruling ("A1
     now", 2026-08-26; ledger 29): imperative request-response
     methods on the boundary-A actor binding. Landing shape adjusts
     the A1 sketch by one step: the methods live on the new
     api.menu.MenuTransactions interface implemented by BindingActor
     NEXT TO Actor - ChannelArbiter and test recordings are
     legitimate claim-only actors. The §6.1 backlog entries
     OpenMenu/CloseMenu/MenuClick as Intent KINDS are thereby moot:
     menu operations are transactions, never intents. gametest
     craftsViaMenuTransactions pins the chain end to end (clicks
     only, no raw container writes).
   - Double chests land the full vanilla merge (ChestBlock
     .getContainer, sixRows for the pair; blocked chests rejected
     like vanilla) - supersedes the reject-only interim above.
   Closeout 2026-08-26 (9e113c5, 72e7498, f5e7bd9, 6580b99;
   37/37 gametests): the planner half landed and Phase 2 is
   complete. api CraftingView projects grid + result out of a
   MenuView through SlotRole - callers never do flat arithmetic,
   and the projection validates the vanilla pair (4 or 9 GRID
   slots), rejecting non-crafting menus at of() time. core.menu
   .MenuPlanner is pure static planning from one snapshot:
   planGridFill (whole-stack lift from MAIN/HOTBAR in snapshot
   order, one-item-per-cell right-click deposits, remainder returns
   to its source) and planTakeResult (quick-move on the result
   slot). Mixed recipes compose by chaining single-material plans -
   each plan leaves the cursor spent or returned. Unsatisfiable
   demand fails before any step exists. craftsViaMenuTransactions
   migrated to planner-driven execution (its hardcoded flat layout
   deleted, not relocated), which also live-proved QUICK_MOVE on
   the result slot rides vanilla quickMoveStack so ResultSlot.onTake
   consumes the grid. walksToTableAndCrafts pins the acceptance
   criterion verbatim: goto mission to the adjacent cell retires as
   a success, then open → planned fill → take → close → exactly one
   product in the binding container. Offline gates: CraftingViewTest
   (7 tests), MenuPlannerTest (9 tests). Remaining known gaps moved
   to function-map §7: chest-side planning sequences, use-item/use-
   block verbs.
3. **Phase 3 (M)** crafting automation - `RecipeManager` query
   service, quick-move sequences.
4. **Phase 4 (XL)** full parity - remaining menu kinds, `UseItem`
   (eat / drink / bow / place), armor, offhand, XP / hunger sense.

## 6. Frozen-surface review agenda (the Stage 3 review decides)

1. **Intent kinds grow** (`InteractBlock`, `InteractEntity`,
   `UseItem`, `OpenMenu`, `CloseMenu`, `MenuClick`, `DropSelected`).
   Same discipline as issue 0004 F4: frozen boundary A territory;
   this issue is the backlog text until the review lifts it.
2. **Menu transaction surface.** Menu operations are
   request-response (open once, click at will, close once); they are
   NOT per-tick declarative claims and must not ride the claim
   machinery (claims expire per tick and arbitrate - both wrong
   for transactions). Working hypothesis: `USE` stays
   entity-melee untouched (CombatBehavior zero change); a new
   per-tick `INTERACT` channel carries held block/entity
   interaction (press semantics ARE per-tick); menu transactions
   go imperative through Actor-grown transactional methods, so
   boundary A's single-mutation-surface rule survives. The
   alternative - a separate `MenuController` outside Actor -
   needs the review to sanction a second boundary-A write surface
   (AGENTS.md 2.4 calls unsanctioned mutation a violation).
3. **Carrier ruling.** Path A facade on the ratified PathfinderMob
   body vs reopening the Stage 1 ServerPlayer decision. Ruling
   context added 2026-08-25: the user ruled the WORLD treats the
   bot as a player; that resolved into two orthogonal axes - the
   threat axis is already shipped (carrier-side presence pass in
   BotBodyEntity: hostiles acquire the body on sight; workplan
   follow-up 9) and the interaction axis is exactly this issue's
   facade plan. The Stage 3 review weighs Path A vs Path B with
   both axes live: presence keeps working either way (a real
   ServerPlayer is visible to scans natively), so the ruling adds
   no new Path B pressure.
4. **Tick ordering.** Simplified but retained: menu transactions
   still run on the server thread at a defined point in the bot
   tick (after `actor.flush()`, before the next perception
   snapshot) - "between ticks" in claim terms, never off-thread.
5. **Disclosure shape.** `carried` is model-relevant and exposed
   (a harness planning clicks must know the ghost stack);
   `stateId` is a network-sync artifact with no connection to
   serve - not exposed.

## 7. Risks

- **Carrier-bound calibration debt (2026-08-25 note)**: the body's
  locomotion is calibrated to THIS PathfinderMob carrier, and the
  calibrations accumulate - direct `jumpFromGround` /
  `jumpInFluid` calls (the flag path produced zero vertical
  velocity on this carrier, issue 0004 F2), drive/sprint/brake
  constants, the swapped-out move/look/jump controls, and the
  eyeHeight delta (entity-default ~1.53 vs a player's 1.62, which
  shifts every eye-line based check: melee clips, sprint
  clearance, sight lines). None of this is wrong for Path A - the
  device's physics are the carrier's physics - but every feature
  landed on the mob carrier raises Path B's (FakeServerPlayer)
  port cost. This feeds the section 6.3 carrier ruling: the
  Stage 3 review should weigh the accumulated calibration surface,
  not just the interaction-surface fit.
- **Facade blast radius**: VERIFIED - `Player` declares exactly two
  abstract methods (`isSpectator`, `isCreative`); the risk is not
  "implement 150 methods" but default-method delegation: default
  bodies on the menu/Slot call chain reach `getGameProfile()`
  (null), `getInventory()` (null), `connection` (null ->
  `sendSystemMessage` NPE). The audit enumerates which Player
  methods the menu call graph actually touches before writing the
  facade.
- **Armor index order (ruled 2026-08-25, amended 2026-08-26, now
  LANDED)**: vanilla `Inventory` flat slots 36-39 run feet→head (the
  armor list index is `EquipmentSlot.getIndex()` with FEET=0, and
  `InventoryMenu`'s armor slots address container slots 39..36 for
  head..feet), while `BindingInventory` and `InventoryView` run
  head→feet. `BridgeInventory`'s "flat index maps 1:1" claim held
  for 0-35 and 40 only. Original ruling deferred the fix to Phase 4;
  the SlotRole disclosure round made armor first-class addressable,
  so the translation was pulled forward and landed bridge-side
  (binding = 75 - vanillaFlat, armor only, in the four flat-index
  accessors; b24b9d2, pinned by equipsArmorThroughMenuClicks).
- **Core never writes menu state.** `ResultSlot.onTake` consumes
  grid materials on take; a core-side slot writer bypasses that
  and duplicates items. All menu mutations go through the
  adapter's `menu.clicked()`; core plans click sequences over a
  read-only `MenuView` and holds no menu state. This replaces the
  original "core MenuModel state machine" idea.
- **Boundary A purity**: `ItemView` carries `String itemId`, menu
  kinds are a plain enum, recipe matching never enters `core/`.
- **Perception amplification**: menus double the WorldView surface;
  the shape-vs-traits discipline (boundaries decision 19/19a)
  applies - menu reads answer from slot state, never from block ids.
