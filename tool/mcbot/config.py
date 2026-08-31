"""Gradle task constants and gradle.properties reading.

The task-name lists are the canonical mapping between CLI
subcommands (``build compile``, ``test``, ``lint``, ...) and the
gradle tasks they invoke. Change a task name here and in
``doc/guide/build-and-run.md`` in the same commit.
"""
from __future__ import annotations

import re

from mcbot.paths import PROJECT_ROOT

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


def _read_gradle_props() -> dict:
    props = {}
    gp = PROJECT_ROOT / "gradle.properties"
    if gp.exists():
        for ln in gp.read_text(encoding="utf-8", errors="replace").splitlines():
            m = re.match(r"^([A-Za-z_]\w*)\s*=\s*(.*)$", ln)
            if m:
                props[m.group(1)] = m.group(2).strip()
    return props
