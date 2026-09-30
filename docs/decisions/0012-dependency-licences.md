# ADR 0012: Dependency licence policy

- Status: Proposed
- Date: 2026-09-30

## Context

The build prompt says: check licences before borrowing code or depending on projects, and record how each licence is complied with. The findings are in `docs/research-notes.md` §2.

## Decision

1. **Copying code.** No code is copied from any project without recording its licence here and in `THIRD_PARTY_NOTICES.md`.
2. **Allowed licences for bundled or linked dependencies:** MIT, BSD, Apache-2.0 (including the LLVM exception), MPL-2.0, and the PSF licence. Code under LGPL is used only as an unmodified, separately distributed dependency; NeoForge itself is LGPL-2.1 and is a platform dependency. GPL and AGPL code is not bundled or linked.
3. **All-Rights-Reserved mods** (for example FTB Quests):
   - allowed only as optional runtime soft dependencies;
   - compiled against their published artifacts, or called via reflection;
   - never bundled, redistributed or copied from.
4. **JEI and EMI (both MIT).** Compile-only API dependencies. They are not bundled; the pack provides them at runtime.
5. **Baritone (LGPL-3.0).** Not used (ADR 0004).
6. **Research code.** Voyager, Mineflayer and mineflayer-pathfinder are all MIT. They are used for ideas. Any ported logic keeps its notice.
7. **Datasets.** A dataset with no stated licence (the VPT contractor data) is used only with the operator's approval, and only for aggregate statistics. Nothing from it is redistributed.
8. **Minecraft.** No Minecraft code or assets are redistributed.
9. **The project's own licence** is the operator's decision (see `milestones.md`). MIT or Apache-2.0 is compatible with everything above.

## Consequences

- CI gains a licence check that lists dependency licences for Gradle and Python and fails on any licence not on the allowed list.
