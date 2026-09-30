# ADR 0011: Milestone order. Keep the suggested order, pull four slices forward

- Status: Proposed
- Date: 2026-09-30

## Context

Build prompt §20 suggests M1–M13 and allows reordering with a reason. Some later milestones hold pieces that earlier ones depend on, or safety features that must exist before the capabilities they protect.

## Decision

Keep M1–M13 in the suggested order, and move four slices earlier:

1. **Into M1:**
   - config skeleton (one profile, fixed-core constants, the mod reading enforcement settings);
   - server allowlist gate;
   - structured logs.

   *Why:* safety and control before capability. The kill switch and allowlist must exist before the agent moves.
2. **Into M2:** KnownWorld, the perceived-terrain store.

   *Why:* fair-mode pathfinding in M4 cannot work without it. Rich spatial memory stays in M8.
3. **Into M4:** basic reading of the recipe manager.

   *Why:* the `craft` primitive needs recipes. JEI/EMI, quests and the per-pack knowledge base stay in M7.
4. **Into M5:** decision-trace recording and a minimal read-only trace page.

   *Why:* the brain cannot be debugged without them. The full viewer and replay stay in M12.

Two gates are added:

- **Chat sending stays disabled until M10**, when the outbound guard ships.
- **No real servers until M10 and M11 are complete.** Local offline servers are used from M1.

## Alternatives rejected

- **Pack discovery (M7) before the brain (M5).** The brain can be built and tested on vanilla content first. Pack discovery is easier to validate once the brain can use it.
- **Survival (M6) before the brain.** The reflex layer is independent of the brain, but "survive the first days unassisted" needs goal-directed play.

## Consequences

- M1 grows slightly, and M11 and M12 shrink slightly.
- Every milestone still ends with a runnable demo.
