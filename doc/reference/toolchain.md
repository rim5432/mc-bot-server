---
title: Toolchain Reference
last_verified: 2026-08-22
covers:
  - gradle.properties
  - build.gradle
  - settings.gradle
  - gradle/wrapper
---

# Toolchain Reference

Facts about this repo's build toolchain. This document **covers**
`gradle.properties`, so any version bump triggers DOC_DRIFT in
`doc check` — that is intentional.

## Pinned versions (source of truth: `gradle.properties`)

| Component | Version |
|---|---|
| Minecraft | see `minecraft_version` |
| Forge | see `forge_version` |
| Gradle wrapper | see `gradle/wrapper/gradle-wrapper.properties` |
| Java toolchain (compile) | 17 (`build.gradle`) |

Do not hardcode versions into new docs; link here instead.

## Verified working state (2026-08-21)

- `gradlew compileJava` passes end to end. First run takes ~6 min
  (NeoForm downloads MC client/server, merges, decompiles with
  ForgeFlower, patches, applies Parchment, recompiles). Later runs are
  seconds thanks to configuration cache + up-to-date checks.
- Gradle itself may run on JDK 21; compilation uses the declared Java 17
  toolchain. Both Temurin builds were present on the dev machine.
- `gradle jar` produces `build/libs/mcbotserver-<version>.jar`, including
  the generated `META-INF/mods.toml` and the `reobfJar` remap step
  required by legacy Forge.
- Test stack: JUnit 5.10.2 BOM plus gson 2.10.1 on the test
  classpath (gson is also provided at runtime by MC itself); plain
  JUnit runs need no Forge runtime, which is what keeps the api/core
  gate runnable anywhere.

## Known constraints

1. **Gradle >= 8.8 required.** The `net.neoforged.moddev.legacyforge`
   plugin does not configure on older Gradle.
2. **Wrapper distribution mirror**: `distributionUrl` points at the
   Tencent mirror for fast CN downloads. Switch back to
   `services.gradle.org` if mirroring lags behind new releases.
3. **Never re-run project init generators** over this repo: a previous
   re-run silently reset `gradle-wrapper.properties` back to an old
   version and left a stray root-level `META-INF/` directory.
4. Deprecation warning at compile time for
   `FMLJavaModLoadingContext.get()` is known and non-blocking.

Related: [ADR-0001 use moddev-legacyforge](../decisions/0001-moddev-legacyforge.md)
