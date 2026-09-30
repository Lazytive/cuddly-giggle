# ADR 0003: Inject synthetic input at the GLFW-callback boundary

- Status: Proposed
- Date: 2026-09-30

## Context

All actions must go through simulated player input: key states, mouse deltas into the camera, and GUI clicks through the screen's own handlers. There must be no packets, teleports or instant rotation. The kill switch has to work even when a screen is open and the service is dead.

Verified in NeoForge 1.21.1 patches (`KeyboardHandler.java.patch`, `MouseHandler.java.patch`):

- **Keys.** GLFW key events enter `KeyboardHandler.keyPress`. When a screen is open, it fires `ScreenEvent.KeyPressed.Pre`, then `screen.keyPressed`, then the `Post` event. If the screen consumes the key, processing stops before `InputEvent.Key` is posted at the end of the method.
- **Mouse.** Mouse motion accumulates in `accumulatedDX/DY`. `turnPlayer` scales those values by sensitivity (and the cinematic camera) before turning the player. NeoForge lets mods adjust the sensitivity values through `ClientHooks.getTurnPlayerValues`.

## Decision

The virtual keyboard and mouse call the **same callback methods GLFW would call**: key press and release, character input, cursor position, mouse buttons, and scroll. Downstream code (key mappings, screens, NeoForge events, sensitivity, other mods) therefore cannot tell synthetic input from physical input.

- **Camera.** Driven per render frame by a stream of cursor movements from the motion model, not per tick.
- **Tagging.** Synthetic calls run with a thread-local "synthetic" flag. The kill switch, pause and takeover detection ignore flagged events.
- **Kill switch.** Detected in `ScreenEvent.KeyPressed.Pre`, in `InputEvent.Key`, and by a per-frame physical-key poll.
- **Direct polls.** Code that polls GLFW directly instead of using callbacks gets a small shim so it also sees virtual held keys. The call sites are listed in M1 and tested in M4.
- **Access.** Callback methods that are private get an access transformer entry. Each entry is documented and covered by a test.

## Alternatives rejected

- **`KeyMapping.setDown` / `click` only.** This misses screen key handling, character input, NeoForge input events, and mods that listen to raw input.
- **Rewriting `Input` in `MovementInputUpdateEvent`.** Movement only, and it bypasses key state, so other mods and vanilla sprint and sneak logic would disagree.
- **OS-level input (`java.awt.Robot`, xdotool, SendInput).** It needs window focus, takes over the operator's real cursor and keyboard, is platform-specific, and makes the kill switch race the injector.
- **Setting rotation directly.** Forbidden by the build prompt, and it skips sensitivity quantisation.

## Consequences

- Movement feels exactly like a player at the keyboard, including GUI behaviour quirks.
- We depend on a handful of private vanilla methods. They are stable within 1.21.1 and isolated in the version adapter.
- Physical input from the operator still works. Takeover detection pauses the agent when the operator touches the controls, if configured to.
