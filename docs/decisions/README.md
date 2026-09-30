# Architecture decision records

We write a short record for each significant decision: the options considered, the one chosen, and why the others were rejected. Records are numbered and never renumbered. To change a decision, write a new record that supersedes the old one, and update the old record's status.

Status values:
- `Proposed`: written, but the operator has not yet approved the plan.
- `Accepted`: approved.
- `Superseded by NNNN`: replaced by a later record.

Every record listed here is `Proposed` until the operator approves the plan.

| # | Title | Status |
|---|---|---|
| [0001](0001-two-process-architecture.md) | Two processes: client mod + Python agent service | Proposed |
| [0002](0002-client-only-mod-packaging.md) | Client-only mod packaging | Proposed |
| [0003](0003-input-injection-at-glfw-callback-boundary.md) | Inject synthetic input at the GLFW-callback boundary | Proposed |
| [0004](0004-custom-pathfinder-not-baritone.md) | Write our own pathfinder; do not use Baritone | Proposed |
| [0005](0005-protocol-websocket-json-schema.md) | Protocol: loopback WebSocket, JSON, JSON Schema as source of truth | Proposed |
| [0006](0006-data-stores.md) | Data stores: SQLite + sqlite-vec in the service; KnownWorld in the mod | Proposed |
| [0007](0007-skill-sandbox-wasi.md) | Skill sandbox: CPython on WASI inside wasmtime | Proposed (spike in M5) |
| [0008](0008-model-router.md) | Model router: roles, adapters, guards, comparison | Proposed |
| [0009](0009-configuration.md) | Configuration: one TOML file, profiles, mod-enforced settings | Proposed |
| [0010](0010-fixed-core-enforcement.md) | Fixed-core enforcement, cheats without evasion, disclosure amendment | Proposed (one item needs an operator decision) |
| [0011](0011-milestone-order.md) | Milestone order: pull four slices forward | Proposed |
| [0012](0012-dependency-licences.md) | Dependency licence policy | Proposed |
| [0013](0013-build-toolchain-and-version-isolation.md) | Build toolchain and version isolation | Proposed |

New records start from [`0000-template.md`](0000-template.md).
