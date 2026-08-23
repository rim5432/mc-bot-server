#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RCON client and session ledger for driving the bot externally.

Zero external dependencies: implements the Source RCON protocol over
a plain socket. The mod side exposes four /bot verbs that answer in
single-line JSON (see adapter/BotCommands.java); this client carries
those commands and keeps a per-run session folder so every poll,
command, and verdict lands on disk.

Usage:
  python tool/rcon.py init                       # first-time config
  python tool/rcon.py new-session <label>        # create session dir
  python tool/rcon.py "<command>"                # run, print response
  python tool/rcon.py --session <dir> "<cmd>"    # also log to commands.jsonl
  python tool/rcon.py poll <sessionDir>          # drain /bot events

Config lives at tool/.runtime/rcon.json (gitignored):
  {"host": "127.0.0.1", "port": 25575, "password": "..."}
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent
CONFIG_PATH = TOOL_DIR / ".runtime" / "rcon.json"
SESSIONS_DIR = TOOL_DIR / "sessions"

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2


class RconError(Exception):
    """Raised on connection, auth, or protocol failures."""


def _send_packet(sock: socket.socket, req_id: int, ptype: int,
                 payload: str) -> None:
    data = struct.pack("<ii", req_id, ptype)
    data += payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)


def _recv_exact(sock: socket.socket, size: int) -> bytes:
    chunks = []
    while size > 0:
        chunk = sock.recv(size)
        if not chunk:
            raise RconError("connection closed by server")
        chunks.append(chunk)
        size -= len(chunk)
    return b"".join(chunks)


def _recv_packet(sock: socket.socket):
    raw_len = _recv_exact(sock, 4)
    (length,) = struct.unpack("<i", raw_len)
    body = _recv_exact(sock, length)
    req_id, ptype = struct.unpack("<ii", body[:8])
    payload = body[8:-2].decode("utf-8", errors="replace")
    return req_id, ptype, payload


def load_config() -> dict:
    if not CONFIG_PATH.exists():
        raise RconError(
            f"no config at {CONFIG_PATH}; run: python tool/rcon.py init")
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))


def run_command(cfg: dict, command: str) -> str:
    """Authenticate, run one command, return the raw response text."""
    try:
        with socket.create_connection(
                (cfg["host"], int(cfg["port"])), timeout=10) as sock:
            _send_packet(sock, 1, SERVERDATA_AUTH, cfg["password"])
            while True:
                req_id, _, _ = _recv_packet(sock)
                if req_id == -1:
                    raise RconError("authentication failed")
                if req_id == 1:
                    break
            _send_packet(sock, 2, SERVERDATA_EXECCOMMAND, command)
            _, _, response = _recv_packet(sock)
            return response
    except (OSError, socket.timeout) as exc:
        raise RconError(f"connection error: {exc}") from exc


def _append(path: Path, obj: dict) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(obj, ensure_ascii=False) + "\n")


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def new_session(label: str) -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    session_dir = SESSIONS_DIR / f"{stamp}-{label}"
    session_dir.mkdir(parents=True, exist_ok=False)
    meta = {"label": label, "started_utc": _utc_now()}
    (session_dir / "session.json").write_text(
        json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    (session_dir / "cursor.txt").write_text("0\n", encoding="utf-8")
    for name in ("events.jsonl", "commands.jsonl"):
        (session_dir / name).touch()
    print(f"[rcon] session {session_dir}")
    return session_dir


def poll(session_dir: Path) -> None:
    """Drain /bot events from the stored cursor and record the batch."""
    cfg = load_config()
    cursor_path = session_dir / "cursor.txt"
    since = int(cursor_path.read_text(encoding="utf-8").strip() or "0")
    response = run_command(cfg, f"/bot events {since}")
    line_start = response.find("{")
    if line_start < 0:
        raise RconError(f"non-JSON response: {response!r}")
    batch = json.loads(response[line_start:])
    _append(session_dir / "events.jsonl",
            {"at": _utc_now(), "since": since, "batch": batch})
    latest = int(batch.get("batch", batch).get("latest", since))
    cursor_path.write_text(f"{latest}\n", encoding="utf-8")
    print(response)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--session", type=Path, default=None,
                        help="session dir; logs the command when set")
    parser.add_argument("command", nargs="*",
                        help="init | new-session <label> | poll <dir> "
                             "| any server command")
    args = parser.parse_args(argv)
    parts = args.command
    if not parts:
        parser.print_help()
        return 2
    if parts[0] == "init":
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        host = input(f"host [127.0.0.1]: ").strip() or "127.0.0.1"
        port = input("port [25575]: ").strip() or "25575"
        password = input("rcon.password: ")
        CONFIG_PATH.write_text(json.dumps(
            {"host": host, "port": port, "password": password},
            indent=2) + "\n", encoding="utf-8")
        print(f"[rcon] wrote {CONFIG_PATH}")
        return 0
    if parts[0] == "new-session":
        new_session(parts[1] if len(parts) > 1 else "run")
        return 0
    if parts[0] == "poll":
        poll(Path(parts[1]))
        return 0
    command = " ".join(parts)
    response = run_command(load_config(), command)
    if args.session:
        _append(args.session / "commands.jsonl",
                {"at": _utc_now(), "cmd": command, "resp": response})
    print(response)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RconError as exc:
        print(f"[rcon] ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
