#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patrol - the first skill written natively in mc syntax.

Drives the bot through a walking patrol using ONLY the mc CLI path
namespace (issue 0012 D4) - zero raw RCON, zero wire spelling:

    mc cat /player/pos        pre-flight position read
    mc write /tasks/goto      submit each patrol leg
    mc wait <taskId>          block until the leg reaches a terminal
    mc cat /tasks/<taskId>    post-hoc state by id (must agree)
    mc events                 audit drain when the patrol ends

A leg is only a success when wait reports TASK_COMPLETED and the
derived cat agrees. Any other terminal kind prints its reason and
fails the patrol - the skill never retries or corrects on its own
(translation-layer transparency is a CLI property; skills get to be
opinionated but must report honestly).

Default route rides the dev-world platform and runway (issue 0012
shadow-comparison prep: stone at spawn y=60, runway north on x=3).
Override with CLI args: patrol.py x,y,z[,tol[,timeout]] ...

Exit codes: 0 patrol complete; 1 a leg failed or was rejected;
3 environment error (rcon unreachable).
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

MC = Path(__file__).resolve().parent.parent / "tool" / "harness" / "mc.py"

# Platform is -3..3 on x/z at y=60 (surface 61); runway is x=3,
# z 4..130. Legs stay on stone the whole way.
DEFAULT_LEGS = [
    ("3,61,3", 1, 600),
    ("0,61,0", 1, 600),
    ("3,61,30", 1, 900),
    ("3,61,3", 1, 900),
]


def mc(*args: str) -> tuple[int, dict, str]:
    """Run one mc verb; return (exit_code, parsed_stdout_json, stderr).

    stdout of every mc verb is a single JSON document (write echoes
    the wire receipt, wait the terminal event, cat the derived state).
    """
    proc = subprocess.run([sys.executable, str(MC), *args],
                          capture_output=True, text=True)
    try:
        payload = json.loads(proc.stdout)
    except json.JSONDecodeError:
        payload = {"raw": proc.stdout.strip()}
    return proc.returncode, payload, proc.stderr.strip()


def main() -> int:
    legs = DEFAULT_LEGS
    if len(sys.argv) > 1:
        legs = []
        for spec in sys.argv[1:]:
            parts = spec.split(",")
            target = ",".join(parts[0:3])
            tol = int(parts[3]) if len(parts) > 3 else 1
            timeout = int(parts[4]) if len(parts) > 4 else 600
            legs.append((target, tol, timeout))

    rc, pos, _ = mc("cat", "/player/pos")
    if rc != 0:
        print(f"patrol: cannot read bot position (mc cat rc={rc})",
              file=sys.stderr)
        return 3
    # /player/pos replies with a bare JSON array ([x, y, z]).
    start = pos if isinstance(pos, list) else pos.get("pos", pos)
    print(f"patrol: start at {start}")

    completed = 0
    for i, (target, tol, timeout) in enumerate(legs, 1):
        rc, receipt, err = mc("write", "/tasks/goto", target,
                              "--tol", str(tol), "--timeout", str(timeout))
        task_id = receipt.get("task")
        if rc != 0 or not receipt.get("ok") or not task_id:
            print(f"leg {i} -> {target}: REJECTED "
                  f"{json.dumps(receipt)} {err}", file=sys.stderr)
            return 1

        rc, terminal, _ = mc("wait", task_id, "--timeout",
                             str(max(30, timeout // 10)))
        kind = terminal.get("kind", "NO_TERMINAL")
        attrs = terminal.get("attrs", {})
        if rc != 0 or kind != "TASK_COMPLETED":
            print(f"leg {i} -> {target}: {kind} reason={attrs.get('reason')}",
                  file=sys.stderr)
            return 1

        rc, state, _ = mc("cat", f"/tasks/{task_id}")
        if rc != 0 or state.get("status") != "completed":
            print(f"leg {i} -> {target}: derived state disagrees "
                  f"({state.get('status')} vs {kind})", file=sys.stderr)
            return 1
        completed += 1
        print(f"leg {i}: {target} {kind} ({task_id})")

    rc, _, err = mc("events")
    if rc != 0:
        print(f"patrol: events drain failed: {err}", file=sys.stderr)
    print(f"patrol: {completed}/{len(legs)} legs complete, audit drained")
    return 0


if __name__ == "__main__":
    sys.exit(main())
