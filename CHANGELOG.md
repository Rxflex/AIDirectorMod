# Changelog

All notable changes to this project will be documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.1] - 2026-05-18

A bug-fix release for OpenAI-compatible gateways.

### Fixed

- Tool calls are now given unique, non-empty ids, and the assistant
  message lists exactly the calls that are answered (1:1). Some models
  emit blank or duplicate tool-call ids; a Gemini-style gateway could
  not then match a tool result to its call and rejected the request
  with "function_response.name: Name cannot be empty".
- The default embedding model is now `nvidia/nv-embed-v1` — a symmetric
  model that needs no non-standard `input_type` field, so it works
  through any OpenAI-compatible endpoint, including proxies that drop
  that field. Asymmetric models still work when pointed at an endpoint
  that forwards `input_type` (see `embed_base_url`). Embedding failures
  caused by a stripped `input_type` now log an actionable hint.

## [0.4.0] - 2026-05-18

A feedback-driven expansion: the director acts boldly and builds whole
scenes instead of lone lines, and gains genuinely new powers — phantom
players, mob re-shaping, atmospheric dressing, and themed loot.

### Added

- **Phantom players.** `phantom_join` / `phantom_say` / `phantom_leave` —
  a fake player joins the server with a believable join message, speaks in
  chat, and leaves, with no entity in the world. A purely server-side scare.
- **`modify_mob`.** Re-shapes a tracked mob via a curated set of modifiers —
  glow, body scale, movement speed, silence, gravity, name, and an optional
  status effect on the mob itself.
- **`dress_scene`.** Places atmospheric blocks (cobweb, vine, soul torch,
  candle, ...) but only ever into empty air, so it can never damage the
  player's builds — available without the destructive-tools opt-in.
- **`give_loot` and a bundled loot pack.** Four themed loot tables shipped as
  JSON in the jar; the director hands the player a varied, weighted bundle
  instead of single items.
- **Adaptive pacing.** Under high tension the director may act again after a
  shorter throttle floor, so pressure is answered promptly and calm moments
  are left alone.

### Changed

- **System prompt rewritten.** The director is told it controls a live
  Minecraft world and must build coordinated multi-tool SCENES (effects,
  mobs, weather, sound together), not lone narration lines; varying its
  mechanics across ticks is now mandatory.
- Up to 5 tool calls per iteration (was 2); guardrail caps raised so a bold
  scene is not clipped.
- Default chat model is now `nvidia/nemotron-3-super-120b-a12b` —
  benchmarked the most reliable tool-caller on NIM and the fastest of the
  reliable models. `openai/gpt-oss-120b` frequently emitted malformed
  tool-call JSON.
- Embeddings may be routed to a dedicated endpoint (`embed_base_url` /
  `embed_api_key`) for gateways that drop NVIDIA's `input_type` field.

### Fixed

- Tool calls with a blank function name are dropped before they enter the
  conversation — echoing the empty name made strict gateways reject the
  whole request.

## [0.3.0] - 2026-05-18

The director gains intent and a longer memory: a planned story, NPCs with
fates, a session journal, selectable personas — plus a Paper plugin and
support for more Minecraft versions.

### Added

- **Campaign planner.** A Showrunner loop bootstraps and revises a persistent
  multi-act campaign (premise, theme, tone, ordered acts). The per-tick
  director only reads it and picks actions that serve the current act.
- **NPC character arcs.** NPCs carry a relationship to the player, an arc
  note, and a fate — active, missing, dead, or hostile. The `evolve_npc` tool
  advances them; a fate change is recorded as canon. The prompt's NPC roster
  shows resolved fates so the director can honour them.
- **Session chronicle.** On logout the director writes a short in-fiction
  journal entry for the session; on the next login it is delivered to the
  player as a written book. Entries are also ingested into RAG.
- **Director presets.** A `director_preset` config key selects a storyteller
  archetype — `balanced`, `trickster`, `loremaster`, `cruel`, `comforter` —
  injecting a persona directive into the system prompt without overriding the
  safety rules.
- **Visible artifacts.** `place_sign`, `build_structure` (cairn, grave,
  shrine, memorial, campfire ring, waystone), and `give_treasure_map` (a real
  filled map) let the director leave physical, discoverable marks.
- **Paper plugin.** A Paper / Spigot build of the same engine — a single jar
  that runs across the 1.21.x line, with the platform layer behind Bukkit.
- **Multi-version support.** A version matrix in `settings.gradle.kts` builds
  the mod for Minecraft 1.21.1, 1.21.3, and 1.21.4 via `-Pmc=<version>` from a
  single source tree.

### Changed

- The prompt's `# Active NPCs` section is now `# NPC roster` and renders each
  NPC's status, relationship, and arc.

### Fixed

- The Fabric module did not pull in `:core` and never compiled; it now builds
  and tests alongside NeoForge.
- Minecraft API differences between 1.21.1 and 1.21.3+ (entity creation,
  cross-dimension teleport, mob-effect holder lookup) are resolved within a
  single source tree, so no per-version source fork is needed.

## [0.2.1] - 2026-05-14

Prompt safety and more sensors.

### Added

- **Prompt safety layer.** Every user-controlled string (player name, NPC
  personality, RAG fact content, recent event payloads, quest objectives,
  retrieved facts, chat captures) is run through `PromptSafety.sanitize`
  before reaching the LLM. Strips control chars, bidi overrides, model
  special tokens (`<|im_start|>`, etc), role markers (`Human:`, `System:`),
  and 10+ jailbreak phrase patterns. Replaces matches with `[redacted]` /
  `[role]` markers.
- **Context budget.** Prompts are bounded by `ContextBudget` (~40k chars by
  default ≈ 10k tokens). When the assembled prompt exceeds the cap, lower-
  priority sections are trimmed in order: recent events → RAG retrievals →
  active NPCs → active quests → narrative arc. The system prompt, player
  state, and tension score are never trimmed.
- **More event sensors.** Wired through Architectury events:
  - `EntityEvent.LIVING_HURT` → `player.hurt` event (throttled to 1.5s)
  - `PlayerEvent.CHANGE_DIMENSION` → `player.change_dimension`
  - `PlayerEvent.CRAFT_ITEM` → `player.craft` (throttled 3s)
  - `BlockEvent.BREAK` → `player.break_block` (throttled 3s)
  - `ChatEvent.RECEIVED` → `player.chat` (throttled 0.25s, sanitized)
  All event sources go through `EventThrottle` so high-frequency hooks
  don't blow out the SQLite log.
- **Tension curve uses real damage.** `recentlyTookDamage` now reads the
  new `player.hurt` events (plus `player.death`) within the last 15s.
- **Live LLM smoke test.** `LiveLlmSmokeTest` hits the configured endpoint
  for a chat and an embedding call. Gated on `AIDIRECTOR_LLM_API_KEY` env
  var so it is skipped in CI and on dev machines without a key.
- **Tests** for `PromptSafety` (12 cases) and `ContextBudget` (4 cases).

### Notes

- Tests run against mocks (`MockWebServer`, in-memory SQLite,
  `FakeEmbedder`); the live smoke test is the only end-to-end check and is
  opt-in.
- The prompt safety layer is hygiene, not a formal guarantee. A determined
  adversary writing into RAG content could still confuse a particularly
  obedient model. The system prompt explicitly tells the LLM to treat the
  retrieved sections as data, not instructions.

## [0.2.0] - 2026-05-14

A complete rewrite of the director's brain. The 0.1 line shipped a single-
shot LLM call per tick; 0.2 turns the director into a real agent with memory,
retrieval, multi-step reasoning, and persistent NPCs / quests / advancements.

### Added

- **ReAct agent loop.** The director now runs up to N iterations per tick:
  call tools, read each result back as a `role:"tool"` message, decide whether
  to act again. Tunable via `director.max_agent_iterations` and
  `director.max_tool_calls_per_iteration`.
- **Embeddings + RAG.** An OpenAI-compatible `/v1/embeddings` client wraps a
  fact store; every prompt is enriched with the top-k semantically-relevant
  long-term facts. Linear cosine search in-memory — fine to ~50k facts.
- **Persistent NPCs.** New `spawn_npc` tool spawns a tagged villager or
  wandering trader. NPCs are stored in SQLite with role + personality. Right-
  clicking an NPC triggers an immediate dialogue agent loop scoped to that
  NPC's voice, with RAG retrieval and active-quest context.
- **Persistent quests.** `assign_quest` / `update_quest` / `complete_quest`
  tools. Objectives are free-form text owned by the LLM. Reward items are
  granted on completion.
- **Dynamic advancements.** `grant_advancement` builds an Advancement on the
  fly, injects it via `ClientboundUpdateAdvancementsPacket`, and awards it
  immediately. Icon, frame (task / goal / challenge), and chat-announce are
  configurable.
- **Mob control.** `spawn_mob` (with optional TTL auto-despawn),
  `set_mob_equipment`, `set_mob_target`, `kill_mob`. Boss-tier mobs are
  denylisted at the platform layer.
- **Atmosphere tools.** `spawn_particle` (simple particles only),
  `strike_lightning` (cosmetic by default), `create_boss_bar` with auto-fill
  animation and auto-expire.
- **Lore notes.** `give_lore_note` hands the player a written book and
  ingests the contents as a high-importance RAG fact.
- **World tools** (gated by `director.allow_destructive_tools = true`):
  `set_time`, `place_block` (with a destructive-block denylist), and
  `teleport_player`.
- **Reflection cycle.** Every `director.reflection_interval_ms` (10 min
  default), the director: summarises recent events into a 2-3 sentence
  narrative arc (persisted in `world_state`), embeds any pending facts, and
  despawns expired tracked mobs.
- **Database refactor.** `Memory` now exposes typed sub-stores: `events`,
  `facts`, `npcs`, `quests`, `mobs`, `advancements`, `worldState`. Migrations
  are idempotent.

### Changed

- `director.max_tool_calls_per_tick` → `director.max_tool_calls_per_iteration`
  (with new `director.max_agent_iterations`).
- `ServerActions` interface expanded with 14 new methods. The single
  `CommonServerActions` implementation in the common module covers all
  loaders.
- Prompt builder now consumes a `PromptInput` data class and renders six
  sections: state, tension, narrative arc, active NPCs, active quests, recent
  events, retrieved RAG memory.
- Default tool registry grew from 5 to 19 tools (+3 destructive when opted
  in).

### Notes

- API keys still travel in `aidirector.toml`. The first run writes a default
  file with `PUT-YOUR-API-KEY-HERE` and the director stays disabled until
  the operator fills it in and runs `/aidirector reload`.
- Tests grew accordingly: new coverage for `FactStore` cosine retrieval,
  `Rag` ingest/retrieve/backfill, `AgentLoop` multi-step termination,
  `NpcStore` / `QuestStore` / `MobStore` lifecycles, and an updated
  end-to-end `DirectorTest` against `MockWebServer`.

## [0.1.0] - 2026-05-14

Initial public release.

### Added
- Multi-loader build (Fabric + NeoForge) via Architectury for Minecraft 1.21.1.
- TOML configuration with first-run default file generation.
- LLM client targeting any OpenAI-compatible endpoint (NVIDIA NIM, vLLM,
  LM Studio, Ollama), with retry, exponential backoff, and rate-limit handling.
- Tool registry with five MVP tools:
  - `send_narration` — narrator / whisper / title-overlay messages
  - `play_sound` — atmospheric sound near the player
  - `give_item` — small gifts, gated by rarity cap
  - `apply_effect` — short status effects, with banned-effect denylist
  - `modify_weather` — clear / rain / thunder
- Per-player sliding-window guardrails per tool, configurable in TOML.
- SQLite-backed event memory with retention/row caps and indexed reads.
- Tension curve derived from player vitals, hostility, time of day, and weather.
- `/aidirector` command (op level 2): `status`, `pause`, `resume`, `reload`, `trigger`.
- Test suite (~50 cases) covering config parsing, LLM client retry behavior,
  tool argument validation, rate limiter sliding window, tension curve,
  prompt rendering, memory persistence, and end-to-end director dispatch.
