#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Boundary-D black-box QA runner: mc.py against a live runServer.

The server must already be RUNNING and RCON-reachable when this
script starts (lifecycle is orchestrated by the operator through
mcbot_tool; batch C2's restart is likewise external, coordinated via
the state file). Every case drives the REAL CLI in a subprocess and
asserts on the JSON contract only - no mc.py imports, no wire mocks.

Receipts land beside this script: receipt-<stamp>.json plus a
paste-ready markdown table. Verdicts:
  PASS            contract held
  FAIL            contract broken (bug or bad expectation - reviewed)
  RED-CONFIRMED   expected-failure pin reproduced (infrastructure gap
                  proven; a SUCCESS for batch C - the case runs to
                  completion when the gap is present)
  UNEXPECTED-GREEN expected failure did not reproduce - review needed

Operator lessons baked in (2026-08-31 round):
  - a body parked in an UNLOADED chunk is invisible to @e selectors:
    tp silently misses. Reset through the binding instead
    (botdespawn + botspawn lands a fresh ticking body at spawn).
  - goto targets a few hundred blocks out can fast-fail NO_PATH
    (exhaustedOpenSet) before a cancel lands; "live task" cases use
    +30-block walks, which stay inside the planning horizon.
  - the fall probe (mid-air park, watch y) is the cheapest liveness
    detector for an unticketed chunk.

Run: python qa-results/boundary-d/run_boundary_d_qa.py --only a,b
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MC = REPO / "tool" / "harness" / "mc.py"
RCON = REPO / "tool" / "rcon.py"
CURSOR = REPO / "tool" / ".runtime" / "mc_cursor.txt"
HERE = Path(__file__).resolve().parent
STATE = HERE / "state.json"


class CaseFailed(AssertionError):
    """One broken expectation; carries the evidence line."""


def mc(*args: str, timeout: int = 180) -> tuple[int, object]:
    """Run the real CLI; return (exit code, parsed JSON or raw text)."""
    proc = subprocess.run(
        [sys.executable, str(MC), *args],
        capture_output=True, text=True, timeout=timeout, cwd=str(REPO))
    out = proc.stdout.strip()
    try:
        return proc.returncode, json.loads(out)
    except json.JSONDecodeError:
        return proc.returncode, {"raw": out, "stderr": proc.stderr.strip()}


def rcon(command: str) -> str:
    """One vanilla/bot command through the raw RCON client (text back)."""
    proc = subprocess.run(
        [sys.executable, str(RCON), command],
        capture_output=True, text=True, timeout=60, cwd=str(REPO))
    return (proc.stdout + proc.stderr).strip()


def cursor_snapshot() -> str | None:
    return CURSOR.read_text(encoding="utf-8") if CURSOR.exists() else None


def cursor_restore(value: str | None) -> None:
    if value is None:
        CURSOR.unlink(missing_ok=True)
    else:
        CURSOR.write_text(value, encoding="utf-8")


class Ledger:
    def __init__(self) -> None:
        self.rows: list[dict] = []
        self.evidence: list[str] = []

    def case(self, batch: str, cid: str, desc: str, expect: str):
        def wrap(fn):
            saved = cursor_snapshot()
            ev: list[str] = []
            started = time.time()
            try:
                fn(ev)
                verdict = "RED-CONFIRMED" if expect == "RED" else "PASS"
                detail = "contract held" if verdict == "PASS" else "gap reproduced"
            except CaseFailed as exc:
                verdict = "UNEXPECTED-GREEN" if expect == "RED" else "FAIL"
                detail = str(exc)
            except Exception as exc:  # noqa: BLE001 - the receipt wants the error
                verdict = "ERROR"
                detail = f"{type(exc).__name__}: {exc}"
            finally:
                cursor_restore(saved)
            self.rows.append({
                "batch": batch, "id": cid, "desc": desc,
                "expect": expect, "verdict": verdict, "detail": detail,
                "sec": round(time.time() - started, 1),
            })
            self.evidence.append(f"[{cid}] {verdict}: {detail}")
            self.evidence.extend(f"    {line}" for line in ev)
            print(f"  [{cid}] {verdict}  ({desc}) - {detail}")
        return wrap

    def table(self) -> str:
        head = ("| id | batch | verdict | expect | detail | sec |\n"
                "|---|---|---|---|---|---|\n")
        rows = "\n".join(
            f"| {r['id']} | {r['batch']} | {r['verdict']} | {r['expect']} "
            f"| {r['detail']} | {r['sec']} |" for r in self.rows)
        return head + rows


def submit_goto(value: str, ev: list[str], **flags: str) -> str:
    args = ["write", "/tasks/goto", value]
    for name, val in flags.items():
        args += [f"--{name}", val]
    code, out = mc(*args)
    assert code == 0 and out.get("ok") is True, f"submit exit {code}: {out}"
    task_id = out.get("task")
    assert isinstance(task_id, str) and task_id, f"no task key: {out}"
    ev.append(f"submit {value} -> {task_id}")
    return task_id


def current_pos() -> dict:
    _, out = mc("cat", "/player/pos")
    if isinstance(out, list):
        return {"x": out[0], "y": out[1], "z": out[2]}
    return out.get("pos", out)


# ---------------------------------------------------------------------------
# Batch A - translation layer, real wire (expect green)
# ---------------------------------------------------------------------------

def batch_a(led: Ledger) -> None:
    print("Batch A: translation layer, real wire")

    @led.case("A", "A1", "ls / answers the canonical roots", "GREEN")
    def _(ev):
        code, out = mc("ls", "/")
        assert code == 0, f"exit {code}: {out}"
        roots = out if isinstance(out, list) else out.get("roots", out)
        for root in ("/player", "/blocks", "/recipes", "/stations",
                     "/tasks", "/events"):
            assert root in json.dumps(roots), f"missing root {root}: {roots}"
        ev.append(f"roots={json.dumps(roots)[:200]}")

    @led.case("A", "A2", "help carries usage", "GREEN")
    def _(ev):
        code, out = mc("help")
        assert code == 0 and "write" in json.dumps(out), f"exit {code}: {out}"
        ev.append("help ok")

    @led.case("A", "A3", "cat /player/status -> flat state face", "GREEN")
    def _(ev):
        code, out = mc("cat", "/player/status")
        assert code == 0 and isinstance(out, dict), f"exit {code}: {out}"
        for field in ("pos", "yaw", "pitch", "dim", "items", "slot",
                      "effects", "task", "healthHearts", "freeSlots",
                      "food", "xp"):
            assert field in out, f"missing state field {field}"
        ev.append(f"status face={sorted(out)}")

    @led.case("A", "A4", "cat /player/pos -> [x, y, z]", "GREEN")
    def _(ev):
        code, out = mc("cat", "/player/pos")
        assert code == 0 and isinstance(out, list) and len(out) == 3, \
            f"exit {code}: {out}"
        assert all(isinstance(v, (int, float)) for v in out), f"{out}"
        ev.append(f"pos={out}")

    @led.case("A", "A5", "cat /player/inventory -> items map", "GREEN")
    def _(ev):
        code, out = mc("cat", "/player/inventory")
        assert code == 0 and isinstance(out, dict), f"exit {code}: {out}"
        ev.append(f"inventory={json.dumps(out)[:160]}")

    @led.case("A", "A6", "events -> batch shape with stream-truth keys", "GREEN")
    def _(ev):
        code, out = mc("events")
        assert code == 0 and out.get("ok") is True, f"exit {code}: {out}"
        batch = out.get("batch", {})
        for key in ("latest", "resetAt", "dropped", "events"):
            assert key in batch, f"missing batch key {key}: {batch}"
        ev.append(f"latest={batch.get('latest')} resetAt={batch.get('resetAt')}")

    @led.case("A", "A7", "piped stdout is one JSON line (stream discipline)", "GREEN")
    def _(ev):
        proc = subprocess.run(
            [sys.executable, str(MC), "cat", "/player/pos"],
            capture_output=True, text=True, timeout=60, cwd=str(REPO))
        lines = [ln for ln in proc.stdout.strip().splitlines() if ln]
        assert len(lines) == 1, f"{len(lines)} lines, expected 1"
        json.loads(lines[0])
        ev.append("single-line JSON ok")

    @led.case("A", "A8", "read on any path suggests cat (typed error)", "GREEN")
    def _(ev):
        code, out = mc("read", "/player/pos")
        assert code == 1 and "cat" in json.dumps(out), f"exit {code}: {out}"
        ev.append("typed redirect ok")

    @led.case("A", "A9", "ls /blocks is a typed reject (not enumerable)", "GREEN")
    def _(ev):
        code, out = mc("ls", "/blocks")
        assert code == 1, f"exit {code}: {out}"
        ev.append(f"reject={json.dumps(out)[:160]}")

    @led.case("A", "A10", "ls /stations/ scans nearby containers", "GREEN")
    def _(ev):
        code, out = mc("ls", "/stations/")
        assert code == 0, f"exit {code}: {out}"
        ev.append(f"scan={json.dumps(out)[:160]}")


# ---------------------------------------------------------------------------
# Batch B - task lifecycle over the real queue (expect green)
# ---------------------------------------------------------------------------

def batch_b(led: Ledger) -> None:
    print("Batch B: task lifecycle")

    @led.case("B", "B1", "goto receipt carries the id under 'task'", "GREEN")
    def _(ev):
        pos = current_pos()
        tid = submit_goto(f"{int(pos['x'])},{int(pos['y'])},{int(pos['z'])}",
                          ev, tol="1", timeout="100")
        ev.append(f"receipt task={tid}")

    @led.case("B", "B2", "wait exits 0 on TASK_COMPLETED (goto-to-self)", "GREEN")
    def _(ev):
        pos = current_pos()
        tid = submit_goto(f"{int(pos['x'])},{int(pos['y'])},{int(pos['z'])}",
                          ev, tol="1", timeout="200")
        code, out = mc("wait", tid, "--timeout", "60")
        assert code == 0, f"wait exit {code}: {out}"
        ev.append(f"wait verdict={json.dumps(out)[:200]}")

    @led.case("B", "B3", "wait exits 1 on TASK_FAILED (deep unreachable)", "GREEN")
    def _(ev):
        pos = current_pos()
        tid = submit_goto(f"{int(pos['x'])},{int(pos['y']) - 30},{int(pos['z'])}",
                          ev, tol="0", timeout="200")
        code, out = mc("wait", tid, "--timeout", "90")
        assert code == 1, f"wait exit {code}, expected 1: {out}"
        ev.append(f"failed verdict={json.dumps(out)[:200]}")

    @led.case("B", "B4", "cancel is terminal and wait does not burn timeout", "GREEN")
    def _(ev):
        pos = current_pos()
        # Engine quirk (open observation, 2026-08-31): the mission
        # directly after a NO_PATH failure can itself NO_PATH
        # instantly - a cross-mission poisoning window this case is
        # not about. Retry the liveness submit within a small bound.
        # Healing move: a goto-to-self COMPLETES and breaks the
        # chain (retrying the far goto just re-arms the window - each
        # NO_PATH re-poisons the next submit, pinned live 2026-08-31).
        spot = f"{int(pos['x'])},{int(pos['y'])},{int(pos['z'])}"
        healer = submit_goto(spot, ev, tol="1", timeout="200")
        mc("wait", healer, "--timeout", "30")
        tid = submit_goto(f"{int(pos['x']) + 30},{int(pos['y'])},{int(pos['z'])}",
                          ev, tol="1", timeout="4000")
        time.sleep(0.8)
        _, check_task = mc("cat", f"/tasks/{tid}")
        assert check_task.get("status") != "failed",             f"mission still poisoned after a completed healer: {check_task}"
        code, out = mc("write", f"/tasks/{tid}/cancel", "qa-cancel")
        assert code == 0 and out.get("ok") is True, f"cancel exit {code}: {out}"
        started = time.time()
        code, out = mc("wait", tid, "--timeout", "60")
        waited = time.time() - started
        assert code == 1, f"wait exit {code}, expected 1: {out}"
        assert waited < 30, f"cancel-then-wait burned {waited:.0f}s"
        assert "TASK_CANCELLED" in json.dumps(out), f"not cancelled: {out}"
        ev.append(f"cancelled in {waited:.1f}s")

    @led.case("B", "B5", "admin stop sweeps every live mission", "GREEN")
    def _(ev):
        pos = current_pos()
        first = submit_goto(f"{int(pos['x']) + 30},{int(pos['y'])},{int(pos['z'])}",
                            ev, tol="1", timeout="4000")
        second = submit_goto(f"{int(pos['x']) - 30},{int(pos['y'])},{int(pos['z'])}",
                             ev, tol="1", timeout="4000")
        code, out = mc("admin", "stop")
        assert code == 0, f"stop exit {code}: {out}"
        ev.append(f"stop={json.dumps(out)[:160]}")
        for tid in (first, second):
            code, out = mc("wait", tid, "--timeout", "60")
            assert code == 1, f"{tid} not terminal: {out}"
            ev.append(f"{tid} terminal={json.dumps(out)[:120]}")

    @led.case("B", "B6", "events cursor advances; --since 0 re-anchors", "GREEN")
    def _(ev):
        _, first = mc("events")
        latest = first["batch"]["latest"]
        pos = current_pos()
        tid = submit_goto(f"{int(pos['x'])},{int(pos['y'])},{int(pos['z'])}",
                          ev, tol="1", timeout="200")
        mc("wait", tid, "--timeout", "60")
        code, second = mc("events")
        batch = second["batch"]
        assert batch["latest"] > latest, f"latest did not advance: {latest}"
        ids = [e.get("attrs", {}).get("taskId") for e in batch["events"]]
        assert tid in ids or batch["events"] == [], "new events missing the goto"
        code, full = mc("events", "--since", "0")
        assert code == 0 and full["batch"]["latest"] == batch["latest"], \
            "re-anchor diverged"
        ev.append(f"latest {latest} -> {batch['latest']}, drain ok")

    @led.case("B", "B7", "idempotency key dedupes while the task is live", "GREEN")
    def _(ev):
        pos = current_pos()
        far = f"{int(pos['x']) + 30},{int(pos['y'])},{int(pos['z'])}"
        code, first = mc("write", "/tasks/goto", far, "--tol", "1",
                         "--timeout", "4000", "--key", "qa-replay-key")
        assert code == 0 and first.get("replay") is False, f"first: {first}"
        # Live window: same key while the mission is still running must
        # replay the receipt, never mint a second mission. (After a
        # terminal verdict the same key legitimately starts a new task
        # - dedup scope is the live window, not forever.)
        code, again = mc("write", "/tasks/goto", far, "--tol", "1",
                         "--timeout", "4000", "--key", "qa-replay-key")
        assert code == 0, f"replay exit {code}: {again}"
        assert again.get("replay") is True, \
            f"live resubmit minted a new task: {again}"
        assert again.get("task") == first.get("task"), \
            f"replay id diverged: {first} vs {again}"
        ev.append(f"first={first} again={again}")
        mc("write", f"/tasks/{first['task']}/cancel", "qa-cleanup")
        mc("wait", first["task"], "--timeout", "30")

    @led.case("B", "B8", "status pos matches the completed goto target", "GREEN")
    def _(ev):
        pos = current_pos()
        target = (int(pos["x"]) + 1, int(pos["y"]), int(pos["z"]))
        tid = submit_goto(f"{target[0]},{target[1]},{target[2]}",
                          ev, tol="1", timeout="400")
        code, out = mc("wait", tid, "--timeout", "90")
        assert code == 0, f"goto neighbor failed: {out}"
        after = current_pos()
        assert abs(after["x"] - target[0]) <= 1.5, f"pos {after} vs {target}"
        ev.append(f"arrived {after}")


# ---------------------------------------------------------------------------
# Batch C1 - entity-ticking ticket gap (expected RED)
# Mechanism pinned live: the QUEUE is server-tick driven and stays
# alive (tasks fail honestly on timeout); it is the BODY (entity
# physics/AI) that freezes in an unticketed chunk.
# ---------------------------------------------------------------------------

def batch_c1(led: Ledger) -> None:
    print("Batch C1: body keeps ticking away from every framework ticket")

    # Contract after the 0015 fix (forceload-backed BotChunkTicket):
    # the body NEVER freezes on a bare server. The original gap shape
    # (body frozen, queue honest) is preserved as the 2026-08-31 red
    # receipts in git history; this case pins the fixed behavior.
    @led.case("C", "C1", "far-tp body keeps entity-ticking (ticket fix)", "GREEN")
    def _(ev):
        tp = rcon("tp @e[type=mcbotserver:bot_body,limit=1] 350.5 120 350.5")
        ev.append(f"tp: {tp[:120]}")
        assert "Teleported" in tp,             f"tp missed - body not selectable (unloaded chunk?): {tp}"
        time.sleep(30)
        _, settle = mc("cat", "/player/pos")
        ev.append(f"after 30s: {json.dumps(settle)[:120]}")
        # A frozen body parks mid-air at y=120; a ticking body falls
        # to terrain well within the window.
        assert settle[1] < 119,             f"body still parked mid-air - chunk not entity-ticking: {settle}"
        load = rcon("forceload query")
        ev.append(f"forceload: {load[:160]}")


# ---------------------------------------------------------------------------
# Batch C2 - resetAt epoch honesty across a JVM restart (expected RED)
# ---------------------------------------------------------------------------

def read_reset_at(ev: list[str]) -> int:
    _, out = mc("events")
    epoch = out["batch"]["resetAt"]
    ev.append(f"resetAt={epoch}")
    return int(epoch)


def batch_c2_respawn(led: Ledger) -> None:
    print("Batch C2 respawn: epoch must grow within one boot too")

    @led.case("C", "C2b", "botdespawn+botspawn mints a beyond-head epoch",
              "GREEN")
    def _(ev):
        _, before = mc("events")
        before_epoch = before["batch"]["resetAt"]
        rcon("botdespawn")
        time.sleep(1)
        rcon("botspawn")
        time.sleep(2)
        _, after = mc("events")
        after_epoch = after["batch"]["resetAt"]
        ev.append(f"respawn epoch {before_epoch} -> {after_epoch}")
        assert after_epoch > before_epoch,             f"respawn collided epochs: {before_epoch} -> {after_epoch}"


def batch_c2_pre(led: Ledger) -> None:
    print("Batch C2 pre-restart: record the epoch")

    @led.case("C", "C2-pre", "record pre-restart resetAt for the boot test", "GREEN")
    def _(ev):
        epoch = read_reset_at(ev)
        state = json.loads(STATE.read_text()) if STATE.exists() else {}
        state["reset_at_before"] = epoch
        STATE.write_text(json.dumps(state, indent=1))
        assert epoch >= 1, f"nonsense epoch {epoch}"


def batch_c2_post(led: Ledger) -> None:
    print("Batch C2 post-restart: epoch must be beyond-head")

    # Contract after the 0015 fix (SavedData epoch store): every boot
    # draws strictly beyond the persisted watermark, so a client
    # bookmark from before the restart always sees the reset signal.
    @led.case("C", "C2-post", "resetAt after restart is beyond pre-restart head", "GREEN")
    def _(ev):
        state = json.loads(STATE.read_text())
        before = int(state["reset_at_before"])
        after = read_reset_at(ev)
        assert after > before,             f"epoch not beyond-head: before={before} after={after}"
        ev.append(f"beyond-head holds: {before} -> {after}")


# ---------------------------------------------------------------------------
# Batch D1 - menu fields surface (expect green)
# Generic container snapshot / role-filter / typed-error surface over
# the real menu command batch (issue 0012 D1). Furnace-specific
# INPUT/FUEL/OUTPUT roles plus burn/cook progress are the D2 batch; D1
# uses a chest (an undifferentiated CONTAINER region) and the error
# paths.
#
# Fixtures are built by RCON setblock - the bot has no /give verb, so
# the deposit round-trip first TAKES from a pre-filled source chest
# (resolveRole maps OUTPUT->CONTAINER on an undifferentiated chest) and
# then deposits into an empty sink. Both chests sit inside the 4.5-block
# interaction reach and are kept an air block apart so they never merge
# into a double chest.
#
# Manifest (self-describing id/desc/expect, definition order = run order):
#   D1-1  scan discovers the placed chests               GREEN
#   D1-2  full snapshot carries the L0 field shape       GREEN
#   D1-3  pre-filled items visible in their slots        GREEN
#   D1-4  container role filter returns the chest region GREEN
#   D1-5  absent role filter returns empty (read has no  GREEN
#         resolveRole mapping - pins the read/write split)
#   D1-6  role filter is case-insensitive                GREEN
#   D1-7  take output from a chest lands on CONTAINER    GREEN
#   D1-8  deposit input into the empty sink lands        GREEN
#   D1-9  stateless session: a second station opens      GREEN
#         right after the first closes
#   D1-10 typed error: station path without an @         GREEN
#   D1-11 typed error: write without a role segment      GREEN
#   D1-12 typed error: deposit value missing item:count  GREEN
#         (rejected before any menu opens)
#   D1-13 typed error: unknown role segment              GREEN
#   D1-14 cat /player/menu is explicitly unsupported     GREEN
#   D1-15 open at an unreachable coordinate rejects      GREEN
# ---------------------------------------------------------------------------

def setup_d1_fixtures(ev: list[str]):
    """Place a pre-filled source chest and an empty sink chest within
    reach of the body. Returns (source, sink) as (x,y,z) tuples.
    Idempotent: setblock replace overwrites any prior fixture."""
    _, body = mc("cat", "/player/pos")
    bx, by, bz = int(body[0]), int(body[1]), int(body[2])
    # Both on the prologue platform's center line (z strip bz-1..bz+1),
    # east of the body: source at +2 (dist ~2.3), sink at +4 (dist ~4.15,
    # still inside the 4.5 interaction reach). One air block at +3 keeps
    # them from merging into a double chest.
    src = (bx + 2, by, bz)
    sink = (bx + 4, by, bz)
    sx, sy, sz = src
    qx, qy, qz = sink
    # Clear a pocket so neither chest is embedded (open rejects blocked)
    # and no leftover fixture block occupies the cells.
    rcon(f"fill {sx - 1} {sy} {sz - 1} {qx + 1} {sy + 1} {qz + 1} minecraft:air")
    # Pre-fill the source: slot 0 = 32 cobblestone, slot 5 = 16 oak logs.
    # src_nbt is a plain string so its braces stay literal.
    src_nbt = ('{Items:[{Slot:0b,id:"minecraft:cobblestone",Count:32b},'
               '{Slot:5b,id:"minecraft:oak_log",Count:16b}]}')
    rcon(f"setblock {sx} {sy} {sz} minecraft:chest[facing=north]{src_nbt} replace")
    rcon(f"setblock {qx} {qy} {qz} minecraft:chest[facing=north] replace")
    time.sleep(1)
    ev.append(f"fixtures source={src} sink={sink}")
    return src, sink


def station_path(kind: str, pos: tuple, role: str | None = None) -> str:
    """Build a /stations/<kind>@<x,y,z>[/<role>] path."""
    x, y, z = pos
    base = f"/stations/{kind}@{x},{y},{z}"
    return f"{base}/{role}" if role else f"{base}/"


def _slot_empty(slot: dict) -> bool:
    """A snapshot slot is empty when its item reports count 0."""
    return slot.get("item", {}).get("count", 0) == 0


def batch_d1(led: Ledger) -> None:
    print("Batch D1: menu fields surface")
    setup_ev: list[str] = []
    source, sink = setup_d1_fixtures(setup_ev)
    sx, sy, sz = source

    @led.case("D1", "D1-1", "scan discovers the placed chests", "GREEN")
    def _(ev):
        code, out = mc("ls", "/stations/")
        assert code == 0, f"scan exit {code}: {out}"
        text = out.get("raw", "") if isinstance(out, dict) else str(out)
        assert f"chest@{sx},{sy},{sz}" in text, f"source chest missing: {text}"
        ev.append(f"scan={text[:200]}")

    @led.case("D1", "D1-2", "full snapshot carries the L0 field shape", "GREEN")
    def _(ev):
        code, menu = mc("cat", station_path("chest", source))
        assert code == 0 and isinstance(menu, dict), f"cat exit {code}: {menu}"
        for key in ("type", "sourcePos", "carried", "containerSize", "slots"):
            assert key in menu, f"missing L0 key {key}: {menu}"
        assert menu["type"] == "chest", f"type={menu['type']}"
        assert menu["sourcePos"] == [sx, sy, sz], f"sourcePos={menu['sourcePos']}"
        assert isinstance(menu["slots"], list) and menu["slots"], "empty slots"
        for slot in menu["slots"]:
            for key in ("index", "role", "item"):
                assert key in slot, f"slot missing {key}: {slot}"
            assert set(slot["item"]) >= {"id", "count"}, f"item shape: {slot['item']}"
        ev.append(f"type={menu['type']} size={menu['containerSize']} "
                  f"slots={len(menu['slots'])}")

    @led.case("D1", "D1-3", "pre-filled items visible in their slots", "GREEN")
    def _(ev):
        code, menu = mc("cat", station_path("chest", source))
        assert code == 0, f"cat exit {code}: {menu}"
        by_index = {s["index"]: s["item"] for s in menu["slots"]}
        cobble = by_index.get(0)
        logs = by_index.get(5)
        assert cobble and cobble["id"].endswith("cobblestone") and cobble["count"] == 32, \
            f"slot0={cobble}"
        assert logs and logs["id"].endswith("oak_log") and logs["count"] == 16, \
            f"slot5={logs}"
        ev.append(f"slot0={cobble} slot5={logs}")

    @led.case("D1", "D1-4", "container role filter returns the chest region", "GREEN")
    def _(ev):
        code, full = mc("cat", station_path("chest", source))
        assert code == 0, f"full cat exit {code}: {full}"
        # Vanilla single-chest menu = 27 storage + 36 player slots.
        assert full["containerSize"] == 63, \
            f"chest menu size={full['containerSize']} (expected 63)"
        code, filtered = mc("cat", station_path("chest", source, "container"))
        assert code == 0, f"role cat exit {code}: {filtered}"
        roles = {s["role"] for s in filtered["slots"]}
        assert roles <= {"CONTAINER"}, f"non-container role leaked: {roles}"
        # The 36 player-region slots (MAIN/HOTBAR) must be filtered out,
        # leaving exactly the 27-slot storage region.
        assert len(filtered["slots"]) == 27, \
            f"filtered {len(filtered['slots'])} (expected 27 storage slots)"
        idxs = {s["index"] for s in filtered["slots"]}
        assert {0, 5} <= idxs, f"filled slots missing: {idxs}"
        ev.append(f"container slots={len(filtered['slots'])} roles={roles}")

    @led.case("D1", "D1-5", "absent role filter returns empty (read has no role mapping)",
              "GREEN")
    def _(ev):
        # Read-side filtering is literal (no resolveRole fallback), so a
        # chest - whose slots are CONTAINER, never FUEL - answers empty.
        # This pins the deliberate read/write split: write output maps
        # onto CONTAINER, but cat fuel does not.
        code, filtered = mc("cat", station_path("chest", source, "fuel"))
        assert code == 0, f"role cat exit {code}: {filtered}"
        assert filtered["slots"] == [], f"chest answered FUEL slots: {filtered}"
        ev.append("fuel filter empty as designed")

    @led.case("D1", "D1-6", "role filter is case-insensitive", "GREEN")
    def _(ev):
        code, lower = mc("cat", station_path("chest", source, "container"))
        code_mixed, mixed = mc("cat", station_path("chest", source, "CoNtAiNeR"))
        assert code == 0 and code_mixed == 0, f"{code}/{code_mixed}"
        assert len(mixed["slots"]) == len(lower["slots"]), \
            f"mixed-case diverged: {len(lower['slots'])} vs {len(mixed['slots'])}"
        ev.append(f"both {len(lower['slots'])} slots")

    @led.case("D1", "D1-7", "take output from a chest lands on CONTAINER", "GREEN")
    def _(ev):
        code, reply = mc("write", station_path("chest", source, "output"), "all")
        assert code == 0 and reply.get("ok") is True, f"take exit {code}: {reply}"
        taken = reply.get("taken", 0)
        assert taken >= 48, f"expected 32+16=48 taken, got {taken}: {reply}"
        # Source chest region is now empty.
        _, after = mc("cat", station_path("chest", source, "container"))
        left = sum(s["item"]["count"] for s in after["slots"] if not _slot_empty(s))
        assert left == 0, f"source not drained: {left}"
        # The items moved into the bot's own inventory.
        _, bag = mc("cat", "/player/inventory")
        bag_dump = json.dumps(bag)
        assert "cobblestone" in bag_dump and "oak_log" in bag_dump, \
            f"bot did not receive items: {bag_dump[:200]}"
        ev.append(f"taken={taken} bag={bag_dump[:160]}")

    @led.case("D1", "D1-8", "deposit input into the empty sink lands on CONTAINER", "GREEN")
    def _(ev):
        code, reply = mc("write", station_path("chest", sink, "input"),
                         "minecraft:cobblestone:16")
        assert code == 0 and reply.get("ok") is True, f"deposit exit {code}: {reply}"
        assert reply.get("placed") == 16, f"placed={reply.get('placed')}"
        _, menu = mc("cat", station_path("chest", sink, "container"))
        total = sum(s["item"]["count"] for s in menu["slots"]
                    if not _slot_empty(s) and s["item"]["id"].endswith("cobblestone"))
        assert total == 16, f"sink holds {total} cobblestone, expected 16"
        ev.append(f"placed=16 sink total={total}")

    @led.case("D1", "D1-9", "stateless session: second station opens after first closes",
              "GREEN")
    def _(ev):
        # Every cat is a complete open-read-close transaction (finally
        # block), so back-to-back different stations never collide with
        # a dangling "menu already open".
        code_a, _ = mc("cat", station_path("chest", source))
        code_b, _ = mc("cat", station_path("chest", sink))
        assert code_a == 0 and code_b == 0, f"{code_a} / {code_b}"
        ev.append("sequential opens both ok")

    @led.case("D1", "D1-10", "typed error: station path without an @", "GREEN")
    def _(ev):
        code, out = mc("cat", "/stations/chest/")
        assert code == 1, f"expected reject, exit {code}: {out}"
        err = out.get("stderr", "") if isinstance(out, dict) else str(out)
        assert "invalid station path" in err, f"wrong error: {err}"
        ev.append(f"rejected: {err[:120]}")

    @led.case("D1", "D1-11", "typed error: write without a role segment", "GREEN")
    def _(ev):
        code, out = mc("write", station_path("chest", sink), "x")
        assert code == 1, f"expected reject, exit {code}: {out}"
        err = out.get("stderr", "") if isinstance(out, dict) else str(out)
        assert "role segment" in err, f"wrong error: {err}"
        ev.append(f"rejected: {err[:120]}")

    @led.case("D1", "D1-12", "typed error: deposit value missing item:count", "GREEN")
    def _(ev):
        # Fails value validation BEFORE opening the menu (wire call
        # count stays honest).
        code, out = mc("write", station_path("chest", sink, "input"), "nocolon")
        assert code == 1, f"expected reject, exit {code}: {out}"
        err = out.get("stderr", "") if isinstance(out, dict) else str(out)
        assert "item:count" in err, f"wrong error: {err}"
        ev.append(f"rejected pre-open: {err[:120]}")

    @led.case("D1", "D1-13", "typed error: unknown role segment", "GREEN")
    def _(ev):
        code, out = mc("write", station_path("chest", sink, "bogus"), "x")
        assert code == 1, f"expected reject, exit {code}: {out}"
        err = out.get("stderr", "") if isinstance(out, dict) else str(out)
        assert "unknown role" in err, f"wrong error: {err}"
        ev.append(f"rejected: {err[:120]}")

    @led.case("D1", "D1-14", "cat /player/menu is explicitly unsupported", "GREEN")
    def _(ev):
        code, out = mc("cat", "/player/menu")
        assert code == 1, f"expected reject, exit {code}: {out}"
        err = out.get("stderr", "") if isinstance(out, dict) else str(out)
        assert "unsupported" in err, f"wrong error: {err}"
        ev.append(f"rejected: {err[:120]}")

    @led.case("D1", "D1-15", "open at an unreachable coordinate rejects", "GREEN")
    def _(ev):
        far = (sx + 38, sy, sz)
        code, out = mc("cat", station_path("chest", far))
        assert code == 1, f"expected reject, exit {code}: {out}"
        err = out.get("stderr", "") if isinstance(out, dict) else str(out)
        assert err.strip(), "reject carried no reason"
        ev.append(f"rejected far open: {err[:120]}")


# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="a,b",
                        help="comma list from a,b,c1,c2b,c2-pre,c2-post,d1")
    args = parser.parse_args()
    selected = set(args.only.split(","))

    # Liveness gate: fail loud before receipts if the server is down.
    code, out = mc("cat", "/player/pos")
    if code != 0:
        print(f"server not reachable via mc CLI (exit {code}): {out}")
        return 2

    # Batch-order hygiene: a previous C1 leaves the body frozen in an
    # UNLOADED chunk, where @e selectors cannot reach it (tp silently
    # misses) and every goto fails NO_PATH. Respawn through the
    # binding instead: despawn+spawn lands a fresh ticking body at
    # world spawn without any selector.
    rcon("botdespawn")
    time.sleep(1)
    spawn = rcon("botspawn")
    time.sleep(3)
    # Deterministic walkway: every world wipe rolls a new seed, and a
    # spawn hemmed in by water/cliff makes the +30/-30 liveness targets
    # NO_PATH instantly (B4/B7 lost rounds to that lottery). Build a
    # TERRAIN-INDEPENDENT platform instead: a 3-wide, 81-long slab with
    # 3 blocks of headroom carved above it, floating over whatever the
    # seed generated. Width 3 matters - a 1-wide corridor carved
    # through a mountainside fails move primitives' flanking checks
    # (+5 NO_PATH even on smooth stone, pinned live 2026-08-31).
    body = mc("cat", "/player/pos")[1]
    bx, by, bz = int(body[0]), int(body[1]), int(body[2])
    rcon(f"fill {bx-40} {by-1} {bz-1} {bx+40} {by-1} {bz+1} minecraft:smooth_stone")
    rcon(f"fill {bx-40} {by} {bz-1} {bx+40} {by+2} {bz+1} minecraft:air")
    # Re-seat the body on the platform (the air carve may have left it
    # inside terrain at its spawn column).
    rcon(f"tp @e[type=mcbotserver:bot_body,limit=1] {bx}.5 {by} {bz}.5")
    print(f"  [prologue] platform built at ({bx},{by},{bz})")

    led = Ledger()
    runners = {"a": batch_a, "b": batch_b, "c1": batch_c1,
               "c2b": batch_c2_respawn, "c2-pre": batch_c2_pre,
               "c2-post": batch_c2_post, "d1": batch_d1}
    for key in ("a", "b", "c1", "c2b", "c2-pre", "c2-post", "d1"):
        if key in selected:
            runners[key](led)

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    receipt = {
        "schema": 1,
        "task": "boundary-d-qa",
        "finished_at": datetime.now(timezone.utc).isoformat(),
        "cases": led.rows,
        "evidence": led.evidence,
    }
    path = HERE / f"receipt-{stamp}.json"
    path.write_text(json.dumps(receipt, indent=1), encoding="utf-8")
    (HERE / f"results-{stamp}.md").write_text(led.table(), encoding="utf-8")
    red = sum(1 for r in led.rows if r["verdict"] in ("FAIL", "ERROR",
                                                      "UNEXPECTED-GREEN"))
    print(f"\nreceipt: {path}")
    print(led.table())
    return 1 if red else 0


if __name__ == "__main__":
    raise SystemExit(main())
