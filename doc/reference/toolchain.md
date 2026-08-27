---
title: Toolchain Reference
last_verified: 2026-08-27
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
| Checkstyle | 12.3.1 (`build.gradle`) — last version shipping Java-17 bytecode; all of 13.x+ are Java-21 (probed class-major straight from maven jars, 2026-08-27) |
| PMD / CPD | 7.26.0 (`build.gradle`) — PMD 7 needs Gradle >= 8.6; Gradle 8.8 supports it via explicit `pmd.toolVersion` (default stays 6.55.0) |
| Spotless / formatter | plugin 8.10.0 + palantir-java-format 2.97.0 (both Java-11 bytecode) — formatter output IS the ecosystem standard: Sun-style 4-space indent, K&R braces, 120 columns |
| Error Prone | plugin 5.1.0 + core 2.42.0 — 2.42.0 is the last core that runs on a JDK 17 toolchain; verified working on the moddev compile chain unmodified, flags must go through `options.errorprone.check(...)` (raw `-Xep:` compilerArgs are rejected by plain javac when disabled) |
| SpotBugs | plugin 6.5.11 + tool 4.10.4 — scoped to api/core packages via `onlyAnalyze`, which removes the MC auxclasspath problem entirely |

Do not hardcode versions into new docs; link here instead.

## Static analysis tooling notes (2026-08-27)

- Checkstyle config lives at `config/checkstyle/checkstyle.xml` with
  `suppressions.xml` beside it; PMD ruleset at `config/pmd/ruleset.xml`.
  The plugin's default config/pmd path is NOT honored on the PMD-7
  route: `ruleSetFiles` must stay wired explicitly in `build.gradle`,
  otherwise the built-in ruleset runs silently instead.
- The custom `cpdCheck` JavaExec task invokes
  `net.sourceforge.pmd.cli.PmdCli cpd`; since PMD 7, CPD is a
  subcommand of the unified CLI launcher.
- Gradle's Checkstyle task exposes no forked-JVM knob (`jvmArgs`
  unavailable), so violation messages render in the daemon JVM locale;
  known cosmetic issue.
- Lint invocation goes through the locked gradle passthrough with the
  opt-in `-Plint` flag; postures and deferral rationale live in
  [Build & Run Guide](../guide/build-and-run.md#static-analysis--plint).

## Verified working state

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
