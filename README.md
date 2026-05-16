# AI Director

A server-side Minecraft mod (and Paper plugin) that puts an LLM in the role of
a Game Director. It watches the world, keeps long-term memory of what has
happened and who the player has met, plans a multi-act story, and occasionally
takes a turn — a distant howl in a quiet forest, a named stranger with a past,
a quest that remembers the player who took it three sessions ago, a grave for
an NPC who did not make it.

It runs entirely on the server. Players install nothing. Singleplayer works
because Minecraft runs an internal server.

## How it works

Three loops run at different speeds:

- **Showrunner** (slow, ~20 min) — bootstraps and revises a multi-act campaign:
  a premise, a theme, a tone, and an ordered set of acts. This is the story's
  spine; it changes rarely and deliberately.
- **Director** (per tick) — for each online player, takes a sensor snapshot,
  scores tension, retrieves relevant memory, builds a bounded prompt, and runs
  a ReAct agent loop against the LLM. Most ticks it does nothing on purpose —
  it only acts on a genuine narrative beat.
- **Reflection** (~10 min) — summarises the world's narrative arc, embeds new
  facts for retrieval, and cleans up expired director-spawned mobs.

State lives in a SQLite database (WAL mode) inside the world save directory:
events, facts with embeddings, NPCs, quests, mobs, advancements, the campaign,
and the session chronicle.

## Features

- **Campaign planner** — a persistent multi-act story the director works
  toward, instead of disconnected reactions.
- **Agent loop** — multi-step tool calling: the director reads each tool
  result and decides whether to act again.
- **RAG memory** — facts are embedded and retrieved by cosine similarity;
  high-importance facts are pinned so they are never lost from context.
- **Persistent NPCs with arcs** — named villagers carry a role, a
  personality, a relationship to the player, and a fate (active, missing,
  dead, hostile) that the director advances over time. Right-clicking an NPC
  runs a dialogue agent loop scoped to that NPC's voice.
- **Quests** — assigned, updated, and completed by the director or by NPCs;
  objectives and rewards persist across sessions.
- **Visible artifacts** — the director leaves physical marks: inscribed
  signs, small built structures (graves, shrines, memorials, waystones), and
  real filled treasure maps.
- **Session chronicle** — when a player logs out, the director writes a
  short in-fiction journal entry of the session; on the next login it is
  handed to the player as a written book.
- **Director presets** — a storyteller archetype chosen in config:
  `balanced`, `trickster`, `loremaster`, `cruel`, or `comforter`.
- **Dynamic advancements** — the director builds and grants advancements at
  runtime.
- **Mob control** — spawn tracked mobs with an optional time-to-live, set
  equipment and targets; boss-tier mobs are denylisted.
- **Atmosphere** — narration, sounds, particles, cosmetic lightning, boss
  bars, weather.

Every tool call passes through argument validation, registry checks
(unknown items / sounds / effects / blocks / entities are refused before
they reach the server thread), allow/deny lists, and per-player
sliding-window rate limits.

The director never reads the player's chat as a command — chat is captured
only as ambient context. Players cannot directly instruct the director.

## Installation

The mod and the plugin share the same engine; pick the one that matches your
server software.

### Mod (Fabric / NeoForge)

1. Install Fabric Loader **or** NeoForge for your Minecraft version.
2. Install the dependencies:
   - [Architectury API](https://modrinth.com/mod/architectury-api)
   - [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) (Fabric)
   - [Kotlin for Forge](https://www.curseforge.com/minecraft/mc-mods/kotlin-for-forge) (NeoForge)
3. Drop the matching `aidirector-<loader>-<version>.jar` into `mods/`.

### Plugin (Paper / Spigot)

1. Drop `aidirector-paper-<version>.jar` into `plugins/`.
2. No extra dependencies; libraries are shaded into the jar.

### First run (both)

1. Start the server once. A default config is written to
   `config/aidirector.toml` (mod) or `plugins/AIDirector/aidirector.toml`
   (plugin). The director stays disabled until configured.
2. Set `api_key`, and adjust `base_url` / `model` / `embed_model` if needed.
3. Run `/aidirector reload` (or restart).

## Supported versions

Minecraft 1.21.1, 1.21.3, and 1.21.4. The mod ships a separate jar per
version; the Paper plugin is a single jar that runs across the 1.21.x line.

## Configuration

`aidirector.toml` is generated on first run with every key documented inline.
Notable settings:

| Key | Purpose |
| --- | --- |
| `llm.api_key` | API key for your endpoint. The director stays off until set. |
| `llm.base_url` | OpenAI-compatible base URL. |
| `llm.model` | Chat model — must support tool calling. |
| `llm.embed_model` | Embedding model for RAG. |
| `director.director_preset` | Storyteller archetype (see Features). |
| `director.campaign_enabled` | Whether the Showrunner plans a campaign. |
| `director.chronicle_enabled` | Whether session journals are written. |
| `director.allow_destructive_tools` | Enables `set_time`, `place_block`, `teleport_player`. |
| `director.output_language` | Language the director narrates in. |
| `guardrails.*` | Per-minute caps on spawns, effects, sounds, items, narrations. |

## Commands

All require operator permission.

| Command | Effect |
| --- | --- |
| `/aidirector status` | Show running / paused / stopped. |
| `/aidirector pause` | Stop issuing actions; loops stay alive. |
| `/aidirector resume` | Undo `pause`. |
| `/aidirector reload` | Re-read the config and rebuild the agent stack. |
| `/aidirector trigger` | Force one immediate evaluation pass. |
| `/aidirector chronicle` | Write and deliver the calling player's session chronicle now. |

## LLM provider

Any OpenAI-compatible endpoint works, as long as the chat model supports the
`tools` / `tool_calls` fields. Tested against NVIDIA NIM
(`https://integrate.api.nvidia.com/v1`). Self-hosted options (vLLM, Ollama,
LM Studio, llama.cpp's server, LiteLLM) and aggregators (OpenRouter) work the
same way.

## Privacy and cost

Per tick, the director sends to your endpoint: player name, dimension, biome,
position, vitals, time, weather, an inventory summary, recent events, active
NPC and quest summaries, and the top retrieved facts. It does not send chat
messages, the server address, or the config file. If your provider logs
requests, that data is in the logs — self-host the model if that matters.

Cost scales with the model and the call rate. `min_seconds_between_llm_calls`
is the main throttle; raise it to reduce spend.

## Building from source

JDK 21 is required. The Gradle wrapper is committed, so:

```
./gradlew build
```

The first build downloads Minecraft and mappings (a few GB) via Architectury
Loom. Outputs land in `<module>/build/libs/`.

Build for a specific Minecraft version with `-Pmc` (default `1.21.1`):

```
./gradlew :neoforge:build :fabric:build -Pmc=1.21.4
```

Run the test suite (pure-Kotlin engine module):

```
./gradlew :core:test
```

CI builds and tests on every push — see `.github/workflows/build.yml`.

## Project layout

| Module | Contents |
| --- | --- |
| `core` | Pure-Kotlin director engine — no Minecraft on the classpath. |
| `common` | Shared Minecraft integration (Architectury). |
| `fabric`, `neoforge` | Loader entry points. |
| `paper` | Paper / Spigot plugin. |

## License

MIT. See [LICENSE](./LICENSE).
