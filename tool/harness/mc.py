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
  /recipes/<item>          cat only (RecipeManager query - wire pending)
  /stations/<type>@<x,y,z>/<role>  ls/read/write (menu domain - wire pending)
  /tasks/                  write (submit goto), wait, cancel
  /events                  events verb only

Wire mapping (v1 - goto migration only):
  cat /player/inventory    -> /bot status  (items field)
  cat /player/pos          -> /bot status  (pos field)
  cat /player/status       -> /bot status  (full)
  cat /player/menu         -> unsupported (menu field pending, issue 0012 D1)
  cat /tasks/current       -> /bot status  (task field)
  cat /tasks/<id>          -> /bot events 0, filtered by attrs.taskId
                              (client-side derivation, zero wire change)
  write /tasks/goto "x,y,z" [--tol N] [--timeout N] [--key K] -> /bot goto
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

Exit codes: 0 ok; 1 rejected/unsupported; 124 wait timeout; 3 rcon
error. (argparse usage errors exit 2 by argparse's own convention.)

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
# Verb implementations (v1 - goto migration surface)
# ---------------------------------------------------------------------------

def cmd_cat(path: str) -> int:
    """Flat state read. Maps /player/* and /tasks/current to /bot status."""
    if path.startswith("/player/") or path == "/player":
        field = path[len("/player/"):] if path.startswith("/player/") else "status"
        resp = wire("/bot status")
        state = resp.get("state", resp)
        if field in ("", "status"):
            print(json.dumps(state, indent=2))
        elif field == "inventory":
            print(json.dumps(state.get("items", {}), indent=2))
        elif field == "pos":
            print(json.dumps(state.get("pos", [])))
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
    print(json.dumps(result, indent=2))
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
        # /bot goto returns taskId under the "task" key (legacy naming).
        # Echo it as "taskId" so the write->wait correlation chain uses one
        # canonical name. The full raw response is still printed first.
        print(json.dumps(resp, indent=2))
        if resp.get("ok") and "task" in resp:
            print(f"taskId: {resp['task']}", file=sys.stderr)
        return 0 if resp.get("ok") else 1
    if path.startswith("/tasks/") and path.endswith("/cancel"):
        task_id = path[len("/tasks/"):-len("/cancel")]
        reason = value  # audit payload, passed through (wire ignores extra)
        resp = wire(f"/bot cancel {task_id}")
        print(json.dumps(resp, indent=2))
        return 0 if resp.get("ok") else 1
    print(f"write: unsupported path (v1 supports /tasks/goto, /tasks/<id>/cancel): {path}",
          file=sys.stderr)
    return 1


def cmd_wait(task_id: str, timeout_sec: int = 120, poll_interval: float = 1.0) -> int:
    """Block until task reaches terminal state. Polls /bot events.

    Uses the disk cursor as starting point but does NOT advance it -
    the audit stream must stay complete for a later `mc events`.
    Self-heals across bot restarts: a changed resetAt epoch means the
    stream restarted and every stored cursor is void (boundary D
    no-survive-restart); the scan re-anchors to 0 instead of staring
    at a stale-high cursor forever.
    Returns the terminal event's kind as exit-friendly text.
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
            evt_task = evt.get("attrs", {}).get("taskId",
                         evt.get("attrs", {}).get("task", ""))
            if evt_task == task_id and evt.get("kind") in TERMINAL_KINDS:
                print(json.dumps(evt, indent=2))
                return 0
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


def cmd_events(since: int | None = None) -> int:
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
    resp = wire(f"/bot events {cursor}")
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
        resp = wire("/bot events 0")
        batch = resp.get("batch", {})
        latest = batch.get("latest", 0)
    print(json.dumps(resp, indent=2))
    if since is None and latest > cursor:
        write_cursor_state(latest, stream_epoch)
    return 0


def cmd_ls(path: str) -> int:
    """Discovery. v1: /tasks/ lists current task (status-derived)."""
    if path == "/tasks/" or path == "/tasks":
        resp = wire("/bot status")
        state = resp.get("state", resp)
        current = state.get("task", "idle")
        print(f"current: {current}")
        return 0
    print(f"ls: unsupported path (v1 supports /tasks/; /stations/ pending menu wire): {path}",
          file=sys.stderr)
    return 1


def cmd_read(path: str) -> int:
    """Menu snapshot read. v1: station paths only, wire pending.

    Non-station paths get a typed cat suggestion, never silent
    substitution: read is the session verb, flat state is not.
    """
    if not (path == "/stations" or path.startswith("/stations/")):
        print(f"read: station paths only - {path} is flat state, "
              f"use `mc cat {path}`", file=sys.stderr)
        return 1
    print(f"read: menu domain pending (issue 0012 D1 menu commands): {path}",
          file=sys.stderr)
    return 1


def cmd_admin(action: str) -> int:
    """Operator escape hatch, deliberately outside the namespace.

    stop maps to /bot stop because it sweeps ALL live missions -
    CommandBus.submit has no single-task gate, so multi-live is
    reachable - while write /tasks/<id>/cancel covers exactly one
    (0012 ruling 17). reset is the ADR-0005 crash-latch escape.
    Friction is a feature: destructive ops verbs do not get path
    syntax.
    """
    if action not in ("stop", "reset"):
        print(f"admin: unknown action (stop, reset): {action}",
              file=sys.stderr)
        return 1
    resp = wire(f"/bot {action}")
    print(json.dumps(resp, indent=2))
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

    p_admin = sub.add_parser("admin",
                             help="operator verbs (outside the namespace)")
    p_admin.add_argument("action", choices=["stop", "reset"])

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
            return cmd_events(args.since)
        if args.verb == "admin":
            return cmd_admin(args.action)
    except RconError as exc:
        print(f"rcon error: {exc}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
