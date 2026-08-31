# Boundary-D black-box QA round (2026-08-31)

Instrument: real runServer via RCON, fresh verified-wiped world,
mc.py driven as a black box in subprocesses; two JVM boots for C2.

Verdicts: {'PASS': 20, 'RED-CONFIRMED': 2} over 22 cases.

RED-CONFIRMED are expected-failure pins (infrastructure gaps, both
already owned as open items in issue 0015 section 'S'):
- C1  unticketed chunk freezes the BODY; the queue stays alive and
  fails tasks honestly; forceload wakes the body (gap: bare-server
  chunk ticket, CompanionChunkLoader-class fix)
- C2  resetAt epoch after a JVM restart is NOT beyond the
  pre-restart head (fresh queue restarts at 1) - beyond-head
  promise violated

| id | batch | verdict | expect | detail |
|---|---|---|---|---|
| A1 | A | PASS | GREEN | contract held |
| A2 | A | PASS | GREEN | contract held |
| A3 | A | PASS | GREEN | contract held |
| A4 | A | PASS | GREEN | contract held |
| A5 | A | PASS | GREEN | contract held |
| A6 | A | PASS | GREEN | contract held |
| A7 | A | PASS | GREEN | contract held |
| A8 | A | PASS | GREEN | contract held |
| A9 | A | PASS | GREEN | contract held |
| A10 | A | PASS | GREEN | contract held |
| B1 | B | PASS | GREEN | contract held |
| B2 | B | PASS | GREEN | contract held |
| B3 | B | PASS | GREEN | contract held |
| B4 | B | PASS | GREEN | contract held |
| B5 | B | PASS | GREEN | contract held |
| B6 | B | PASS | GREEN | contract held |
| B7 | B | PASS | GREEN | contract held |
| B8 | B | PASS | GREEN | contract held |
| C1 | C | RED-CONFIRMED | RED | gap reproduced |
| C1b | C | PASS | GREEN | contract held |
| C2-pre | C | PASS | GREEN | contract held |
| C2-post | C | RED-CONFIRMED | RED | gap reproduced |
