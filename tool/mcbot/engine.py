"""GameTest engine receipts.

After every ``runGameTest`` run, ``write_engine_receipt`` parses the
log into a machine-readable JSON file under ``qa-results/engine-runs/``.
The receipt is the currency record the status panel and the H-R5
discipline read: ledger /claims of engine verification cite it
instead of prose.
"""
from __future__ import annotations

import datetime as _dt
import json
import re
import subprocess
from pathlib import Path
from typing import Optional

from mcbot.paths import PROJECT_ROOT

ENGINE_RUN_DIR = PROJECT_ROOT / "qa-results" / "engine-runs"


def _git_head_short() -> Optional[str]:
    """Best-effort current commit id; None outside a repo."""
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=str(PROJECT_ROOT), capture_output=True, text=True, timeout=10,
        )
        return out.stdout.strip() if out.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def write_engine_receipt(log_path: Path) -> Optional[Path]:
    """Parse a finished runGameTest log into a machine-readable
    receipt (scenario totals, failures, git rev) under
    qa-results/engine-runs/. The receipt is the currency record the
    status panel and the H-R5 discipline read: ledger /claims of
    engine verification cite it instead of prose. Returns the
    receipt path, or None when the log holds no verdict (crash,
    interrupt, wrong task)."""
    try:
        text = log_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    m_total = re.search(r"(\d+) GAME TESTS COMPLETE", text)
    if not m_total:
        return None
    total = int(m_total.group(1))
    m_fail = re.search(r"(\d+) required tests failed", text)
    failed_count = int(m_fail.group(1)) if m_fail else 0
    failed = re.findall(r"^\s*- ([a-z0-9_]+)\s*$", text, re.M)
    if len(failed) < failed_count:
        failed = failed[:failed_count] if failed else []
    receipt = {
        "schema": 1,
        "task": "runGameTest",
        "log": str(log_path),
        "git_rev": _git_head_short(),
        "finished_at": _dt.datetime.now().isoformat(timespec="seconds"),
        "scenarios_total": total,
        "failed_count": failed_count,
        "failed": failed,
        "green": failed_count == 0,
    }
    ENGINE_RUN_DIR.mkdir(parents=True, exist_ok=True)
    stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = ENGINE_RUN_DIR / f"gametest-{stamp}.json"
    try:
        path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"[mcbot] engine receipt: {path} (total={total}, failed={failed_count})")
    except OSError as e:
        print(f"[mcbot] WARN: cannot write engine receipt: {e}", file=sys.stderr)
        return None
    return path


def engine_currency_line() -> Optional[str]:
    """One status line: how stale the newest engine receipt is
    relative to commits that touched the engine-verified layers
    (gametest + adapter). None when no receipt exists yet."""
    try:
        receipts = sorted(ENGINE_RUN_DIR.glob("gametest-*.json"))
    except OSError:
        return None
    if not receipts:
        return None
    try:
        data = json.loads(receipts[-1].read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    rev = data.get("git_rev")
    if not rev:
        return None
    try:
        out = subprocess.run(
            ["git", "log", "--oneline", f"{rev}..HEAD", "--",
             "src/main/java/com/mcbot/mcbotserver/gametest",
             "src/main/java/com/mcbot/mcbotserver/adapter"],
            cwd=str(PROJECT_ROOT), capture_output=True, text=True, timeout=10,
        )
        stale = [l for l in out.stdout.splitlines() if l.strip()]
    except (OSError, subprocess.SubprocessError):
        stale = []
    base = (
        f"engine: {data.get('scenarios_total')} scenarios, receipt {receipts[-1].name} "
        f"@{rev} ({'GREEN' if data.get('green') else 'RED'}), {len(stale)} later commits touched adapter/gametest"
    )
    return base + ("" if not stale else "  <- H-R5: rerun `build runGameTest` before verified claims")
