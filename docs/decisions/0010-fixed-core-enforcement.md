# ADR 0010: Fixed-core enforcement, cheats without evasion, and a proposed disclosure amendment

- Status: Proposed. Item C needs an operator decision.
- Date: 2026-09-30

## Context

Build prompt §13 lists six rules that protect the operator and real people and must not be settings. §14 says cheats are plain client features, and forbids anti-cheat detection evasion. §12 makes AI disclosure a setting with four modes, including `deflect` and `stay_in_character`.

## Decision

### A. Where each rule is enforced

Rules are enforced in code, at the narrowest point, and preferably in the mod:

1. **Kill switch and pause always work.** Mod `control`, checked in three places (ADR 0003), plus an emergency chord that cannot be unbound, and a dead-man timer.
2. **Player text is data; links are never clicked.**
   - The service keeps player text structurally separate: untrusted text only ever appears as quoted data, the chat role has no tools, and goal changes need a strategist decision that records provenance.
   - While the agent is in control, the mod cancels link-confirmation screens and URL-opening click events.
3. **No PII or credentials.** The mod never reads or sends the session token. No schema field can carry it, and a test scans every outbound message. The outbound guard applies the operator deny-list. API keys are only read from the environment.
4. **No harassment, slurs or targeted abuse.** The outbound guard (lexicon plus classifier) runs *after* the trash-talk setting, so the setting cannot lift it.
5. **No crash exploits, lag machines or server degradation.** There is no packet API anywhere. The blueprint validator rejects lag-machine patterns. Chat and commands are rate-limited. The sandbox has no raw command access.
6. **No real-money transactions.** No payment capability exists. The social classifier and outbound guard refuse real-money deals, and links are never opened.

### B. Cheats

- Each cheat is its own toggle, read by the mod from disk.
- Cheats are announced to the brain.
- A cheat is never disguised. For example, perfect aim does not add humanised noise to hide itself, and nothing detects or adapts to anti-cheat plugins.
- Config comments warn that servers which forbid cheats may ban the account.

### C. Proposed addition (operator to decide)

**Even under `deflect` or `stay_in_character`, the agent never states that it is human in reply to a sincere question about whether it is an AI.**

How the modes would behave:
- `stay_in_character` keeps the persona during banter and role-play.
- `deflect` may change the subject or decline to answer.
- Neither mode may produce the false claim.

Rationale: a sincere question comes from a real person who wants to know whether they are talking to a human, often to decide what to share or whether the server's rules are being followed. The rest of the fixed core already protects real people outside the game, and this fits with it. It costs the operator almost nothing: the agent can still play fully in character.

If the operator declines, the four modes are implemented exactly as §12 describes.

## Alternatives rejected

- **Enforcing the fixed core with prompt instructions only.** Models can be talked out of instructions. Code-level guards cannot be.

## Consequences

- The outbound guard is a single choke point that every piece of outgoing text passes through: chat, signs, books and anvil names. It has its own red-team fixture set (M10).
