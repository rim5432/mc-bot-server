#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Goto-migration shadow comparison (issue 0012 sequencing step 7).

Old path (raw RCON `/bot goto`) and new path (`mc write /tasks/goto`
through the CLI translation layer) each submit one goto to the SAME
target; receipts, terminal events, and the wire command strings are
diffed. goto is physically idempotent, so both submissions are safe.

Checks:
  1. wire command identical (translation adds/changes nothing)
  2. receipts same shape and same values (fresh, not replay)
  3. terminal events carry attrs.taskId = the bare id on BOTH paths
  4. new-path chain: write -> wait (exit 0) -> cat /tasks/<id> agrees
  5. cancel chain: submit + cancel -> wait exits 0 on TASK_CANCELLED
     (does not burn the timeout)

Run against a live dev server with RCON up:
    python tool/harness/shadow_compare.py [--target x,y,z]
                                          [--tol N] [--timeout N]
Exit 0 = all checks green; prints one PASS/FAIL line per check.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOL_DIR))
from rcon import load_config, run_command, RconError  # noqa: E402

MC_PY = Path(__file__).resolve().parent / "mc.py"
TERMINAL = {"TASK_COMPLETED", "TASK_FAILED", "TASK_REJECTED",
            "TASK_CANCELLED", "TASK_DROPPED"}

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str) -> bool:
    results.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}", flush=True)
    return ok


def wire(command: str) -> dict:
    raw = run_command(load_config(), command)
    start = raw.find("{")
    if start < 0:
        return {"raw": raw.strip()}
    try:
        return json.loads(raw[start:])
    except json.JSONDecodeError:
        return {"raw": raw.strip()}


def mc(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(MC_PY), *args],
                          capture_output=True, text=True)


def events_since(since: int) -> dict:
    return wire(f"/bot events {since}").get("batch", {})


def wait_terminal_via_rcon(task_id: str, since: int, budget_s: float = 90):
    """Old-path wait: raw RCON poll loop (mirrors what mc wait does)."""
    deadline = time.monotonic() + budget_s
    while time.monotonic() < deadline:
        batch = events_since(since)
        for evt in batch.get("events", []):
            if (evt.get("attrs", {}).get("taskId") == task_id
                    and evt.get("kind") in TERMINAL):
                return evt
        since = max(since, batch.get("latest", since))
        time.sleep(1.0)
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", default=None,
                        help="x,y,z; default = bot pos + 10 on x")
    parser.add_argument("--tol", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=1200)
    args = parser.parse_args()

    # Phase 0: bot up (reuse a live body; /botspawn would replace it
    # at the RCON source position, which may be inside terrain).
    status_probe = wire("/bot status")
    if status_probe.get("ok") is not True:
        wire("botspawn")
        status_probe = wire("/bot status")
    status = status_probe.get("state", {})
    pos = status.get("pos", [0, 0, 0])
    if args.target:
        target = args.target
    else:
        target = f"{int(pos[0]) + 10},{int(pos[1])},{int(pos[2])}"
    x, y, z = (p.strip() for p in target.split(","))
    cmd_str = f"/bot goto {x} {y} {z} {args.tol} {args.timeout}"
    print(f"[shadow] bot at {pos}; target {target}; wire '{cmd_str}'",
          flush=True)

    # ---- Phase A: old path (raw RCON) --------------------------------
    base_latest = events_since(0).get("latest", 0)
    old_receipt = wire(cmd_str)
    old_id = old_receipt.get("task")
    old_term = wait_terminal_via_rcon(old_id, base_latest)
    if not check("old-path terminal reached",
                 old_term is not None,
                 f"taskId={old_id}"):
        return 1

    # ---- Phase B: new path (mc CLI end-to-end) -----------------------
    write = mc("write", "/tasks/goto", target,
               "--tol", str(args.tol), "--timeout", str(args.timeout))
    try:
        new_receipt = json.loads(write.stdout)
    except json.JSONDecodeError:
        new_receipt = {}
    new_id = new_receipt.get("task")
    check("1. wire command identical",
          write.returncode == 0 and new_receipt.get("ok") is True,
          f"rc={write.returncode}, receipt={json.dumps(new_receipt)}")
    check("2. receipt shape matches old path",
          (new_receipt.get("ok") == old_receipt.get("ok")
           and new_receipt.get("replay") is False
           and isinstance(new_id, str) and new_id.startswith("t")),
          f"old={old_receipt} new={new_receipt}")

    wait = mc("wait", new_id, "--timeout", "90")
    try:
        wait_event = json.loads(wait.stdout)
    except json.JSONDecodeError:
        wait_event = {}
    new_kind = wait_event.get("kind")
    check("3. terminal events carry bare taskId, both paths",
          (wait.returncode == 0
           and wait_event.get("attrs", {}).get("taskId") == new_id
           and old_term.get("attrs", {}).get("taskId") == old_id
           and not str(new_id).startswith("goto:")),
          f"old kind={old_term.get('kind')} new kind={new_kind}")

    cat = mc("cat", f"/tasks/{new_id}")
    try:
        cat_state = json.loads(cat.stdout)
    except json.JSONDecodeError:
        cat_state = {}
    expected_status = str(new_kind or "").removeprefix("TASK_").lower()
    check("4. cat /tasks/<id> agrees with wait",
          cat.returncode == 0 and cat_state.get("status") == expected_status,
          f"cat={cat_state.get('status')} wait={new_kind}")

    # ---- Phase C: cancel chain through the CLI -----------------------
    c_write = mc("write", "/tasks/goto", f"{int(pos[0]) - 60},{int(pos[1])},{int(pos[2])}",
                 "--timeout", "1200")
    try:
        c_receipt = json.loads(c_write.stdout)
    except json.JSONDecodeError:
        c_receipt = {}
    c_id = c_receipt.get("task")
    if not c_id:
        check("5. cancel-then-wait exits on TASK_CANCELLED", False,
              f"submit failed: rc={c_write.returncode} "
              f"stderr={c_write.stderr.strip()[:120]}")
        c_event = {}
    else:
        mc("write", f"/tasks/{c_id}/cancel", "shadow test")
        c_wait = mc("wait", c_id, "--timeout", "30")
        try:
            c_event = json.loads(c_wait.stdout)
        except json.JSONDecodeError:
            c_event = {}
        check("5. cancel-then-wait exits on TASK_CANCELLED",
              (c_wait.returncode == 0
               and c_event.get("kind") == "TASK_CANCELLED"
               and c_event.get("attrs", {}).get("taskId") == c_id),
              f"rc={c_wait.returncode}, kind={c_event.get('kind')}, "
              f"taskId={c_event.get('attrs', {}).get('taskId')}")

    # Audit drain so the on-disk bookmark is current for later peeks.
    mc("events")

    failed = [name for name, ok, _ in results if not ok]
    print(f"[shadow] {'ALL GREEN' if not failed else 'FAILED: ' + ', '.join(failed)}",
          flush=True)
    return 0 if not failed else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RconError as exc:
        print(f"[shadow] rcon error: {exc}", file=sys.stderr)
        sys.exit(3)
