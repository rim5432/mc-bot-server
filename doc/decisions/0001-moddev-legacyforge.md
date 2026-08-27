---
title: ADR-0001 Use moddev-legacyforge instead of ForgeGradle
last_verified: 2026-08-27
covers:
  - build.gradle
  - gradle.properties
---

# ADR-0001 Use moddev-legacyforge instead of ForgeGradle

- Status: accepted (2026-08)
- Deciders: project owner

## Context

Target is Minecraft 1.20.1 with Forge 47.x. The classic toolchain,
ForgeGradle, is legacy: slow first setup (full decompile/recompile),
fragile wrapper bootstrap, and poor configuration-cache support.

NeoForged maintains ModDevGradle with a `legacyforge` variant
(`net.neoforged.moddev.legacyforge`) that targets old Forge versions
through the modern NeoForm runtime pipeline.

## Decision

Use `net.neoforged.moddev.legacyforge`, currently pinned at 2.0.144 in
`build.gradle`.

Consequences:

1. Requires Gradle >= 8.8 (wrapper was upgraded accordingly).
2. Runs/mods DSL differs from ForgeGradle examples found online;
   consult `build.gradle` in this repo as the working reference.
3. Parchment mappings are wired through the plugin's `parchment` block
   using properties from `gradle.properties`.
4. Plugin upgrades should be tested with a clean
   `doc check && python tool/mcbot_tool.py build jar` before commit.
