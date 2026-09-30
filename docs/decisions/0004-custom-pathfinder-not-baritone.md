# ADR 0004: Write our own pathfinder; do not use Baritone

- Status: Proposed
- Date: 2026-09-30

## Context

Pathfinding must:

- use only terrain the agent has perceived or remembers when fair mode is on;
- look human when executed.

The build prompt asks us to evaluate existing projects and their licences first.

Checked on 2026-09-30:

- **Baritone** (`cabaletta/baritone`) is **LGPL-3.0**. Its `1.21.1` branch (v1.11.x) builds for Fabric, Forge and NeoForge. Its `BlockStateInterface` reads block states from the `ClientChunkCache` plus Baritone's own cached regions, so it sees every loaded chunk, whether or not the player has seen it. It also controls rotation and input through its own behaviours.
- **mineflayer-pathfinder** is **MIT**. It is JavaScript, runs on Mineflayer's protocol-bot world model, and cannot be embedded in a Java client mod.

## Decision

Write a clean-room pathfinder in `mod/core`, with no Minecraft dependency. It has two parts.

**Planning:**
- A* over a `WorldView` interface backed by KnownWorld when fair mode is on, and by loaded chunks when it is off.
- Movement cost for walk, sprint, jump up, safe drop, swim, climb, pillar, bridge, break-through and place-over.
- Unknown voxels get an explicit penalty and are re-planned once seen.
- Hazards (lava, fire, drop height, suffocation) carry costs.
- Incremental re-planning when new terrain is perceived.

**Execution:** a separate path executor built on the human-motion model, with curves, speed variation, sprint-jump rhythm, edge sneaking and glances.

General, well-known ideas (movement-cost tables, segment re-planning) may be used. No code is copied from any project.

## Alternatives rejected

- **Baritone as a library.** Its world source breaks fair mode. Reworking that means modifying LGPL code deep in its internals, which carries LGPL obligations and ongoing merge work. Its execution also looks robotic.
- **Baritone planner only, with our executor.** Same world-source problem. We would also carry a large dependency just for A*.
- **Porting mineflayer-pathfinder.** Possible under MIT, but its model of physics and movement is tied to Mineflayer. It is not a better starting point than a purpose-built planner over our own world view.

## Consequences

- More work in M4. In exchange, fair mode is enforced by construction, and execution is designed to look human from the start.
- The pathfinder is testable without Minecraft (synthetic grids) and with GameTests (built terrain).
