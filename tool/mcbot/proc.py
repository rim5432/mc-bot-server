"""Java/Gradle process census.

``list_gradle_processes`` returns every JVM process whose command
line mentions gradle (or whose comm is java/gradle). Used by
``status`` and ``proc killdaemon``.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys


def list_gradle_processes() -> list:
    """Return list of dicts: pid, ws_mb, desc."""
    if os.name != "nt":
        # Linux/mac: parse `ps`
        try:
            out = subprocess.check_output(
                ["ps", "-A", "-o", "pid=,rss=,comm=,args="],
                text=True, timeout=5,
            )
        except Exception:
            return []
        results = []
        for line in out.splitlines():
            parts = line.strip().split(None, 3)
            if len(parts) < 4:
                continue
            try:
                pid = int(parts[0])
                rss_kb = int(parts[1])
            except ValueError:
                continue
            desc = parts[3]
            if "gradle" in desc.lower() or parts[2].lower() in ("java", "gradle"):
                results.append({"pid": pid, "ws_mb": rss_kb / 1024.0, "desc": desc[:160]})
        return results
    # Windows: PowerShell CIM
    try:
        cmd = [
            "powershell", "-NoProfile", "-NonInteractive", "-Command",
            "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
            "Select-Object ProcessId,WorkingSetSize,CommandLine | "
            "ConvertTo-Json -Depth 1 -Compress",
        ]
        out = subprocess.check_output(cmd, text=True, timeout=10)
        data = json.loads(out) if out.strip() else []
        if isinstance(data, dict):
            data = [data]
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, json.JSONDecodeError) as e:
        print(f"[mcbot] proc list failed: {e}", file=sys.stderr)
        return []
    results = []
    for d in data:
        pid = d.get("ProcessId")
        ws = d.get("WorkingSetSize") or 0
        cl = (d.get("CommandLine") or "")[:160]
        results.append({"pid": pid, "ws_mb": ws / 1_000_000.0, "desc": cl})
    return results
