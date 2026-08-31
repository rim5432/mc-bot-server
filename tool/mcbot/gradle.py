"""Gradle discovery and hang-proof execution.

``find_gradle`` implements the $MCBOT_GRADLE > wrapper-dist > PATH
resolution chain. ``run_gradle`` streams the child's stdout to both
the console and a timestamped log file so a full pipe buffer can
never block the child.
"""
from __future__ import annotations

import glob
import os
import re
import subprocess
import sys
from datetime import datetime as _dt
from pathlib import Path
from typing import Optional

from mcbot.capability.receipt import record_test_run
from mcbot.engine import write_engine_receipt
from mcbot.paths import LAST_LOG, PROJECT_ROOT, RUNTIME_DIR, ensure_runtime_dir

# ---------------------------------------------------------------------------
# gradle discovery
# ---------------------------------------------------------------------------
_VERSION_RE = re.compile(r"distributionUrl\s*=.*?gradle-([\d.]+)-")


def read_wrapper_version() -> Optional[str]:
    p = PROJECT_ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"
    if not p.exists():
        return None
    try:
        for line in p.read_text(encoding="utf-8").splitlines():
            m = _VERSION_RE.search(line)
            if m:
                return m.group(1)
    except OSError:
        pass
    return None


def find_gradle() -> Optional[Path]:
    """Locate gradle.bat / gradle sh.

    Priority: $MCBOT_GRADLE > ~/.gradle/wrapper/dists/<ver>-bin/<hash>/<ver>/bin/gradle.* > $PATH
    """
    # 1) env
    env = os.environ.get("MCBOT_GRADLE", "").strip()
    if env:
        cand = Path(env)
        if cand.exists():
            return cand
    # 2) wrapper distribution
    ver = read_wrapper_version()
    if ver:
        user_home = Path(os.environ.get("USERPROFILE") or str(Path.home()))
        wrapper_root = user_home / ".gradle" / "wrapper" / "dists" / f"gradle-{ver}-bin"
        if wrapper_root.exists():
            matches = glob.glob(
                str(wrapper_root / "*" / f"gradle-{ver}" / "bin" / ("gradle.bat" if os.name == "nt" else "gradle"))
            )
            if matches:
                return Path(matches[0])
    # 3) PATH
    exe = "gradle.bat" if os.name == "nt" else "gradle"
    for d in os.environ.get("PATH", "").split(os.pathsep):
        cand = Path(d) / exe
        if cand.exists():
            return cand
    return None


def run_gradle(
    gradle_path: Path,
    args: list,
    *,
    no_daemon: bool = False,
    with_cc: bool = False,
    log_name: Optional[str] = None,
) -> int:
    ensure_runtime_dir()
    ts = _dt.now().strftime("%Y%m%d-%H%M%S")
    # log_name = the user-facing subcommand name (compile, jar, runClient, ...).
    # Falls back to the first gradle arg (compileJava, test, ...) so the file is
    # still meaningful if log_name isn't passed.
    task_name = log_name or (args[0] if args else "gradle")
    log_path = RUNTIME_DIR / f"build-{task_name}-{ts}.log"

    cmd = [str(gradle_path), *args, "--console=plain"]
    if no_daemon:
        cmd.append("--no-daemon")
    if with_cc:
        cmd.append("--configuration-cache")

    env = os.environ.copy()
    env.setdefault("PYTHONIOENCODING", "utf-8")
    # ensure UTF-8 in java child too (so gradle's console encoding matches)
    env.setdefault("JAVA_TOOL_OPTIONS", "")

    header = (
        f"# mcbot_tool build log\n"
        f"# task    : {' '.join(args)}\n"
        f"# cmd     : {' '.join(cmd)}\n"
        f"# cwd     : {PROJECT_ROOT}\n"
        f"# start   : {ts}\n"
        f"# pid     : {os.getpid()}\n"
        f"# gradle  : {gradle_path}\n"
        f"# {('-' * 70)}\n"
    )
    try:
        LAST_LOG.write_text(str(log_path), encoding="utf-8")
    except OSError as e:
        print(f"[mcbot] WARN: cannot write build-last.log: {e}", file=sys.stderr)

    print(f"[mcbot] $ {' '.join(cmd)}")
    print(f"[mcbot] log: {log_path}")
    sys.stdout.flush()

    try:
        logf = open(log_path, "w", encoding="utf-8", newline="\n")
    except OSError as e:
        print(f"[mcbot] FATAL: cannot open log file: {e}", file=sys.stderr)
        return 2

    logf.write(header)
    logf.flush()

    try:
        proc = subprocess.Popen(
            cmd,
            cwd=str(PROJECT_ROOT),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            encoding="utf-8",
            errors="replace",
            env=env,
        )
    except OSError as e:
        logf.close()
        print(f"[mcbot] FATAL: failed to start gradle: {e}", file=sys.stderr)
        return 127

    try:
        for line in proc.stdout:  # type: ignore[union-attr]
            sys.stdout.write(line)
            sys.stdout.flush()
            logf.write(line)
            logf.flush()
    except KeyboardInterrupt:
        print("\n[mcbot] KeyboardInterrupt — terminating gradle", file=sys.stderr)
        try:
            proc.terminate()
        except OSError:
            pass
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
        logf.write("\n# KeyboardInterrupt: terminated\n")
        logf.close()
        return 130
    finally:
        try:
            logf.close()
        except OSError:
            pass

    proc.wait()
    rc = proc.returncode
    if task_name == "runGameTest":
        try:
            receipt_path = write_engine_receipt(log_path)
        except Exception as e:  # receipt is telemetry, never fatal
            print(f"[mcbot] WARN: engine receipt failed: {e}", file=sys.stderr)
            receipt_path = None
        try:
            record_test_run(log_path, receipt_path=receipt_path)
        except Exception as e:  # DB receipt is telemetry, never fatal
            print(f"[mcbot] WARN: test receipt DB write failed: {e}", file=sys.stderr)
    if rc != 0:
        print(f"[mcbot] gradle exit code: {rc}  (full log: {log_path})", file=sys.stderr)
        # Auto-tail last 30 error lines for fast diagnosis
        print(f"[mcbot] --- last 30 lines of log ---", file=sys.stderr)
        try:
            with open(log_path, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
            for ln in lines[-30:]:
                print(ln.rstrip(), file=sys.stderr)
        except OSError:
            pass
        print(f"[mcbot] --- run `python tool/mcbot_tool.py log cat {task_name}` for full log ---", file=sys.stderr)
    return rc


def _resolve_gradle() -> Optional[Path]:
    g = find_gradle()
    if not g:
        print(
            "[mcbot] gradle not found. Set $MCBOT_GRADLE to point at a gradle.bat, "
            "or run `gradle wrapper` from a working install.",
            file=sys.stderr,
        )
        return None
    return g
