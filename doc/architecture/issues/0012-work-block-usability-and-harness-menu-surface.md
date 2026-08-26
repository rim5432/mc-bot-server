---
title: Work-block usability - vanilla construction delegation and the harness manual menu surface
last_verified: 2026-08-26
covers:
  - doc/architecture/boundaries.md
  - src/main/java/com/mcbot/mcbotserver/adapter/MenuOpener.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BindingMenu.java
  - src/main/java/com/mcbot/mcbotserver/api/menu/MenuTransactions.java
  - src/main/java/com/mcbot/mcbotserver/api/menu/SlotRole.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BotCommands.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BotControlSocket.java
  - src/main/java/com/mcbot/mcbotserver/adapter/RecipeCatalog.java
  - src/main/java/com/mcbot/mcbotserver/core/tick/MissionReporter.java
  - tool/harness/mc.py
status: open (design complete - primary interaction model promoted, wire translation table as integration contract, translation-layer invariants recorded; goto migration CLI skeleton shipped 2026-08-26, hardened same day: typed verb discipline, /tasks/<id> derivation, admin stop/reset landing, cursor bookmark rule, 36 wire-mocked CLI tests; goto shadow comparison ALL GREEN with the TASK_COMPLETED sample + cancel chain; step 4 done - skills/patrol.py first mc-syntax skill 4/4 legs live; three live findings recorded: resetAt cursor re-anchoring, missing entity-ticking ticket on bare servers, resetAt not surviving JVM restarts; menu command implementation queued behind the in-flight menu refactor)
related:
  - doc/architecture/issues/0007-player-parity-interaction.md
  - doc/architecture/issues/0011-harness-surface-convergence.md
  - doc/architecture/function-map.md
---

# Issue 0012: Work-block usability - vanilla construction delegation and the harness manual menu surface

## 1. Problem

Two gaps, one convergence concern: making work blocks (furnace, anvil,
brewing stand, ...) usable.

1. Construction is per-kind Java. `MenuOpener` hand-builds each menu
   (an if-chain on block class; chest read directly from the block
   entity; double chests rejected outright). Every new container block
   costs adapter code, and the direct-BE chest route bypasses vanilla
   edge semantics (`canOpen` occlusion, loot unpacking, the
   double-chest `CompoundContainer` merge).
2. The harness has no menu path at all. `MenuView` never reaches
   boundary D (no verb, no event, no status field), and
   `MenuTransactions` (ledger 29) is bot-internal. An LLM driver
   cannot inspect or operate a furnace.

User ruling (2026-08-26): harness manual use must be maintained
data-driven - adding a menu kind must cost zero harness-side code.

## 2. Verified ground (decompiled 1.20.1, read-only)

- Construction is already data-driven in vanilla:
  `BlockState.getMenuProvider(level, pos).createMenu(id, inv, player)`
  covers crafting table (`CraftingTableBlock:37`), anvil
  (`AnvilBlock:69`), chest including the double merge (`ChestBlock:288`
  via `MENU_PROVIDER_COMBINER.acceptDouble` returning `sixRows`), and
  every BE menu through `BaseContainerBlockEntity`.
  `BotPlayerFacade extends Player` slots into the third parameter
  directly. A `ContainerDataProvider` interface floated in an external
  proposal does not exist in 1.20.1;
  `AbstractFurnaceBlockEntity.dataAccess` is `protected final` and is
  carried by the BE's own `createMenu` - no field digging is needed.
- Bare `(ServerPlayer)` casts exist at exactly three sites:
  `CraftingMenu:61` (solved by `BotCraftingMenu`),
  `BrewingStandMenu:183` (BREWED_POTION trigger),
  `EnchantmentMenu:187` (ENCHANTED_ITEM trigger). All other uses
  (`FurnaceResultSlot:48`, `MerchantMenu:161`,
  `AbstractContainerMenu.removed` / `clearContainer`) are
  instanceof-guarded and facade-safe. A generic mixin is not warranted;
  the residue is two ~15-line subclass overrides at first open.
- Vanilla menus carry no semantic labels - layout is bare `addSlot`
  order. The disclosure vocabulary is ours (`SlotRole`); the role table
  grows one case per kind, written against the decompiled constructor.
- Transport is RCON over TCP (127.0.0.1:25575), Source RCON protocol,
  synchronous request-response with a 10-second command latch ceiling
  (`BotControlSocket.EXEC_WAIT_SECONDS`). Dedicated servers use vanilla
  RCON; integrated servers use the `BotControlSocket` bridge. There is
  no server push - the event stream is pull-only (`/bot events [since]`).
- `MenuTransactions` (ledger 29) is a boundary-A Java interface with
  five methods (`openMenu`, `openInventoryMenu`, `menuSnapshot`,
  `menuClick`, `closeMenu`), implemented by `BindingActor`, running on
  the server tick thread. It is NOT exposed as a `/bot` command today.
- `RecipeCatalog` exists (adapter-side, queries the server
  `RecipeManager`, supports `byId` and `byResult`, shaped-recipe only)
  but is NOT exposed as a `/bot` command.
- `MissionReporter` is state-derived: every tick it compares
  `previousCurrent` against `arbiter.current()` and emits
  `TASK_COMPLETED` / `TASK_FAILED` on hand-off. Behaviors do not emit
  their own lifecycle events.
- The boundary-D verb table today is six verbs: `status`, `goto`,
  `cancel`, `stop`, `events`, `reset`. `goto` is the only process kind
  and goes through `CommandBus` (async submit + event-stream result).
  No menu verb exists.
- Event payload taskId key inconsistency (resolved 2026-08-26, additive):
  `MissionReporter.emit` used `"task"` key; `CommandBus.finishTask` used
  `"taskId"` key. Fixed by adding `"taskId"` to `MissionReporter.emit`
  alongside the existing `"task"` key (backward compatible). `wait`
  correlation reads `"taskId"` as canonical.
- Goto migration facts (2026-08-26): no existing skills/prompts call
  `/bot goto` directly (grep hits only doc/ files). `mc` syntax is
  born as target syntax with zero old-call-point migration. Event buffer
  depth is 200 (`InMemoryEventQueue.DEFAULT_CAP`). `/bot status` does
  not expose taskId (only `currentTaskSummary` text). `goto` `[key]`
  parameter is an idempotency key with no existing callers.

## 3. Design

### D0 Construction delegation (bot side)

`MenuOpener.open` routes through
`state.getMenuProvider(level, pos)` then
`createMenu(NEXT_ID++, facade.getInventory(), facade)`, keeping
`BotCraftingMenu` as the crafting-table special case (its cast site
lives inside vanilla `slotChangedCraftingGrid`). Effects: double chests
open as `sixRows` - the current rejection and the P2 review
"double-chest half-open" finding dissolve by design; furnace, smoker,
blast furnace, brewing stand, hopper, dispenser, anvil, and beacon open
with zero per-kind construction code; blocked-chest and loot semantics
come along for free.

### D4 Primary interaction model (harness side - the skeleton)

The LLM harness has no slot concept, only desired item-space outcomes and
behavior goals. The primary surface is a Unix-style CLI over a path
namespace - `tool/harness/mc.py`. This is the layer the LLM actually
calls; wire commands (D1) are the translation target, not the API.

**Path namespace:**

| Path prefix | Verbs | Meaning |
|---|---|---|
| `/player/<field>` | `cat` | flat state: inventory, pos, health, menu, status |
| `/recipes/<item>` | `cat` | RecipeManager query (wire pending) |
| `/stations/<type>@<x,y,z>/<role>` | `ls` / `read` / `write` | menu interaction (lazy session bind; wire pending) |
| `/tasks/` | `ls` / `cat` / `write` / `wait` | task submit, current view, post-hoc state by id, wait-for-terminal |
| `/events` | `events` | incremental event drain, cursor on disk |

**Six verbs:**

| Verb | Semantics | Session nature |
|---|---|---|
| `mc ls <path>` | discovery: directory listing | stateless |
| `mc cat <path>` | flat state read (no session) | stateless |
| `mc read <path>` | menu snapshot read (lazy session bind) | lazy-bound |
| `mc write <path> <value>` | write operation (deposit / craft / task submit / cancel) | action |
| `mc wait <taskId> [--timeout N]` | block until task reaches terminal state | blocking (client-side poll loop) |
| `mc events [--since N]` | incremental event drain, advances disk cursor | cursor |

`cat` vs `read` is determined by path prefix: `/player/*` and `/tasks/current` are `cat` (stateless); `/stations/*` is `read` (lazy session bind). Verb misselection is a typed error, never silent substitution: `cat` on a station path replies with the `read` suggestion and exits 1, and vice versa. `cat` is the stateless verb - executing `read` under its name would lazily bind a bot-side menu session as a side effect of a read. Unix tools suggest, they do not substitute.

**Existing six-wire-verb landing table (goto migration - zero wire changes):**

| Existing wire | Namespace landing | Wire change |
|---|---|---|
| `/bot status` | `mc cat /player/*` + `mc cat /tasks/current` | zero |
| `/bot goto x y z tol timeout [key]` | `mc write /tasks/goto "x,y,z" --tol N --timeout N [--key K]` | zero |
| `/bot cancel <id>` | `mc write /tasks/<id>/cancel "reason"` | zero |
| `/bot stop` | `mc admin stop` (operator sweep; outside namespace - see note) | zero |
| `/bot events [since]` | `mc events` (cursor on disk) | zero |
| `/bot reset` | `mc admin reset` (deliberately outside namespace) | zero |

`/bot stop` does NOT land as `write /tasks/current/cancel`: `CommandBus.submit` has no single-task gate, so multiple live missions are reachable (one seated on the arbiter, one or more registered and requesting control), and `/bot stop` sweeps the whole handler map while a cancel kills exactly one. Mapping the sweep onto cancel-current would be a silent semantic lie in precisely the multi-task case where an operator reaches for stop. Both operator panic verbs (`stop`, `reset`) land outside the namespace as `mc admin <verb>` - friction is a feature.

This is NOT a "stateless -> stateful" migration. goto was never stateless -
taskId, TASK_COMPLETED events, and the `events [since]` cursor always
carried state, stored in bot memory and harness call-site closures. The
namespace externalizes implicit state into addressable paths. Wire is
untouched; migration is pure harness-side consumer re-addressing.

**Why goto migrates first:** it is the only living async test payload.
The three hardest machines in the CLI - `wait` poll loop, disk cursor,
task correlation - all live in the async half. goto is the only wire
verb that can drive them today. Menu verbs are not implemented yet;
waiting for them means deferring the riskiest machinery to last. goto
is also physically idempotent (walking to the same cell twice is
harmless), making it the only write operation suitable for shadow
comparison testing.

**Verification flow (6 steps, craft + chest storage):**

```
mc ls /stations/                          # scan: find crafting_table + chest
mc cat /player/inventory                  # confirm materials
mc write /stations/crafting@x,y,z/craft "wooden_pickaxe"   # reply carries inventory delta
mc read /stations/chest@x,y,z/            # station switch: close-before-open
mc write /stations/chest@x,y,z/input "wooden_pickaxe:1"
mc cat /player/inventory                  # confirm pickaxe left inventory
```

Step 4 (crafting -> chest switch) is the first real test of the
close-before-open discipline and the composite-key diff (MENU_CLOSED +
MENU_OPENED in one transition).

**CLI skeleton status:** `tool/harness/mc.py` ships v1 covering the goto
migration surface (`cat /player/*`, `cat /tasks/current`, `cat
/tasks/<id>`, `write /tasks/goto`, `write /tasks/<id>/cancel`, `wait`,
`events`, `ls /tasks/`, `admin stop` / `admin reset`). Menu-domain verbs
(`read /stations/*`, `write /stations/*/input`, `write /stations/*/craft`)
are stubs that report "wire pending" until D1 menu commands land.
Translation layer is transparent-first: no default values, no retries, no
corrections - parameters pass through to wire verbatim. Covered by
wire-mocked unit tests (`tool/harness/test_mc.py`, 30 tests, stdlib
unittest, no server).

**Receipt rule (model-layer, not wire detail):** `write` replies carry
the direct consequence of the write plus a `seq` version number. This is
Agent-visible behavior - `write /stations/crafting@.../craft "wooden_pickaxe"`
reply includes the inventory delta (items consumed, item produced). Agent
does not need a follow-up `cat` to confirm. This rule stays in the model
layer; it does not sink to D5 invariants.

**Two prerequisites:**

- Wire: `freeSlots` joins `BotState` (one integer, rides `getState`).
  `itemCounts` is type-to-count and cannot derive free slots. `health`
  joins `BotState` the same way before `/player/health` can go live.
- Transport: `tool/rcon.py` already exists. `mc.py` reuses its
  `run_command` directly - zero transport duplication.

Multi-tick world behavior (farming, mining as a goal) stays bot-side:
harness submits a mission via `write /tasks/<kind>`, behaviors run the
ticks, `wait` or `events` reports progress - the same split the
HungryProcess issue (0010) already embodies.

### D1 Wire translation table (the integration contract)

This is the contract between bot-side command implementation and
harness-side CLI translation. Both workflows develop against this table.

**Verb x path-prefix -> wire command:**

| CLI verb | Path prefix | Wire command | Status |
|---|---|---|---|
| `cat` | `/player/inventory` | `/bot status` (items field) | live |
| `cat` | `/player/pos` | `/bot status` (pos field) | live |
| `cat` | `/player/status` | `/bot status` (full) | live |
| `cat` | `/player/health` | `/bot status` (health field) | pending: `health` joins `BotState` |
| `cat` | `/player/menu` | `/bot status` (menu field, pending D3) | pending |
| `cat` | `/tasks/current` | `/bot status` (task field) | live |
| `cat` | `/tasks/<id>` | `/bot events 0` filtered by `attrs.taskId` (client-side derivation; strict id match, display-name `task` key never correlates) | live |
| `ls` | `/tasks/` | `/bot status` (task-derived) | live |
| `write` | `/tasks/goto` | `/bot goto x y z tol timeout [key]` | live |
| `write` | `/tasks/<id>/cancel` | `/bot cancel <id>` | live |
| `wait` | `<taskId>` | poll `/bot events` until terminal | live |
| `events` | `/events` | `/bot events <cursor>` | live |
| `ls` | `/stations/` | `/bot scan [radius] [limit]` | pending |
| `read` | `/stations/<type>@<pos>/` | `/bot menu snapshot` | pending |
| `write` | `/stations/<type>@<pos>/input` | `/bot menu deposit <role> <item> <count>` | pending |
| `write` | `/stations/<type>@<pos>/output` | `/bot menu take <role> [count]` | pending |
| `write` | `/stations/crafting@<pos>/craft` | `/bot menu craft <recipeId>` | pending |
| `cat` | `/recipes/<item>` | `/bot recipes <itemId>` | pending |
| `admin` | `stop` / `reset` | `/bot stop` / `/bot reset` | live |

**Menu command inventory (pending implementation - synchronous RPC, NOT CommandBus):**

Menu commands are synchronous request-response transactions. They do NOT
go through `CommandBus` (the async submit/taskId/event-stream channel
used by `goto`). Each is a brigadier `executes` lambda calling
`MenuTransactions` directly on the server thread (RCON `execute()`
already posts via `server.execute()` and waits on a `CountDownLatch`).
No taskId, no event-stream result reporting - the result IS the RCON reply.

| Command | Effect | Reply |
|---|---|---|
| `menu open <x> <y> <z>` | reach-gated open (4.5 blocks); closes any current session first | full `MenuView` snapshot, or `{ok:false, reason}` |
| `menu open-inventory` | bot's own 2x2 crafting grid + full inventory | full snapshot |
| `menu snapshot` | current menu state | full snapshot, or `{ok:false, reason:"no menu open"}` |
| `menu close` | terminal, idempotent | `{ok:true}` |
| `menu deposit <slotRole> <item> <count>` | bot-side composite: find item in inventory, loop `menuClick`, atomic | `{ok:true, placed:N}` or `{ok:false, reason:"not enough", have:N}` |
| `menu take <slotRole> [count]` | bot-side composite: shift-click from slot to inventory | `{ok:true, taken:N}` |
| `menu craft <recipeId>` | bot-side composite: `RecipeCatalog.byId`, place ingredients, take result | `{ok:true, result:item, count:N}` or `{ok:false, reason, blockers:[]}` |
| `scan [radius] [limit]` | read-only: nearby container block entities | `{containers:[...], truncated:bool}` |
| `recipes <itemId>` | read-only: `RecipeCatalog.byResult` | `{recipes:[...]}` |

`deposit`/`take` operate on homogeneous slots (chest, furnace
input/fuel/output). `craft` is shape-driven and SEPARATE - writing
`"stick:2 + plank:3"` to a crafting input is not a deposit because a
multiset does not uniquely determine grid placement (wooden_pickaxe and
wooden_axe share the same multiset, differ only in layout). The CLI
syntax (`write .../craft "wooden_pickaxe"`) stays the same; its wire
mapping is `menu craft`, not `menu deposit`.

Atomicity: sufficiency check and click loop execute inside the same latch
scope (`server.execute()` post). On insufficient items, ZERO clicks
perform - no partial deposit.

### D2 Self-describing disclosure (the data-driven half)

Every snapshot reply carries `type`, `source`, and per-slot `role` plus
item. Harness-generic recipes ("click every `role=OUTPUT` slot",
"move A to B = PICKUP + PLACE") are written once and work for every
kind - zero per-kind harness code. Replies of `open` and `click` are
the full snapshot; a compact mode is deferred until token cost is
measured against a live harness.

### D3 Menu events (state-derived via MissionReporter derivation baseline)

`MENU_OPENED` and `MENU_CLOSED` are emitted by derivation, not by
explicit emission at open/close call sites. The `MissionReporter`
pattern (every tick: compare previous state against current state, emit
on transition) extends to menu state via a new `BindingActor` accessor
`currentMenuKind()` returning `null` or `{kind, x, y, z}`.

Derivation is chosen over emission for completeness: derivation naturally
captures every close path - bot walked out of reach (vanilla
`stillValid` failure), death clears state, lease expiry, any future
mechanism - with zero instrumentation cost. Explicit emission requires
every close path to remember to fire an event; one missed path is silent
state drift. Derivation is reconciliation; emission is instrumentation;
reconciliation does not miss entries.

Three implementation details:

1. **Composite-key diff.** Comparison key is `{kind, x, y, z}`. Previous
   `chest@A` -> current `chest@B` produces TWO events: `MENU_CLOSED(chest@A)`
   then `MENU_OPENED(chest@B)`. Never a single "switched" event - consumers
   need terminal-state semantics, not operation semantics.
2. **Same-tick open-close is invisible by design.** Three RCON commands
   (open -> deposit -> close) in one tick's task phase: derivation sees
   `null -> null`, emits nothing. Correct: synchronous command replies
   report what YOU did; the event stream reports what HAPPENED between
   ticks. A menu that opened and closed within one tick never existed as
   an inter-tick world state.
3. **`currentMenuKind()` is one accessor, two consumers.** Feeds
   `MissionReporter` derivation AND is added to `/bot status` JSON as a
   `menu` field. This is the `menu/current` read endpoint - no separate
   command needed.

Boundary: derivation gives transitions, not causes. `MENU_CLOSED` never
carries a `cause` field. The cause belongs to the synchronous command
reply.

### D5 Translation-layer invariants (MUST constraints for correct translation)

These are not optional reference material - they are the constraints that
make the CLI->wire translation correct. They do not occupy model-layer
position (D4) but are mandatory for any implementation of D1.

**Timeout = unknown, not failure.** RCON latch has a 10-second ceiling.
When it times out, the `server.execute()` post may STILL EXECUTE after
the response returns. A timed-out `deposit` may have deposited all items,
some, or none. The CLI MUST treat menu-command timeout as `unknown`, not
`failed`. Recovery is an idempotent read: `menu snapshot` reconciles
actual state. Never blindly retry a timed-out menu command - retry may
double-execute.

**Channel division principle.** Synchronous commands report operation
results (what YOU did). The event stream reports state transitions (what
HAPPENED between ticks). A command reply and an event may describe the
same action from different vantage points; neither is a substitute for the
other.

**Single-client assumption.** RCON connections are stateless; the menu
session is stateful (lives in `BindingActor.containerMenu`). Slice 1
assumes exactly one RCON client operates menus. A second `menu open`
while a session is active returns `{ok:false, reason:"busy"}`. `busy`
is a tripwire: if it fires, the CLI has a bug (missing close-before-open)
or a second client has entered.

**Lease-based auto-close.** A bare `busy` with no lease is a permanent
deadlock: if the RCON client dies without `close`, the session hangs
forever. Fix: if `containerMenu != null` and no menu command arrives for
30-60s, the tick loop auto-closes. One implementation serves three
purposes: crash recovery, FS-layer "no-IO auto-close" semantics, and
`MENU_CLOSED` auto-entering the event stream (derivation fires on
lease-close for free).

**CLI discipline: close-before-open.** When switching stations, the CLI
MUST send `menu close` before the next `menu open`. One extra localhost
round-trip (negligible); keeps `busy` as a pure tripwire.

**`/bot reset` clears the menu session.** The crash-latch reset command
gains one additional side effect: close any active menu session. This is
the manual escape hatch for a wedged session.

**Two timeouts never merge.** `--timeout` on `write /tasks/goto` is the
task-level movement deadline (server ticks); `--timeout` on `mc wait` is
client patience (seconds). They share spelling and nothing else. A CLI
that derives one from the other, or collapses them into a single knob,
has merged a mission parameter with a harness parameter - the largest
hiding place for migration bugs.

**The disk cursor is the operator's bookmark.** Unix gives every reader
its own file offset; the shared cursor file does not. Only a cursorless
`mc events` advances it; `--since N` is a peek that never advances;
`wait` reads it as a starting point but never advances it (the audit
stream stays complete - internal consumption must not eat events).
Concurrent programmatic consumers pass `--since` and keep their own
offsets: two consumers sharing the bookmark each see a partition, not
the stream. The bookmark stores `<eventId> <resetAt>` and self-heals
on two restart signals: a changed `resetAt` epoch (an explicit
`reset()` mid-boot) and bookmark-beyond-stream-head (a JVM restart -
the reliable cross-restart signal, found live: `resetAt` as shipped
does NOT change across boots, because a fresh queue starts the marker
at 1 again; only explicit `reset()` increments it, so epochs collide
across restarts. The EventQueue doc's "monotonic bot-restart marker"
overpromises as implemented - either the marker must be seeded from a
persistent counter, or the client rule is bookmark-beyond-head. The
CLI implements the latter). On either signal `wait` re-anchors its
scan to 0 and `events` drains the new stream from 0.

### Data-driven maintenance ladder

| Level | Cost | What it buys |
|---|---|---|
| L0 unknown kind | zero files | generic disclosure: container region + player region - modded blocks usable day one |
| L1 known kind | +1 `roleOf` case (bot-side code) | semantic roles (INPUT / FUEL / OUTPUT ...) |
| L2 JSON role table | loader + gate | semantic roles for menus the mod was not compiled against - deferred, reopen trigger in section 6 |

## 4. Rulings recorded (2026-08-26, user direction)

1. Verb set as in D1. No high-level recipe verbs - `smelt` /
   `take_output` are harness-side scripts or behaviors, not boundary-D
   verbs.
2. Full-snapshot replies in slice 1; compact mode deferred.
3. L2 JSON stays deferred until its reopen trigger fires.
4. Primary interaction model (D4) lives harness-side (`tool/harness/mc.py`);
   boundary D stays at the wire layer (D1 translation table). The role
   snapshot is the bot side's only structural commitment to the harness
   side - every capability function is composable from roles + clicks +
   missions, written once, all menu kinds. D1-D3 are accepted by whether
   the CLI can build stably on the wire, not by whether an LLM can read
   raw wire.
5. Menu session ownership is asymmetric loud failure: manual verb against
   a mission-owned session is rejected (`busy, owner=mission`) because an
   unhandled `IllegalStateException` inside a behavior trips the ADR-0005
   crash latch. A mission open displaces a manual session (harness
   supervises and can cancel; MENU_CLOSED + MENU_OPENED events carry the
   transition). The D4 context manager raises a distinct
   `SessionPreempted` exception when it observes the session gone
   mid-`with`-block. `MenuTransactions` signatures stay untouched, ledger
   29 intact.
6. OVERTURNED 2026-08-26 (was: discovery stays explicit). The exploratory
   interaction model makes discovery the entry point: `ls /stations/` has
   nothing to list without `scan`. The two read-only verbs join the wire
   with the D1 batch; recipe source of truth stays the server's own
   RecipeManager.
7. The harness interaction model is a Unix-style shell over a path
   namespace (`type@x,y,z/path`, role paths where L1 exists, numeric
   indices always). Existing verbs fit (`status`=`ps`, `events`=`tail -f`,
   `goto`/`cancel`=job control). Two elements do not transfer: pipes (the
   LLM conversation IS the pipe) and wire-side Unix spelling (`/bot` verbs
   stay descriptive protocol vocabulary; `ls`/`cat`/`write`/`wait` are the
   CLI mapping).
8. Menu commands are synchronous RPC (D1), not CommandBus async
   submissions. Menu commands call `MenuTransactions` directly in the
   brigadier `executes` lambda; the result is the RCON response.
9. Menu events use derivation baseline (D3), not explicit emission.
   `currentMenuKind()` accessor, composite-key diff, no `cause` field.
   Same-tick open-close invisibility is a feature.
10. Synchronous command timeout = unknown (D5). Recovery is `menu snapshot`
    reconciliation; never blindly retry. Channel division: commands report
    operation results, events report state transitions.
11. Single-client session assumption with lease-based auto-close (D5):
    30-60s idle lease, `busy` as tripwire, CLI close-before-open, `/bot
    reset` clears session.
12. `deposit` and `craft` are separate wire commands (D1). `deposit` for
    homogeneous slots; `craft` shape-driven via `RecipeCatalog`. Atomicity:
    all-or-nothing, sufficiency check inside the latch scope.
13. `/bot status` gains a `menu` field (D3 detail 3) carrying
    `currentMenuKind()` - the `menu/current` read endpoint is free.
14. Goto migration is pure harness-side re-addressing (D4): zero wire
    changes, existing six verbs map directly to namespace paths. `mc.py`
    v1 ships the goto surface; menu verbs are stubs until D1 lands.
15. Event payload `taskId` key unified (additive, 2026-08-26):
    `MissionReporter.emit` now carries both `"task"` (backward compat) and
    `"taskId"` (canonical for `wait` correlation). `CommandBus.finishTask`
    already used `"taskId"`.
16. OVERTURNED 2026-08-26 (was: CLI silently tolerates cat-on-station by
    executing read). Typed error with the `read` suggestion instead:
    `cat` is the stateless verb; silent substitution would lazily bind a
    bot-side session as a side effect of a read and contradicts the
    transparent-first manifesto. Unix tools suggest, never substitute.
17. `/bot stop` lands as `mc admin stop`, not `write
    /tasks/current/cancel` (2026-08-26): `CommandBus.submit` has no
    single-task gate, so multi-live missions are reachable; stop sweeps
    all, cancel kills one. Operator sweeps stay outside the namespace
    next to `admin reset` - friction is a feature.

## 5. Sequencing

Blocked: `MenuOpener` / `BindingMenu` / `SlotView` / `MenuTransactions`
were mid-flight in a concurrent session when this issue was filed. The
handoff contract is pinned: the `api/menu` surface (ledger 29) - the
concurrent work is adapter-internal behind it, so D0 waits on file landing,
not shape discovery.

Implementation order:

1. **D0** first (gametest: furnace opens with zero per-kind code; double
   chest opens as sixRows).
2. **D1 menu commands** (`open` / `open-inventory` / `snapshot` / `close` /
   `deposit` / `take` / `craft` / `scan` / `recipes`) - brigadier lambdas
   calling `MenuTransactions` and `RecipeCatalog` directly, NOT through
   CommandBus. Minimal verification chain (craft + chest storage) needs only
   `crafting_table` and `chest` (both already supported by `MenuOpener`) -
   furnace expansion is NOT on the critical path.
3. **D3 derivation events** - `currentMenuKind()` accessor, `MissionReporter`
   extended to compare menu state, `MENU_OPENED` / `MENU_CLOSED` event kinds,
   `/bot status` `menu` field. (Event `taskId` key unification already
   shipped 2026-08-26.)
4. **D5 invariants** - lease auto-close in tick loop, `/bot reset` session
   clear, timeout=unknown in CLI wire contract.
5. **D2** first L1 row (furnace: INPUT / FUEL / OUTPUT) + `freeSlots` on
   `BotState` + furnace burn/cook progress disclosure.
6. **D4 CLI menu translation** - `mc.py` stubs for `read /stations/*`,
   `write /stations/*/input`, `write /stations/*/craft` become real
   translations against D1 commands. Run the 6-step verification chain.
7. **D4 goto migration validation** (can run in parallel with 1-5, since
   goto wire is already live) - shadow comparison EXECUTED 2026-08-26
   via `tool/harness/shadow_compare.py` (old raw-RCON path vs
   `mc write /tasks/goto`, 5 checks): ALL GREEN including the
   TASK_COMPLETED sample on both paths and the cancel chain - wire
   command byte-identical, receipts same shape, bare `taskId` on every
   terminal kind both paths, `cat /tasks/<id>` derivation agrees with
   `wait`, cancel-then-wait exits 0 on TASK_CANCELLED. Two live
   findings along the way, both fixed or recorded:
   (a) cross-restart cursor trap - the bookmark outlived the stream;
   `resetAt` re-anchoring added to the cursor invariants (D5);
   (b) on a bare dedicated server the bot's chunks have no
   entity-ticking ticket: the body freezes (plan advances, Motion
   never decays) until `forceload add` - gametest never hits this
   because the framework supplies its own tickets. The mod has NO
   companion chunk loader (AGENTS.md section 1.1 names the class as a
   convention example, not code) - a bot-side gap this validation
   exposed; the runbook in shadow_compare.py covers the manual prep.
   Also fixed en route: negative-coordinate write values hit argparse
   option parsing twice (token classification + argv=None bypassing
   the shim). Step 4 EXECUTED same day: `skills/patrol.py` is the
   first skill written natively in mc syntax - 4-leg walking patrol,
   each leg submit -> wait -> cat /tasks/<id> cross-check, audit
   drain at the end, zero raw RCON; ran 4/4 TASK_COMPLETED against
   the live server. The grep criterion (`grep -r "bot goto" skills/`
   empty) holds by construction. Third live finding during step 4:
   resetAt does not signal JVM restarts as shipped (see the D5 cursor
   invariant) - CLI detection is bookmark-beyond-head. Completion
   criterion (agent runs N sessions without raw RCON):
   `grep -r "bot goto" skills/` returns empty + agent runs N sessions
   without touching raw RCON.

The CLI wire contract (request/response field-level definitions per D1
command) is the integration surface between bot-side implementation (steps
2-5) and harness-side CLI (step 6) and can be written in parallel once D1's
command inventory is frozen.

## 6. Deferred with reopen

- Brewing / enchant cast-subclass overrides - at first open of either menu,
  following the `BotCraftingMenu` pattern.
- L2 JSON role table - reopen when a customer needs semantic roles for menus
  the mod was not compiled against.
- A mixin for the cast family - reopen only if the subclass count grows past
  trivial (two sites today).
- Full-chain `acquire` (chests -> smelt -> mine via `submit` + event waits)
  and `craft` as a cross-layer orchestrator - slice 2, composed once `smelt`
  and the submit-wait pattern are stable.
- Multi-client session arbitration - slice 2; slice 1 assumes one RCON client
  operating menus. Reopen when a second client requires queuing rather than
  tripwire rejection.
- FUSE mount as a second frontend - deferred until wire layer gains a push
  mechanism (today RCON is synchronous pull-only; FUSE's blocking-read and
  tail -f advantages depend on source-side push or long-blocking wait, which
  the CLI `wait` poll loop already provides at user-space cost).
