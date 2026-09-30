# ADR 0005: Protocol over loopback WebSocket, JSON, with JSON Schema as source of truth

- Status: Proposed
- Date: 2026-09-30

## Context

The build prompt asks for a local WebSocket carrying a versioned JSON schema. The protocol must support:

- snapshots and deltas;
- events;
- skill invocation with progress and results;
- cancellation;
- screenshots;
- heartbeat.

The service validates every message (pydantic). Two languages must agree on one schema.

## Decision

- **Transport.** WebSocket on `127.0.0.1`. The service is the server and the mod the client. The mod uses Java 21's built-in `java.net.http.WebSocket`, so there is nothing to shade. JSON is handled by Gson, which is already on the Minecraft classpath. The Python side uses `websockets` (BSD-3-Clause).
- **Authentication.** A per-run random token, stored in a file only the user can read, sent as a bearer header. Requests that carry an `Origin` header are rejected (browsers always send one).
- **Schema.** JSON Schema files in `protocol/schema/` are the source of truth. Pydantic models are generated from them or checked against them in CI. The Java codec is tested against the same golden fixtures in `protocol/fixtures/`.
- **Framing.** Text frames carry JSON messages. Binary frames carry screenshots, with a small JSON header.
- **Versioning.** `MAJOR.MINOR`. A different major version is refused. A minor version may only add optional fields and message types. Unknown fields are ignored.
- **Delivery.** Observations are sequence-numbered deltas with resync on a gap. Commands are acknowledged and never silently dropped.

The outline is in `docs/protocol.md`.

## Alternatives rejected

- **gRPC/protobuf.** It needs a heavy runtime shaded into the mod and is harder to inspect by hand. It also deviates from the build prompt.
- **MessagePack or CBOR.** Smaller, but less debuggable. JSON is fast enough at 4 Hz deltas. Binary frames already cover images.
- **Raw TCP with custom framing.** Reinvents WebSocket, and has no ready browser tooling for the viewer.

## Consequences

- Messages can be read by eye in logs and traces.
- The schema pipeline must be in place in M1, before any feature work.
