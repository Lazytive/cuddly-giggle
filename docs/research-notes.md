# Research notes

Everything here was checked on **2026-09-30**, against primary sources where the network allowed. When something could not be checked, the note says so and names the milestone where it will be. Minecraft internals that need the decompiled 1.21.1 sources could not be checked here: Mojang's hosts are blocked in this environment.

## 1. Platform facts verified

| Fact | Source | Used in |
|---|---|---|
| Client-only mod code uses `@Mod(value = MODID, dist = Dist.CLIENT)`, and dependency entries in `neoforge.mods.toml` take `side = "CLIENT" / "SERVER" / "BOTH"` | NeoForge docs, 1.21.1 versioned copy: `neoforged/Documentation` → `versioned_docs/version-1.21.1/gettingstarted/modfiles.md` and `concepts/sides.md` | ADR 0002 |
| NeoForge 1.21.1 (branch `1.21.1`) builds with ModDevGradle and Java 21; the latest ModDevGradle release on the Gradle plugin portal is 2.0.148 | `neoforged/NeoForge@1.21.1/gradle.properties`; plugin-portal metadata | ADR 0013 |
| Client events exist in 1.21.1 under these names: `MovementInputUpdateEvent`, `InputEvent.Key`, `InputEvent.MouseButton.Pre/Post`, `InputEvent.InteractionKeyMappingTriggered`, `ClientTickEvent.Pre/Post`, `RenderFrameEvent.Pre/Post`, `ScreenEvent.KeyPressed.Pre`, `ScreenEvent.Opening`, `ClientChatReceivedEvent` (`.Player` / `.System`, `isSystem()`), `ClientPlayerNetworkEvent.LoggingIn/LoggingOut`, `PlaySoundEvent`, `RegisterClientCommandsEvent`, `RegisterGuiLayersEvent`, `RegisterKeyMappingsEvent`, `RegisterGameTestsEvent` | `neoforged/NeoForge@1.21.1` sources under `client/event/` and `event/` | architecture §3 |
| `KeyboardHandler.keyPress` calls the screen `Pre` hook before the screen's own `keyPressed`, and `ClientHooks.onKeyInput` (the `InputEvent.Key` post) at the end. So a screen can consume a key before `InputEvent.Key` fires, which is why the kill switch listens in three places | `patches/net/minecraft/client/KeyboardHandler.java.patch` @1.21.1 | ADR 0003 |
| `MouseHandler.turnPlayer` scales accumulated mouse deltas (`accumulatedDX/DY`) by sensitivity (and the cinematic camera). NeoForge exposes the values via `getXVelocity()/getYVelocity()` and lets mods change sensitivity through `ClientHooks.getTurnPlayerValues` | `patches/net/minecraft/client/MouseHandler.java.patch` @1.21.1 | ADR 0003 |
| NeoForge's config system uses NightConfig (TOML), which is already on the client classpath | `ModConfigSpec.java` @1.21.1 imports `com.electronwill.nightconfig` | ADR 0009 |
| JEI 1.21.1 API: `@JeiPlugin`, `IModPlugin.onRuntimeAvailable(IJeiRuntime)`, `IJeiRuntime.getRecipeManager()`, `IRecipeManager.createRecipeLookup(RecipeType)`, `createRecipeCategoryLookup()` | `mezz/JustEnoughItems@1.21.1` `Common/src/api/...` | architecture §3.9 |
| EMI (1.21 branch) API: `EmiApi.getRecipeManager()`, `EmiRecipeManager.getRecipesByOutput/Input`, `EmiApi.getHoveredStack`, `@EmiEntrypoint`, `EmiPlugin` | `emilyploszaj/emi@1.21` `xplat/src/main/java/dev/emi/emi/api` | architecture §3.9 |
| FTB Quests 1.21.1: `ClientQuestFile.INSTANCE / getInstance() / exists()` (quest file synced from server) | `FTBTeam/FTB-Quests@1.21.1/main` `common/.../client/ClientQuestFile.java` | architecture §3.9 |
| Baritone has a 1.21.1 branch (v1.11.x, loaders include NeoForge). Its `BlockStateInterface` reads blocks from the `ClientChunkCache` plus its own cached regions, i.e. all loaded chunks, seen or not | `cabaletta/baritone@1.21.1` | ADR 0004 |
| Current Minecraft releases use the new `26.x` version scheme (FTB Quests' `main` targets 26.1.2), so the 1.21.1 ecosystem is stable and no longer moving | `FTBTeam/FTB-Quests@main/gradle.properties` | risk R16 |

**To verify in the milestone named:**
- M1: the exact private callback methods in `KeyboardHandler` / `MouseHandler` used for injection, and which code polls GLFW directly (e.g. screen shift checks).
- M1: that the `--quickPlaySingleplayer` launch argument works in 1.21.1.
- M2: the vanilla section-occlusion API.
- M3: how observers compute body yaw.
- M4: the ModDevGradle GameTest run configuration.
- M7: whether 1.21.1 syncs the full recipe set to the client.
- M9: which schematic format to export.

## 2. Licences checked

| Project | Licence (from its LICENSE file or mod metadata) | Our use |
|---|---|---|
| NeoForge | LGPL-2.1 | platform dependency only |
| Baritone | LGPL-3.0 | not used (ADR 0004) |
| Mineflayer, mineflayer-pathfinder | MIT | ideas only; if logic is ported, keep the notice |
| Voyager | MIT | ideas and prompt structure, with attribution |
| JEI | MIT | compile-only API, not bundled |
| EMI | MIT | compile-only API, not bundled |
| FTB Quests | **All Rights Reserved** (`neoforge.mods.toml`) | runtime soft dependency only; compile against the published artifact or call via reflection; no code copied, nothing redistributed |
| OpenAI VPT code | MIT | not used as code |
| OpenAI VPT contractor dataset | **no licence stated** in the README (it only disclaims Minecraft IP) | only with operator approval, for statistics |
| MineStudio (CraftJarvis) | MIT | reference only |
| Python: `websockets` BSD-3-Clause; `pydantic` MIT; `sqlite-vec` MIT/Apache-2.0; `anthropic` MIT; `openai` Apache-2.0; `wasmtime` Apache-2.0 WITH LLVM-exception | PyPI metadata | service dependencies |
| CPython WASI builds (`brettcannon/cpython-wasi-build`, releases up to 3.15 RC) | licence to confirm in the M5 spike (expected PSF) | skill sandbox |

## 3. Research papers (verified to exist; what we take)

| Work | ID / venue | Idea we adopt | Where |
|---|---|---|---|
| Voyager | arXiv 2305.16291 | automatic curriculum; executable skill library; iterative prompting with environment feedback, execution errors and self-verification | M5, M8 |
| Odyssey | arXiv 2407.15325 (IJCAI 2025) | split into primitive skills (40) and compositional skills (183); wiki-derived Q&A as a knowledge source | M4, M7, M8 |
| DEPS | arXiv 2302.01560 (NeurIPS 2023) | describe → explain → plan → select on failure; a selector that ranks candidate subgoals by proximity/feasibility | M5 |
| Mr.Steve | arXiv 2411.06736 (ICLR 2025) | Place Event Memory (what–where–when); alternating exploration and task-solving | M8 |
| MP5 | arXiv 2312.07472 (CVPR 2024) | goal-conditioned *active perception*: the planner asks targeted perception questions | M2 (`obs.request`), M5 |
| Optimus-1 | arXiv 2408.03615 (NeurIPS 2024) | Hierarchical Directed Knowledge Graph plus Abstracted Multimodal Experience Pool; knowledge-guided planner and experience-driven reflector | M7 (recipe/tech graph), M8 |
| Optimus-2 | arXiv 2502.19902 | goal-observation-action conditioned policy (a learned low-level policy) | not adopted now: we use scripted primitives; noted as a future option |
| Optimus-3 | arXiv 2506.10357 | Mixture-of-Experts with task-level routing; knowledge-enhanced data generation | analogy for role routing; not adopted as a model |
| JARVIS-1 | arXiv 2311.05997 | multimodal memory: retrieve past successful plans by situation; self-improvement from experience | M8 retrieval |
| VPT | arXiv 2206.11795 | acting through human keyboard and mouse; contractor data logs keyboard, mouse `dx/dy` and GUI state at 20 Hz | M3 calibration (if approved) |
| Experience Transfer (Echo) | arXiv 2604.05533 (CVPR 2026) | break experience into structure / attribute / process / function / interaction; In-Context Analogy Learning; reports 1.3–1.7× faster object unlocking and "burst" chain-unlocking | M8 experience transfer |
| MineExplorer | arXiv 2605.30931 (2026) | task graphs plus sandbox scenes plus rule-based milestone evaluators; multi-hop tasks with hidden prerequisites; strong models degrade on long chains | M13 scenario design |

**Newer work found (to read in the milestone listed):**
- MineNPC-Task (arXiv 2601.05215): memory-aware tasks with machine-checkable validators and a *bounded-knowledge, no-shortcut* policy, very close to our fair mode. For M5 and M13 evaluation.
- MindCraft, "Collaborating Action by Action" (arXiv 2504.17950): a Mineflayer-based multi-agent framework. For M10 social ideas.
- MineCEraft (arXiv 2608.28884): LLMs as construction engineers in Minecraft. For M9.

## 4. Human motor control (standard references; parameters will be calibrated, not recalled)

- **Fitts's law:** Fitts (1954). Shannon formulation `MT = a + b·log2(D/W + 1)`: MacKenzie (1992).
- **Minimum-jerk trajectories:** Flash & Hogan (1985). Position profile `10τ³ − 15τ⁴ + 6τ⁵`.
- **Optimised submovement model** (primary movement plus corrective submovements): Meyer et al. (1988).
- **Choice reaction time:** Hick (1952), Hyman (1953).
- **Reaction-time distributions** are right-skewed and commonly fit with ex-Gaussian or log-normal. Initial parameters come from the literature ranges; the operator's recordings replace them in M3.
