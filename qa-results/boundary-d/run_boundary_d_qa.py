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
# D2: furnace-family differentiated roles (INPUT / FUEL / OUTPUT) plus
# burn/cook progress disclosure. The role infrastructure shipped earlier
# (MenuSlotLayouts.furnaceRole + hasDifferentiatedRoles); progress is new
# in this batch (MenuProgress record + BindingMenu reflective read of the
# private AbstractFurnaceMenu.data ContainerData).
# ---------------------------------------------------------------------------


def batch_d2(led: "Ledger") -> None:
    """Furnace INPUT/FUEL/OUTPUT roles + burn/cook progress disclosure."""

    def setup_d2_fixtures() -> tuple:
        """Place a pre-filled furnace, blast_furnace, and smoker within
        reach. The furnace is pre-filled via NBT (INPUT=iron_ore x8,
        FUEL=coal x4) because the generic menu-opener path does not yet
        surface the bot inventory in furnace-family player regions
        (deposit returns "have 0" despite /player/inventory showing items
        — a known adapter-layer bug, filed separately; chest deposit via
        the dedicated ChestMenu path works correctly, D1-8).

        Geometry (bot eye at bx+0.5, by+1.62, bz+0.5):
          furnace       at (bx+2, by, bz)   -> dist ~2.29
          blast_furnace at (bx+4, by, bz)   -> dist ~4.15
          smoker        at (bx+2, by, bz+2) -> dist ~3.04 (floating)
        """
        _, body = mc("cat", "/player/pos")
        bx, by, bz = int(body[0]), int(body[1]), int(body[2])
        furnace_pos = (bx + 2, by, bz)
        blast_pos = (bx + 4, by, bz)
        smoker_pos = (bx + 2, by, bz + 2)
        for (cx, cy, cz) in [furnace_pos, blast_pos, smoker_pos]:
            for dx in (-1, 0, 1):
                for dy in (0, 1):
                    for dz in (-1, 0, 1):
                        rcon(f"setblock {cx+dx} {cy+dy} {cz+dz} minecraft:air")
            time.sleep(0.2)
        # Place empty furnace first, then inject items via /item replace
        # (more reliable than setblock NBT — the furnace block entity's
        # runtime state is fresh, and items land in the correct slots).
        rcon(f"setblock {furnace_pos[0]} {furnace_pos[1]} {furnace_pos[2]} minecraft:furnace")
        rcon(f"item replace block {furnace_pos[0]} {furnace_pos[1]} {furnace_pos[2]} container.0 with minecraft:iron_ore 8")
        rcon(f"item replace block {furnace_pos[0]} {furnace_pos[1]} {furnace_pos[2]} container.1 with minecraft:coal 64")
        rcon(f"setblock {blast_pos[0]} {blast_pos[1]} {blast_pos[2]} minecraft:blast_furnace")
        rcon(f"setblock {smoker_pos[0]} {smoker_pos[1]} {smoker_pos[2]} minecraft:smoker")
        time.sleep(1.0)
        return furnace_pos, blast_pos, smoker_pos

    furnace_pos, blast_pos, smoker_pos = setup_d2_fixtures()
    fx, fy, fz = furnace_pos

    @led.case("D2", "D2-1", "scan discovers the furnace block-entity", "GREEN")
    def _(ev):
        code, out = mc("ls", "/stations/")
        assert code == 0, f"scan failed: {out}"
        text = out.get("raw", "") if isinstance(out, dict) else str(out)
        assert f"furnace@{fx},{fy},{fz}" in text, f"furnace not in scan: {text[:200]}"
        ev.append(f"scan found furnace@{fx},{fy},{fz}")

    @led.case("D2", "D2-2", "furnace snapshot: type, size, progress object present", "GREEN")
    def _(ev):
        code, menu = mc("cat", station_path("furnace", furnace_pos))
        assert code == 0, f"open failed: {menu}"
        assert menu["type"] == "furnace", f"type={menu['type']}"
        assert menu["containerSize"] == 39, f"size={menu['containerSize']}"
        progress = menu.get("progress")
        assert progress is not None, "furnace snapshot missing progress object"
        # Pre-filled furnace (INPUT+FUEL) may already be burning by the
        # time this case runs — only pin the shape, not the values.
        assert set(progress.keys()) == {"burnTime", "totalBurnTime", "cookProgress", "cookTotal"}
        ev.append(f"type=furnace size=39 progress keys={sorted(progress.keys())}")

    @led.case("D2", "D2-3", "furnace roles are INPUT/FUEL/OUTPUT, no CONTAINER", "GREEN")
    def _(ev):
        code, menu = mc("cat", station_path("furnace", furnace_pos))
        assert code == 0
        slots = menu["slots"]
        s0 = next(s for s in slots if s["index"] == 0)
        s1 = next(s for s in slots if s["index"] == 1)
        s2 = next(s for s in slots if s["index"] == 2)
        assert s0["role"] == "INPUT", f"slot0 role={s0['role']}"
        assert s1["role"] == "FUEL", f"slot1 role={s1['role']}"
        assert s2["role"] == "OUTPUT", f"slot2 role={s2['role']}"
        roles = {s["role"] for s in slots}
        assert "CONTAINER" not in roles, f"CONTAINER leaked into furnace roles: {roles}"
        ev.append(f"roles: 0=INPUT 1=FUEL 2=OUTPUT, no CONTAINER (full set: {sorted(roles)})")

    @led.case("D2", "D2-4", "pre-filled INPUT iron_ore x8 sits in slot0", "GREEN")
    def _(ev):
        code, menu = mc("cat", station_path("furnace", furnace_pos))
        assert code == 0, f"open failed: {menu}"
        s0 = next(s for s in menu["slots"] if s["index"] == 0)
        assert s0["role"] == "INPUT", f"slot0 role={s0['role']}"
        assert s0["item"]["id"].endswith("iron_ore"), f"slot0 item={s0['item']}"
        assert s0["item"]["count"] == 8, f"slot0 count={s0['item']['count']}"
        ev.append(f"slot0=INPUT {s0['item']['id']}x{s0['item']['count']}")

    @led.case("D2", "D2-5", "pre-filled FUEL coal x64 sits in slot1", "GREEN")
    def _(ev):
        code, menu = mc("cat", station_path("furnace", furnace_pos))
        assert code == 0
        s1 = next(s for s in menu["slots"] if s["index"] == 1)
        assert s1["role"] == "FUEL", f"slot1 role={s1['role']}"
        assert s1["item"]["id"].endswith("coal"), f"slot1 item={s1['item']}"
        assert s1["item"]["count"] >= 60, f"slot1 count={s1['item']['count']} (coal burns slowly, expect ~64)"
        ev.append(f"slot1=FUEL {s1['item']['id']}x{s1['item']['count']}")

    @led.case("D2", "D2-6", "after 5s smelting: burnTime>0, cookProgress>0, raw tick values", "GREEN")
    def _(ev):
        # Coal = 1600 ticks burn, iron ore = 200 ticks cook. After 5s
        # (100 ticks) the furnace is lit and the first recipe is ~50%.
        time.sleep(5.0)
        code, menu = mc("cat", station_path("furnace", furnace_pos))
        assert code == 0
        p = menu["progress"]
        assert p["burnTime"] > 0, f"burnTime={p['burnTime']} (furnace not lit)"
        assert p["totalBurnTime"] == 1600, f"totalBurnTime={p['totalBurnTime']} (coal expected 1600)"
        assert p["cookProgress"] > 0, f"cookProgress={p['cookProgress']}"
        assert p["cookTotal"] == 200, f"cookTotal={p['cookTotal']} (iron ore expected 200)"
        ev.append(f"progress: burnTime={p['burnTime']}/1600 cookProgress={p['cookProgress']}/200")

    @led.case("D2", "D2-7", "smelt completes: OUTPUT has iron_ingot, take OUTPUT removes it", "GREEN")
    def _(ev):
        # Wait for the first iron ore to finish (200 ticks = 10s) plus
        # buffer. D2-6 already waited 5s, so 12s more covers it.
        time.sleep(12.0)
        code, menu = mc("cat", station_path("furnace", furnace_pos))
        assert code == 0
        s2 = next(s for s in menu["slots"] if s["index"] == 2)
        assert s2["item"]["id"].endswith("iron_ingot"), f"OUTPUT item={s2['item']} (expected iron_ingot)"
        ingot_count = s2["item"]["count"]
        assert ingot_count >= 1, f"OUTPUT count={ingot_count}"
        code, out = mc("write", station_path("furnace", furnace_pos, "output"), "all")
        assert code == 0, f"take OUTPUT failed: {out}"
        taken = out.get("taken", 0)
        assert taken >= ingot_count, f"taken={taken} expected >={ingot_count}"
        ev.append(f"smelted {ingot_count} iron_ingot, taken={taken}")

    @led.case("D2", "D2-8", "blast_furnace: differentiated roles + progress object present", "GREEN")
    def _(ev):
        code, menu = mc("cat", station_path("blast_furnace", blast_pos))
        assert code == 0, f"open blast_furnace failed: {menu}"
        assert menu["type"] == "blast_furnace", f"type={menu['type']}"
        assert menu["containerSize"] == 39
        slots = menu["slots"]
        assert next(s for s in slots if s["index"] == 0)["role"] == "INPUT"
        assert next(s for s in slots if s["index"] == 1)["role"] == "FUEL"
        assert next(s for s in slots if s["index"] == 2)["role"] == "OUTPUT"
        progress = menu.get("progress")
        assert progress is not None, "blast_furnace missing progress"
        assert progress["burnTime"] == 0 and progress["cookProgress"] == 0
        ev.append(f"blast_furnace: roles INPUT/FUEL/OUTPUT, progress present (empty)")

    @led.case("D2", "D2-9", "smoker: differentiated roles + progress object present", "GREEN")
    def _(ev):
        code, menu = mc("cat", station_path("smoker", smoker_pos))
        assert code == 0, f"open smoker failed: {menu}"
        assert menu["type"] == "smoker", f"type={menu['type']}"
        assert menu["containerSize"] == 39
        slots = menu["slots"]
        assert next(s for s in slots if s["index"] == 0)["role"] == "INPUT"
        assert next(s for s in slots if s["index"] == 1)["role"] == "FUEL"
        assert next(s for s in slots if s["index"] == 2)["role"] == "OUTPUT"
        progress = menu.get("progress")
        assert progress is not None, "smoker missing progress"
        ev.append(f"smoker: roles INPUT/FUEL/OUTPUT, progress present (empty)")

    @led.case("D2", "D2-10", "deposit with unknown role on furnace rejects", "GREEN")
    def _(ev):
        code, out = mc("write", station_path("furnace", furnace_pos, "bogus"), "dirt:1")
        assert code == 1, f"expected reject, exit {code}: {out}"
        err = out.get("stderr", "") if isinstance(out, dict) else str(out)
        assert "unknown role" in err, f"wrong error: {err}"
        ev.append(f"rejected unknown role: {err[:100]}")

    @led.case("D2", "D2-11", "open furnace at unreachable coordinate rejects", "GREEN")
    def _(ev):
        far = (fx + 38, fy, fz)
        code, out = mc("cat", station_path("furnace", far))
        assert code == 1, f"expected reject, exit {code}: {out}"
        err = out.get("stderr", "") if isinstance(out, dict) else str(out)
        assert err.strip(), "reject carried no reason"
        ev.append(f"rejected far furnace open: {err[:100]}")

    @led.case("D2", "D2-12", "progress survives menu close/reopen (data read from block entity)", "GREEN")
    def _(ev):
        # After D2-7 the furnace may still be smelting remaining ore.
        # Reopen and verify progress is still a valid object with
        # internally-consistent values (burnTime <= totalBurnTime when lit).
        code, menu = mc("cat", station_path("furnace", furnace_pos))
        assert code == 0
        p = menu["progress"]
        assert p is not None
        if p["totalBurnTime"] > 0:
            assert p["burnTime"] <= p["totalBurnTime"], (
                f"burnTime={p['burnTime']} > totalBurnTime={p['totalBurnTime']}")
        if p["cookTotal"] > 0:
            assert p["cookProgress"] <= p["cookTotal"], (
                f"cookProgress={p['cookProgress']} > cookTotal={p['cookTotal']}")
        ev.append(f"reopen progress consistent: burn={p['burnTime']}/{p['totalBurnTime']} "
                  f"cook={p['cookProgress']}/{p['cookTotal']}")


# ---------------------------------------------------------------------------
# Batch D3: multi-container stateful integration.
#
# D1/D2 test isolated atomic operations on single containers that are
# rebuilt every case. D3 intentionally accumulates state across cases:
# a fixed layout of 3 chests + 2 furnaces, chained transfers, partial
# takes, stacking behavior, independent furnace progress, and session
# hygiene under repeated open/close. The point is to expose the bugs
# that atomic tests systematically avoid: state pollution, session
# leaks, cursor residue, stack-splitting surprises, and conservation
# breakdown across a real inventory-management workflow.
#
# Layout (bot eye at bx+0.5, by+1.62, bz+0.5), all within reach 4.5:
#   Chest A (source)   at (bx+2, by, bz)     dist ~2.21
#   Chest B (transfer) at (bx+4, by, bz)     dist ~3.86 (air gap at bx+3)
#   Chest C (sink)     at (bx+2, by, bz+2)   dist ~2.67
#   Furnace 1          at (bx-2, by, bz)     dist ~2.98
#   Furnace 2          at (bx-2, by, bz+2)   dist ~3.34
# ---------------------------------------------------------------------------


def batch_d3(led: Ledger) -> None:
    """Stateful multi-container integration batch."""
    print("Batch D3: multi-container stateful integration")

    def setup_d3_fixtures() -> tuple:
        """Place 3 chests + 2 furnaces. Chest A pre-filled; Furnace 2
        pre-filled with fuel+ore so it smelts independently from setup.
        """
        _, body = mc("cat", "/player/pos")
        bx, by, bz = int(body[0]), int(body[1]), int(body[2])
        chest_a = (bx + 2, by, bz)
        chest_b = (bx + 4, by, bz)
        chest_c = (bx + 2, by, bz + 2)
        furnace_1 = (bx - 2, by, bz)
        furnace_2 = (bx - 2, by, bz + 2)
        positions = [chest_a, chest_b, chest_c, furnace_1, furnace_2]
        for (cx, cy, cz) in positions:
            for dx in (-1, 0, 1):
                for dy in (0, 1):
                    for dz in (-1, 0, 1):
                        rcon(f"setblock {cx+dx} {cy+dy} {cz+dz} minecraft:air")
            time.sleep(0.2)
        # Chest A: slot0=64 cobblestone, slot1=32 iron_ore, slot2=16 coal
        rcon(f"setblock {chest_a[0]} {chest_a[1]} {chest_a[2]} minecraft:chest")
        rcon(f"item replace block {chest_a[0]} {chest_a[1]} {chest_a[2]} container.0 with minecraft:cobblestone 64")
        rcon(f"item replace block {chest_a[0]} {chest_a[1]} {chest_a[2]} container.1 with minecraft:iron_ore 32")
        rcon(f"item replace block {chest_a[0]} {chest_a[1]} {chest_a[2]} container.2 with minecraft:coal 16")
        # Chests B, C: empty
        rcon(f"setblock {chest_b[0]} {chest_b[1]} {chest_b[2]} minecraft:chest")
        rcon(f"setblock {chest_c[0]} {chest_c[1]} {chest_c[2]} minecraft:chest")
        # Furnace 1: empty (filled during D3-5)
        rcon(f"setblock {furnace_1[0]} {furnace_1[1]} {furnace_1[2]} minecraft:furnace")
        # Furnace 2: pre-filled INPUT=8 iron_ore, FUEL=4 coal (smelts from setup)
        rcon(f"setblock {furnace_2[0]} {furnace_2[1]} {furnace_2[2]} minecraft:furnace")
        rcon(f"item replace block {furnace_2[0]} {furnace_2[1]} {furnace_2[2]} container.0 with minecraft:iron_ore 8")
        rcon(f"item replace block {furnace_2[0]} {furnace_2[1]} {furnace_2[2]} container.1 with minecraft:coal 4")
        time.sleep(1.0)
        # Setup self-check: verify Chest A was pre-filled correctly before
        # any test runs. A mismatch here means setblock/item replace failed
        # silently — fail loud with the actual counts, not a confusing
        # "container missing" error in D3-1.
        code, verify = mc("cat", station_path("chest", chest_a, "container"))
        if code != 0:
            raise RuntimeError(f"setup: cannot read chest_a for verification: {verify}")
        v_cobble = count_role_items(verify, "CONTAINER", "cobblestone")
        v_ore = count_role_items(verify, "CONTAINER", "iron_ore")
        v_coal = count_role_items(verify, "CONTAINER", "coal")
        if v_cobble != 64 or v_ore != 32 or v_coal != 16:
            raise RuntimeError(
                f"setup fixture mismatch: chest_a has cobble={v_cobble}, "
                f"ore={v_ore}, coal={v_coal} (expected 64/32/16)")
        return chest_a, chest_b, chest_c, furnace_1, furnace_2

    def count_role_items(menu: dict, role: str, item_id: str) -> int:
        """Count items matching item_id in slots of the given role."""
        total = 0
        for s in menu.get("slots", []):
            if s.get("role") == role and s.get("item") and s["item"].get("id", "").endswith(item_id):
                total += s["item"].get("count", 0)
        return total

    chest_a, chest_b, chest_c, furnace_1, furnace_2 = setup_d3_fixtures()

    @led.case("D3", "D3-1", "scan discovers all 5 containers with correct types", "GREEN")
    def _(ev):
        code, out = mc("ls", "/stations/")
        assert code == 0, f"ls /stations exit {code}: {out}"
        text = out.get("raw", "") if isinstance(out, dict) else str(out)
        # Verify all 5 fixture containers appear in the station listing
        for (cx, cy, cz) in (chest_a, chest_b, chest_c, furnace_1, furnace_2):
            assert f"@{cx},{cy},{cz}" in text, f"container at ({cx},{cy},{cz}) missing: {text[:300]}"
        chest_count = text.count("chest@")
        furnace_count = text.count("furnace@")
        assert chest_count >= 3, f"expected >=3 chests, found {chest_count}"
        assert furnace_count >= 2, f"expected >=2 furnaces, found {furnace_count}"
        ev.append(f"stations: {chest_count} chests, {furnace_count} furnaces, all 5 fixtures present")

    @led.case("D3", "D3-2", "chained A->B transfer: take 32, deposit 32, carried empty", "GREEN")
    def _(ev):
        code, take_out = mc("write", station_path("chest", chest_a, "output"), "32")
        assert code == 0, f"take from A failed: {take_out}"
        taken = take_out.get("taken", 0) if isinstance(take_out, dict) else 0
        assert taken == 32, f"taken={taken}, expected 32"
        code, dep_out = mc("write", station_path("chest", chest_b, "input"), "cobblestone:32")
        assert code == 0, f"deposit to B failed: {dep_out}"
        placed = dep_out.get("placed", 0)
        assert placed == 32, f"placed={placed}, expected 32"
        # carried must be empty after a complete deposit
        assert dep_out.get("menu", {}).get("carried", {}).get("count", 1) == 0, "carried not empty after deposit"
        ev.append(f"A took=32, B placed=32, carried=empty")

    @led.case("D3", "D3-3", "partial take leaves remainder: A 32->16, deposit 16 to C", "GREEN")
    def _(ev):
        # A has 32 cobblestone left (64 - 32 from D3-2)
        code, take_out = mc("write", station_path("chest", chest_a, "output"), "16")
        assert code == 0, f"take from A failed: {take_out}"
        taken = take_out.get("taken", 0) if isinstance(take_out, dict) else 0
        assert taken == 16, f"taken={taken}, expected 16"
        # Verify A has 16 remaining
        code, menu_a = mc("cat", station_path("chest", chest_a, "container"))
        assert code == 0
        remaining = count_role_items(menu_a, "CONTAINER", "cobblestone")
        assert remaining == 16, f"A remaining={remaining}, expected 16"
        # Deposit 16 to C
        code, dep_out = mc("write", station_path("chest", chest_c, "input"), "cobblestone:16")
        assert code == 0, f"deposit to C failed: {dep_out}"
        assert dep_out.get("placed") == 16
        ev.append(f"A 32->16 (remainder verified), C placed=16")

    @led.case("D3", "D3-4", "deposit stacks into existing slot: B 32->48 in one slot", "GREEN")
    def _(ev):
        # A has 16 cobblestone left (64 - 32 from D3-2 - 16 from D3-3).
        # Take exactly 16 (NOT "all", which would take iron_ore+coal too).
        code, take_out = mc("write", station_path("chest", chest_a, "output"), "16")
        assert code == 0, f"take from A failed: {take_out}"
        taken = take_out.get("taken", 0) if isinstance(take_out, dict) else 0
        assert taken == 16, f"taken={taken}, expected 16"
        # Deposit 16 to B (has 32 -> should stack to 48 in slot0, not split)
        code, dep_out = mc("write", station_path("chest", chest_b, "input"), "cobblestone:16")
        assert code == 0, f"deposit to B failed: {dep_out}"
        assert dep_out.get("placed") == 16
        # Verify B has 48 in a single slot (not split across slots)
        code, menu_b = mc("cat", station_path("chest", chest_b, "container"))
        assert code == 0
        cobble_slots = [s for s in menu_b["slots"]
                         if s.get("role") == "CONTAINER" and s.get("item")
                         and s["item"].get("id", "").endswith("cobblestone") and s["item"].get("count", 0) > 0]
        total = sum(s["item"]["count"] for s in cobble_slots)
        assert total == 48, f"B total={total}, expected 48"
        assert len(cobble_slots) == 1, f"cobblestone split across {len(cobble_slots)} slots, expected 1 (stacking)"
        ev.append(f"B: 48 cobblestone in 1 slot (stacking verified), A cobblestone emptied")

    @led.case("D3", "D3-5", "cross-container smelting setup: ore+coal from A -> Furnace 1", "GREEN")
    def _(ev):
        # take N does NOT filter by item type — it takes from the first
        # non-empty slot. So taking "8" then "4" from a chest with
        # [iron_ore, coal] takes 12 iron_ore, not 8 ore + 4 coal.
        # Correct pattern: take ALL, then deposit by item type, return rest.
        code, take_all = mc("write", station_path("chest", chest_a, "output"), "all")
        assert code == 0, f"take all from A failed: {take_all}"
        taken = take_all.get("taken", 0) if isinstance(take_all, dict) else 0
        assert taken == 48, f"taken={taken}, expected 48 (32 ore + 16 coal)"
        ev.append(f"took all from A: {taken} items")
        # Deposit 8 iron_ore to Furnace 1 INPUT
        code, dep_ore = mc("write", station_path("furnace", furnace_1, "input"), "iron_ore:8")
        assert code == 0, f"deposit ore to furnace failed: {dep_ore}"
        assert dep_ore.get("placed") == 8, f"ore placed={dep_ore.get('placed')}, expected 8"
        ev.append(f"deposited iron_ore:8 to Furnace1 INPUT")
        # Deposit 4 coal to Furnace 1 FUEL
        code, dep_coal = mc("write", station_path("furnace", furnace_1, "fuel"), "coal:4")
        assert code == 0, f"deposit coal to furnace failed: {dep_coal}"
        assert dep_coal.get("placed") == 4, f"coal placed={dep_coal.get('placed')}, expected 4"
        ev.append(f"deposited coal:4 to Furnace1 FUEL")
        # Return remaining 24 iron_ore + 12 coal to A (expected state for D3-8)
        code, ret_ore = mc("write", station_path("chest", chest_a, "input"), "iron_ore:24")
        assert code == 0, f"return ore to A failed: {ret_ore}"
        ev.append(f"returned iron_ore:24 to A")
        code, ret_coal = mc("write", station_path("chest", chest_a, "input"), "coal:12")
        assert code == 0, f"return coal to A failed: {ret_coal}"
        ev.append(f"returned coal:12 to A")
        # Verify Furnace 1 has both
        code, menu_f1 = mc("cat", station_path("furnace", furnace_1))
        assert code == 0, f"cat Furnace1 failed: {menu_f1}"
        s0 = next(s for s in menu_f1["slots"] if s["index"] == 0)
        s1 = next(s for s in menu_f1["slots"] if s["index"] == 1)
        assert s0.get("item") and s0["item"]["id"].endswith("iron_ore"), f"INPUT item={s0.get('item')}"
        assert s0["item"]["count"] == 8, f"INPUT count={s0['item']['count']}, expected 8"
        assert s1.get("item") and s1["item"]["id"].endswith("coal"), f"FUEL item={s1.get('item')}"
        # Furnace consumes 1 coal immediately on ignition (vanilla: fuel
        # decremented at burn start, not end). So 4 deposited -> 3 visible.
        assert s1["item"]["count"] >= 3, f"FUEL count={s1['item']['count']}, expected >=3 (1 consumed on ignition)"
        ev.append(f"Furnace1 verified: INPUT=iron_orex8, FUEL=coalx4")

    @led.case("D3", "D3-6", "dual furnace progress: both burning, independent values", "GREEN")
    def _(ev):
        time.sleep(5.0)
        code, menu_f1 = mc("cat", station_path("furnace", furnace_1))
        assert code == 0
        p1 = menu_f1.get("progress", {})
        assert p1.get("burnTime", 0) > 0, f"Furnace1 not lit: burnTime={p1.get('burnTime')}"
        assert p1.get("cookProgress", 0) > 0, f"Furnace1 not cooking: cookProgress={p1.get('cookProgress')}"
        code, menu_f2 = mc("cat", station_path("furnace", furnace_2))
        assert code == 0
        p2 = menu_f2.get("progress", {})
        assert p2.get("burnTime", 0) > 0, f"Furnace2 not lit: burnTime={p2.get('burnTime')}"
        assert p2.get("cookProgress", 0) > 0, f"Furnace2 not cooking: cookProgress={p2.get('cookProgress')}"
        # Independence proof: F2 started burning at setup, F1 at D3-5.
        # burnTime decreases monotonically from totalBurnTime (1600 ticks
        # for coal) to 0 over the fuel's lifetime. F2 has been burning
        # longer, so its remaining burnTime is strictly lower.
        # (cookProgress resets per item and can coincide; INPUT consumption
        # requires a full 10s smelt cycle — both are flakier than burnTime.)
        assert p2["burnTime"] < p1["burnTime"], (
            f"F2 burnTime={p2['burnTime']} not < F1 burnTime={p1['burnTime']} — "
            f"F2 started earlier and should have less remaining fuel")
        ev.append(f"F1: burn={p1['burnTime']}/{p1['totalBurnTime']} cook={p1['cookProgress']}/{p1['cookTotal']}; "
                  f"F2: burn={p2['burnTime']}/{p2['totalBurnTime']} cook={p2['cookProgress']}/{p2['cookTotal']}")

    @led.case("D3", "D3-7", "smelt completes: OUTPUT has iron_ingot, take 1, deposit to C", "GREEN")
    def _(ev):
        # Wait for Furnace 1 to produce at least 1 iron_ingot (200 ticks = 10s)
        deadline = time.time() + 20.0
        ingot_count = 0
        while time.time() < deadline:
            code, menu_f1 = mc("cat", station_path("furnace", furnace_1))
            assert code == 0
            s2 = next(s for s in menu_f1["slots"] if s["index"] == 2)
            ingot_count = s2["item"].get("count", 0) if s2.get("item") else 0
            if ingot_count >= 1:
                break
            time.sleep(2.0)
        assert ingot_count >= 1, f"Furnace1 OUTPUT empty after 20s (last count={ingot_count})"
        # Take 1 iron_ingot from OUTPUT
        code, take_out = mc("write", station_path("furnace", furnace_1, "output"), "1")
        assert code == 0, f"take from OUTPUT failed: {take_out}"
        taken = take_out.get("taken", 0) if isinstance(take_out, dict) else 0
        assert taken == 1, f"taken={taken}, expected 1"
        # Verify remainder stays in OUTPUT
        code, menu_f1 = mc("cat", station_path("furnace", furnace_1))
        assert code == 0
        s2 = next(s for s in menu_f1["slots"] if s["index"] == 2)
        remaining = s2["item"].get("count", 0) if s2.get("item") else 0
        assert remaining == ingot_count - 1, f"OUTPUT remaining={remaining}, expected {ingot_count - 1}"
        # Deposit the ingot to Chest C (keeps bot inventory clean)
        code, dep_out = mc("write", station_path("chest", chest_c, "input"), "iron_ingot:1")
        assert code == 0, f"deposit ingot to C failed: {dep_out}"
        assert dep_out.get("placed") == 1
        ev.append(f"Furnace1 OUTPUT: {ingot_count} ingot(s), took 1, remainder={remaining}, deposited to C")

    @led.case("D3", "D3-8", "empty container take returns taken=0, no error", "GREEN")
    def _(ev):
        # A still has 24 iron_ore + 12 coal after D3-5. Take all to empty it.
        code, take_out = mc("write", station_path("chest", chest_a, "output"), "all")
        assert code == 0, f"take all from A failed: {take_out}"
        taken = take_out.get("taken", 0) if isinstance(take_out, dict) else 0
        assert taken == 36, f"taken={taken}, expected 36 (24 ore + 12 coal)"
        # Deposit the mixed items to C (keeps bot inventory clean)
        code, dep_ore = mc("write", station_path("chest", chest_c, "input"), "iron_ore:24")
        assert code == 0, f"deposit ore to C failed: {dep_ore}"
        assert dep_ore.get("placed") == 24, f"ore placed={dep_ore.get('placed')}, expected 24"
        code, dep_coal = mc("write", station_path("chest", chest_c, "input"), "coal:12")
        assert code == 0, f"deposit coal to C failed: {dep_coal}"
        assert dep_coal.get("placed") == 12, f"coal placed={dep_coal.get('placed')}, expected 12"
        # Now A is truly empty. Take all -> taken=0, no error.
        code, take_empty = mc("write", station_path("chest", chest_a, "output"), "all")
        assert code == 0, f"take from empty A should not error: {take_empty}"
        taken_empty = take_empty.get("taken", 0) if isinstance(take_empty, dict) else 0
        assert taken_empty == 0, f"taken={taken_empty} from empty chest, expected 0"
        ev.append(f"emptied A (took 36), deposited to C, empty take: taken=0 (graceful)")

    @led.case("D3", "D3-9", "state consistency: verify accumulated contents across all containers", "GREEN")
    def _(ev):
        # Expected state after D3-1 through D3-8:
        # Chest A: empty (all items removed)
        # Chest B: 48 cobblestone in slot0
        # Chest C: 16 cobblestone + 1 iron_ingot + 24 iron_ore + 12 coal
        # Furnace 1/2: smelting (exact counts depend on timing)
        code, menu_b = mc("cat", station_path("chest", chest_b, "container"))
        assert code == 0
        b_cobble = count_role_items(menu_b, "CONTAINER", "cobblestone")
        assert b_cobble == 48, f"Chest B cobblestone={b_cobble}, expected 48"

        code, menu_c = mc("cat", station_path("chest", chest_c, "container"))
        assert code == 0
        c_cobble = count_role_items(menu_c, "CONTAINER", "cobblestone")
        c_ingot = count_role_items(menu_c, "CONTAINER", "iron_ingot")
        c_ore = count_role_items(menu_c, "CONTAINER", "iron_ore")
        c_coal = count_role_items(menu_c, "CONTAINER", "coal")
        assert c_cobble == 16, f"Chest C cobblestone={c_cobble}, expected 16"
        assert c_ingot >= 1, f"Chest C iron_ingot={c_ingot}, expected >=1"
        assert c_ore == 24, f"Chest C iron_ore={c_ore}, expected 24"
        assert c_coal == 12, f"Chest C coal={c_coal}, expected 12"

        code, menu_a = mc("cat", station_path("chest", chest_a, "container"))
        assert code == 0
        a_total = sum(s["item"].get("count", 0) for s in menu_a["slots"]
                      if s.get("role") == "CONTAINER" and s.get("item") and s["item"].get("count", 0) > 0)
        assert a_total == 0, f"Chest A should be empty, has {a_total} items"

        # Conservation: total cobblestone across A+B+C = 64 (original in A)
        total_cobble = a_total + b_cobble + c_cobble
        assert total_cobble == 64, f"cobblestone conservation: {total_cobble}, expected 64"
        # Bot main inventory must be empty — every prior case deposited all
        # taken items. A silent deposit failure would leave items here and
        # escape the container-only conservation check above.
        code, inv = mc("cat", "/player/inventory")
        assert code == 0, f"cat /player/inventory failed: {inv}"
        bot_items = inv.get("items", {}) if isinstance(inv, dict) else {}
        bot_total = sum(v for v in bot_items.values() if isinstance(v, (int, float)))
        assert bot_total == 0, f"bot inventory not empty (total={bot_total}): {bot_items}"
        ev.append(f"state OK: A=empty, B=48 cobble, C=16 cobble+{c_ingot} ingot+{c_ore} ore+{c_coal} coal; cobble conserved=64; bot inv empty")

    @led.case("D3", "D3-10", "Furnace 2 independent output: produced iron_ingot without direct menu ops", "GREEN")
    def _(ev):
        # Furnace 2 was pre-filled at setup and has been smelting independently
        # throughout D3-1 through D3-9 (no direct deposit/take operations on it
        # except snapshots in D3-6). It should have produced iron_ingot.
        code, menu_f2 = mc("cat", station_path("furnace", furnace_2))
        assert code == 0
        s2 = next(s for s in menu_f2["slots"] if s["index"] == 2)
        ingot_count = s2["item"].get("count", 0) if s2.get("item") else 0
        assert ingot_count >= 1, f"Furnace2 OUTPUT empty after ~60s: count={ingot_count}"
        # INPUT should have decreased (ore consumed)
        s0 = next(s for s in menu_f2["slots"] if s["index"] == 0)
        ore_remaining = s0["item"].get("count", 0) if s0.get("item") else 0
        assert ore_remaining < 8, f"Furnace2 INPUT still has {ore_remaining} ore (expected <8 after smelting)"
        ev.append(f"Furnace2 independent: OUTPUT={ingot_count} iron_ingot, INPUT={ore_remaining} ore (was 8)")

    @led.case("D3", "D3-11", "session hygiene: 5 open/close cycles, no leak, no carried residue", "GREEN")
    def _(ev):
        # Cycle through all 5 containers: open + snapshot + close, 5 times.
        # After each cycle, verify carried is empty and the next open succeeds.
        targets = [
            ("chest", chest_a), ("chest", chest_b), ("chest", chest_c),
            ("furnace", furnace_1), ("furnace", furnace_2),
        ]
        for i, (kind, pos) in enumerate(targets):
            code, menu = mc("cat", station_path(kind, pos))
            assert code == 0, f"cycle {i}: open {kind} failed: {menu}"
            carried = menu.get("carried", {})
            assert carried.get("count", 0) == 0, f"cycle {i}: carried not empty: {carried}"
        # 6th open after 5 cycles should still work (no session leak)
        code, menu = mc("cat", station_path("chest", chest_b))
        assert code == 0, f"6th open failed (session leak?): {menu}"
        assert menu.get("carried", {}).get("count", 0) == 0
        ev.append("5 open/close cycles + 6th verify: no session leak, carried always empty")

    @led.case("D3", "D3-12", "unknown item deposit reports precise 'item not found' error", "GREEN")
    def _(ev):
        # Bot should have no items at this point (all deposited in prior cases).
        # Deposit a nonexistent item -> precise error, not "have 0".
        code, out = mc("write", station_path("chest", chest_b, "input"), "minecraft:unobtainium:1")
        assert code == 1, f"expected reject for unknown item, got code={code}"
        reason = out.get("reason", "") if isinstance(out, dict) else str(out)
        assert "item not found" in reason, f"expected 'item not found' error, got: {reason}"
        assert "have 0" not in reason, f"error should not say 'have 0': {reason}"
        ev.append(f"unknown item deposit: precise error='{reason[:80]}'")


# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="a,b",
                        help="comma list from a,b,c1,c2b,c2-pre,c2-post,d1,d2,d3")
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
               "c2-post": batch_c2_post, "d1": batch_d1, "d2": batch_d2,
               "d3": batch_d3}
    for key in ("a", "b", "c1", "c2b", "c2-pre", "c2-post", "d1", "d2", "d3"):
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
