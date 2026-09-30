# Protocol outline (mod ↔ agent service)

Status: **outline for review.** The normative source will be JSON Schema files in `protocol/schema/`, with golden fixtures in `protocol/fixtures/`. The Python pydantic models are generated from or checked against those schemas, and the Java codec is tested against the same fixtures (ADR 0005).

## 1. Transport

- **Connection.** WebSocket over loopback only. The service listens on `127.0.0.1:<port>` and the mod is the client, so the mod runs fine with no service present.
- **Authentication.** At startup the service writes a random token to a file that only the current user can read. The mod sends it as `Authorization: Bearer <token>`. The service rejects:
  - a missing or wrong token;
  - any request with an `Origin` header. Browsers always send one; the mod never does. This blocks drive-by connections from web pages.
- **Frames.**
  - *Text* frames carry one JSON message each.
  - *Binary* frames carry screenshots: a 4-byte big-endian header length, then a JSON header (`{"type":"screenshot.result", "id":…, "format":"png"|"jpeg", "w":…, "h":…}`), then the image bytes.
- **Flow control.**
  - Observation deltas are coalesced in the mod. If the socket is slow, older unsent deltas are merged, never queued without bound.
  - Commands from the service are never dropped. They are acknowledged or rejected.

## 2. Handshake and versioning

1. The mod sends `hello`:
   ```json
   {"v":1,"type":"hello","id":"m-1","ts":1767225600123,"payload":{
     "protocol":"1.0","mod_version":"0.1.0","mc_version":"1.21.1","loader":"neoforge",
     "pack":{"fingerprint":"sha256:…","mods":[{"id":"jei","version":"19.x"}]},
     "world":{"kind":"singleplayer","id":"New World#1767225000","allowed":true},
     "control_state":"RUNNING",
     "effective":{"fair_mode":true,"cheats":{"xray":false,"flight":false},"realism":0.8},
     "capabilities":["primitives/v1","gui_reader/v1","screenshot/png","jei","ftbquests"]}}
   ```
2. The service replies `hello.ack` with `{protocol, session_id, accepted, reason?}`.
3. **Version rules.** `protocol` is `MAJOR.MINOR`. A different major version is refused. A minor version can only add optional fields or new message types. Unknown message types get an `error` reply with code `unsupported`; unknown fields are ignored.
4. **Authority of `effective`.** The `effective` block reports settings that the mod enforces and read from disk. The service must plan within them and **cannot change them** (ADR 0009).

## 3. Envelope

```json
{ "v": 1, "type": "<namespace.name>", "id": "<unique per sender>", "ts": <epoch ms>,
  "re": "<id this replies to, optional>", "seq": <observation sequence, optional>, "payload": { } }
```

## 4. Observations

**`obs.snapshot`** (mod → service) is the full state. It has these sections:
- `player`: health, food, saturation, armour, effects, xp, pos, rot, velocity, on_ground, pose, dimension, biome, light;
- `inventory`: slots with item id, count, components summary and tooltip hash, plus hotbar selection, offhand and armour;
- `screen`: see §7;
- `visible_entities`, with id, type, name, pos, rot, health if shown, equipment, hostile hint, `first_seen`, `last_seen`;
- `visible_blocks_summary`: counts and salient blocks. Bulk terrain is **not** in snapshots; it stays in the mod's KnownWorld and is available through queries;
- `world`: time, weather, difficulty and game mode where the client knows them;
- `control`: control state and current primitive.

**`obs.delta`** (mod → service) carries `base_seq` and per-section patches, keyed by entity id or slot index. If the service sees a gap in `seq`, it sends `obs.request {full:true}`.

**`obs.request`** (service → mod) asks for a snapshot, or for specific sections at a given detail level. This supports MP5-style *active perception*: the brain asks for what the task needs.

Every observation that contains player-authored text puts it in an `untrusted_text` wrapper: `{"untrusted_text": "...", "source": "chat|sign|book|item_name|entity_name"}`.

## 5. Events (mod → service)

Each event type is `event.<name>`. The initial list:

- **Health and survival:** `damage_taken` (source, amount, attacker), `death` (message, pos, last inventory), `respawn`.
- **Items and blocks:** `item_picked_up`, `block_broken`, `block_placed`.
- **Screens:** `screen_opened` and `screen_closed` (class and title).
- **Entities:** `entity_appeared` and `entity_lost` (visibility transitions, fair mode aware).
- **Sounds:** `sound_heard` (subtitle key, category, direction bucket, distance bucket).
- **Chat:** `chat_received` (untrusted; player or system; sender UUID if any).
- **World and progress:** `dimension_changed`, `advancement`, `quest_updated` (FTB Quests present).
- **Control:** `reflex` (cause, action taken, what it preempted); `control_changed` (paused, resumed, killed, takeover, dormant, with reason); `server_joined` / `server_left` (allowed or not).
- **Operator:** `operator_suggestion` (trusted advice from the private channel).

## 6. Skills (primitives) and cancellation

| Message | Direction | Payload |
|---|---|---|
| `skill.invoke` | service → mod | `{invocation_id, name, args, timeout_ms, priority, preempt: "never"\|"lower"\|"always"}` |
| `skill.accepted` / `skill.rejected` | mod → service | reject codes: `unknown_skill`, `bad_args`, `not_allowed` (for example a cheat that is off), `busy` |
| `skill.progress` | mod → service | `{invocation_id, fraction?, stage, detail}` |
| `skill.result` | mod → service | `{invocation_id, status: "success"\|"failure"\|"cancelled"\|"preempted"\|"timeout", code?, evidence}` |
| `skill.cancel` | service → mod | `{invocation_id, reason}`; the mod releases that primitive's inputs, then replies with a `skill.result` of `cancelled` |

- **Primitive names (v1):** `look_at`, `move_to`, `follow_path`, `mine_block`, `place_block`, `use_item`, `attack_entity`, `open_container`, `click_slot`, `transfer_items`, `craft`, `select_hotbar`, `drop_item`, `gui_click_widget`, `gui_type_text`, `close_screen`, `type_chat`, `sleep`, `look_around`, `wait`.
- **Failure codes** include: `unreachable`, `out_of_reach`, `not_visible`, `target_changed`, `gui_mismatch`, `inventory_full`, `missing_item`, `tool_insufficient`, `interrupted_by_reflex`, `blocked_by_policy`.
- **`evidence`** holds the observations the mod used to decide the outcome. Examples: the block state after mining, or the slot contents after a transfer.

## 7. GUI description

`screen` object:

```json
{"class":"net.minecraft.client.gui.screens.inventory.CraftingScreen","title":"Crafting",
 "container":{"menu_type":"minecraft:crafting","slots":[{"index":0,"x":124,"y":35,"item":"minecraft:stick","count":4,"role":"result"}]},
 "widgets":[{"kind":"button","id":"w3","x":10,"y":10,"w":20,"h":20,"label":"…","active":true}],
 "text":[{"x":8,"y":6,"text":"Crafting"}],
 "progress":[{"slot_hint":"furnace_burn","value":0.4}],
 "introspection":"full|partial|none"}
```

When `introspection` is `partial` or `none`, the service is expected to request a screenshot.

## 8. Queries (request/response)

`query.request {kind, args}` → `query.response {re, ok, data | error}`. Supported kinds:

- `known_world_region`: bounds, returning a compressed voxel slab, fair-mode filtered.
- `path_estimate`: cost and length only.
- `recipes_for` and `recipe_uses`: vanilla recipe manager, then JEI, then EMI.
- `registry_dump`: paged, for knowledge-base ingestion.
- `tooltip`: for an item stack.
- `quest_file`: FTB Quests, if present.
- `gui_detail`: deeper introspection of the current screen.

## 9. Screenshots

The service sends `screenshot.request {purpose, max_dim, format, hide_hud?}`. The mod replies with a binary `screenshot.result` or an `error` with code `throttled`. The throttle lives in the mod.

## 10. Output to the world and operator UI

| Message | Direction | Notes |
|---|---|---|
| `chat.send` | service → mod | `{text, channel: "public"\|"whisper", to?}`. The mod applies a final length and rate check, then types the text through the chat screen at human speed. The service's outbound guard has already run. |
| `overlay.set` | service → mod | `{goal, step, summary}`. Shown only when the overlay is enabled. |

## 11. Health

`heartbeat` goes both ways every second:
- the mod's heartbeat carries tick and frame timings, queue sizes and the ray budget in use;
- the service's heartbeat carries brain state and pending model calls.

If the mod sees no heartbeat from the service for `service_timeout_s`, it enters dead-man mode (architecture.md §3.2).

## 12. Errors

`error {re?, code, message, retryable}`. Codes: `bad_message`, `unsupported`, `schema_violation`, `not_allowed`, `internal`, `throttled`.
