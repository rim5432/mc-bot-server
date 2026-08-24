---
title: Player parity - inventory, menus, crafting, and the interaction surface
last_verified: 2026-08-24
covers:
  - doc/architecture/function-map.md
  - src/main/java/com/mcbot/mcbotserver/adapter/BindingActor.java
  - src/main/java/com/mcbot/mcbotserver/adapter/entity/BotBodyEntity.java
status: open (Stage 3 review agenda; unlocked by the pre-Stage-3 survival gate)
related:
  - doc/architecture/boundaries.md
  - doc/decisions/0002-capability-model-task-arbiter.md
  - doc/decisions/0004-tick-pipeline-actor-channels.md
  - doc/architecture/issues/0004-movement-primitive-vocabulary.md
  - doc/architecture/issues/0005-player-feel-motion-layer.md
---

# Issue 0007: Player parity - inventory, menus, crafting, interaction

## 1. Scope

The user directive for Stage 3: make the bot equivalent to a player
at the information-interaction level - inventory, container menus,
the crafting-table disclosure, block/entity interaction - but only
after the pre-Stage-3 survival gate closes (a body that cannot
survive a night does not need a crafting table yet). This issue
reviews the function-map rows it would flip: the [DEFERRED]
inventory-management / crafting / tool-loadout row, the "Take
orders" harvest-and-place GAP, and the survival "automatic eating"
GAP.

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
2. **Phase 2 (L)** menu system + crafting-table disclosure - api
   `MenuView` / `CraftingView` (2x2 `InventoryMenu` baseline, 3x3
   table extension), core as a click-sequence PLANNER over the
   read-only view (core holds no menu state - see risk 2),
   `MenuOpener` per block kind, `BotPlayerFacade`.
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
   request-response (open once, click at will, close once); they
   are NOT per-tick declarative claims and must not ride the claim
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
   body vs reopening the Stage 1 ServerPlayer decision.
4. **Tick ordering.** Simplified but retained: menu transactions
   still run on the server thread at a defined point in the bot
   tick (after `actor.flush()`, before the next perception
   snapshot) - "between ticks" in claim terms, never off-thread.
5. **Disclosure shape.** `carried` is model-relevant and exposed
   (a harness planning clicks must know the ghost stack);
   `stateId` is a network-sync artifact with no connection to
   serve - not exposed.

## 7. Risks

- **Facade blast radius**: VERIFIED - `Player` declares exactly two
  abstract methods (`isSpectator`, `isCreative`); the risk is not
  "implement 150 methods" but default-method delegation: default
  bodies on the menu/Slot call chain reach `getGameProfile()`
  (null), `getInventory()` (null), `connection` (null ->
  `sendSystemMessage` NPE). The audit enumerates which Player
  methods the menu call graph actually touches before writing the
  facade.
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
