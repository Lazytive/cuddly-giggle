# ADR 0001: Two processes, a client mod and a Python agent service

- Status: Proposed
- Date: 2026-09-30

## Context

The build prompt specifies a client-side NeoForge mod plus a Python asyncio service, connected by a local WebSocket. The agent has to:

- play any NeoForge 1.21.1 modpack in a real client;
- show human-looking play on its own screen and to other players;
- keep reflexes working when models are slow or down;
- evolve its brain quickly, using the Python LLM and ML ecosystem.

## Decision

Keep the specified split, and pin down the ownership rules:

- **The mod owns every tick-rate and safety-critical concern.** That covers perception and the visibility filter, input, motion, primitives, navigation, reflexes, the kill switch and pause, the allowlist, and cheat enforcement.
- **The service owns everything that needs models, memory or long-horizon reasoning.**
- **The mod is authoritative for enforcement settings.** It reads them from disk and reports them in `hello`. The service can never change them (ADR 0009).
- **Loopback only**, with token authentication (ADR 0005).
- **The mod works without the service.** It is DORMANT when none is connected, and runs `reflex_only` when the service dies mid-session.

## Alternatives rejected

- **A single JVM process with the brain in Java.** This loses the Python LLM, embedding and sandbox ecosystem. Model-call bugs could also stall the game thread.
- **A headless protocol bot (Mineflayer-style).** It cannot load client-side mods, render GUIs, or look like a human client on screen. It also cannot join modded servers that require a modded client.
- **An external pixels-only agent (VPT-style) using OS input.** It throws away structured state the client already has, and needs learned low-level policies we do not have. It fights the operator for the real mouse and keyboard, and needs window focus.

## Consequences

- There are two codebases and one schema. The schema is shared through `protocol/` and golden fixtures.
- Every cross-process call adds latency. Tactical loops therefore run as mod primitives, with Python skills sequencing them, not steering them frame by frame.
- Crashes are isolated: a service crash cannot crash the game.
