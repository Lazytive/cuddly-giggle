# ADR 0002: Client-only mod packaging

- Status: Proposed
- Date: 2026-09-30

## Context

The mod must:

- join any server, vanilla or modded, without the server installing it;
- not break when the server lacks it;
- load inside arbitrary NeoForge 1.21.1 modpacks.

What the NeoForge 1.21.1 docs say (`gettingstarted/modfiles.md`, `concepts/sides.md`, versioned copy in `neoforged/Documentation`, checked 2026-09-30):

- `@Mod(value = MODID, dist = Dist.CLIENT)` makes the mod class load only on the physical client.
- Dependency entries take `side = "CLIENT" | "SERVER" | "BOTH"`.
- Mods "should verify that the mod actually runs on a physical client, and no-op in the event that it does not."

Server/client mod negotiation happens through registered payloads and synced registries.

## Decision

- **Entry point.** A single `@Mod(value = "mcagent", dist = Dist.CLIENT)` entry point. If the jar ends up on a dedicated server, it does nothing.
- **Nothing to negotiate.** The mod registers no network payloads, no registry objects (blocks, items, entities, menus, data components), and no data-pack or resource content that a server would need to match.
- **Soft dependencies.** JEI, EMI, FTB Quests, guidebook mods and minimap mods are `type = "optional"`, `side = "CLIENT"`. Integration classes are only loaded after `ModList.get().isLoaded(id)` returns true.
- **Test.** M1 connects to a vanilla 1.21.1 server and to a NeoForge server that does not have the mod, and both must succeed.

## Alternatives rejected

- **A common mod with a server-side companion.** This breaks "joins any server". It would also make server-side data tempting, which fair mode forbids.

## Consequences

- Everything the agent knows comes from what the client legitimately receives. That fits fair mode, and it constrains knowledge extraction: loot tables, for example, are not synced to the client.
