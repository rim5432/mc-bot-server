#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mcbot_tool.py — mc-bot-server 开发流程集中工具
================================================

agent / 人都用这套 CLI 跑 build / test / 看日志 / 管进程 / 协调并发。
不要再直接 `./gradlew ...` —— 那条路你之前已经踩过坑了。

设计要点：
  1. **hang-proof log**：用 Popen + 实时 readline 双写 console + 文件，
     gradle 跟 console 真实同步，不会因为 pipe buffer 满阻塞。
  2. **跨进程文件锁**（`tool/.runtime/<name>.lock`）：写 build/ 的任务
     （compile/test/jar/build/clean/sync）共用全局 `build` 锁；每个长跑
     任务（runClient / runServer / runGameTest / runData）持独立
     `run.<task>` 锁——专用服和客户端可同时存活（它们只读构建产物）。
     同一命名空间的第二个调用立刻 fail-fast 而不是悄悄抢资源。
  3. **stale lock takeover**：如果上家 PID 已死，自动接管或提示清掉；
     `lock status/clear/takeover` 遍历所有命名空间。
  4. **gradle 自动发现**：优先 $MCBOT_GRADLE > 本机 wrapper dist > PATH。
  5. **build / test 默认 --no-daemon**：避免 daemon 残留和锁冲突；
     跑 game / runClient / runServer 之类当然也不 daemon。
  6. **零外部依赖**：只 stdlib，跨 Windows / mac / Linux。

用法：见 `python tool/mcbot_tool.py --help` 或 tool/README.md。
"""
from __future__ import annotations

import argparse
import ctypes
import ctypes.wintypes
import datetime as _dt
import glob
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Optional

# ---------------------------------------------------------------------------
# paths
# ---------------------------------------------------------------------------
TOOL_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = TOOL_DIR.parent
RUNTIME_DIR = TOOL_DIR / ".runtime"
def _lock_paths(name: str = "build") -> tuple:
    """Lock + meta file pair for one lock namespace.

    ``build`` is the global namespace for anything that writes
    build/ outputs; each long-running game task gets its own
    ``run.<task>`` namespace so a server and a client can coexist.
    """
    return RUNTIME_DIR / f"{name}.lock", RUNTIME_DIR / f"{name}.lock.meta.json"
LAST_LOG = RUNTIME_DIR / "build-last.log"

# ---------------------------------------------------------------------------
# gradle task -> command mapping
# ---------------------------------------------------------------------------
COMPILE_JAVA = ["compileJava"]
JAR_TASK = ["jar"]
BUILD_TASK = ["build"]
CLEAN_TASK = ["clean"]
SYNC_TASK = ["idea"]  # legacyforge + java-library 都有这个
TEST_TASK = ["test"]
TASKS_ALL = ["tasks", "--all"]
DEPS_TASK = ["dependencies"]

RUN_CLIENT = ["runClient"]
# --nogui 不能走命令行：moddev 的 run 任务不认这个选项，
# build.gradle 的 server 块已用 programArgument 注入
RUN_SERVER = ["runServer"]
RUN_DATA = ["runData"]
RUN_GAMETEST = ["runGameTestServer"]

# canonical -Plint dashboard round; mirrors the invocation documented
# in doc/guide/build-and-run.md - change both in the same commit
LINT_TASKS = ["qualityCheck", "-Plint", "--continue"]

# 这些 task 跑时强制 --no-daemon（防 daemon 残留 / 锁冲突）
NEEDS_NO_DAEMON = {
    tuple(COMPILE_JAVA), tuple(JAR_TASK), tuple(BUILD_TASK), tuple(CLEAN_TASK),
    tuple(TEST_TASK), tuple(RUN_CLIENT), tuple(RUN_SERVER),
    tuple(RUN_DATA), tuple(RUN_GAMETEST),
}

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


# ---------------------------------------------------------------------------
# runtime dir
# ---------------------------------------------------------------------------
def ensure_runtime_dir() -> None:
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)


# ---------------------------------------------------------------------------
# PID alive check (zero external dep)
# ---------------------------------------------------------------------------
def _pid_alive(pid: Optional[int]) -> bool:
    if not pid or pid <= 0:
        return False
    if os.name == "nt":
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        STILL_ACTIVE = 259
        try:
            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        except OSError:
            return False
        h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not h:
            err = ctypes.get_last_error()
            # 5 = ERROR_ACCESS_DENIED (process exists but no perm)
            # 87 = ERROR_INVALID_PARAMETER (no such PID)
            return err == 5
        try:
            code = ctypes.wintypes.DWORD()
            ok = kernel32.GetExitCodeProcess(h, ctypes.byref(code))
            return bool(ok) and code.value == STILL_ACTIVE
        finally:
            kernel32.CloseHandle(h)
    else:
        try:
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            return True


# ---------------------------------------------------------------------------
# file lock
# ---------------------------------------------------------------------------
def _read_meta(name: str = "build") -> Optional[dict]:
    _, meta_path = _lock_paths(name)
    if not meta_path.exists():
        return None
    try:
        return json.loads(meta_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _force_clear_lock(name: str = "build") -> None:
    lock_path, meta_path = _lock_paths(name)
    for p in (lock_path, meta_path):
        try:
            p.unlink()
        except OSError:
            pass


# ---------------------------------------------------------------------------
# file lock
# ---------------------------------------------------------------------------
# Cross-process lock via atomic exclusive file create (`os.open(O_CREAT|O_EXCL)`).
# Why not msvcrt.locking / fcntl.flock? Both are advisory locks and Windows
# `LockFile` is per-process — two threads in the same process can each "hold"
# it. O_CREAT|O_EXCL is atomic on both POSIX and NTFS and works across
# processes AND threads.
_INTRA_LOCK = threading.Lock()


class BuildLock:
    """Non-blocking cross-process build lock.

    Lock files are namespaced: the default ``build`` lock serializes
    everything that writes build/ outputs (compile / test / jar ...),
    while each long-running game task owns its own
    ``run.<task>.lock`` so a dedicated server and a dev client can be
    alive at the same time - they only read build outputs.

    Usage:
        with BuildLock("run.runServer") as lock:
            if not lock.acquire("build runServer"):
                return _print_busy("run.runServer")
            ... do work ...
    """

    def __init__(self, name: str = "build") -> None:
        ensure_runtime_dir()
        self._name = name
        self._lock_path, self._meta_path = _lock_paths(name)
        self._fd: Optional[int] = None
        self._acquired = False

    def acquire(self, command: str) -> bool:
        # Stale check first (cheap, no side effects)
        meta = _read_meta(self._name)
        if meta:
            holder_pid = meta.get("pid")
            if holder_pid and holder_pid != os.getpid():
                if not _pid_alive(holder_pid):
                    print(
                        f"[mcbot] stale {self._name} lock from dead PID {holder_pid} "
                        f"({meta.get('command')}, started {meta.get('start_iso')}) — auto-takeover",
                        file=sys.stderr,
                    )
                    _force_clear_lock(self._name)
                    # fall through to atomic create
                else:
                    return False
            elif holder_pid == os.getpid():
                # Re-entrant from same process — already own it
                self._acquired = True
                return True

        # Atomic exclusive create. If the file already exists, it's because
        # another process (or thread that beat us through stale check) is
        # holding it — treat as busy. Do NOT unlink here, since the file may
        # exist legitimately during a holder's meta-write race window.
        with _INTRA_LOCK:
            try:
                fd = os.open(
                    str(self._lock_path),
                    os.O_CREAT | os.O_EXCL | os.O_RDWR,
                    0o644,
                )
            except FileExistsError:
                # Race: another process created the file in the tiny window
                # between our stale-check and the open call. Busy.
                return False
            except PermissionError:
                # Windows: ERROR_SHARING_VIOLATION when another process has
                # the lock file open. Treat as busy.
                return False
            except OSError as e:
                print(f"[mcbot] WARN: lock open failed: {e}", file=sys.stderr)
                return False

        # Got the lock — write PID into the file (handy for debugging)
        try:
            os.write(fd, str(os.getpid()).encode("utf-8"))
        except OSError:
            pass

        self._fd = fd
        self._acquired = True
        meta = {
            "pid": os.getpid(),
            "command": command,
            "start_iso": _dt.datetime.now().isoformat(timespec="seconds"),
            "cwd": str(PROJECT_ROOT),
        }
        try:
            self._meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")
        except OSError as e:
            print(f"[mcbot] WARN: failed to write lock meta: {e}", file=sys.stderr)
        return True

    def release(self) -> None:
        if not self._acquired:
            return
        try:
            if self._fd is not None:
                os.close(self._fd)
        except OSError:
            pass
        self._fd = None
        # Only remove lock file if it's still ours (check PID written in it)
        try:
            if self._lock_path.exists():
                with open(self._lock_path, "rb") as f:
                    data = f.read().decode("utf-8", errors="replace").strip()
                if data == str(os.getpid()):
                    self._lock_path.unlink()
        except OSError:
            pass
        # Same for meta
        meta = _read_meta(self._name)
        if meta and meta.get("pid") == os.getpid():
            try:
                self._meta_path.unlink()
            except OSError:
                pass
        self._acquired = False

    def __enter__(self) -> "BuildLock":
        return self

    def __exit__(self, *exc) -> None:
        self.release()


def lock_status(name: str = "build") -> dict:
    _, meta_path = _lock_paths(name)
    if not meta_path.exists():
        return {"locked": False, "name": name}
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"locked": False, "name": name}
    pid = meta.get("pid")
    return {
        "locked": True,
        "alive": _pid_alive(pid) if pid else False,
        "name": name,
        **meta,
    }


def all_locks() -> list:
    """Every lock namespace currently present on disk, global first."""
    names = sorted(p.name[: -len(".lock")] for p in RUNTIME_DIR.glob("*.lock"))
    ordered = [n for n in ["build"] if n in names] + [
        n for n in names if n != "build"
    ]
    return [lock_status(n) for n in ordered]


# ---------------------------------------------------------------------------
# subprocess + hang-proof log
# ---------------------------------------------------------------------------
def run_gradle(
    gradle_path: Path,
    args: list,
    *,
    no_daemon: bool = False,
    with_cc: bool = False,
    log_name: Optional[str] = None,
) -> int:
    ensure_runtime_dir()
    ts = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
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


# ---------------------------------------------------------------------------
# process list
# ---------------------------------------------------------------------------
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


# ---------------------------------------------------------------------------
# commands
# ---------------------------------------------------------------------------
def _print_busy(lock_name: str = "build") -> int:
    s = lock_status(lock_name)
    if not s.get("locked"):
        return 1
    pid = s.get("pid")
    alive = s.get("alive", False)
    print(f"[mcbot] BUSY: '{lock_name}' lock is held by another run.", file=sys.stderr)
    print(f"  holder pid : {pid}  ({'alive' if alive else 'dead'})", file=sys.stderr)
    print(f"  command    : {s.get('command')}", file=sys.stderr)
    print(f"  started    : {s.get('start_iso')}", file=sys.stderr)
    if alive:
        print("  [hint] wait for it to finish, or run `lock clear` if you know it's stale.", file=sys.stderr)
    else:
        print("  [hint] holder is dead — re-run to auto-takeover, or run `lock clear`.", file=sys.stderr)
    return 1


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


def cmd_build(args) -> int:
    g = _resolve_gradle()
    if not g:
        return 2
    sub = args.subcommand
    gradle_args_map = {
        "compile": COMPILE_JAVA,
        "jar": JAR_TASK,
        "build": BUILD_TASK,
        "runClient": RUN_CLIENT,
        "runServer": RUN_SERVER,
        "runData": RUN_DATA,
        "runGameTest": RUN_GAMETEST,
        "clean": CLEAN_TASK,
        "sync": SYNC_TASK,
    }
    base = gradle_args_map.get(sub)
    if base is None:
        print(f"[mcbot] unknown build subcommand: {sub}", file=sys.stderr)
        return 2
    gradle_args = list(base) + list(args.passthrough or [])
    no_daemon = args.no_daemon or tuple(gradle_args[: len(base)]) in NEEDS_NO_DAEMON
    with_cc = args.cc or os.environ.get("MCBOT_CC") == "1"
    command = f"build {sub}"
    # Long-running game tasks only READ build outputs, so each gets
    # its own lock namespace: a dedicated server and a dev client can
    # be alive at the same time. Anything that writes build/ still
    # serializes on the global "build" lock.
    if sub in {"runClient", "runServer", "runGameTest", "runData"}:
        lock_name = f"run.{sub}"
    else:
        lock_name = "build"
    with BuildLock(lock_name) as lock:
        if not lock.acquire(command):
            return _print_busy(lock_name)
        return run_gradle(g, gradle_args, no_daemon=no_daemon, with_cc=with_cc, log_name=sub)


def cmd_test(args) -> int:
    g = _resolve_gradle()
    if not g:
        return 2
    gradle_args = TEST_TASK + list(args.passthrough or [])
    with_cc = args.cc or os.environ.get("MCBOT_CC") == "1"
    with BuildLock() as lock:
        if not lock.acquire("test"):
            return _print_busy()
        return run_gradle(g, gradle_args, no_daemon=True, with_cc=with_cc, log_name="test")


def cmd_gradle(args) -> int:
    g = _resolve_gradle()
    if not g:
        return 2
    if not args.gradle_args:
        print("[mcbot] gradle passthrough: provide at least one arg", file=sys.stderr)
        return 2
    with_cc = args.cc or os.environ.get("MCBOT_CC") == "1"
    command = f"gradle {args.gradle_args[0]}"
    with BuildLock() as lock:
        if not lock.acquire(command):
            return _print_busy()
        return run_gradle(g, args.gradle_args, no_daemon=args.no_daemon, with_cc=with_cc)


def cmd_lint(args) -> int:
    g = _resolve_gradle()
    if not g:
        return 2
    gradle_args = LINT_TASKS + list(args.passthrough or [])
    with_cc = args.cc or os.environ.get("MCBOT_CC") == "1"
    with BuildLock() as lock:
        if not lock.acquire("lint"):
            return _print_busy()
        return run_gradle(g, gradle_args, no_daemon=args.no_daemon, with_cc=with_cc, log_name="lint")


def cmd_passthrough_no_lock(args) -> int:
    g = _resolve_gradle()
    if not g:
        return 2
    return run_gradle(g, args.gradle_args, no_daemon=args.no_daemon, with_cc=False)


def cmd_status(args) -> int:
    for s in all_locks():
        if s.get("locked"):
            print(
                f"lock[{s['name']}]: BUSY  pid={s.get('pid')} alive={s.get('alive')} "
                f"cmd={s.get('command')} since={s.get('start_iso')}"
            )
        else:
            print(f"lock[{s['name']}]: free")
    if LAST_LOG.exists():
        try:
            p = LAST_LOG.read_text(encoding="utf-8").strip()
            print(f"last log: {p}")
        except OSError:
            pass
    procs = list_gradle_processes()
    print(f"java/gradle processes: {len(procs)}")
    for p in procs[:30]:
        print(f"  pid={p['pid']:<8} ws_mb={p['ws_mb']:<8.0f} {p['desc'][:200]}")
    # doc health (cheap early-warning; full audit is `doc check`)
    if DOC_DIR.exists():
        try:
            results = run_doc_checks()
            n_err = sum(1 for r in results if _doc_status_word(r["issues"]) == "ERR")
            n_warn = sum(1 for r in results if _doc_status_word(r["issues"]) == "warn")
            hint = "" if (n_err or n_warn) == 0 else "  <- run: python tool/mcbot_tool.py doc check"
            print(f"docs: {len(results)} files, rot-errors={n_err}, warnings={n_warn}{hint}")
        except Exception:
            pass
    return 0


def cmd_log(args) -> int:
    if args.action == "tail":
        if not LAST_LOG.exists():
            print("[mcbot] no build-last.log pointer", file=sys.stderr)
            return 1
        try:
            p = LAST_LOG.read_text(encoding="utf-8").strip()
        except OSError as e:
            print(f"[mcbot] read last-log pointer failed: {e}", file=sys.stderr)
            return 1
        if not p or not Path(p).exists():
            print(f"[mcbot] last log not found: {p}", file=sys.stderr)
            return 1
        n = args.lines
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
        except OSError as e:
            print(f"[mcbot] read failed: {e}", file=sys.stderr)
            return 1
        for line in lines[-n:]:
            print(line, end="")
        if lines and not lines[-1].endswith("\n"):
            print()
        return 0

    if args.action == "list":
        if not RUNTIME_DIR.exists():
            print("[mcbot] no .runtime/ yet")
            return 0
        logs = sorted(
            RUNTIME_DIR.glob("build-*.log"),
            key=lambda q: q.stat().st_mtime,
            reverse=True,
        )
        for p in logs[:50]:
            sz = p.stat().st_size
            mt = _dt.datetime.fromtimestamp(p.stat().st_mtime).strftime("%Y-%m-%d %H:%M:%S")
            print(f"{mt}  {sz:>10}  {p.name}")
        return 0

    if args.action == "cat":
        if not args.task:
            print("[mcbot] need task name (e.g. `log cat compile`)", file=sys.stderr)
            return 2
        if not RUNTIME_DIR.exists():
            print(f"[mcbot] no log for task {args.task}", file=sys.stderr)
            return 1
        # Fuzzy: `log cat compile` should match both `build-compile-*.log`
        # and `build-compileJava-*.log`. The most recent wins.
        matches = sorted(
            RUNTIME_DIR.glob(f"build-*{args.task}*.log"),
            key=lambda q: q.stat().st_mtime,
            reverse=True,
        )
        if not matches:
            print(f"[mcbot] no log matching task {args.task}", file=sys.stderr)
            return 1
        path = matches[0]
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                sys.stdout.write(f.read())
        except OSError as e:
            print(f"[mcbot] read failed: {e}", file=sys.stderr)
            return 1
        return 0

    print(f"[mcbot] unknown log action: {args.action}", file=sys.stderr)
    return 2


def cmd_lock(args) -> int:
    if args.action == "status":
        for s in all_locks():
            if not s.get("locked"):
                print(f"lock[{s['name']}]: free")
                continue
            pid = s.get("pid")
            alive = s.get("alive", False)
            print(
                f"lock[{s['name']}]: BUSY  pid={pid} alive={alive} "
                f"cmd={s.get('command')} since={s.get('start_iso')}"
            )
            if not alive:
                print(f"  (holder is dead — run `lock clear` to remove {s['name']})")
        return 0
    if args.action == "clear":
        refused = False
        for s in all_locks():
            if not s.get("locked"):
                continue
            if s.get("alive"):
                print(
                    f"[mcbot] refusing to clear '{s['name']}': holder pid {s.get('pid')} is still alive",
                    file=sys.stderr,
                )
                refused = True
                continue
            _force_clear_lock(s["name"])
            print(f"lock[{s['name']}] cleared")
        return 1 if refused else 0
    if args.action == "takeover":
        refused = False
        for s in all_locks():
            if not s.get("locked"):
                continue
            if s.get("alive"):
                print(
                    f"[mcbot] refusing to takeover '{s['name']}': holder pid {s.get('pid')} is still alive",
                    file=sys.stderr,
                )
                refused = True
                continue
            _force_clear_lock(s["name"])
            print(f"lock[{s['name']}] taken over (previous holder was dead)")
        if not any(s.get("locked") for s in all_locks()) and not refused:
            print("no locks present, nothing to take over")
        return 1 if refused else 0
    print(f"[mcbot] unknown lock action: {args.action}", file=sys.stderr)
    return 2


def cmd_proc(args) -> int:
    if args.action == "list":
        procs = list_gradle_processes()
        for p in procs:
            print(f"pid={p['pid']}  ws_mb={p['ws_mb']:.0f}  {p['desc'][:200]}")
        return 0
    if args.action == "killdaemon":
        if not args.yes:
            print(
                "[mcbot] this will kill gradle daemons. re-run with --yes to confirm.",
                file=sys.stderr,
            )
            return 1
        procs = list_gradle_processes()
        targets = []
        for p in procs:
            d = p["desc"]
            if "GradleDaemon" in d or "gradle-launcher" in d or "GradleMain" in d:
                targets.append(p["pid"])
        if not targets:
            print("[mcbot] no gradle daemon found")
            return 0
        for pid in targets:
            print(f"  killing pid {pid}")
            try:
                if os.name == "nt":
                    subprocess.run(
                        ["taskkill", "/F", "/PID", str(pid)],
                        check=True,
                        capture_output=True,
                    )
                else:
                    os.kill(pid, 15)
            except Exception as e:
                print(f"  fail: {e}")
        return 0
    print(f"[mcbot] unknown proc action: {args.action}", file=sys.stderr)
    return 2


# ---------------------------------------------------------------------------
# doc management — documentation rot control
# ---------------------------------------------------------------------------
DOC_DIR = PROJECT_ROOT / "doc"
DOC_INDEX = DOC_DIR / "README.md"
DOC_CATEGORIES = ["architecture", "guide", "reference", "decisions", "archive"]
DEFAULT_STALE_DAYS = 90

_PLACEHOLDER_RE = re.compile(r"\b(TODO|TBD|FIXME|XXX)\b")
_MD_LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
# Repo paths quoted in backticks: files under src/tool/doc/gradle (must have an
# extension or trailing slash) plus a few root-level well-known files.
_DEADPATH_RE = re.compile(
    r"`((?:src|tool|doc|gradle)/(?:[\w./\-]+\.[A-Za-z0-9]+|[\w./\-]+/)|"
    r"(?:gradle\.properties|build\.gradle|settings\.gradle|gradlew(?:\.bat)?))`"
)
_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

_GIT_DATE_CACHE: dict = {}


def _read_gradle_props() -> dict:
    props = {}
    gp = PROJECT_ROOT / "gradle.properties"
    if gp.exists():
        for ln in gp.read_text(encoding="utf-8", errors="replace").splitlines():
            m = re.match(r"^([A-Za-z_]\w*)\s*=\s*(.*)$", ln)
            if m:
                props[m.group(1)] = m.group(2).strip()
    return props


def _git_last_commit_date(rel_posix: str) -> Optional[_dt.date]:
    """Last commit date touching a repo path; None if untracked/git missing."""
    if rel_posix in _GIT_DATE_CACHE:
        return _GIT_DATE_CACHE[rel_posix]
    result = None
    try:
        out = subprocess.run(
            ["git", "log", "-1", "--format=%cs", "--", rel_posix],
            cwd=str(PROJECT_ROOT), capture_output=True, text=True, timeout=10,
        )
        line = out.stdout.strip().splitlines()[0] if out.stdout.strip() else ""
        if _DATE_RE.match(line):
            result = _dt.date.fromisoformat(line)
    except Exception:
        result = None
    _GIT_DATE_CACHE[rel_posix] = result
    return result


def _parse_frontmatter(text: str):
    """Minimal YAML-ish subset parser. Returns (meta, body, error)."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return {}, text, "front-matter block missing (file must start with '---')"
    try:
        end = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
    except StopIteration:
        return {}, text, "front-matter not closed (no second '---')"
    meta: dict = {}
    current_key = None
    for ln in lines[1:end]:
        s = ln.strip()
        if not s or s.startswith("#"):
            continue
        m = re.match(r"^([A-Za-z][\w-]*):\s*(.*)$", ln)
        if m:
            current_key = m.group(1)
            val = m.group(2).strip().strip("'\"")
            meta[current_key] = [] if val == "[]" else val
        elif re.match(r"^\s+-\s+\S", ln) and current_key is not None:
            item = s.lstrip("- ").strip().strip("'\"")
            prev = meta.get(current_key)
            if isinstance(prev, list):
                prev.append(item)
            elif isinstance(prev, str) and prev:
                meta[current_key] = [prev, item]
            else:
                meta[current_key] = [item]
    return meta, "\n".join(lines[end + 1:]), None


def _strip_code_fences(body: str) -> str:
    out, in_fence = [], False
    for ln in body.splitlines():
        if ln.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if not in_fence:
            out.append(ln)
    return "\n".join(out)


def _iter_doc_files() -> list:
    if not DOC_DIR.exists():
        return []
    return sorted(p for p in DOC_DIR.rglob("*.md") if p != DOC_INDEX)


def check_doc(path: Path, stale_days: int = DEFAULT_STALE_DAYS) -> list:
    """Return list of issues: (level 'ERR'|'WARN', code, message)."""
    rel = path.relative_to(PROJECT_ROOT).as_posix()
    rel_doc = path.relative_to(DOC_DIR).as_posix()
    issues = []
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        return [("ERR", "DOC_READ", f"{rel}: {e}")]
    meta, body, err = _parse_frontmatter(text)
    if err:
        return [("ERR", "DOC_FRONTMATTER", f"{rel}: {err}")]

    in_archive = "archive" in path.relative_to(DOC_DIR).parts

    title = str(meta.get("title", "")).strip("'\"") if isinstance(meta.get("title"), str) else ""
    if not title:
        issues.append(("ERR", "DOC_FRONTMATTER", f"{rel}: front-matter 'title' missing"))

    lv_raw = str(meta.get("last_verified", "")).strip()
    verified = None
    if not _DATE_RE.match(lv_raw):
        issues.append(("ERR", "DOC_FRONTMATTER",
                       f"{rel}: front-matter 'last_verified' missing or not YYYY-MM-DD"))
    else:
        verified = _dt.date.fromisoformat(lv_raw)

    if in_archive:
        sup = str(meta.get("superseded_by", "")).strip("'\"")
        if not sup:
            issues.append(("ERR", "ARCHIVE_NO_SUCCESSOR",
                           f"{rel}: archived doc requires 'superseded_by:' front-matter"))
        elif not (DOC_DIR / sup.replace("\\", "/")).exists():
            issues.append(("ERR", "ARCHIVE_BAD_LINK",
                           f"{rel}: superseded_by target not found: {sup}"))
        # archived docs are exempt from age/drift checks by design
        return issues

    if "decisions" in rel_doc.split("/") and not re.match(r"\d{4}-", path.name):
        issues.append(("WARN", "ADR_NAMING", f"{rel}: ADR filename should start with NNNN-"))

    if verified:
        age_days = (_dt.date.today() - verified).days
        if age_days < 0:
            issues.append(("WARN", "DOC_FUTURE_DATE",
                           f"{rel}: last_verified {lv_raw} is in the future"))
        elif age_days > stale_days:
            issues.append(("WARN", "DOC_STALE",
                           f"{rel}: not verified for {age_days} days (> {stale_days}) "
                           f"— re-check content, then run `doc touch`"))

    covers = meta.get("covers", [])
    if isinstance(covers, str):
        covers = [covers] if covers else []
    for c in covers:
        c = c.strip("'\"").replace("\\", "/")
        target = PROJECT_ROOT / c
        if not target.exists():
            issues.append(("WARN", "DEAD_COVER", f"{rel}: covered path does not exist: {c}"))
            continue
        gdate = _git_last_commit_date(c)
        if verified and gdate and gdate > verified:
            issues.append((
                "ERR", "DOC_DRIFT",
                f"{rel}: covered '{c}' last changed {gdate.isoformat()}, after "
                f"last_verified {lv_raw} — doc content is likely rotten; fix it, then `doc touch`",
            ))

    plain = _strip_code_fences(body)
    for m in _MD_LINK_RE.finditer(plain):
        target = m.group(1).split("#")[0].strip()
        if not target or "://" in target or target.startswith("/"):
            continue
        if not (path.parent / target).exists():
            issues.append(("ERR", "BROKEN_LINK", f"{rel}: link target missing: {target}"))
    for m in _DEADPATH_RE.finditer(plain):
        cand = m.group(1)
        if not (PROJECT_ROOT / cand).exists():
            issues.append(("WARN", "DEAD_PATH", f"{rel}: referenced path does not exist: {cand}"))
    m = _PLACEHOLDER_RE.search(plain)
    if m:
        issues.append(("WARN", "PLACEHOLDER",
                       f"{rel}: placeholder marker '{m.group(1)}' still present"))
    return issues


def run_doc_checks(stale_days: int = DEFAULT_STALE_DAYS) -> list:
    results = []
    for p in _iter_doc_files():
        rel_doc = p.relative_to(DOC_DIR).as_posix()
        try:
            head = p.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            head = []
        t = ""
        for ln in head:
            mt = re.match(r"^title:\s*(.+)$", ln)
            if mt:
                t = mt.group(1).strip().strip("'\"")
                break
        lv = ""
        for ln in head:
            ml = re.match(r"^last_verified:\s*(\S+)", ln)
            if ml:
                lv = ml.group(1)
                break
        age = ""
        if _DATE_RE.match(lv):
            age = str((_dt.date.today() - _dt.date.fromisoformat(lv)).days)
        results.append({"path": rel_doc, "title": t, "verified": lv,
                        "age": age, "issues": check_doc(p, stale_days)})
    return results


def _doc_status_word(issues: list) -> str:
    if any(lv == "ERR" for lv, _, _ in issues):
        return "ERR"
    if issues:
        return "warn"
    return "ok"


def cmd_doc_list(args) -> int:
    if not DOC_DIR.exists():
        print("[mcbot] no doc/ directory yet", file=sys.stderr)
        return 1
    results = run_doc_checks()
    print(f"{'STATUS':<6} {'AGE(d)':>6}  {'VERIFIED':<10}  PATH")
    for r in results:
        print(f"{_doc_status_word(r['issues']):<6} {r['age']:>6}  {r['verified']:<10}  {r['path']}")
    errs = sum(1 for r in results if _doc_status_word(r["issues"]) == "ERR")
    warns = sum(1 for r in results if _doc_status_word(r["issues"]) == "warn")
    print(f"\n{len(results)} docs | ok={len(results)-errs-warns} warn={warns} err={errs}")
    return 0


def cmd_doc_check(args) -> int:
    props = _read_gradle_props()
    print(f"[mcbot] current: MC {props.get('minecraft_version', '?')} / "
          f"Forge {props.get('forge_version', '?')} / mod {props.get('mod_id', '?')} "
          f"{props.get('mod_version', '?')}")
    if not DOC_DIR.exists():
        print("[mcbot] no doc/ directory yet — nothing to check", file=sys.stderr)
        return 1
    results = run_doc_checks(args.stale_days)
    failed = 0
    for r in results:
        errs = [i for i in r["issues"] if i[0] == "ERR"]
        warns = [i for i in r["issues"] if i[0] == "WARN"]
        status = _doc_status_word(r["issues"])
        marker = {"ok": "OK  ", "warn": "WARN", "ERR": "ERR!"}[status]
        print(f"\n[{marker}] doc/{r['path']}  ({r['title'] or '?'}, verified {r['verified'] or '?'})")
        for _, code, msg in errs + warns:
            print(f"  - {code}: {msg}")
        if errs or (args.strict and warns):
            failed += 1
    n_err = sum(len([i for i in r["issues"] if i[0] == "ERR"]) for r in results)
    n_warn = sum(len([i for i in r["issues"] if i[0] == "WARN"]) for r in results)
    print(f"\n[mcbot] docs: {len(results)} checked | errors={n_err} | warnings={n_warn}")
    if failed:
        print(f"[mcbot] FAIL: {failed} doc(s) rotten — fix issues above, then "
              f"`doc touch <name>` after re-verifying content.", file=sys.stderr)
        return 1
    print("[mcbot] docs healthy")
    return 0


def _fuzzy_find_docs(fragment: str) -> list:
    frag = fragment.lower().strip("/")
    return [p for p in _iter_doc_files() if frag in p.relative_to(DOC_DIR).as_posix().lower()]


def cmd_doc_touch(args) -> int:
    hits = _fuzzy_find_docs(args.name)
    if not hits:
        print(f"[mcbot] no doc matching fragment: {args.name}", file=sys.stderr)
        return 1
    if len(hits) > 1:
        print("[mcbot] ambiguous match:", file=sys.stderr)
        for h in hits:
            print(f"  {h.relative_to(PROJECT_ROOT).as_posix()}", file=sys.stderr)
        return 2
    path = hits[0]
    today = _dt.date.today().isoformat()
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        print(f"[mcbot] read failed: {e}", file=sys.stderr)
        return 1
    new_text, n = re.subn(r"(?m)^last_verified:.*$", f"last_verified: {today}", text)
    if n == 0:
        lines = new_text.splitlines()
        try:
            close = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
            lines.insert(close, f"last_verified: {today}")
            new_text = "\n".join(lines) + "\n"
        except StopIteration:
            print(f"[mcbot] cannot touch: no closed front-matter in {path}", file=sys.stderr)
            return 1
    try:
        path.write_text(new_text, encoding="utf-8", newline="\n")
    except OSError as e:
        print(f"[mcbot] write failed: {e}", file=sys.stderr)
        return 1
    print(f"[mcbot] touched: {path.relative_to(PROJECT_ROOT).as_posix()} -> last_verified: {today}")
    return 0


def cmd_doc_new(args) -> int:
    slug = re.sub(r"[^a-z0-9\-_]+", "-", args.slug.lower()).strip("-")
    if not slug:
        print("[mcbot] invalid slug", file=sys.stderr)
        return 2
    if args.category == "decisions" and not re.match(r"\d{4}-", slug):
        existing = sorted(int(p.name[:4]) for p in (DOC_DIR / "decisions").glob("*.md")
                          if p.name[:4].isdigit()) if (DOC_DIR / "decisions").exists() else []
        nxt = (existing[-1] + 1) if existing else 1
        slug = f"{nxt:04d}-{slug}"
    cat_dir = DOC_DIR / args.category
    cat_dir.mkdir(parents=True, exist_ok=True)
    path = cat_dir / f"{slug}.md"
    if path.exists():
        print(f"[mcbot] refusing to overwrite existing doc: {path}", file=sys.stderr)
        return 1
    title = args.title or slug.replace("-", " ").title()
    today = _dt.date.today().isoformat()
    template = (
        "---\n"
        f"title: {title}\n"
        f"last_verified: {today}\n"
        "covers: []\n"
        "---\n"
        f"# {title}\n\n"
        "Write content here.\n\n"
        "`covers:` lists repo paths this document describes. When those paths change\n"
        "in git after `last_verified`, `doc check` flags the doc as DRIFT (rot).\n"
        "After updating the content, run `python tool/mcbot_tool.py doc touch {slug}`.\n"
    ).format(slug=slug)
    path.write_text(template, encoding="utf-8", newline="\n")
    print(f"[mcbot] created {path.relative_to(PROJECT_ROOT).as_posix()}")
    print(f"[mcbot] hint: regenerate the index with `doc index`")
    return 0


def cmd_doc_index(args) -> int:
    if not DOC_DIR.exists():
        print("[mcbot] no doc/ directory yet", file=sys.stderr)
        return 1
    results = run_doc_checks()
    by_cat: dict = {}
    for r in results:
        parts = r["path"].split("/", 1)
        cat = parts[0] if len(parts) > 1 else "(root)"
        by_cat.setdefault(cat, []).append(r)

    today = _dt.date.today().isoformat()
    out = [
        "<!-- AUTO-GENERATED by `python tool/mcbot_tool.py doc index` -- DO NOT EDIT -->",
        "# MC Bot Server - Documentation Index",
        "",
        f"Generated: {today}. Validate anytime with `python tool/mcbot_tool.py doc check`.",
        "",
    ]
    for cat in DOC_CATEGORIES:
        rows = by_cat.pop(cat, [])
        if not rows:
            continue
        out.append(f"## {cat}/")
        out.append("")
        out.append("| Document | Title | Verified | Status |")
        out.append("|---|---|---|---|")
        for r in rows:
            name = r["path"].split("/", 1)[1]
            status = {"ok": "ok", "warn": "stale?", "ERR": "**ROT**"}[_doc_status_word(r["issues"])]
            out.append(f"| [{name}]({cat}/{name}) | {r['title'] or '?'} | "
                       f"{r['verified'] or '?'} | {status} |")
        out.append("")
    for cat, rows in sorted(by_cat.items()):
        out.append(f"## {cat}/")
        out.append("")
        for r in rows:
            out.append(f"- [{r['path']}]({r['path']}) — {r['title'] or '?'}")
        out.append("")

    out += [
        "## Maintaining these documents",
        "",
        "- Every doc carries front-matter: `title`, `last_verified`, `covers:`.",
        "- `covers:` lists repo paths the doc describes. When git history shows those",
        "  paths changed *after* `last_verified`, `doc check` flags the doc as rotten.",
        "- Workflow after editing covered code: update the affected docs, run",
        "  `doc check`, then `doc touch <name>` on what you re-verified.",
        "- Superseded docs move to `archive/` with a `superseded_by:` pointer.",
        "- Hard constraints: root `AGENTS.md`; full tool table: tool/README.md.",
        "",
    ]
    DOC_INDEX.write_text("\n".join(out), encoding="utf-8", newline="\n")
    print(f"[mcbot] regenerated {DOC_INDEX.relative_to(PROJECT_ROOT).as_posix()} "
          f"({len(results)} docs)")
    return 0


def cmd_doc(args) -> int:
    dispatch = {
        "list": cmd_doc_list,
        "check": cmd_doc_check,
        "touch": cmd_doc_touch,
        "new": cmd_doc_new,
        "index": cmd_doc_index,
    }
    fn = dispatch.get(args.action)
    if fn is None:
        print(f"[mcbot] unknown doc action: {args.action}", file=sys.stderr)
        return 2
    return fn(args)


# ---------------------------------------------------------------------------
# argparse wiring
# ---------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser(
        prog="mcbot_tool",
        description=(
            "mc-bot-server dev workflow CLI. Use this instead of raw ./gradlew.\n"
            "All build/test/gradle subcommands share a cross-process file lock so "
            "multiple agents can run concurrently without stepping on each other."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = ap.add_subparsers(dest="cmd", required=True)

    # build
    p_build = sub.add_parser("build", help="build / jar / runXxx (with concurrency gate)")
    p_build.add_argument(
        "subcommand",
        choices=["compile", "jar", "build", "runClient", "runServer",
                 "runData", "runGameTest", "clean", "sync"],
    )
    p_build.add_argument("--no-daemon", action="store_true", help="force --no-daemon")
    p_build.add_argument("--cc", action="store_true", help="enable --configuration-cache")
    p_build.add_argument(
        "passthrough", nargs=argparse.REMAINDER,
        help="extra gradle args after `--`, e.g. `build compile -- --info --stacktrace`",
    )
    p_build.set_defaults(func=cmd_build)

    # test
    p_test = sub.add_parser("test", help="run tests (with concurrency gate)")
    p_test.add_argument("--cc", action="store_true")
    p_test.add_argument("passthrough", nargs=argparse.REMAINDER)
    p_test.set_defaults(func=cmd_test)

    # gradle passthrough
    p_gradle = sub.add_parser(
        "gradle", help="passthrough any gradle command (with concurrency gate)",
    )
    p_gradle.add_argument("--no-daemon", action="store_true")
    p_gradle.add_argument("--cc", action="store_true")
    p_gradle.add_argument("gradle_args", nargs=argparse.REMAINDER)
    p_gradle.set_defaults(func=cmd_gradle)

    # lint: canonical -Plint dashboard round (see build-and-run.md)
    p_lint = sub.add_parser(
        "lint",
        help="full static-analysis round: pmd/cpd/spotbugs dashboards + EP compiles (-Plint)",
    )
    p_lint.add_argument("--no-daemon", action="store_true")
    p_lint.add_argument("--cc", action="store_true")
    p_lint.add_argument("passthrough", nargs=argparse.REMAINDER)
    p_lint.set_defaults(func=cmd_lint)

    # status / log / lock / proc
    p_status = sub.add_parser("status", help="one-page status: lock, last log, processes")
    p_status.set_defaults(func=cmd_status)

    p_log = sub.add_parser("log", help="tail / list / cat build logs")
    p_log_sub = p_log.add_subparsers(dest="action", required=True)
    p_log_tail = p_log_sub.add_parser("tail", help="tail last build's log (default 50 lines)")
    p_log_tail.add_argument("-n", "--lines", type=int, default=50)
    p_log_sub.add_parser("list", help="list all build-*.log files with size/mtime")
    p_log_cat = p_log_sub.add_parser("cat", help="cat full log of a task (most recent)")
    p_log_cat.add_argument("task", help="task name fragment, e.g. compile, runClient")
    p_log.set_defaults(func=cmd_log)

    p_lock = sub.add_parser("lock", help="lock status / clear / takeover")
    p_lock_sub = p_lock.add_subparsers(dest="action", required=True)
    p_lock_sub.add_parser("status")
    p_lock_sub.add_parser("clear", help="force-clear lock (only if holder is dead)")
    p_lock_sub.add_parser("takeover", help="alias for clear; emphasizes dead-holder recovery")
    p_lock.set_defaults(func=cmd_lock)

    p_proc = sub.add_parser("proc", help="list / kill gradle daemon")
    p_proc_sub = p_proc.add_subparsers(dest="action", required=True)
    p_proc_sub.add_parser("list")
    p_proc_killd = p_proc_sub.add_parser("killdaemon", help="kill all gradle daemons (needs --yes)")
    p_proc_killd.add_argument("--yes", action="store_true")
    p_proc.set_defaults(func=cmd_proc)

    # doc management (rot control; read-only except touch/new/index)
    p_doc = sub.add_parser("doc", help="documentation management: list/check/touch/new/index")
    p_doc_sub = p_doc.add_subparsers(dest="action", required=True)
    p_doc_sub.add_parser("list", help="list docs with health status")
    p_doc_check = p_doc_sub.add_parser(
        "check", help="audit docs for rot (exit 1 on errors — gate builds/PRs on this)")
    p_doc_check.add_argument("--strict", action="store_true",
                             help="treat warnings as failures too")
    p_doc_check.add_argument("--stale-days", type=int, default=DEFAULT_STALE_DAYS,
                             help=f"days before last_verified counts as stale "
                                  f"(default {DEFAULT_STALE_DAYS})")
    p_doc_touch = p_doc_sub.add_parser(
        "touch", help="mark a doc as re-verified today (fuzzy name match)")
    p_doc_touch.add_argument("name", help="name fragment, e.g. toolchain, 0001")
    p_doc_new = p_doc_sub.add_parser(
        "new", help="scaffold a new doc with front-matter template")
    p_doc_new.add_argument("category", choices=DOC_CATEGORIES)
    p_doc_new.add_argument("slug", help="file slug, e.g. event-bus-map or 0002-my-decision")
    p_doc_new.add_argument("--title", default="", help="human title (defaults from slug)")
    p_doc_sub.add_parser("index", help="regenerate doc/README.md index")
    p_doc.set_defaults(func=cmd_doc)

    # inspect-only (no lock). Always run with the canonical args; ignore
    # any user-supplied positional (REMAINDER doesn't play with default=).
    p_tasks = sub.add_parser("tasks", help="gradle tasks --all (no lock; read-only)")
    p_tasks.set_defaults(gradle_args=TASKS_ALL, no_daemon=False, func=cmd_passthrough_no_lock)

    p_deps = sub.add_parser("deps", help="gradle dependencies (no lock; read-only)")
    p_deps.set_defaults(gradle_args=DEPS_TASK, no_daemon=False, func=cmd_passthrough_no_lock)

    args = ap.parse_args()

    # REMAINDER usually includes the leading `--`; strip it if present
    if hasattr(args, "passthrough") and args.passthrough:
        if args.passthrough[0] == "--":
            args.passthrough = args.passthrough[1:]
    if hasattr(args, "gradle_args") and args.gradle_args:
        if isinstance(args.gradle_args, list) and args.gradle_args and args.gradle_args[0] == "--":
            args.gradle_args = args.gradle_args[1:]

    return args.func(args)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[mcbot] interrupted", file=sys.stderr)
        sys.exit(130)
