# ADR 0008: Model router design

- Status: Proposed
- Date: 2026-09-30

## Context

No model may be hard-coded. The build prompt names six roles (strategist, planner, fast actor, vision, critic, chat), each configured independently. Each role needs a provider, a model, a reasoning level, timeouts and fallbacks.

Required adapters: OpenAI-compatible APIs, the Anthropic API, and local runners (Ollama, llama.cpp server, vLLM). There is no cost ceiling, but runaway loops must be prevented. The operator will compare models before assigning them to roles.

## Decision

- **Roles.** The six specified roles plus **`embedder`**, because retrieval for memory and skills needs an embedding model and the operator should be able to choose it (local or hosted) like any other role.
- **Normalised request and response.** A request carries messages with text and image parts, an optional JSON output schema, a reasoning level, and a max-token budget. A response carries text or parsed JSON, usage, latency, provider, model, and whether a fallback was used.
- **Adapters:**
  - `openai_compatible` covers OpenAI, vLLM, llama.cpp server, Ollama's OpenAI endpoint, and other compatible hosts;
  - `anthropic` uses the Messages API;
  - `ollama` is the native API, for model management and keep-alive.

  The reasoning level maps to each provider's own parameter. The names are verified against current SDK docs when implemented in M5, not assumed here.
- **Guards:**
  - per-role timeouts and retries (exponential backoff with jitter);
  - an ordered fallback chain;
  - a circuit breaker per provider;
  - per-role concurrency and rate caps;
  - an identical-call detector: the same role and prompt hash repeated N times in a window is blocked and raises a stuck signal;
  - usage alarms, which warn but do not cap.
- **Structured output.** Validated with pydantic. At most one repair attempt.
- **Comparison tooling.** `router replay` re-runs recorded prompts against another role assignment and scores the results. An optional per-role shadow mode logs a second model's answer without acting on it.
- **Local-model hygiene.** Every role can be local or remote. Using a local model for a role is opt-in, because a local model may share the GPU with the game.

## Alternatives rejected

- **LiteLLM or a similar meta-SDK as the only adapter layer.** Convenient, but it adds a large, fast-moving dependency between us and provider-specific features (reasoning parameters, image input, caching). We may still use it as an extra adapter.
- **One model for everything.** Contradicts the build prompt, and wastes latency on small decisions.

## Consequences

- Every call is recorded in the decision trace, including prompt, response, model and latency. That makes model comparisons reproducible.
