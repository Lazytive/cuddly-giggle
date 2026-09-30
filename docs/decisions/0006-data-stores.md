# ADR 0006: Data stores. SQLite and sqlite-vec in the service; KnownWorld in the mod

- Status: Proposed
- Date: 2026-09-30

## Context

Several kinds of memory must persist across sessions:

- spatial, episodic, semantic and experience memory;
- the skill library;
- per-pack knowledge bases;
- decision traces.

Knowledge must not leak between packs. Pathfinding runs in the mod and needs fast voxel access to perceived terrain. The operator should not have to run database servers.

## Decision

- **The mod owns KnownWorld.** It is a per-world, per-dimension sparse store of perceived block states. Each 16³ section is palette-compressed and carries a known-mask and last-seen ticks. It is persisted under `<gameDir>/mcagent/known_world/`. The service gets terrain through `known_world_region` queries.
- **The service uses SQLite** (WAL mode), with the `sqlite-vec` extension for embeddings (MIT/Apache-2.0):
  - `data/agent.sqlite` holds global data: episodic memory, `game`-scoped facts, experience lessons, skill metadata, and personality history;
  - `data/packs/<fingerprint>/kb.sqlite` holds each pack's knowledge base;
  - `data/worlds/<world-id>/world.sqlite` holds each world's spatial memory, deaths and goals.
- **Skill source** lives as files (`data/skills/<scope>/<name>/v<N>.py`), with metadata in SQLite.
- **Traces** are written as JSONL plus images per session, indexed in SQLite.
- **The recipe and tech graph** is stored as SQLite tables (nodes and edges) and traversed in Python. It does not need a graph database.

## Alternatives rejected

- **PostgreSQL with pgvector.** A server for the operator to run and maintain; overkill for a single agent.
- **LanceDB or Chroma.** Workable, but they add a dependency and a second storage engine. sqlite-vec keeps vectors next to the rows they describe.
- **Keeping terrain in the service.** Every path plan would then cross the socket, and the mod could not path while the service is down.
- **A graph database (Neo4j and similar).** Operational weight for graphs that fit comfortably in SQLite.

## Consequences

- Plain files make backups easy. Deleting a pack or world directory forgets exactly that pack or world.
- Writes are asynchronous (executor thread) so the event loop never blocks.
