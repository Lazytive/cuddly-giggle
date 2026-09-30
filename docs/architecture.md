# Architecture plan

Status: **draft for operator review**. No code has been written yet. Milestone 1 starts only after the operator approves this plan.

Companion documents:

- [`milestones.md`](milestones.md): milestone list, demos, acceptance criteria, and the decisions the operator needs to make.
- [`protocol.md`](protocol.md): outline of the mod ↔ service message schema.
- [`research-notes.md`](research-notes.md): sources checked while writing this plan, and what we take from each.
- [`decisions/`](decisions/): architecture decision records (ADRs).

Working name: **`mcagent`**, used for the mod id, Java package root and Python package. It is a placeholder, and the operator can rename it (see milestones.md, "Operator decisions").

---

## 1. System overview

```
┌──────────────────────── Minecraft 1.21.1 client (NeoForge, Java 21) ────────────────────────┐
│  mcagent mod (client-only)                                                                  │
│                                                                                             │
│  control ── kill switch / pause / takeover / dead-man   (works with the service down)       │
│     │                                                                                       │
│  reflex ──┐   primitives ──┐   navigation ──┐                                               │
│           ▼                ▼                ▼                                               │
│         InputArbiter (priority: kill > reflex > operator > skill > idle)                    │
│           │                                                                                 │
│         HumanMotionModel ──► VirtualInput ──► GLFW-callback boundary ──► vanilla input path │
│                                                                                             │
│  perception: VisibilityFilter → KnownWorld, EntityTracker, GuiReader, Sounds, Chat,         │
│              Screenshots, Inventory/PlayerState  →  ObservationBuilder (snapshots + deltas) │
│  knowledge:  registries / tags / recipes / JEI / EMI / FTB Quests (soft deps)               │
│  bridge:     WebSocket client, codec, heartbeat, outbound queue          overlay, commands  │
└───────────────────────────────────────────┬─────────────────────────────────────────────────┘
                                            │ ws://127.0.0.1  (token-authenticated, versioned JSON)
┌───────────────────────────────────────────▼─────────────────────────────────────────────────┐
│  agent service (Python 3.12+, asyncio)                                                      │
│                                                                                             │
│  transport + protocol (pydantic)      config + profiles + fixed core                        │
│  brain: scheduler · goals · curriculum · planner · executor · critic · monitor · death      │
│  router: roles → provider adapters (OpenAI-compatible, Anthropic, Ollama) + guards          │
│  memory: spatial · episodic · semantic · experience          skills: library + WASI sandbox │
│  knowledge: per-pack KB · web lookup · guidebooks            building: design → build       │
│  social: chat pipeline · outbound guard · disclosure         personality                    │
│  observability: logs · decision traces · replay · local web viewer                          │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

The two-process split, the three speed layers and the transport are specified by the build prompt. ADR 0001 records why we keep them and what we add.

### Speed layers and where they run

| Layer | Latency | Runs in | Examples | Model calls? |
|---|---|---|---|---|
| Reflex | every tick (50 ms) and every frame for the camera | mod | dodge, flee, shield, eat, lava/fall/suffocation avoidance, basic melee | never |
| Tactical | 0.1 s to tens of seconds | mod primitives, driven by Python skills | mine a vein, craft a chain, walk somewhere, fight a mob | none, except an optional *fast actor* for small choices |
| Strategic | seconds to minutes | service | choose goals, plan, reflect, write skills, design builds, chat | yes, triggered by events and on a cadence |

---

## 2. Repository layout (proposed)

```
mod/                         Gradle multi-project (ModDevGradle), Java 21
  core/                      pure Java, NO Minecraft on the classpath: motion model maths, pathfinder,
                             visibility geometry, protocol codec, config model, reflex rules
  neoforge-1.21.1/           the actual mod: adapters implementing core interfaces against 1.21.1
  gametest/                  separate test mod (both sides) with NeoForge GameTests
service/                     Python package `mcagent` (uv-managed), pytest, ruff, pyright
protocol/                    JSON Schema (source of truth) + golden message fixtures
config/                      example mcagent.toml, example personality profile
scenarios/                   scenario worlds and scripted checks
tools/                       launch scripts (dev client, Xvfb, local test server), trace export
docs/                        this plan, ADRs, config reference (generated), runbooks
```

**Version isolation (build prompt §1).** `mod/core` is compiled without Minecraft on the classpath, so the compiler enforces that version-specific code stays in `mod/neoforge-1.21.1`. A port to another version adds a sibling adapter project. The core also gets fast plain JUnit tests that need no game launch. See ADR 0013.

---

## 3. The mod (client-only)

### 3.1 Packaging and loading

- `@Mod(value = "mcagent", dist = Dist.CLIENT)`, the mechanism the NeoForge 1.21.1 docs recommend for code that must only load on the physical client (verified; see research-notes.md).
- The mod registers **no** network payloads, registry entries, blocks, items or data-pack content. Vanilla and NeoForge servers therefore have nothing to negotiate with it, so it can join servers that lack it. M1 verifies this against a vanilla server and a NeoForge server.
- Soft dependencies (JEI, EMI, FTB Quests, guidebook mods, minimap mods) are declared `type="optional"`. Their classes are only touched behind a `ModList` check, in isolated integration classes.
- Mixins and access transformers are kept to a documented minimum. Each one has a comment saying why, and a GameTest or scenario that would break if the target moves.

### 3.2 Control (`control`)

A state machine: `DORMANT → RUNNING ⇄ PAUSED`, with `KILLED` reachable from any state.

- **DORMANT**: no service connected, server not on the allowlist, or the agent is disabled for this world. The mod does nothing but observe.
- **Kill switch**: releases every virtual key and button, cancels all primitives, reflexes and the motion model, stops sending input, and latches `KILLED` until the operator re-arms it. It is detected in three independent places, so a screen that swallows the key cannot block it:
  1. `ScreenEvent.KeyPressed.Pre`, which fires before the open screen sees the key;
  2. `InputEvent.Key`, for in-world input;
  3. a per-frame poll of the physical key state in `RenderFrameEvent.Pre`.

  There are two triggers: a rebindable key mapping, and a fixed emergency chord that cannot be unbound (fixed core §13.1).
- **Pause/resume**: the same release-all step, but resumable. The service is told, and in-flight skills get `cancelled: paused`.
- **Operator takeover**: real (non-synthetic) keyboard or mouse input while the agent is running pauses it (setting: `takeover = "pause" | "ignore"`, default `pause`).
- **Dead-man**: if no heartbeat arrives from the service for `service_timeout_s` (default 5 s), inputs are released. The mod then either stays in `reflex_only` mode, which keeps the player alive without pursuing goals (default), or freezes, depending on the setting.
- **Server allowlist**: the address is checked on `ClientPlayerNetworkEvent.LoggingIn`. If it is not on the allowlist, the mod stays `DORMANT`. The agent can only initiate joins to allowlisted addresses. This gate is read by the mod from disk and cannot be changed over the socket (ADR 0009).

### 3.3 Input (`input`)

**Synthetic input is injected at the GLFW-callback boundary** (ADR 0003). The virtual keyboard and mouse call the same `KeyboardHandler` / `MouseHandler` callback methods that GLFW calls for physical devices. Everything downstream then sees exactly what a real device would produce: `KeyMapping` state and click counts, screen `keyPressed`/`mouseClicked`/`charTyped`, NeoForge input and screen events, mouse sensitivity and the `turnPlayer` pipeline (verified in the 1.21.1 `MouseHandler` patch), and other mods' input hooks.

- Camera movement is a stream of mouse deltas, applied per render frame, not per tick.
- GUI interaction moves a virtual cursor along human trajectories, then presses and releases through the same callbacks.
- Text is typed through `charTyped` at human speed, for chat, text fields and JEI search.
- **Synthetic events are tagged** (thread-local flag during injection), so the kill switch and takeover detection only react to real devices.
- **Known gap**: some code polls GLFW directly instead of using callbacks (for example shift-click checks in screens). A small shim makes those polls also see virtual held keys. This is verified in M1 and M4 against the 1.21.1 decompiled sources.
- `InputArbiter` gives each input channel (movement keys, look, hands, hotbar, GUI cursor, text) to the highest-priority requester: `kill > reflex > operator-directed > skill > idle`. Preempted owners get a `preempted` result.

### 3.4 Human-motion model (`motion`)

Everything that moves the camera, cursor or avatar goes through this model. Cheats bypass it deliberately and visibly (§3.12).

- **Aiming**: primary submovement on a minimum-jerk profile, sized by Fitts's law (`MT = a + b·log2(D/W + 1)`, where D is the angular distance and W the angular target size). It overshoots or undershoots, then makes 0–2 corrective submovements. Small tremor noise is added, and misses are possible.
- **Reaction delays**: sampled from a right-skewed distribution (log-normal by default; ex-Gaussian available). Context modifiers: expected events are faster, surprises slower, choices scale with the number of options (Hick–Hyman), and fatigue raises the mean and variance over long sessions.
- **GUI**: cursor trajectories use the same aiming model. There is a reading pause when a screen first opens (scaled by the number of slots and widgets and by familiarity), click intervals have variance, and hotbar switches are sometimes done by scroll wheel and sometimes by number keys.
- **Third-person appearance**: other players see only what the server relays: position, head yaw and pitch, pose (sneak, swim, sleep), sprint, arm swing, held and worn items, and block-break progress. Body yaw is derived on the observer's client from movement and head yaw. The model shapes those channels: an attention/glance scheduler (looks at mobs, players, sounds and interesting blocks), curved paths with slight wander, sprint-jump rhythm, sneaking near edges, look-around stops, idle fidgets, and occasional build mistakes that it then fixes. That the observer's client derives body yaw is **a hypothesis to confirm in M3** with a second observer client.
- **Recorder**: logs camera deltas, inputs and timing, both from the operator's own play (for calibration) and from the agent (for comparison). The human-likeness tests in M3 and M13 compare these distributions.
- All parameters are settings, plus an overall `realism` level that scales them.

### 3.5 Perception (`perception`)

- **ObservationBuilder** assembles player state (health, food, saturation, armour, effects, XP, position, facing, F3-level data), inventory, hotbar, equipment, open-container contents, time, weather, dimension and biome. It emits full **snapshots** (on connect, on request, every N s) and **deltas** (default 4 Hz), each carrying a sequence number (protocol.md §4).
- **VisibilityFilter (fair mode)** runs in the mod, so the rule is enforced in code, not left to the model:
  - candidate blocks are those in render distance, inside the camera frustum, in render sections that vanilla's own section-occlusion pass considers visible, and with at least one face next to a non-occluding block (fully enclosed blocks can never be seen);
  - candidates are confirmed by line-of-sight raycasts to face sample points. Transparent and non-occluding blocks (glass, leaves, fluids, per state) let rays through;
  - a per-tick **ray budget** is spent in priority order (newly entered view, near, salient); the remaining rays revisit stale areas;
  - entities are checked with rays to several points on their bounding box, respecting invisibility. Player name tags, which vanilla renders through walls, count as visible;
  - with fair mode off, perception reads loaded chunks directly.
- **KnownWorld** is a per-dimension sparse voxel store of what the agent has perceived (block state plus last-seen tick), persisted per world. It is the only terrain source for fair-mode pathfinding. The service holds higher-level spatial memory (§4.6).
- **GuiReader** describes any screen generically: screen class, title, container slots (position, item, count, tooltip), widgets from the screen's child listeners (buttons, text fields, tabs, sliders, checkboxes), and progress bars where they can be inferred. If a modded screen draws custom widgets that introspection cannot describe, the service requests a screenshot for the vision role. The learned layout is then cached in the pack knowledge base as a *GUI map*.
- **Sounds**: subtitle-level only (what it is, rough direction, rough distance), via the same kind of listener the vanilla subtitle overlay uses.
- **Chat**: `ClientChatReceivedEvent` (player versus system distinguished), always sent as `untrusted_text`. The same applies to signs, books, item names and entity names.
- **Screenshots**: captured on request and throttled. The capture happens on the render thread, where it must; encoding runs on a worker thread. Sent as binary frames.

### 3.6 Primitives (`action`)

Multi-tick state machines that act only through `motion` and `input`: `look_at`, `move_to` / `follow_path`, `mine_block`, `place_block`, `use_item`, `attack_entity`, `open_container`, `click_slot`, `transfer_items`, `craft`, `select_hotbar`, `drop_item`, `gui_click_widget`, `gui_type_text`, `close_screen`, `type_chat`, `sleep`, `look_around`, `wait`. Each one checks its preconditions (reach, visibility, the right GUI) and **verifies its postcondition from fresh observations**, for example that the block is actually gone or the item is actually in the slot. It returns a structured result with failure codes (protocol.md §6).

### 3.7 Navigation (`navigation`)

- A custom pathfinder (ADR 0004). A* over KnownWorld with movement costs for walk, sprint, jump up, safe drop, swim, ladder/vine, pillar, bridge, break-through and place-over. Unknown voxels are *unknown* (optimistic but penalised, and re-planned once seen), never assumed to be air. Hazards (lava, fire, drop height, suffocation) carry costs.
- A path executor turns the discrete path into human-like motion: lookahead smoothing into curves, speed variation, sprint-jump rhythm, sneaking at edges, and glances handed to the attention model.
- Baritone was evaluated and rejected. Its pathing reads the full client chunk cache, which breaks fair mode, and it sets rotations directly. Details in ADR 0004.

### 3.8 Reflex (`reflex`)

This layer runs every tick in the mod and never waits for the service:

- Threat detection: approaching hostiles (vanilla, plus modded ones learned from damage history pushed by the service), incoming projectiles, a creeper fuse (hiss sound plus swelling), lava/fire/void/fall edges, drowning, suffocation, low health, starvation.
- Responses: step away, strafe, raise the shield, retreat along a known-safe path, eat, swap to a better weapon, pillar or block, drink a potion, fight back when the risk policy allows.
- Reflexes also use the motion model and reaction-time distribution. A human dodges fast, but not in 0 ms.
- Every preemption emits `event.reflex` with its cause, so the brain can learn and replan.

### 3.9 Knowledge extraction (`knowledge`)

Runs at world join and when the pack fingerprint changes. It dumps registries (blocks, items, entities, fluids, enchantments, biomes, dimensions), tags, the recipe manager (including modded recipe types, serialised as generic ingredient/result structures where possible), language strings, tooltips and item data components. Soft integrations:

- **JEI**: `IModPlugin.onRuntimeAvailable(IJeiRuntime)` → `getRecipeManager()` → `createRecipeLookup(RecipeType)`.
- **EMI**: `EmiApi.getRecipeManager()` → `getRecipesByOutput(...)`.
- **FTB Quests**: `ClientQuestFile.getInstance()` / `exists()` (the quest file synced from the server).

All three were verified against the 1.21.1 sources (see research-notes.md). Guidebooks are read through their GUI, with screenshot and vision where needed.

1.21.1 note: in this version the client receives the full recipe set from the server. This is expected to change in later versions, so recipe access sits behind an adapter interface.

### 3.10 Bridge (`bridge`)

- Java 21's built-in `java.net.http.WebSocket` client, so there is no extra dependency to shade. Gson (already on the Minecraft classpath) does the JSON.
- It runs on its own thread. Inbound commands go through a lock-free queue that is drained on the client thread at `ClientTickEvent.Pre`. Outbound observations are coalesced, so a slow service cannot build up an unbounded queue.
- Reconnects with backoff. Auth token and version handshake as in protocol.md §2.

### 3.11 Operator UI (`overlay`, `commands`)

- **Thought overlay**: a HUD layer registered via `RegisterGuiLayersEvent`, showing the current goal, plan step and a short reasoning summary. Off by default.
- **Private suggestion channel**: a dedicated in-game screen opened by hotkey. It never touches the chat box, so nothing typed there can reach the server. The same channel is available from the web viewer. A client command (`RegisterClientCommandsEvent`) is offered as a convenience. The service treats suggestions as trusted *advice*, not orders.

### 3.12 Cheats (`cheats`)

Each cheat (x-ray, entity ESP, flight, reach, perfect aim, fast clicking, instant crafting, visibility filter off) is a separate toggle. Cheats are read by the mod from the config file, never enabled over the socket, and announced to the brain in `hello` so it can plan around them. Cheats are **plain client features**: no detection evasion, no humanised disguise, and no packet tricks. The config comments warn that servers which forbid cheats may ban the account (ADR 0010).

---

## 4. The agent service (Python)

### 4.1 Runtime

Python ≥ 3.12, a single asyncio event loop, and uv for environment management. Key libraries (licences checked, ADR 0012): `websockets`, `pydantic` v2, `structlog`, the `openai` and `anthropic` SDKs, `sqlite-vec`, and `wasmtime` for the skill sandbox. The web viewer uses a small ASGI app. Heavy CPU work (embeddings, preview rendering) runs in executor processes so the loop is never blocked. asyncio slow-callback warnings are enabled in development.

### 4.2 Brain

- **Goal hierarchy**: `aim → objective → task` nodes with status, priority, provenance (`quest`, `self`, `operator_suggestion`, `survival`), progress metric, success predicate, attempt history and cooldown. The strategist owns the tree and can revise it.
- **Scheduler**: wakes the strategist on events (goal done or failed, death, new dimension or biome, new item class, quest completed, damage spike, being addressed in chat, operator suggestion, stuck signal) and on a cadence. It never runs per tick.
- **Curriculum**: a Voyager-style proposer. It keeps a frontier of what is unlocked, proposes goals just beyond current ability, weights them by personality interests and novelty, and cools down goals that failed recently. When a quest book exists, quests are candidates like any other, and the personality decides how closely to follow it.
- **Planner**: DEPS-style *describe → explain → plan → select*. A plan is a list of steps `{skill, args, postcondition, timeout, on_fail}`. Postconditions are predicates evaluated on observations, such as inventory counts, block states or position.
- **Executor (tactical)**: runs steps, streams progress, handles preemption and cancellation, and **verifies every postcondition from observations**, never from the model saying it succeeded.
- **Critic**: fuzzy verification ("is the roof finished?", from a screenshot), and review of new skills.
- **Monitor (stuck and loop detection)**: flags repeated identical failures, small radius of gyration while movement input is active, revisiting the same cells, A↔B goal oscillation, a progress metric that stops changing, and repeated identical model calls. Its response escalates: retry differently → reflect → pick an alternative → abandon with a recorded lesson.
- **Death handler**: records the cause (death message, last damage events, location, dimension, time) and the lost inventory (last snapshot). It decides whether to retrieve by weighing item value against estimated risk and despawn time. It writes a lesson to experience memory.

### 4.3 Model router

Roles, each configured independently: `strategist`, `planner`, `fast_actor`, `vision`, `critic`, `chat`, plus **`embedder`**, which this plan adds because memory and skill retrieval need embeddings. Per role: provider, model, generation parameters, a *reasoning level* (mapped to each provider's own parameter), timeout, retries, a fallback chain, concurrency and rate limits.

- **Adapters**: `openai_compatible` (OpenAI, vLLM, llama.cpp server, Ollama's OpenAI endpoint, other compatible hosts), `anthropic` (Messages API), and `ollama` (native, for model management and keep-alive). Parameter names are verified against current SDK docs when implemented (M5), not assumed here.
- **Structured output**: every role that returns data must match a pydantic schema. At most one repair retry, then failure.
- **Guards**: identical-call detection (same role and prompt hash repeating within a window → blocked, plus a stuck signal), exponential backoff with jitter, a circuit breaker per provider, and global concurrency caps. There is no cost ceiling, but there are usage alarms in the viewer.
- **Comparison**: `mcagent router replay` re-runs recorded prompts from traces against other model assignments and scores them with deterministic checks plus the critic. There is also an optional shadow mode per role that logs a second model's answer without acting on it.

### 4.4 Skills

- **Two levels**: *primitives* in the mod (Java), and *skills* in Python composed from primitives and other skills (Odyssey-style primitive/compositional split).
- **Sandbox**: agent-written skills run in CPython compiled to WASI, inside wasmtime, in a worker process. No preopened directories and no sockets. The only host functions are `primitive()`, `observe()`, `memory_query()`, `call_skill()` and `log()`. CPU is limited by fuel/epoch interruption, memory by a linear-memory cap, and every run has a wall-clock timeout. ADR 0007 is **proposed**, pending a spike in M5.
- **Promotion pipeline**: static checks → critic review → trial run in the live game → verified postconditions → saved with metadata, embedding, version and success statistics. Skills are scoped `global`, `pack` or `world`.

### 4.5 Knowledge (per modpack)

- **Pack fingerprint**: a hash of the sorted `(modId, version)` list plus relevant config hashes. Each fingerprint has its own knowledge base.
- **On a fingerprint change**: mods that were added, removed or changed version are diffed, and only knowledge sourced from those mods is invalidated.
- **Knowledge base contents**: registries, tags, recipes (as a hierarchical tech/recipe graph, following Optimus-1), quest graph, item descriptions, GUI maps, pack-scoped facts and skills.
- **Web lookup**: a tool for the strategist and planner that searches and fetches mod wikis and documentation. Page content is untrusted data, like chat. Retrieved facts are stored with their source and a confidence.

### 4.6 Memory

- **Spatial**: points of interest (ores, structures, containers with their contents, machines, farms, landmarks, bases, other players' bases, deaths) and region summaries. Terrain voxels stay in the mod's KnownWorld; the service keeps meaning and indexes.
- **Episodic**: what–where–when events (after Mr.Steve's place-event memory), including failures and their causes.
- **Semantic**: facts with scope (`game`, `pack:<fp>`, `world:<id>`), confidence and source.
- **Experience**: abstracted lessons indexed along structure, attribute, process, function and interaction (after the 2026 Echo / experience-transfer work). Retrieved by analogy when a new task or pack looks similar.
- **Retrieval**: hybrid search (vector similarity, keyword, spatial proximity, recency, success weighting).

### 4.7 Building

The pipeline is design → blueprint → bill of materials → acquisition tasks → build order → construction → review.

- **Blueprints** are JSON (palette plus a 3-D block array plus metadata), with export to a common schematic format (format chosen and verified in M9).
- **Design** combines procedural grammars (footprint, massing, roof, openings) with model-driven choices from the personality's style profile. The vision role critiques rendered previews and in-game screenshots.
- **Build order**: foundation up, with support and scaffold planning, reachability and safe standing spots, and minimal travel. The motion model adds human rhythm, occasional mistakes that get fixed, and step-back-and-look pauses.
- **Blueprint validator**: rejects lag machines and server-degrading contraptions (fixed core §13.5).

### 4.8 Social

The chat pipeline is: ingest (untrusted) → classify (addressed to me? question? sincere question about being an AI? rules or server info?) → decide whether to respond → compose (chat role, with personality) → **outbound guard** → mod types it at human speed.

- The outbound guard is fixed core and cannot be configured. It blocks operator PII, links, slurs and targeted harassment, and real-money dealings, and enforces a chat and command rate limit.
- No chat content can change goals, settings or rules. The chat role has no tools. The strategist sees chat only as quoted data with a speaker label.

### 4.9 Personality

A TOML profile edited by the operator: name, pronouns (default she/her), playstyle, building style, risk tolerance, curiosity, patience, sociability, likes and dislikes. It evolves slowly: each change is bounded, logged with a reason, and can be reverted. The operator can lock any field.

### 4.10 Configuration

A single TOML file (`mcagent.toml`) with `[profiles.*]`, inheritance, and selection rules keyed by world or server address (ADR 0009).

- The mod reads its enforcement settings (allowlist, fair mode, cheats, realism, overlay default) **directly from disk**. The service cannot change them over the socket.
- API keys come from environment variables, and operator PII for the deny-list from a separate file. Neither goes into prompts.
- The fixed core is code, not config.
- `docs/config-reference.md` is generated from the pydantic config model, so every setting has a documented default.

---

## 5. Data stores

| Store | Owner | Format | Contents |
|---|---|---|---|
| `mcagent.toml` | operator | TOML | all settings and profiles |
| `personality/*.toml` | operator + agent (bounded drift) | TOML + change log | personality profile |
| `operator-private.toml` | operator | TOML, never sent to models | PII deny-list strings for the outbound guard |
| `<gameDir>/mcagent/known_world/<world>/<dim>/` | mod | palette-compressed 16³ sections with a known-mask | perceived terrain |
| `<gameDir>/mcagent/recordings/` | mod | compressed JSONL | motion and input traces (operator and agent) |
| `data/agent.sqlite` | service | SQLite (WAL) + sqlite-vec | episodic, global semantic facts, experience, skill metadata, personality history |
| `data/packs/<fingerprint>/kb.sqlite` | service | SQLite + sqlite-vec | registries, tags, recipe graph, quests, GUI maps, pack facts and skills |
| `data/worlds/<world-id>/world.sqlite` | service | SQLite | spatial POIs, containers, bases, deaths, world goals |
| `data/skills/<scope>/<name>/v<N>.py` | service | source + JSON metadata | skill code and verification records |
| `data/traces/<session>/` | service | JSONL + images, indexed in SQLite | decision traces, model calls, screenshots |
| `data/blueprints/` | service | JSON (+ schematic export) | designs |

World identity: singleplayer uses the save folder name plus the level's creation time. Multiplayer uses the normalised server address. Every store is keyed so nothing leaks between packs, worlds or servers unless it is deliberately promoted (to `game`-scoped facts or experience).

---

## 6. Fixed core enforcement (build prompt §13)

| # | Rule | Enforced by |
|---|---|---|
| 1 | Kill switch and pause always work | mod `control`: three detection paths, a fixed emergency chord, release-all, dead-man timer; no setting can disable them |
| 2 | Player text is data, never commands; never click links | service: structural separation (chat role has no tools; untrusted text only as quoted data). Mod: cancels link-confirmation screens and URL-opening click events while the agent is in control |
| 3 | No operator PII or credentials | mod never reads or sends the session token (no schema field exists for it; outbound messages checked in tests); service outbound guard with the operator deny-list; API keys only from the environment |
| 4 | No harassment, slurs or targeted abuse | outbound guard (lexicon + classifier), applied *after* the trash-talk setting |
| 5 | No crash exploits, lag machines or server degradation | no packet access anywhere; blueprint validator; sandbox API has no raw command spam; chat/command rate limit |
| 6 | No real-money transactions | no payment capability exists; the outbound guard and social classifier refuse real-money deals; links are never opened |

ADR 0010 also **proposes one addition for the operator to decide**: even under `deflect` or `stay_in_character`, the agent never *asserts* that it is human in reply to a sincere question. It may deflect, but it may not lie.

---

## 7. Key risks and mitigations

| # | Risk | Mitigation |
|---|---|---|
| R1 | **This cloud environment cannot build or run the mod.** Its network policy denies `maven.neoforged.net`, Mojang's hosts (`piston-meta`, `libraries`, `resources.download.minecraft.net`), `maven.parchmentmc.org`, `maven.blamejared.com` (JEI) and `maven.terraformersmc.com` (EMI). | The operator either allows these hosts in the environment's network settings or runs Gradle and the client locally. The Python service and `mod/core` can be built and tested here either way. Decision needed before M1. |
| R2 | Automated client runs need a display and GPU. | Xvfb plus Mesa software rendering for light scenarios; a self-hosted runner with a GPU (likely the operator's machine) for modpack scenarios. Minecraft's `pauseOnLostFocus` is disabled in agent profiles. |
| R3 | Input-injection fidelity: code that polls GLFW directly, focus loss, other mods' input handling. | Inject at the callback boundary; add a shim for direct polls; M1 and M4 scenario tests on shift-click, drag-split, JEI search typing, and modded GUIs. |
| R4 | Cost and correctness of the visibility filter. | Pre-filter candidates with vanilla section occlusion and exposed faces; per-tick ray budget; tick overhead measured and budgeted (§8); GameTests with hidden-ore and wall fixtures; ground-truth oracle in scenario tests. |
| R5 | Human-likeness is hard to measure without reference data. | Recorder in M3; the operator records some sessions; distribution tests (Fitts slope, velocity profile, reaction times, overshoot rate, click intervals). The VPT contractor dataset is a candidate reference, but it states no licence, so it needs the operator's OK. |
| R6 | Arbitrary modded GUIs. | Generic introspection, then vision fallback, then cached GUI maps per pack. JEI/EMI "show recipe" views as a second source. |
| R7 | Recipe types that cannot be interpreted structurally, or missing loot knowledge (loot tables are not synced to the client). | JEI/EMI plugins, guidebooks, web lookup, and learning from experience. Knowledge carries confidence and source. |
| R8 | Safety of generated code. | WASI sandbox with capability-only host API, resource limits, critic review, trial runs. No filesystem or network access. |
| R9 | Prompt injection via chat, signs, books or web pages. | Structural: untrusted text is only ever quoted data; the roles that see it cannot change goals or settings; enforcement settings live in the mod and on disk. Red-team fixtures in tests. |
| R10 | LLM latency or outages mid-fight. | Reflexes in the mod; tactical work in code; fallback chains; dead-man switch to `reflex_only`. |
| R11 | Runaway model loops. | Identical-call guard, circuit breakers, rate caps, stuck detection, usage alarms. |
| R12 | Long-session stability (leaks, reconnects, database growth). | Soak tests in M13; bounded caches; WAL checkpoints; log and trace rotation; reconnect tests with the service killed and restarted. |
| R13 | Server rules and account risk: many servers ban bots. | Allowlist; rule-following on by default, and it includes rules about bots and automation (if they are forbidden, the agent pauses and tells the operator); disclosure default `disclose_if_sincerely_asked`; no evasion. |
| R14 | Licences. | ADR 0012. Nothing is copied from All-Rights-Reserved projects (FTB Quests is a runtime soft dependency only); Baritone (LGPL-3.0) is not used; `THIRD_PARTY_NOTICES` is kept current. |
| R15 | Local models sharing the GPU with the game. | Local use is optional per role; frame-time monitoring; the router can move a role to a remote provider. |
| R16 | Version churn. Minecraft has moved on (26.x at the time of writing), and 1.21.2+ changed client recipe sync. | Adapter boundary (§2); the 1.21.1 ecosystem is stable, so a port is a contained, separate project. |

---

## 8. Performance budgets (initial targets, measured from M1)

| Item | Target |
|---|---|
| Mod work per client tick (perception + reflex + executor + bridge) | mean < 1 ms, p99 < 3 ms on the reference machine |
| Per-frame camera update | < 0.2 ms |
| Visibility ray budget | adaptive; default 2 000 rays/tick, lowered automatically if the tick budget is exceeded |
| Observation deltas | 4 Hz default; snapshot every 10 s or on request |
| Screenshots | ≤ 1 per 2 s by default; encoding off the render thread |
| Service event loop | no callback > 50 ms |
| Reflex reaction | human-distributed (not faster than the configured minimum) |

The mod publishes its own timings in the heartbeat, so the viewer can graph them.

---

## 9. Testing strategy (summary; details per milestone)

- **`mod/core`**: plain JUnit, running in seconds: motion maths, pathfinder on synthetic grids, visibility geometry, codec round-trips.
- **NeoForge GameTests** (in the `gametest` test mod, run by the GameTest server): world-logic parts of primitives against real server levels. This covers placement-face and reach computation, the visibility filter on hidden-block fixtures, and the pathfinder on built terrain. GameTest is server-side, so **end-to-end input-driven primitives are tested with client scenarios instead**.
- **Client scenarios**: the Python scenario runner launches the dev client (Xvfb on Linux) into a template world, drives the agent through the real protocol, and checks results. It uses a *test-only oracle* that reads integrated-server state, compiled out of release builds, and is used for the fairness checks.
- **Human-likeness**: statistical comparison of recorder traces against human baselines.
- **Multi-pack**: a vanilla-plus, a tech-heavy and a magic-heavy pack (chosen in M7).
- **Python**: pytest, ruff and pyright; protocol conformance against the golden fixtures shared with Java.
