#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""mc - Unix-style CLI for the MC Bot Server boundary-D surface.

Six verbs over a path namespace:
  mc ls    <path>          discovery (directory listing)
  mc cat   <path>          flat state read (no session)
  mc read  <path>          menu snapshot read (lazy session bind)
  mc write <path> <value>  write operation (deposit / craft / task submit / cancel)
  mc wait  <taskId>        block until task reaches terminal state
  mc events                incremental event drain, cursor on disk

Namespace:
  /player/<field>          cat only (inventory, pos, health, menu, status)
  /recipes/<item>          cat only (RecipeManager query)
  /stations/<type>@<x,y,z>/<role>  ls/read/write (menu domain)
  /tasks/                  write (submit goto), wait, cancel
  /events                  events verb only

Wire mapping:
  cat /player/inventory    -> /bot status  (items field)
  cat /player/pos          -> /bot status  (pos field)
  cat /player/status       -> /bot status  (full)
  cat /player/menu         -> unsupported (menu field pending, issue 0012 D1)
  cat /recipes/<item>      -> recipes <item>
  cat /tasks/current       -> /bot status  (task field - a displayName
                              summary like "goto:t14", NOT a
                              wait-correlatable id; ids come from
                              write receipts)
  cat /tasks/<id>          -> /bot events 0, filtered by attrs.taskId
                              (client-side derivation, zero wire change)
  ls /stations/             -> scan 16 50  (nearby container discovery)
  read /stations/<t>@<pos>/ -> menu open -> menu snapshot -> menu close
                              (lazy session bind, stateless per-call)
  read /stations/<t>@<pos>/<role> -> same + client-side role filter
  write /stations/<t>@<pos>/input "item:count"  -> menu deposit INPUT
  write /stations/<t>@<pos>/fuel "item:count"   -> menu deposit FUEL
  write /stations/<t>@<pos>/output "all"|"N"    -> menu take OUTPUT [N]
  write /stations/crafting@<pos>/recipe "id"     -> menu craft <id>
  write /tasks/goto "x,y,z" [--tol N] [--timeout N] [--key K] -> /bot goto
                              (receipt carries the id under the "task" key)
  write /tasks/<id>/cancel "reason" -> /bot cancel <id>
  wait <taskId> [--timeout N] -> poll /bot events until terminal kind
  events [--since N] -> /bot events <cursor>
  admin stop|reset -> /bot stop | /bot reset  (operator verbs,
      deliberately outside the namespace: stop sweeps ALL live
      missions - CommandBus.submit has no single-task gate - while
      cancel covers exactly one)

Verb discipline: typed errors, never silent substitution. cat on a
station path errors with the read suggestion; read on a non-station
path errors with the cat suggestion. cat is the stateless verb and
must not lazily bind a bot-side menu session as a side effect of a
read (issue 0012 D4).

Translation layer is transparent-first: no default values, no retries,
no corrections. Parameters pass through to wire verbatim. If behavior
diverges from raw RCON, the bug is in this file (grepable) or does
not exist.

Exit codes: 0 ok (wait returns it ONLY on TASK_COMPLETED); 1
rejected/unsupported and every non-COMPLETED terminal kind; 124 wait
timeout; 3 rcon error. (argparse usage errors exit 2 by argparse's
own convention.)

Cursor lives at tool/.runtime/mc_cursor.txt (gitignored), storing
"<eventId> <resetAt>". The disk cursor is the operator's bookmark,
not a per-reader offset: only a cursorless `mc events` advances it;
`--since N` is a peek and never advances; wait reads the cursor as a
starting point but does not advance it (the audit stream must stay
complete - internal consumption must not eat events). Concurrent
programmatic consumers pass --since and keep their own offsets. A
changed resetAt epoch (bot restart - the event stream does not
survive restarts by boundary-D contract) voids the bookmark: wait
re-anchors its scan to 0, events drains the new stream from 0.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

# Reuse the existing RCON client (zero transport duplication).
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from rcon import load_config, run_command, RconError  # noqa: E402

TOOL_DIR = Path(__file__).resolve().parent.parent
CURSOR_PATH = TOOL_DIR / ".runtime" / "mc_cursor.txt"

# Materialized recipe cache: `mc admin dump-recipes` writes one file
# per shaped recipe here. Thereafter `mc cat /recipes/<slug>` reads
# locally (zero wire), and grep over this directory is the reverse
# lookup ("what can I make with iron_ingot?"). Vanilla recipes are
# static; re-run dump after a datapack reload.
RECIPES_DIR = Path.home() / ".mc" / "recipes"


def only_suffix(only: str | None) -> str:
    """Wire narrowing for `events --only`: rides the server-side
    [only] kind-prefix filter (0011 D3); cursor-integrity kinds
    survive every filter server-side, never client-side."""
    return f" only {only}" if only else ""


def emit_json(obj) -> None:
    """stdout answer: stable JSON - indented for a human at a tty,
    one line when piped (canon harness-interaction.md section 7)."""
    print(json.dumps(obj, indent=2 if sys.stdout.isatty() else None))

# Terminal task states for wait correlation. Includes CANCELLED and
# DROPPED: a cancel-then-wait must not burn the full timeout, and a
# reflex eviction (TASK_DROPPED) is terminal for the waiting harness.
TERMINAL_KINDS = {"TASK_COMPLETED", "TASK_FAILED", "TASK_REJECTED",
                   "TASK_CANCELLED", "TASK_DROPPED"}


# ---------------------------------------------------------------------------
# RCON wrapper
# ---------------------------------------------------------------------------

def wire(command: str) -> dict:
    """Run one RCON command and parse the JSON response.

    The bot answers every /bot verb with a single-line JSON object.
    Non-JSON output (e.g. vanilla command feedback) is returned as
    {"raw": text}. Raises RconError on connection/protocol failure.
    """
    cfg = load_config()
    raw = run_command(cfg, command)
    # Find the first JSON object - RCON may prepend human-readable lines.
    start = raw.find("{")
    if start < 0:
        return {"raw": raw.strip()}
    try:
        return json.loads(raw[start:])
    except json.JSONDecodeError:
        return {"raw": raw.strip()}


# ---------------------------------------------------------------------------
# Cursor management
# ---------------------------------------------------------------------------

def read_cursor_state() -> tuple[int, int]:
    """Read the operator bookmark as (eventId, resetAt-epoch).

    Old-format files (a bare int) parse with epoch 0, which never
    matches a live stream (resetAt starts at 1) - first comparison
    re-anchors once. That is the correct one-time cost: an epoch-less
    cursor cannot be trusted against any stream.
    """
    if CURSOR_PATH.exists():
        parts = CURSOR_PATH.read_text(encoding="utf-8").split()
        if parts:
            event_id = int(parts[0])
            epoch = int(parts[1]) if len(parts) > 1 else 0
            return event_id, epoch
    return 0, 0


def write_cursor_state(event_id: int, epoch: int) -> None:
    CURSOR_PATH.parent.mkdir(parents=True, exist_ok=True)
    CURSOR_PATH.write_text(f"{event_id} {epoch}\n", encoding="utf-8")


def read_cursor() -> int:
    return read_cursor_state()[0]


def write_cursor(value: int) -> None:
    write_cursor_state(value, read_cursor_state()[1])


# ---------------------------------------------------------------------------
# Station path parsing and session management (menu domain)
# ---------------------------------------------------------------------------

# Roles that map to deposit (homogeneous input slots).
DEPOSIT_ROLES = frozenset({"INPUT", "FUEL"})
# Roles that map to take (output slots).
TAKE_ROLES = frozenset({"OUTPUT"})
# recipe is the craft trigger: write recipeId -> menu craft.
CRAFT_ROLE = "RECIPE"
# All known role segments (for error messages).
ALL_ROLES = DEPOSIT_ROLES | TAKE_ROLES | frozenset({CRAFT_ROLE})


def parse_station_path(path: str) -> dict | None:
    """Parse /stations/<type>@<x,y,z>/<role> into a dict.

    Returns None if the path is not a valid station path. role is None
    when the path addresses the whole menu (no role segment). The role
    is uppercased in the returned dict for case-insensitive matching.
    """
    if not path.startswith("/stations/"):
        return None
    rest = path[len("/stations/"):]
    if not rest or rest == "/":
        return None  # /stations/ itself is handled by cmd_ls
    parts = rest.split("/", 1)
    station = parts[0]
    role = parts[1].rstrip("/") if len(parts) > 1 and parts[1] else None
    if "@" not in station:
        return None
    type_, pos_str = station.split("@", 1)
    try:
        x, y, z = (int(p.strip()) for p in pos_str.split(","))
    except (ValueError, AttributeError):
        return None
    return {"type": type_, "x": x, "y": y, "z": z,
            "role": role.upper() if role else None}


def station_open(station: dict) -> dict:
    """Open a station menu. Returns the open reply dict."""
    return wire(f"menu open {station['x']} {station['y']} {station['z']}")


def station_close() -> None:
    """Close the current station menu. Best-effort, never raises.

    Called in a finally block so a failed operation never leaves a
    dangling session (the bot-side lease is the backstop, but closing
    proactively keeps the session state honest).
    """
    try:
        wire("menu close")
    except RconError:
        pass


# ---------------------------------------------------------------------------
# Recipe materialization (static data -> disk files -> grep takes over)
# ---------------------------------------------------------------------------

def fits_inventory_grid(recipe: dict) -> bool:
    """Client-side replica of RecipeView.fitsInventoryGrid().

    A recipe fits the 2x2 inventory crafting grid when every occupied
    pattern cell sits in the top-left 2x2. Otherwise it needs a
    crafting_table. This derivation is pure — no wire call needed.
    """
    width = recipe.get("patternWidth", 3)
    for pos_str in recipe.get("placements", {}):
        pos = int(pos_str)
        if pos // width > 1 or pos % width > 1:
            return False
    return True


def derive_inputs(recipe: dict) -> dict[str, int]:
    """Derive the economics-view inputs from a recipe's placements.

    Each pattern cell accepts one or more item ids (tag-expanded). For
    the materialized file we take the first accepted id per cell as the
    representative and count occurrences. This is the "what does it
    cost" view; exact placement is the bot's internal concern at craft
    time (RecipeCatalog.byId -> MenuDriver clicks).
    """
    from collections import Counter
    counts: Counter[str] = Counter()
    for accepted in recipe.get("placements", {}).values():
        if accepted:
            counts[accepted[0]] += 1
    return dict(counts)


def format_recipe_file(recipe: dict) -> str:
    """Format one shaped recipe as a Unix-style text file.

    Schema (four sections, no shape grid — that is the bot's internal
    detail):
        # recipe: <registry-id>
        station: inventory|crafting_table
        inputs:
          <item-id> x<count>
        output:
          <item-id> x<count>
    """
    station = "inventory" if fits_inventory_grid(recipe) else "crafting_table"
    inputs = derive_inputs(recipe)
    lines = [
        f"# recipe: {recipe['recipeId']}",
        f"station: {station}",
        "inputs:",
    ]
    for item, count in sorted(inputs.items()):
        lines.append(f"  {item} x{count}")
    lines.append("output:")
    lines.append(f"  {recipe['resultItemId']} x{recipe['resultCount']}")
    return "\n".join(lines) + "\n"


def cmd_admin_dump_recipes() -> int:
    """Dump every shaped recipe to ~/.mc/recipes/<slug>.

    Pages through `/bot recipes list` (RCON payload cap makes a single
    call impossible), writes one file per recipe. The slug is the
    recipe id with the namespace stripped (minecraft:wooden_pickaxe ->
    wooden_pickaxe); non-vanilla namespaces are preserved in the
    filename to avoid collisions. Thereafter `mc cat /recipes/<slug>`
    reads locally and grep over the directory is reverse lookup.
    """
    RECIPES_DIR.mkdir(parents=True, exist_ok=True)
    offset = 0
    total = None
    written = 0
    while True:
        resp = wire(f"recipes list {offset} 50")
        if not resp.get("ok"):
            print(f"dump-recipes: list failed at offset {offset}: "
                  f"{resp.get('reason', 'unknown')}", file=sys.stderr)
            return 1
        batch = resp.get("recipes", [])
        total = resp.get("total", total)
        if not batch:
            break
        for recipe in batch:
            rid = recipe.get("recipeId", "")
            # Strip "minecraft:" prefix for vanilla; keep other namespaces.
            slug = rid.split(":", 1)[1] if rid.startswith("minecraft:") else rid
            if not slug:
                continue
            (RECIPES_DIR / slug).write_text(
                format_recipe_file(recipe), encoding="utf-8")
            written += 1
        offset += len(batch)
        if total is not None and offset >= total:
            break
    print(f"dumped {written} recipes to {RECIPES_DIR}")
    return 0


# ---------------------------------------------------------------------------
# Verb implementations (v1 - goto migration surface)
# ---------------------------------------------------------------------------

def cmd_cat(path: str) -> int:
    """Flat state read. Maps /player/* and /tasks/current to /bot status."""
    if path.startswith("/player/") or path == "/player":
        field = path[len("/player/"):] if path.startswith("/player/") else "status"
        resp = wire("/bot status")
        state = resp.get("state", resp)
        if field in ("", "status"):
            emit_json(state)
        elif field == "inventory":
            emit_json(state.get("items", {}))
        elif field == "inventory/free":
            print(json.dumps(state.get("freeSlots", 0)))
        elif field == "pos":
            print(json.dumps(state.get("pos", [])))
        elif field == "health":
            print(json.dumps({"healthHearts": state.get("healthHearts"),
                              "freeSlots": state.get("freeSlots")}))
        elif field == "menu":
            # /bot status does not carry a menu field yet (D1 menu commands
            # are pending). Report unsupported rather than silently returning
            # the task summary under the wrong name.
            print("cat /player/menu: unsupported (menu wire pending, issue 0012 D1)",
                  file=sys.stderr)
            return 1
        else:
            print(f"unknown field: {field}", file=sys.stderr)
            return 1
        return 0
    if path == "/tasks/current":
        resp = wire("/bot status")
        state = resp.get("state", resp)
        print(state.get("task", "idle"))
        return 0
    task_match = re.fullmatch(r"/tasks/([A-Za-z0-9._:-]+)", path)
    if task_match and path not in ("/tasks/goto",):
        return cmd_cat_task(task_match.group(1))
    block_match = re.fullmatch(r"/blocks/(-?\d+),(-?\d+),(-?\d+)", path)
    if block_match:
        x, y, z = block_match.groups()
        resp = wire(f"block {x} {y} {z}")
        emit_json(resp)
        return 0 if resp.get("ok") else 1
    if path == "/nearby":
        # Aggregated situational read, client-side over the entities
        # wire read (the proposal's `view` verb collapsed into cat:
        # new capabilities are new paths, never new verbs).
        pos_resp = wire("/bot status")
        state = pos_resp.get("state", pos_resp)
        resp = wire("entities 8 32")
        if not resp.get("ok"):
            print(f"cat /nearby: entities read failed: "
                  f"{resp.get('reason', 'unknown')}", file=sys.stderr)
            return 1
        others = [e for e in resp.get("entities", [])
                  if not e.get("self")]
        nearby = {"pos": state.get("pos"),
                  "task": state.get("task"),
                  "nearby": others,
                  "truncated": resp.get("truncated", False)}
        emit_json(nearby)
        return 0
    if path == "/recipes" or path.startswith("/recipes/"):
        item = path[len("/recipes/"):] if path.startswith("/recipes/") else ""
        if not item:
            print("cat: /recipes/<item> needs an item id", file=sys.stderr)
            return 1
        # Local materialized file first (zero wire). After `mc admin
        # dump-recipes`, every shaped recipe lives at ~/.mc/recipes/<slug>
        # and cat reads it directly — the wire byResult query is only the
        # fallback for items not yet dumped.
        local = RECIPES_DIR / item
        if local.exists():
            sys.stdout.write(local.read_text(encoding="utf-8"))
            return 0
        resp = wire(f'recipes "{item}"')
        emit_json(resp)
        return 0 if resp.get("ok") else 1
    if path == "/stations" or path.startswith("/stations/"):
        # Typed verb error, never silent substitution: cat is the
        # stateless verb; executing read here would lazily bind a
        # bot-side menu session as a side effect of a read (0012 D4).
        print(f"cat: station paths use the session verb - `mc read {path}` "
              "(menu wire pending)", file=sys.stderr)
        return 1
    print(f"cat: unsupported path (v1 supports /player/*, /tasks/current, "
          f"/tasks/<id>): {path}", file=sys.stderr)
    return 1


def cmd_cat_task(task_id: str) -> int:
    """Post-hoc task state by id, derived client-side from the full
    event buffer (zero wire change).

    Strict attrs.taskId match only: the wire has carried the real id
    on every goto lifecycle kind since the 2026-08-26 unification, so
    fuzzy matching against the display-name "task" key would add
    translation-layer magic, not coverage. Status is the LAST matching
    event's kind (completed/failed/cancelled/dropped/...); no events
    means unknown - either the id never existed or the terminal event
    fell out of the 200-deep ring, which "gap" reports honestly.
    """
    resp = wire("/bot events 0")
    batch = resp.get("batch", {})
    events = batch.get("events", [])
    matching = [e for e in events
                if e.get("attrs", {}).get("taskId") == task_id]
    status = (matching[-1]["kind"].removeprefix("TASK_").lower()
              if matching else "unknown")
    result = {"taskId": task_id, "status": status}
    if any(e.get("kind") == "EVENT_GAP" for e in events):
        result["gap"] = True
    result["events"] = matching
    emit_json(result)
    return 0


def cmd_write(path: str, value: str, tol: int | None = None,
               timeout: int | None = None, key: str | None = None) -> int:
    """Write operation. v1: task submit (goto) and task cancel."""
    if path == "/tasks/goto":
        # value is "x,y,z"
        parts = value.split(",")
        if len(parts) != 3:
            print("write /tasks/goto: value must be 'x,y,z'", file=sys.stderr)
            return 1
        x, y, z = parts[0].strip(), parts[1].strip(), parts[2].strip()
        # CLI mirrors the server default: GotoCommandHandler.DEFAULT_TIMEOUT_TICKS
        # = 1200 (one minute at 20 tps). tolerance defaults to 0 (exact cell,
        # GoalBlock). These are wire-required args, so the CLI supplies the
        # server's own defaults when the caller omits them.
        tol_val = tol if tol is not None else 0
        timeout_val = timeout if timeout is not None else 1200
        cmd = f"/bot goto {x} {y} {z} {tol_val} {timeout_val}"
        if key:
            cmd += f" {key}"
        resp = wire(cmd)
        emit_json(resp)
        return 0 if resp.get("ok") else 1
    if path == "/actions/place":
        # value "x,y,z,face" - synchronous one-shot, post-state
        # verified on the wire (0013 R2).
        parts = value.split(",")
        if len(parts) != 4:
            print("write /actions/place: value must be 'x,y,z,face'",
                  file=sys.stderr)
            return 1
        x, y, z, face = (pp.strip() for pp in parts)
        resp = wire(f"place {x} {y} {z} {face.lower()}")
        emit_json(resp)
        return 0 if resp.get("ok") and resp.get("placed") else 1
    if path == "/actions/equip":
        # value is a hotbar slot index 0..8. Synchronous selection;
        # the wire updates both body.selectedSlot and the inventory
        # mirror so place/drop and state disclosure agree (0013).
        try:
            slot = int(value.strip())
        except ValueError:
            print("write /actions/equip: value must be an integer 0..8",
                  file=sys.stderr)
            return 1
        if slot < 0 or slot > 8:
            print("write /actions/equip: slot must be 0..8",
                  file=sys.stderr)
            return 1
        resp = wire(f"equip {slot}")
        emit_json(resp)
        return 0 if resp.get("ok") else 1
    if path == "/tasks/dig":
        # Same shape as goto: value "x,y,z", wire-required timeout
        # mirrored from DigCommandHandler.DEFAULT_TIMEOUT_TICKS.
        parts = value.split(",")
        if len(parts) != 3:
            print("write /tasks/dig: value must be 'x,y,z'",
                  file=sys.stderr)
            return 1
        x, y, z = (pp.strip() for pp in parts)
        timeout_val = timeout if timeout is not None else 1200
        resp = wire(f"/bot dig {x} {y} {z} {timeout_val}")
        emit_json(resp)
        if resp.get("ok") and "task" in resp:
            print(f"taskId: {resp['task']}", file=sys.stderr)
        return 0 if resp.get("ok") else 1
    if path == "/tasks/mine":
        # Composite mining task (issue 0014): value "blockType:count",
        # e.g. "minecraft:stone:10". The blockType is a registry id which
        # may contain a colon, so we split on the LAST colon. timeout
        # mirrors MineCommandHandler.DEFAULT_TIMEOUT_TICKS = 2400.
        if ":" not in value:
            print("write /tasks/mine: value must be 'blockType:count'",
                  file=sys.stderr)
            return 1
        block_type, count_str = value.rsplit(":", 1)
        block_type = block_type.strip()
        try:
            count = int(count_str.strip())
        except ValueError:
            print("write /tasks/mine: count must be an integer",
                  file=sys.stderr)
            return 1
        if count <= 0:
            print("write /tasks/mine: count must be positive",
                  file=sys.stderr)
            return 1
        timeout_val = timeout if timeout is not None else 2400
        # Quote the registry id: Brigadier's string() type rejects a
        # bare colon, so "minecraft:stone" must arrive quoted (same
        # rule as the menu verbs' item ids).
        resp = wire(f'/bot mine "{block_type}" {count} {timeout_val}')
        emit_json(resp)
        if resp.get("ok") and "task" in resp:
            print(f"taskId: {resp['task']}", file=sys.stderr)
        return 0 if resp.get("ok") else 1
    if path.startswith("/tasks/") and path.endswith("/cancel"):
        task_id = path[len("/tasks/"):-len("/cancel")]
        reason = value  # audit payload, passed through (wire ignores extra)
        resp = wire(f"/bot cancel {task_id}")
        emit_json(resp)
        return 0 if resp.get("ok") else 1
    if path.startswith("/stations/"):
        return cmd_write_station(path, value)
    print(f"write: unsupported path (v1 supports /tasks/goto, /tasks/<id>/cancel, "
          f"/stations/<type>@<pos>/<role>): {path}",
          file=sys.stderr)
    return 1


def cmd_write_station(path: str, value: str) -> int:
    """Write to a station role. Opens, deposits/takes/crafts, closes.

    Path is role-segmented: /input and /fuel map to deposit (value is
    "item:count"), /output maps to take (value is "all" or "N"),
    /recipe maps to craft (value is recipeId). The role is the last
    path segment, never a verb — this is the Unix-style design from
    0012 D4: paths carry roles, values carry data.
    """
    station = parse_station_path(path)
    if station is None:
        print(f"write: invalid station path "
              f"(expected /stations/<type>@<x,y,z>/<role>): {path}",
              file=sys.stderr)
        return 1
    role = station["role"]
    if not role:
        print(f"write: station path needs a role segment "
              f"(input/fuel/output/recipe): "
              f"/stations/{station['type']}@.../input",
              file=sys.stderr)
        return 1
    if role not in ALL_ROLES:
        print(f"write: unknown role '{role.lower()}' "
              f"(input/fuel/output/recipe)", file=sys.stderr)
        return 1

    # Pre-validate value format before opening the menu: an invalid
    # value must not consume a session (the bot-side lease is the
    # backstop, but failing fast keeps the wire call count honest).
    if role in DEPOSIT_ROLES and ":" not in value:
        print(f"write: deposit value must be 'item:count': {value}",
              file=sys.stderr)
        return 1
    if role in DEPOSIT_ROLES:
        _, count_str = value.rsplit(":", 1)
        try:
            int(count_str.strip())
        except ValueError:
            print(f"write: count must be integer: {count_str}",
                  file=sys.stderr)
            return 1
    if role in TAKE_ROLES and value.lower() not in ("all", ""):
        try:
            int(value.strip())
        except ValueError:
            print(f"write: take value must be 'all' or integer: {value}",
                  file=sys.stderr)
            return 1

    open_reply = station_open(station)
    if not open_reply.get("ok"):
        print(f"write: cannot open {station['type']}@"
              f"{station['x']},{station['y']},{station['z']}: "
              f"{open_reply.get('reason', 'unknown')}", file=sys.stderr)
        return 1
    try:
        if role == CRAFT_ROLE:
            # craft: value is recipeId
            reply = wire(f'menu craft "{value}"')
        elif role in DEPOSIT_ROLES:
            # deposit: value is "item:count" (validated above)
            item, count_str = value.rsplit(":", 1)
            count = int(count_str.strip())
            reply = wire(f'menu deposit {role} "{item.strip()}" {count}')
        elif role in TAKE_ROLES:
            # take: value is "all" or "N" (validated above)
            if value.lower() == "all" or value == "":
                reply = wire(f"menu take {role}")
            else:
                count = int(value.strip())
                reply = wire(f"menu take {role} {count}")
        else:
            # Unreachable: ALL_ROLES check above covers this.
            print(f"write: unknown role '{role.lower()}'", file=sys.stderr)
            return 1

        emit_json(reply)
        return 0 if reply.get("ok") else 1
    finally:
        station_close()


def cmd_wait(task_id: str, timeout_sec: int = 120, poll_interval: float = 1.0) -> int:
    """Block until task reaches terminal state. Polls /bot events.

    Uses the disk cursor as starting point but does NOT advance it -
    the audit stream must stay complete for a later `mc events`.
    Self-heals across bot restarts: a changed resetAt epoch means the
    stream restarted and every stored cursor is void (boundary D
    no-survive-restart); the scan re-anchors to 0 instead of staring
    at a stale-high cursor forever.
    Exit codes carry the verdict (harness-interaction.md 3.4):
    0 only on TASK_COMPLETED, 1 on every other terminal kind,
    124 on timeout. Success is the only zero - a chaining shell
    must never step past a failure.
    """
    since, epoch = read_cursor_state()
    deadline = time.monotonic() + timeout_sec
    while time.monotonic() < deadline:
        resp = wire(f"/bot events {since}")
        batch = resp.get("batch", {})
        events = batch.get("events", [])
        latest = batch.get("latest", since)
        stream_epoch = batch.get("resetAt", epoch)
        # Two restart signals: resetAt only bumps on an explicit
        # reset() - a JVM restart spins up a fresh queue with resetAt
        # back at 1 (found live: epochs collide across boots). The
        # reliable cross-restart signal is the id space restarting:
        # since > latest is impossible in a monotonic stream.
        if epoch != stream_epoch or since > latest:
            why = (f"resetAt {epoch}->{stream_epoch}" if epoch != stream_epoch
                   else f"cursor {since} beyond stream head {latest}")
            epoch = stream_epoch
            since = 0
            print(f"wait: stream restarted ({why}); re-anchored to 0",
                  file=sys.stderr)
            continue
        for evt in events:
            # Strict taskId match only: an event without the key
            # carries displayName under "task" ("goto:t14") and must
            # never correlate - same rule as cmd_cat_task.
            if (evt.get("attrs", {}).get("taskId") == task_id
                    and evt.get("kind") in TERMINAL_KINDS):
                emit_json(evt)
                # Success is the only zero (harness-interaction.md
                # 3.4): every non-COMPLETED terminal - FAILED,
                # REJECTED, CANCELLED, DROPPED - is a failure for
                # the chaining shell.
                return 0 if evt.get("kind") == "TASK_COMPLETED" else 1
            # EVENT_GAP means we lost history - reconcile and restart
            if evt.get("kind") == "EVENT_GAP":
                since = int(evt.get("attrs", {}).get("oldest", since))
        if latest > since:
            since = latest
        time.sleep(poll_interval)
    print(f"wait: timeout after {timeout_sec}s for task {task_id} "
          f"(cursor={since}, disk_cursor={read_cursor()})", file=sys.stderr)
    # 124 follows GNU timeout's convention; argparse already owns 2.
    return 124


def cmd_events(since: int | None = None, follow: bool = False,
               idle: int = 30, only: str | None = None) -> int:
    """Incremental event drain.

    Only a cursorless invocation advances the disk cursor: it is the
    operator's bookmark, not a per-reader offset. An explicit --since
    is a peek and never advances it (0012 D5 cursor invariant).
    Self-heals across bot restarts: a changed resetAt epoch voids the
    stored bookmark, so the drain restarts from 0 of the new stream.
    """
    cursor, epoch = read_cursor_state()
    if since is not None:
        cursor = since
    resp = wire(f"/bot events {cursor}{only_suffix(only)}")
    batch = resp.get("batch", {})
    latest = batch.get("latest", cursor)
    stream_epoch = batch.get("resetAt", epoch)
    # Same two restart signals as wait: resetAt catches an explicit
    # reset() mid-boot; cursor > latest catches a JVM restart (fresh
    # queue, ids from 1, resetAt back at 1 - epochs collide).
    if since is None and (epoch != stream_epoch or cursor > latest):
        why = (f"resetAt {epoch}->{stream_epoch}" if epoch != stream_epoch
               else f"cursor {cursor} beyond stream head {latest}")
        print(f"events: stream restarted ({why}); "
              "draining the new stream from 0", file=sys.stderr)
        cursor = 0
        resp = wire(f"/bot events 0{only_suffix(only)}")
        batch = resp.get("batch", {})
        latest = batch.get("latest", 0)
    emit_json(resp)
    if since is None and latest > cursor:
        write_cursor_state(latest, stream_epoch)
    if not follow:
        return 0
    # tail -f semantics: peek-only polling (never advances the
    # operator bookmark), one JSON line per new event, terminates
    # after --idle seconds of silence. GAP/DROPPED reconcile the
    # same way as wait.
    last_seen = latest
    epoch = stream_epoch
    idle_deadline = time.monotonic() + idle
    while True:
        time.sleep(1.0)
        resp = wire(f"/bot events {last_seen}{only_suffix(only)}")
        batch = resp.get("batch", {})
        stream_epoch = batch.get("resetAt", epoch)
        head = batch.get("latest", last_seen)
        if epoch != stream_epoch or last_seen > head:
            epoch = stream_epoch
            last_seen = 0
            print("events: stream restarted; re-anchored to 0",
                  file=sys.stderr)
            idle_deadline = time.monotonic() + idle
            continue
        for evt in batch.get("events", []):
            print(json.dumps(evt))
        if head > last_seen:
            last_seen = head
            idle_deadline = time.monotonic() + idle
        elif time.monotonic() >= idle_deadline:
            print(f"events: follow idle {idle}s, stopping",
                  file=sys.stderr)
            return 0


HELP = {
    "ls": ("ls <path>", "ls /  # list the mounted roots"),
    "cat": ("cat <path>", "cat /tasks/t3  # one task's verdict attrs"),
    "read": ("read /stations/<type>@<x,y,z>[/<role>]", "read /stations/chest@10,64,20"),
    "write": ("write <path> <value> [--tol --timeout --key]",
              "write /tasks/goto 100,64,-200 && mc wait $TASK"),
    "wait": ("wait <taskId> [--timeout S]", "mc wait task-4; echo $?  # 0 done, 1 failed, 124 timeout"),
    "events": ("events [--since N] [--only PREFIX] [--follow] [--idle S]",
               "events --only TASK --follow"),
    "admin": ("mc admin stop|reset|dump-recipes", "mc admin dump-recipes"),
}


def cmd_help(verb: str | None = None) -> int:
    """Per-verb help with examples; operator meta, plain text."""
    if verb is None:
        print("six verbs over the bot namespace; `mc help <verb>` for detail:")
        for name, (usage, _) in HELP.items():
            print(f"  {usage}")
        return 0
    entry = HELP.get(verb)
    if entry is None:
        print(f"help: unknown verb {verb}; try one of: "
              f"{', '.join(HELP)}", file=sys.stderr)
        return 1
    print(f"usage: mc {entry[0]}")
    print(f"  example: {entry[1]}")
    return 0


def cmd_ls(path: str) -> int:
    """Discovery. /tasks/ -> current task; /stations/ -> scan nearby."""
    if path == "/tasks/" or path == "/tasks":
        resp = wire("/bot status")
        state = resp.get("state", resp)
        current = state.get("task", "idle")
        print(f"current: {current}")
        return 0
    if path == "/entities/" or path == "/entities":
        resp = wire("entities")
        if not resp.get("ok"):
            print(f"ls: entities read failed: "
                  f"{resp.get('reason', 'unknown')}", file=sys.stderr)
            return 1
        for e in resp.get("entities", []):
            p_ = e.get("pos", [0, 0, 0])
            mark = " [self]" if e.get("self") else ""
            print(f"{e.get('type')}@{p_[0]},{p_[1]},{p_[2]} "
                  f"hp={e.get('health')}/{e.get('maxHealth')} "
                  f"dist={e.get('dist')}{mark}")
        if resp.get("truncated"):
            print("... truncated (limit=32)", file=sys.stderr)
        return 0
    if path == "/stations/" or path == "/stations":
        # scan with default radius=16, limit=50 (0012 D1 defaults).
        resp = wire("scan 16 50")
        if not resp.get("ok"):
            print(f"ls: scan failed: {resp.get('reason', 'unknown')}",
                  file=sys.stderr)
            return 1
        containers = resp.get("containers", [])
        for c in containers:
            print(f"{c['type']}@{c['x']},{c['y']},{c['z']}")
        if resp.get("truncated"):
            print("... truncated (limit=50)", file=sys.stderr)
        return 0
    if path == "/":
        for root in ("/tasks/", "/player/", "/blocks/", "/entities/",
                     "/nearby/", "/actions/", "/recipes/", "/stations/",
                     "/events"):
            print(root)
        return 0
    print(f"ls: unsupported path (supports /, /tasks/, /entities/, "
          f"/stations/): {path}", file=sys.stderr)
    return 1


def cmd_read(path: str) -> int:
    """Menu snapshot read. Station paths: open, snapshot, close.

    Non-station paths get a typed cat suggestion, never silent
    substitution: read is the session verb, flat state is not.
    A role segment (/input, /fuel, /output) filters the snapshot
    client-side (zero wire change).
    """
    if not (path == "/stations" or path.startswith("/stations/")):
        print(f"read: station paths only - {path} is flat state, "
              f"use `mc cat {path}`", file=sys.stderr)
        return 1
    station = parse_station_path(path)
    if station is None:
        print(f"read: invalid station path "
              f"(expected /stations/<type>@<x,y,z>/[<role>]): {path}",
              file=sys.stderr)
        return 1
    open_reply = station_open(station)
    if not open_reply.get("ok"):
        print(f"read: cannot open {station['type']}@"
              f"{station['x']},{station['y']},{station['z']}: "
              f"{open_reply.get('reason', 'unknown')}", file=sys.stderr)
        return 1
    try:
        # The open reply already carries the first snapshot - a
        # separate menu snapshot call would be a redundant round trip
        # (nothing can change between open and read on one wire).
        menu = open_reply.get("menu", open_reply)
        if station["role"]:
            # Client-side filter by role (zero wire change).
            filtered = [s for s in menu.get("slots", [])
                        if s.get("role", "").upper() == station["role"]]
            print(json.dumps({"type": menu.get("type"),
                               "sourcePos": menu.get("sourcePos"),
                               "role": station["role"].lower(),
                               "slots": filtered}, indent=2))
        else:
            emit_json(menu)
        return 0
    finally:
        station_close()


def cmd_admin(action: str) -> int:
    """Operator escape hatch, deliberately outside the namespace.

    stop maps to /bot stop because it sweeps ALL live missions -
    CommandBus.submit has no single-task gate, so multi-live is
    reachable - while write /tasks/<id>/cancel covers exactly one
    (0012 ruling 17). reset is the ADR-0005 crash-latch escape.
    dump-recipes is a CLI-internal composite: pages through `/bot
    recipes list` and writes one file per recipe to ~/.mc/recipes/.
    Friction is a feature: destructive ops and maintenance verbs do
    not get path syntax.
    """
    if action == "dump-recipes":
        return cmd_admin_dump_recipes()
    if action not in ("stop", "reset"):
        print(f"admin: unknown action (stop, reset, dump-recipes): {action}",
              file=sys.stderr)
        return 1
    resp = wire(f"/bot {action}")
    emit_json(resp)
    return 0 if resp.get("ok") else 1


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    # Normalize early: the CLI entry calls main() with argv=None and
    # argparse would then read sys.argv itself, bypassing the
    # negative-value shim below (found live by shadow_compare).
    argv = sys.argv[1:] if argv is None else argv

    parser = argparse.ArgumentParser(
        prog="mc",
        description="Unix-style CLI for MC Bot Server boundary-D surface")
    sub = parser.add_subparsers(dest="verb", required=True)

    p_help = sub.add_parser("help", help="per-verb help with examples")
    p_help.add_argument("topic", nargs="?", default=None)

    p_ls = sub.add_parser("ls", help="discovery: directory listing")
    p_ls.add_argument("path", nargs="?", default="/")

    p_cat = sub.add_parser("cat", help="flat state read")
    p_cat.add_argument("path")

    p_read = sub.add_parser("read", help="menu snapshot read")
    p_read.add_argument("path")

    p_write = sub.add_parser("write", help="write operation")
    p_write.add_argument("path")
    p_write.add_argument("value")
    p_write.add_argument("--tol", type=int, default=None, help="goto tolerance")
    p_write.add_argument("--timeout", type=int, default=None, help="goto timeout ticks")
    p_write.add_argument("--key", type=str, default=None, help="idempotency key")

    p_wait = sub.add_parser("wait", help="block until task terminal")
    p_wait.add_argument("task_id")
    p_wait.add_argument("--timeout", type=int, default=120, help="seconds")

    p_events = sub.add_parser("events", help="incremental event drain")
    p_events.add_argument("--since", type=int, default=None,
                          help="peek from N; does not advance the cursor")
    p_events.add_argument("--follow", action="store_true",
                          help="tail -f: keep polling, print new events")
    p_events.add_argument("--idle", type=int, default=30,
                          help="follow terminates after N idle seconds")
    p_events.add_argument("--only", type=str, default=None,
                          help="server-side kind-prefix narrowing (0011 D3)")

    p_admin = sub.add_parser("admin",
                             help="operator verbs (outside the namespace)")
    p_admin.add_argument("action", choices=["stop", "reset", "dump-recipes"])

    # Negative coordinates ("-60,64,200") look like option tokens to
    # argparse (its negative-number exemption only covers pure digits).
    # Reorder write's value behind "--" so the dash survives.
    if argv and argv[0] == "write" and len(argv) >= 3:
        path, value, rest = argv[1], argv[2], argv[3:]
        if value.startswith("-"):
            argv = ["write", path, *rest, "--", value]

    args = parser.parse_args(argv)
    try:
        if args.verb == "ls":
            return cmd_ls(args.path)
        if args.verb == "cat":
            return cmd_cat(args.path)
        if args.verb == "read":
            return cmd_read(args.path)
        if args.verb == "write":
            return cmd_write(args.path, args.value, args.tol, args.timeout, args.key)
        if args.verb == "wait":
            return cmd_wait(args.task_id, args.timeout)
        if args.verb == "events":
            return cmd_events(args.since, follow=getattr(args, "follow", False),
                              idle=getattr(args, "idle", 30),
                              only=getattr(args, "only", None))
        if args.verb == "help":
            return cmd_help(getattr(args, "topic", None))
        if args.verb == "admin":
            return cmd_admin(args.action)
    except RconError as exc:
        print(f"rcon error: {exc}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
