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
  cat /tasks/current       -> /bot status  (task field)
  write /tasks/goto "x,y,z" [--tol N] [--timeout N] [--key K] -> /bot goto
  write /tasks/<id>/cancel "reason" -> /bot cancel <id>
  wait <taskId> [--timeout N] -> poll /bot events until TASK_COMPLETED/TASK_FAILED
  events [--since N] -> /bot events <cursor>

Translation layer is transparent-first: no default values, no retries,
no corrections. Parameters pass through to wire verbatim. If behavior
diverges from raw RCON, the bug is in this file (grepable) or does not
exist.

Cursor lives at tool/.runtime/mc_cursor.txt (gitignored). The events
verb advances it; wait reads from it but does NOT advance (audit stream
must stay complete - internal consumption must not eat events).
"""

from __future__ import annotations

import argparse
import json
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

def read_cursor() -> int:
    if CURSOR_PATH.exists():
        text = CURSOR_PATH.read_text(encoding="utf-8").strip()
        return int(text) if text else 0
    return 0


def write_cursor(value: int) -> None:
    CURSOR_PATH.parent.mkdir(parents=True, exist_ok=True)
    CURSOR_PATH.write_text(f"{value}\n", encoding="utf-8")


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
    print(f"cat: unsupported path (v1 supports /player/*, /tasks/current): {path}",
          file=sys.stderr)
    return 1


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
    Returns the terminal event's kind as exit-friendly text.
    """
    since = read_cursor()
    deadline = time.monotonic() + timeout_sec
    while time.monotonic() < deadline:
        resp = wire(f"/bot events {since}")
        batch = resp.get("batch", {})
        events = batch.get("events", [])
        latest = batch.get("latest", since)
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
    return 2


def cmd_events(since: int | None = None) -> int:
    """Incremental event drain. Advances the disk cursor."""
    cursor = since if since is not None else read_cursor()
    resp = wire(f"/bot events {cursor}")
    batch = resp.get("batch", {})
    latest = batch.get("latest", cursor)
    print(json.dumps(resp, indent=2))
    if latest > cursor:
        write_cursor(latest)
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
    """Menu snapshot read. v1: not yet available (menu wire pending)."""
    print(f"read: menu domain pending (issue 0012 D1 menu commands): {path}",
          file=sys.stderr)
    return 1


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
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
    p_events.add_argument("--since", type=int, default=None)

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
    except RconError as exc:
        print(f"rcon error: {exc}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
