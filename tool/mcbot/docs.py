"""Documentation health checks (rot detection).

Every doc under ``doc/`` carries front-matter (``title`` /
``last_verified`` / ``covers:``). Rot detection is git-history-driven:
if any path in ``covers:`` changed after the verified date,
``check_doc`` flags DRIFT. These are pure functions — the CLI
rendering lives in ``mcbot.cli``.
"""
from __future__ import annotations

import datetime as _dt
import re
import subprocess
from pathlib import Path
from typing import Optional

from mcbot.paths import PROJECT_ROOT

DOC_DIR = PROJECT_ROOT / "doc"
STATE_DIR = DOC_DIR / "_state"
DOC_CATEGORIES = ["architecture", "guide", "reference", "decisions"]
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
    return sorted(DOC_DIR.rglob("*.md"))


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
    # State YAML files (doc/_state/*.yaml)
    results.extend(check_state_yamls())
    return results


def _doc_status_word(issues: list) -> str:
    if any(lv == "ERR" for lv, _, _ in issues):
        return "ERR"
    if issues:
        return "warn"
    return "ok"


def _fuzzy_find_docs(fragment: str) -> list:
    frag = fragment.lower().strip("/")
    return [p for p in _iter_doc_files() if frag in p.relative_to(DOC_DIR).as_posix().lower()]


# ---------------------------------------------------------------------------
# State YAML checks (doc/_state/*.yaml — single source of truth files)
# ---------------------------------------------------------------------------

# Expected state files and their structural invariants. Each entry maps a
# filename to a dict of: required_top_keys (list), required_meta_keys (list),
# and an optional count_check (top_key -> expected_count or None).
_STATE_FILE_SPEC = {
    "code-health.yaml": {
        "required_top_keys": ["meta", "rules", "abstractions", "open_items"],
        "required_meta_keys": ["last_updated", "source_doc"],
        "count_check": {"rules": 12},  # H-R1 through H-R12
    },
    "workplan.yaml": {
        "required_top_keys": ["meta", "convergence_criteria", "open_items"],
        "required_meta_keys": ["last_updated", "source_doc", "current_stage"],
        "count_check": {"convergence_criteria": 3},
    },
    "boundaries.yaml": {
        "required_top_keys": ["meta", "decisions", "amendment_chains"],
        "required_meta_keys": ["last_updated", "source_doc", "total_decisions"],
        "count_check": None,  # total_decisions validated against max id separately
    },
}


def _parse_yaml_top_level(text: str) -> dict:
    """Minimal YAML parser for our state files.

    Returns a dict with top-level keys. Values are either:
    - str (scalar)
    - list of str (top-level list items, one per '- ' line)
    - list of dict (list items with nested 'key: value' pairs)
    Only handles the subset our state files use — no anchors, no flow style.
    """
    result: dict = {}
    current_key = None
    current_list: list = []
    current_item: dict = {}
    in_list = False

    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        # Top-level key (no leading whitespace)
        if not raw.startswith(" ") and not raw.startswith("\t"):
            if in_list and current_key:
                if current_item:
                    current_list.append(current_item)
                    current_item = {}
                result[current_key] = current_list
                in_list = False
            m = re.match(r"^([A-Za-z_][\w-]*):\s*(.*)$", raw)
            if m:
                current_key = m.group(1)
                val = m.group(2).strip()
                if val == "":
                    # Could be a list or nested dict — decide on next line
                    in_list = False
                    current_list = []
                    result[current_key] = None
                else:
                    result[current_key] = val.strip("'\"")
            continue
        # Indented content
        if current_key is None:
            continue
        stripped = raw.strip()
        if stripped.startswith("- "):
            if not in_list:
                in_list = True
                current_list = []
                current_item = {}
            if current_item:
                current_list.append(current_item)
                current_item = {}
            item_content = stripped[2:].strip()
            if ":" in item_content and not item_content.startswith("{"):
                k, _, v = item_content.partition(":")
                current_item[k.strip()] = v.strip().strip("'\"")
            else:
                current_list.append(item_content.strip("'\""))
        elif in_list and ":" in stripped:
            k, _, v = stripped.partition(":")
            current_item[k.strip()] = v.strip().strip("'\"")
        elif not in_list and ":" in stripped:
            # Nested dict under current_key (e.g. meta:)
            if result.get(current_key) is None:
                result[current_key] = {}
            if isinstance(result[current_key], dict):
                k, _, v = stripped.partition(":")
                result[current_key][k.strip()] = v.strip().strip("'\"")

    if in_list and current_key:
        if current_item:
            current_list.append(current_item)
        result[current_key] = current_list
    return result


def _count_yaml_list_items(text: str, key: str) -> int:
    """Count top-level '- ' items under a given key in YAML text."""
    count = 0
    in_section = False
    section_indent = -1
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if not raw.startswith(" ") and not raw.startswith("\t"):
            m = re.match(r"^([A-Za-z_][\w-]*):", raw)
            if m:
                if in_section:
                    break
                if m.group(1) == key:
                    in_section = True
                    section_indent = len(raw) - len(raw.lstrip())
            continue
        if in_section:
            stripped = raw.strip()
            if stripped.startswith("- "):
                count += 1
    return count


def check_state_yaml(path: Path) -> list:
    """Validate one state YAML file. Returns list of (level, code, message)."""
    rel = path.relative_to(PROJECT_ROOT).as_posix()
    issues = []
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        return [("ERR", "STATE_READ", f"{rel}: {e}")]

    spec = _STATE_FILE_SPEC.get(path.name)
    if spec is None:
        return [("WARN", "STATE_UNKNOWN", f"{rel}: unknown state file, no spec")]

    parsed = _parse_yaml_top_level(text)

    # Required top-level keys
    for k in spec["required_top_keys"]:
        if k not in parsed or parsed[k] is None:
            issues.append(("ERR", "STATE_STRUCTURE", f"{rel}: missing top-level key '{k}'"))

    # Meta block
    meta = parsed.get("meta")
    if not isinstance(meta, dict):
        issues.append(("ERR", "STATE_STRUCTURE", f"{rel}: 'meta' block missing or not a mapping"))
    else:
        for k in spec["required_meta_keys"]:
            if k not in meta:
                issues.append(("ERR", "STATE_STRUCTURE", f"{rel}: meta missing '{k}'"))
        lu = meta.get("last_updated", "")
        if lu and not _DATE_RE.match(str(lu)):
            issues.append(("ERR", "STATE_DATE", f"{rel}: meta.last_updated '{lu}' not YYYY-MM-DD"))
        src = meta.get("source_doc", "")
        if src and not (PROJECT_ROOT / str(src)).exists():
            issues.append(("ERR", "STATE_SOURCE", f"{rel}: source_doc '{src}' does not exist"))

    # Count checks
    if spec["count_check"]:
        for key, expected in spec["count_check"].items():
            actual = _count_yaml_list_items(text, key)
            if actual != expected:
                issues.append(("ERR", "STATE_COUNT",
                               f"{rel}: '{key}' has {actual} items, expected {expected}"))

    # boundaries.yaml: total_decisions == max decision id
    if path.name == "boundaries.yaml":
        total_declared = 0
        if isinstance(meta, dict):
            try:
                total_declared = int(str(meta.get("total_decisions", "0")))
            except ValueError:
                pass
        max_id = 0
        decisions = parsed.get("decisions", [])
        if isinstance(decisions, list):
            for item in decisions:
                if isinstance(item, dict):
                    try:
                        did = int(str(item.get("id", "0")))
                        max_id = max(max_id, did)
                    except ValueError:
                        pass
        if total_declared and max_id and total_declared != max_id:
            issues.append(("ERR", "STATE_COUNT",
                           f"{rel}: meta.total_decisions={total_declared} but max decision id={max_id}"))

    return issues


def check_state_yamls() -> list:
    """Validate all state YAML files. Returns list of result dicts."""
    results = []
    if not STATE_DIR.exists():
        return results
    for p in sorted(STATE_DIR.glob("*.yaml")):
        rel = p.relative_to(DOC_DIR).as_posix()
        issues = check_state_yaml(p)
        results.append({
            "path": rel,
            "title": f"state:{p.name}",
            "verified": "",
            "age": "",
            "issues": issues,
        })
    return results
