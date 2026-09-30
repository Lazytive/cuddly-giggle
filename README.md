# cuddly-giggle (working name: mcagent)

An autonomous AI that plays Minecraft (NeoForge 1.21.1) the way a human does. It has two parts:

- a client-only mod that perceives the game fairly, acts through simulated human input and handles reflexes;
- a Python agent service that holds the brain: goals, planning, a model router, memory, skills, building, chat and personality.

**Status:** planning. Nothing has been built yet. The plan is waiting for the operator's go-ahead before milestone 1 starts.

## Documents

- [Build prompt](docs/build-prompt.md): the original brief.
- [Architecture plan](docs/architecture.md): modules, data stores, fixed-core enforcement, risks.
- [Protocol outline](docs/protocol.md): the mod ↔ service message schema.
- [Milestones](docs/milestones.md): M1–M13 with demos and acceptance criteria, plus the **operator decisions needed**.
- [Research notes](docs/research-notes.md): verified APIs, licences and papers.
- [Decision records](docs/decisions/): the architecture decision records.
