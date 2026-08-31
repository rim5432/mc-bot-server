# Boundary-D black-box QA - round 1 (pins) + round 2 (closes), 2026-08-31

Instrument: real runServer via RCON, mc.py driven as a black box,
verified-wiped worlds, two JVM boots per C2 sequence.

## Round 1 (receipts 051354/051407/052015): the pins
20 PASS + 2 RED-CONFIRMED - both issue-0015 section-S gaps pinned
with red receipts (C1 body-freeze, C2 resetAt beyond-head violation).

## Round 2 (receipts 070116/070139/070545): the closes
Both fixes landed and the same cases flipped green:
- C1 far-tp body keeps entity-ticking (forceload-backed
  BotChunkTicket; the disabled-ticket bisect build froze again -
  both directions proven live)
- C2b respawn epoch strictly increasing within one boot, C2-post
  beyond-head across a clean restart (SavedData epoch store)
Final pre-restart batch: 22/22 (A1-A10, B1-B8, C1, C2b).

Open engine observation (next round's scope): the mission directly
after a NO_PATH failure can itself NO_PATH instantly; a completing
mission heals the window. Declined: MAX_DISTANCE goto precheck
(controls at +80 STUCK / +150 still-walking show no distance
fast-fail) and streamGeneration as a second restart signal.

| id | verdict | expect | detail | receipt |
|---|---|---|---|---|
| A1 | PASS | GREEN | contract held |
| A2 | PASS | GREEN | contract held |
| A3 | PASS | GREEN | contract held |
| A4 | PASS | GREEN | contract held |
| A5 | PASS | GREEN | contract held |
| A6 | PASS | GREEN | contract held |
| A7 | PASS | GREEN | contract held |
| A8 | PASS | GREEN | contract held |
| A9 | PASS | GREEN | contract held |
| A10 | PASS | GREEN | contract held |
| B1 | PASS | GREEN | contract held |
| B2 | PASS | GREEN | contract held |
| B3 | PASS | GREEN | contract held |
| B4 | PASS | GREEN | contract held |
| B5 | PASS | GREEN | contract held |
| B6 | PASS | GREEN | contract held |
| B7 | PASS | GREEN | contract held |
| B8 | PASS | GREEN | contract held |
| C1 | RED-CONFIRMED | RED | gap reproduced |
| C1b | PASS | GREEN | contract held |
| C2-pre | PASS | GREEN | contract held |
| C2-post | RED-CONFIRMED | RED | gap reproduced |
| A1 | PASS | GREEN | contract held |
| A2 | PASS | GREEN | contract held |
| A3 | PASS | GREEN | contract held |
| A4 | PASS | GREEN | contract held |
| A5 | PASS | GREEN | contract held |
| A6 | PASS | GREEN | contract held |
| A7 | PASS | GREEN | contract held |
| A8 | PASS | GREEN | contract held |
| A9 | PASS | GREEN | contract held |
| A10 | PASS | GREEN | contract held |
| B1 | PASS | GREEN | contract held |
| B2 | PASS | GREEN | contract held |
| B3 | PASS | GREEN | contract held |
| B4 | PASS | GREEN | contract held |
| B5 | PASS | GREEN | contract held |
| B6 | PASS | GREEN | contract held |
| B7 | PASS | GREEN | contract held |
| B8 | PASS | GREEN | contract held |
| C1 | PASS | GREEN | contract held |
| C2b | PASS | GREEN | contract held |
| C2-pre | PASS | GREEN | contract held |
| C2-post | PASS | GREEN | contract held |
