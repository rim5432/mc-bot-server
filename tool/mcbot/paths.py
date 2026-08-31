"""Project paths and runtime directory management.

Every other module imports its root paths from here so there is a
single source of truth for where the repo, tool dir, and runtime
scratch space live.
"""
from __future__ import annotations

from pathlib import Path

# mcbot/paths.py -> tool/mcbot/ -> tool/
TOOL_DIR = Path(__file__).resolve().parent.parent
PROJECT_ROOT = TOOL_DIR.parent
RUNTIME_DIR = TOOL_DIR / ".runtime"
LAST_LOG = RUNTIME_DIR / "build-last.log"


def _lock_paths(name: str = "build") -> tuple:
    """Lock + meta file pair for one lock namespace.

    ``build`` is the global namespace for anything that writes
    build/ outputs; each long-running game task gets its own
    ``run.<task>`` namespace so a server and a client can coexist.
    """
    return RUNTIME_DIR / f"{name}.lock", RUNTIME_DIR / f"{name}.lock.meta.json"


def ensure_runtime_dir() -> None:
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
