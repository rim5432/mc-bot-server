# MC Bot Server

A mechanical bot device for Minecraft 1.20.1 (Forge), built around a
clean separation between the **device** (this mod) and the **driver**
(an external LLM harness). The mod owns a small, contract-frozen
control surface; the harness plans and decides; the bot executes with
vanilla-mechanics fidelity — pathfinding, digging, crafting, combat,
menu and inventory work are modeled as mechanical device functions,
not scripted spells.

| | |
|---|---|
| Mod id | `mcbotserver` |
| Version | 0.1.0 (early development, surfaces still moving) |
| Requires | Minecraft 1.20.1, Forge 47.4.10, Java 17 |
| License | MIT |

## Architecture in one minute

The codebase is split by four boundary contracts (full texts in
[doc/architecture/boundaries.md](doc/architecture/boundaries.md)):

- **A — world vs actor**: `api/` gives `core/` a read-only world view;
  the actor emits intents only. Neither layer imports Minecraft.
- **B — behavior monopoly**: side-effect-free processes compile down to
  `Directive{Goal, Overrides}`; a behavior monopolizes the actor via
  per-channel claims; feedback closes through `ExecutionReport`.
- **C — reflexes first**: reflex ticks run before missions; a non-null
  reflex preempts the mission and preemption carries interruption
  context; resuming revalidates the world.
- **D — harness blindness**: the bot knows nothing about any specific
  harness. The wire is `submit`/`cancel` plus an event stream and a
  state snapshot. The reference consumer lives in
  [tool/harness/mc.py](tool/harness/mc.py).

Only `adapter/` (and mixins) may touch Minecraft classes, so the
device logic is testable offline as plain Java.

## Building and running

All build/test/run flows go through the development toolbox (it owns
cross-process locking so multiple agents can share one checkout):

```bash
python tool/mcbot_tool.py build compile   # fast sanity check
python tool/mcbot_tool.py test            # offline JUnit + style gates
python tool/mcbot_tool.py build jar       # package the mod
python tool/mcbot_tool.py build runServer # launch a dedicated server
```

Engine gametests (`build runGameTest`) exercise behavior against a real
game instance and stay a local step; CI runs the offline gate only.
The toolbox needs no external Python packages. Full command table:
[tool/README.md](tool/README.md).

## Driving the bot

The bot exposes its control surface through a loopback-only socket
authenticated with the server's RCON credentials (`enable-rcon` +
`rcon.password` in `server.properties`); boundary D defines the wire.
[tool/harness/mc.py](tool/harness/mc.py) is a small reference harness
you can read or replace — the bot never knows who is on the other end.
The interaction model is specified in
[doc/architecture/harness-interaction.md](doc/architecture/harness-interaction.md).

> Use responsibly: automation is against the rules on many multiplayer
> servers. Check where you play.

## Documentation

- [doc/guide/onboarding.md](doc/guide/onboarding.md) — cold-start
  reading order
- [doc/architecture/boundaries.md](doc/architecture/boundaries.md) —
  the live contract
- [doc/guide/workplan.md](doc/guide/workplan.md) — authoritative,
  dependency-ordered implementation checklist
- [doc/architecture/ledger.md](doc/architecture/ledger.md) —
  append-only architectural verdicts
- [AGENTS.md](AGENTS.md) — the hard-constraint checklist every
  contributor (human or AI agent) codes under

This repository is developed agent-first: the specification, decision
ledger, and issue archive in `doc/` are written so that AI coding
agents can collaborate on it under the same rules as humans. The
governance you see is the governance it runs on.

## Contributing

Read [AGENTS.md](AGENTS.md) first — it is short, normative, and the
gate configuration enforces most of it. Then follow the onboarding
order above. Pull requests must keep `build compile` + `test` green;
the offline test suite is the contract.

## License

[MIT](LICENSE)
