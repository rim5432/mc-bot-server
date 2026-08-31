"""Cross-process file locks and PID liveness checks.

Uses atomic exclusive file create (``os.open(O_CREAT|O_EXCL)``)
rather than advisory locks because both ``msvcrt.locking`` and
``fcntl.flock`` are per-process on Windows — two threads in the
same process could each "hold" them. O_CREAT|O_EXCL is atomic on
both POSIX and NTFS and works across processes AND threads.
"""
from __future__ import annotations

import ctypes
import ctypes.wintypes
import datetime as _dt
import json
import os
import sys
import threading
from typing import Optional

from mcbot.paths import PROJECT_ROOT, RUNTIME_DIR, _lock_paths, ensure_runtime_dir

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
