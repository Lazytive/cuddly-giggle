# ADR 0007: Skill sandbox. CPython on WASI inside wasmtime

- Status: Proposed. A spike in M5 confirms or replaces this before skills written by the agent are enabled.
- Date: 2026-09-30

## Context

The agent writes Python skills. They must run with timeouts and no filesystem or network access beyond the agent's own API. Pure-Python in-process sandboxes have a long history of escapes. The operator's OS is not yet known, and could be Windows or Linux.

Checked on 2026-09-30:

- `wasmtime` on PyPI is version 49.0.0, licensed Apache-2.0 WITH LLVM-exception.
- CPython WASI builds are published in `brettcannon/cpython-wasi-build`, with releases up to the 3.15 release candidates.

## Decision

Run each skill invocation in a WASI build of CPython inside wasmtime, in a worker process:

- **No capabilities by default.** No preopened directories, no sockets, no environment variables.
- **Host functions are the whole API:** `primitive(name, args)`, `observe(query)`, `memory_query(q)`, `call_skill(name, args)`, `log(msg)`. Each is checked against the invoking skill's scope.
- **Limits.** CPU is bounded by fuel or epoch interruption, memory by a linear-memory cap, and every run has a wall-clock timeout. Instances are kept warm to hide startup latency.
- **Checks before a skill is saved.** Before a skill reaches the sandbox: static checks (parse, forbidden constructs, size), then critic review, then a trial run whose postconditions are verified.

## Alternatives rejected

- **RestrictedPython or AST allowlists in-process.** Known escape classes, and they share the service's memory and credentials.
- **A subprocess plus an OS sandbox (seccomp, bubblewrap, job objects).** Strong on Linux, but uneven across platforms and complex to get right.
- **Containers (Docker or Podman).** Heavy, and not available on every operator machine.
- **JavaScript isolates.** A different language from the one the build prompt specifies.
- **A declarative skill DSL only.** Safe, but too weak for the loops and conditionals real skills need. It remains the fallback if the spike fails.

## Consequences

- Skills can only use the stdlib subset available on WASI. That is acceptable, because skills should only call our API.
- Spike acceptance criteria:
  - cold start under 300 ms and warm start under 20 ms per invocation;
  - an escape test suite fails closed;
  - the WASI CPython build's licence is confirmed.
