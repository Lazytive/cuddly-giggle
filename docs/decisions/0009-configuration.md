# ADR 0009: Configuration. One TOML file, profiles, and settings the mod enforces

- Status: Proposed
- Date: 2026-09-30

## Context

The build prompt asks for a single human-readable config file with profiles, selectable per world or per server address. Every setting must have a documented default. The fixed core must not be configurable.

Some settings grant capabilities: cheats, fair mode off, and the server allowlist. If the service could change those over the socket, a bug or a prompt injection in the service could escalate the agent's abilities.

NeoForge 1.21.1 already ships NightConfig, TOML support included (verified from `ModConfigSpec` imports), so the mod can read TOML with no extra dependency. Python 3.11+ has `tomllib` for reading. `tomlkit` can edit the file while keeping comments.

## Decision

- **One file**, `mcagent.toml`, with this structure:
  - `[profiles.<name>]` sections, which can inherit via `extends`;
  - `[[profile_rules]]` entries that pick a profile by server address or singleplayer world name, falling back to `default_profile`.
- **Separate files for sensitive values:**
  - the personality profile is a separate TOML file, referenced by path;
  - operator PII for the outbound guard lives in `operator-private.toml` and is never sent to any model;
  - API keys are read only from environment variables.
- **Enforcement settings are read by the mod from disk.** These are the allowlist, fair mode, the cheat toggles, realism parameters, and the overlay default. The mod reports them in `hello` as `effective`. **No protocol message can change them.** The service reads the same file for everything else.
- **The fixed core is code.** Any key that tries to touch a fixed-core behaviour is a load error.
- **Documentation is generated.** The config schema is a pydantic model with a description and default for every field. `docs/config-reference.md` is generated from it and checked in CI.
- **Recommended defaults**, as the build prompt specifies:
  - cheats off on server profiles;
  - fair mode on;
  - disclosure `disclose_if_sincerely_asked`;
  - rule-following on;
  - griefing and stealing `infer_from_context`.
- **Reloading.** Non-enforcement settings hot-reload in the service. Enforcement settings apply on the next world join, or on an explicit reload via the operator's client command.

## Alternatives rejected

- **YAML.** Its implicit typing causes surprises (`no` → false, and similar), and the mod side would need an extra parser.
- **NeoForge `ModConfigSpec` as the only config.** Its per-mod file layout does not suit a shared, profile-based file that both processes read.
- **The service pushes config to the mod.** Rejected on safety grounds (see Context).

## Consequences

- The operator edits one file and sees every setting documented.
- Changing an enforcement setting takes a deliberate reload, never a message from the service.
