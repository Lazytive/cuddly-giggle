# ADR 0013: Build toolchain and version isolation

- Status: Proposed
- Date: 2026-09-30

## Context

- The target is Minecraft 1.21.1 with NeoForge and Java 21.
- Version-specific code must sit behind interfaces, so a port stays contained.
- The NeoForge 1.21.1 repository itself builds with ModDevGradle (`moddevgradle_plugin_version=2.0.115` on its branch). The Gradle plugin portal lists 2.0.148 as the latest ModDevGradle release (checked 2026-09-30).
- The latest NeoForge `21.1.x` build could not be read from here, because `maven.neoforged.net` is blocked by this environment's network policy.

## Decision

- **Mod build.** ModDevGradle, with Gradle as a multi-project build:
  - `mod/core` is pure Java 21, with **no Minecraft or NeoForge on its classpath** and plain JUnit 5 tests;
  - `mod/neoforge-1.21.1` holds the adapters and the mod entry point, and depends on `core`;
  - `mod/gametest` is a separate test-only mod that runs NeoForge GameTests.
- **Mappings.** Official Mojang names, with Parchment parameter names if the Parchment maven is reachable.
- **Pinning.** Exact NeoForge and ModDevGradle versions are pinned in M1, from the maven metadata, once the host is reachable. They are recorded in this ADR at that point.
- **Service.** Python ≥ 3.12, managed with `uv` (lockfile committed), and checked with `ruff`, `pyright` and `pytest`.
- **CI.** GitHub Actions runs core unit tests, the GameTest server, and Python tests. Client scenario tests run on a self-hosted runner, or under Xvfb for light scenarios.

## Alternatives rejected

- **NeoGradle.** Still maintained, but ModDevGradle is what NeoForge's own 1.21.1 build uses, and its configuration is simpler.
- **A single-module mod with packages for isolation.** Nothing enforces the boundary. A separate `core` module makes the compiler enforce it.

## Consequences

- Porting means writing a new adapter module against `core`'s interfaces.
- `core` tests run in seconds, with no game download. They also work in environments where the Minecraft hosts are blocked, such as this one.
