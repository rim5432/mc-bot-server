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
        tid = submit_goto(f"{int(pos['x']) + 30},{int(pos['y'])},{int(pos['z'])}",
                          ev, tol="1", timeout="4000")
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
    print("Batch C1: entity-ticking ticket gap")

    @led.case("C", "C1", "unticketed chunk freezes the body; forceload wakes it",
              "RED")
    def _(ev):
        # Park the body mid-air far outside any spawn-chunk ticket. A
        # ticking chunk would make it fall within seconds, so the y
        # read after the settle window doubles as a liveness probe.
        tp = rcon("tp @e[type=mcbotserver:bot_body,limit=1] 350.5 120 350.5")
        ev.append(f"tp: {tp[:120]}")
        assert "Teleported" in tp, \
            f"tp missed - body not selectable (unloaded chunk?): {tp}"
        time.sleep(3)
        _, park = mc("cat", "/player/pos")
        ev.append(f"parked at {json.dumps(park)[:120]}")
        assert park[1] == 120, f"body already moved: {park}"
        time.sleep(30)
        _, settle = mc("cat", "/player/pos")
        ev.append(f"after 30s settle: {json.dumps(settle)[:120]}")
        assert settle[1] >= 119, \
            f"body fell - chunk still entity-ticking: {settle}"

        # Frozen body proven. Now prove the documented unblock path:
        # forceload (BLOCK coords; 336,336 sits inside chunk 21,21).
        load = rcon("forceload add 336 336")
        ev.append(f"forceload: {load[:120]}")
        fell = False
        for _ in range(15):
            time.sleep(1)
            _, live = mc("cat", "/player/pos")
            if live[1] < 119:
                fell = True
                break
        rcon("forceload remove all")
        assert fell, "even forceload did not wake the body"
        ev.append(f"awake at {live}")
        ev.append("gap pinned: body frozen until forceload, queue alive")

    @led.case("C", "C1b", "goto against a frozen body fails honestly, no hang",
              "GREEN")
    def _(ev):
        # The consumer-facing shape of the same gap: the mission
        # burns its budget and returns a terminal verdict while the
        # body never moves - the harness gets an honest failure, not
        # a hang, and the receipt pipeline stays usable.
        tp = rcon("tp @e[type=mcbotserver:bot_body,limit=1] 360.5 120 360.5")
        ev.append(f"tp: {tp[:120]}")
        assert "Teleported" in tp, f"tp missed: {tp}"
        time.sleep(30)
        _, frozen = mc("cat", "/player/pos")
        ev.append(f"frozen at {json.dumps(frozen)[:120]}")
        assert frozen[1] >= 119, "setup failed: chunk is ticking"
        code, out = mc("write", "/tasks/goto", "366,120,360",
                       "--tol", "1", "--timeout", "200")
        assert code == 0 and out.get("task"), f"submit failed: {out}"
        tid = out["task"]
        code, verdict = mc("wait", tid, "--timeout", "60")
        _, after = mc("cat", "/player/pos")
        assert code == 1, f"wait exit {code}, expected 1: {verdict}"
        assert "TASK_FAILED" in json.dumps(verdict), f"verdict: {verdict}"
        assert after[1] >= 119 and abs(after[0] - 360) < 1, \
            f"body moved while frozen: {after}"
        ev.append(f"honest failure pinned: {json.dumps(verdict)[:160]}")


# ---------------------------------------------------------------------------
# Batch C2 - resetAt epoch honesty across a JVM restart (expected RED)
# ---------------------------------------------------------------------------

def read_reset_at(ev: list[str]) -> int:
    _, out = mc("events")
    epoch = out["batch"]["resetAt"]
    ev.append(f"resetAt={epoch}")
    return int(epoch)


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

    @led.case("C", "C2-post", "resetAt after restart is beyond pre-restart head",
              "RED")
    def _(ev):
        state = json.loads(STATE.read_text())
        before = int(state["reset_at_before"])
        after = read_reset_at(ev)
        assert after <= before, \
            f"epoch is honest (before={before} after={after}) - gap closed?"
        ev.append(f"beyond-head violated: before={before} after={after}")


# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="a,b",
                        help="comma list from a,b,c1,c2-pre,c2-post")
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
    rcon("botspawn")
    time.sleep(3)

    led = Ledger()
    runners = {"a": batch_a, "b": batch_b, "c1": batch_c1,
               "c2-pre": batch_c2_pre, "c2-post": batch_c2_post}
    for key in ("a", "b", "c1", "c2-pre", "c2-post"):
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
