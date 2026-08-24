#!/usr/bin/env python3
"""Keep the dev server front and center in the dev client's server list.

The dev client's servers.dat accumulates hidden direct-connect
entries, and a bare "localhost" entry connects to the DEFAULT port
25565 while the dev server listens on a custom port - picking the
wrong entry sends the client at a foreign server (the netcraft
mismatch crash class). This script upserts one clearly-named entry
for the dev server, pins it to slot 0, makes it visible, and folds
duplicate spellings of the same loopback host.

Usage:
    python tool/dev_server_entry.py [ip]        # default localhost:25585

Run while the client is CLOSED: the client rewrites servers.dat on
list changes and would clobber an edit made mid-session.

stdlib only; handles both raw and gzip-wrapped NBT (vanilla writes
gzip, the dev client has been seen writing raw). NBT compounds are
represented as {name: (tag_type, value)} and lists as
(item_type, [values]) so parse and serialize stay symmetric.
"""

from __future__ import annotations

import gzip
import struct
import sys
from pathlib import Path

SERVERS_DAT = Path(__file__).resolve().parent.parent / "run" / "servers.dat"
ENTRY_NAME = "mcbot-dev"

BYTE, SHORT, INT, LONG, FLOAT, DOUBLE = 1, 2, 3, 4, 5, 6
BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY = 7, 8, 9, 10, 11
_FMT = {BYTE: "b", SHORT: "h", INT: "i", LONG: "q", FLOAT: "f",
        DOUBLE: "d"}


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def unpack(self, fmt: str):
        (val,) = struct.unpack_from(">" + fmt, self.data, self.pos)
        self.pos += struct.calcsize(">" + fmt)
        return val

    def raw(self, n: int) -> bytes:
        chunk = self.data[self.pos:self.pos + n]
        self.pos += n
        return chunk

    def string(self) -> str:
        return self.raw(self.unpack("h")).decode("utf-8", errors="replace")

    def payload(self, tag_type: int):
        if tag_type in _FMT:
            return self.unpack(_FMT[tag_type])
        if tag_type == STRING:
            return self.string()
        if tag_type == BYTE_ARRAY:
            return self.raw(self.unpack("i"))
        if tag_type == INT_ARRAY:
            return [self.unpack("i") for _ in range(self.unpack("i"))]
        if tag_type == LIST:
            item_type = self.raw(1)[0]
            count = self.unpack("i")
            return item_type, [self.payload(item_type)
                               for _ in range(count)]
        if tag_type == COMPOUND:
            out = {}
            while True:
                t = self.raw(1)[0]
                if t == 0:
                    return out
                name = self.string()
                out[name] = (t, self.payload(t))
        raise ValueError(f"unsupported NBT tag type {tag_type}")

    def root(self):
        t = self.raw(1)[0]
        name = self.string() if t else ""
        return t, name, self.payload(t) if t else None


class Writer:
    def __init__(self):
        self.out = bytearray()

    def pack(self, fmt: str, *vals):
        self.out.extend(struct.pack(">" + fmt, *vals))

    def string(self, s: str):
        raw = s.encode("utf-8")
        self.pack("h", len(raw))
        self.out.extend(raw)

    def payload(self, tag_type: int, val):
        if tag_type in _FMT:
            self.pack(_FMT[tag_type], val)
        elif tag_type == STRING:
            self.string(val)
        elif tag_type == BYTE_ARRAY:
            self.pack("i", len(val))
            self.out.extend(val)
        elif tag_type == INT_ARRAY:
            self.pack("i", len(val))
            for v in val:
                self.pack("i", v)
        elif tag_type == LIST:
            item_type, items = val
            self.out.append(item_type)
            self.pack("i", len(items))
            for item in items:
                self.payload(item_type, item)
        elif tag_type == COMPOUND:
            for key, (ct, cval) in val.items():
                self.out.append(ct)
                self.string(key)
                self.payload(ct, cval)
            self.out.append(0)
        else:
            raise ValueError(f"unsupported NBT tag type {tag_type}")


def entry_ip(entry) -> str:
    (t, v) = entry.get("ip", (STRING, ""))
    return v if t == STRING else ""


def main() -> int:
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:25585"
    host = target.split(":")[0]
    if not SERVERS_DAT.exists():
        print(f"[dev-entry] {SERVERS_DAT} not found - nothing to do")
        return 1

    raw = SERVERS_DAT.read_bytes()
    was_gzip = raw[:2] == b"\x1f\x8b"
    data = gzip.decompress(raw) if was_gzip else raw
    root = Reader(data).root()[2]
    _, (_item_type, servers) = root.get(
        "servers", (LIST, (COMPOUND, [])))

    kept, folded = [], 0
    for entry in servers:
        # Same loopback server under an old spelling (bare host, any
        # port, or the other loopback name) folds into the new entry
        # instead of lingering as a connect-to-the-wrong-port trap.
        if entry_ip(entry).split(":")[0] in (
                host, "localhost", "127.0.0.1"):
            folded += 1
        else:
            kept.append(entry)

    new_entry = {"ip": (STRING, target), "name": (STRING, ENTRY_NAME)}
    root["servers"] = (LIST, (COMPOUND, [new_entry] + kept))

    writer = Writer()
    writer.out.append(COMPOUND)
    writer.string("")
    writer.payload(COMPOUND, root)
    backup = SERVERS_DAT.with_suffix(".dat.bak")
    backup.write_bytes(raw)
    SERVERS_DAT.write_bytes(
        gzip.compress(writer.out) if was_gzip else bytes(writer.out))
    print(f"[dev-entry] '{ENTRY_NAME}' -> {target} pinned at slot 0"
          f" ({len(kept) + 1} entries, folded {folded},"
          f" backup {backup.name})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
