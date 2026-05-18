package dev.aidirector.config

import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object ConfigLoader {

    /**
     * Loads the AI Director config from [configDir]. On first run, writes a
     * default file and throws [ConfigNotInitializedException] so the operator
     * can fill in the API key.
     */
    @JvmStatic
    fun load(configDir: Path): DirectorConfig {
        val file = configDir.resolve(DirectorConfig.FILE_NAME)
        if (!Files.exists(file)) {
            writeDefault(file)
            throw ConfigNotInitializedException(file)
        }
        val parsed = try {
            Toml.parse(file)
        } catch (e: IOException) {
            throw ConfigParseException("Failed to read ${file.fileName}: ${e.message}", e)
        }
        if (parsed.hasErrors()) {
            val msg = parsed.errors().joinToString("\n  - ") { it.toString() }
            throw ConfigParseException("Invalid TOML in ${file.fileName}:\n  - $msg")
        }
        return parsed.toConfig()
    }

    /** Writes the default config to the given path atomically (tmp + move). */
    @JvmStatic
    fun writeDefault(file: Path) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, DEFAULT_CONFIG_TOML)
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun TomlParseResult.toConfig(): DirectorConfig {
        val llmTable = requireTable("llm")
        val directorTable = requireTable("director")
        val guardrailsTable = requireTable("guardrails")
        val memoryTable = requireTable("memory")

        return DirectorConfig(
            llm = LlmConfig(
                baseUrl = llmTable.requireString("base_url"),
                apiKey = llmTable.requireString("api_key"),
                model = llmTable.requireString("model"),
                embedModel = llmTable.optionalString("embed_model"),
                embedBaseUrl = llmTable.optionalString("embed_base_url"),
                embedApiKey = llmTable.optionalString("embed_api_key"),
                timeoutSeconds = llmTable.optionalLong("timeout_seconds", default = 60),
                maxRetries = llmTable.optionalLong("max_retries", default = 3).toInt(),
                temperature = llmTable.optionalDouble("temperature", default = 0.7),
                maxTokens = llmTable.optionalLong("max_tokens", default = 8192).toInt(),
            ),
            director = DirectorRuntimeConfig(
                tickIntervalMs = directorTable.optionalLong("tick_interval_ms", default = 15_000),
                maxToolCallsPerIteration = directorTable.optionalLong("max_tool_calls_per_iteration", default = 8).toInt(),
                maxAgentIterations = directorTable.optionalLong("max_agent_iterations", default = 3).toInt(),
                enabled = directorTable.optionalBoolean("enabled", default = true),
                systemPromptOverride = directorTable.optionalString("system_prompt"),
                minSecondsBetweenLlmCalls = directorTable.optionalLong("min_seconds_between_llm_calls", default = 120),
                minSecondsBetweenLlmCallsTense = directorTable.optionalLong("min_seconds_between_llm_calls_tense", default = 45),
                reflectionIntervalMs = directorTable.optionalLong("reflection_interval_ms", default = 600_000),
                ragEnabled = directorTable.optionalBoolean("rag_enabled", default = true),
                ragMaxRetrieved = directorTable.optionalLong("rag_max_retrieved", default = 4).toInt(),
                allowDestructiveTools = directorTable.optionalBoolean("allow_destructive_tools", default = false),
                outputLanguage = directorTable.optionalString("output_language") ?: "English",
                requireSignificantChange = directorTable.optionalBoolean("require_significant_change", default = true),
                cooldownTicksAfterAction = directorTable.optionalLong("cooldown_ticks_after_action", default = 2).toInt(),
                modpackProfile = directorTable.optionalString("modpack_profile"),
                seedFacts = directorTable.optionalStringArray("seed_facts"),
                dumpModRegistry = directorTable.optionalBoolean("dump_mod_registry", default = true),
                contextBudgetChars = directorTable.optionalLong("context_budget_chars", default = 40_000).toInt(),
                recentEventsShown = directorTable.optionalLong("recent_events_shown", default = 20).toInt(),
                pinnedFactsCount = directorTable.optionalLong("pinned_facts_count", default = 12).toInt(),
                campaignEnabled = directorTable.optionalBoolean("campaign_enabled", default = true),
                campaignReviewIntervalMs = directorTable.optionalLong("campaign_review_interval_ms", default = 1_200_000),
                chronicleEnabled = directorTable.optionalBoolean("chronicle_enabled", default = true),
                directorPreset = directorTable.optionalString("director_preset") ?: "balanced",
            ),
            guardrails = GuardrailsConfig(
                maxSpawnsPerMinute = guardrailsTable.optionalLong("max_spawns_per_minute", default = 5).toInt(),
                maxEffectsPerMinute = guardrailsTable.optionalLong("max_effects_per_minute", default = 8).toInt(),
                maxSoundsPerMinute = guardrailsTable.optionalLong("max_sounds_per_minute", default = 8).toInt(),
                maxItemsPerMinute = guardrailsTable.optionalLong("max_items_per_minute", default = 2).toInt(),
                maxNarrationsPerMinute = guardrailsTable.optionalLong("max_narrations_per_minute", default = 4).toInt(),
                maxWeatherPerHour = guardrailsTable.optionalLong("max_weather_per_hour", default = 2).toInt(),
                itemRarityCap = ItemRarityCap.fromString(
                    guardrailsTable.optionalString("item_rarity_cap") ?: "rare",
                ),
            ),
            memory = MemoryConfig(
                dbFileName = memoryTable.optionalString("db_file_name") ?: "aidirector.db",
                maxEventLogRows = memoryTable.optionalLong("max_event_log_rows", default = 10_000).toInt(),
                retentionDays = memoryTable.optionalLong("retention_days", default = 90).toInt(),
            ),
        )
    }

    private fun TomlParseResult.requireTable(key: String): TomlTable =
        getTable(key) ?: throw ConfigParseException("Missing required section [$key]")

    private fun TomlTable.requireString(key: String): String =
        getString(key) ?: throw ConfigParseException("Missing required key '$key' (string)")

    private fun TomlTable.optionalString(key: String): String? = getString(key)

    private fun TomlTable.optionalLong(key: String, default: Long): Long =
        if (contains(key)) getLong(key) ?: throw ConfigParseException("'$key' must be an integer")
        else default

    private fun TomlTable.optionalDouble(key: String, default: Double): Double =
        if (contains(key)) getDouble(key) ?: throw ConfigParseException("'$key' must be a number")
        else default

    private fun TomlTable.optionalStringArray(key: String): List<String> {
        if (!contains(key)) return emptyList()
        val arr = getArray(key) ?: throw ConfigParseException("'$key' must be an array")
        val out = mutableListOf<String>()
        for (i in 0 until arr.size()) {
            out += arr.getString(i)
                ?: throw ConfigParseException("'$key[$i]' must be a string")
        }
        return out
    }

    private fun TomlTable.optionalBoolean(key: String, default: Boolean): Boolean =
        if (contains(key)) getBoolean(key) ?: throw ConfigParseException("'$key' must be a boolean")
        else default

    private val DEFAULT_CONFIG_TOML = """
        # AI Director configuration.
        # Edit values, then run /aidirector reload in-game (op required).

        [llm]
        # OpenAI-compatible endpoint. NVIDIA NIM, vLLM, LM Studio, Ollama (with /v1), etc.
        base_url = "https://integrate.api.nvidia.com/v1"
        # Get a key at build.nvidia.com (free tier available). NEVER commit this file.
        api_key = "PUT-YOUR-API-KEY-HERE"
        # nemotron-3-super-120b-a12b: benchmarked the most reliable tool-caller
        # on NIM (perfect tool-call JSON) and the fastest of the reliable models
        # (~5s/call — a 120B MoE with only 12B active params). The mod is an
        # agent loop, so tool-call reliability matters more than raw size.
        # Alternatives that also passed: "qwen/qwen3-next-80b-a3b-instruct"
        # (fast, 3B active), "meta/llama-3.3-70b-instruct" (solid, slower).
        # Avoid "openai/gpt-oss-120b" — frequently emits malformed tool-call JSON.
        model = "nvidia/nemotron-3-super-120b-a12b"
        # nv-embed-v1 is a SYMMETRIC embedding model: it needs no `input_type`
        # field, so it works through any OpenAI-compatible endpoint, including
        # gateways/proxies. Asymmetric models (e.g. nvidia/nv-embedqa-e5-v5)
        # give marginally better query/passage retrieval but require the
        # non-standard `input_type` field — only use one if your endpoint
        # forwards that field (raw NVIDIA NIM does; many proxies drop it).
        embed_model = "nvidia/nv-embed-v1"
        # Optional: route embeddings to a separate endpoint. If you keep an
        # asymmetric embed_model but your base_url is a proxy that drops the
        # `input_type` field (HTTP 400 "input_type parameter is required"),
        # point embeddings straight at NVIDIA NIM here. Leave blank to reuse
        # the base_url / api_key above.
        # embed_base_url = "https://integrate.api.nvidia.com/v1"
        # embed_api_key = "nvapi-..."
        timeout_seconds = 60
        max_retries = 3
        temperature = 0.7
        # Generous by default — reasoning models spend a large part of the
        # budget on their (stripped) chain-of-thought before the real answer.
        max_tokens = 8192

        [director]
        enabled = true
        # How often the director evaluates the world per player. 15s is a sane default.
        tick_interval_ms = 15000
        # ReAct-style agent loop. Keep small — more iterations = more cost AND
        # more redundant filler. Two of each is plenty.
        max_agent_iterations = 2
        max_tool_calls_per_iteration = 2
        # Minimum delay between agent runs per player. Acts as a floor — the director may
        # still wait longer if nothing significant has happened (see require_significant_change).
        min_seconds_between_llm_calls = 180
        # If true, the director skips the LLM call entirely when nothing notable has
        # changed since the previous tick (no HP loss, no light-level transition into a
        # cave, no time-of-day transition, no weather change, no PLAYER_HURT or chat
        # events). Saves tokens and keeps the world feeling natural — most ticks are
        # silent. Set false to call the LLM on every tick regardless.
        require_significant_change = true
        # After the director takes an action, skip the next N ticks unconditionally.
        # Lets a fright or narration breathe instead of stacking on top of itself.
        cooldown_ticks_after_action = 2
        # Every N ms, the director runs a global reflection pass: summarize recent events,
        # update the narrative arc, embed pending facts. 10 minutes by default.
        reflection_interval_ms = 600000
        # Campaign planner. A separate slow "showrunner" pass designs and revises a
        # multi-act story the director works toward over many hours — this is what
        # turns the director from a reactive bot into an intentional storyteller.
        # The showrunner reviews/advances the campaign every campaign_review_interval_ms
        # (20 minutes by default).
        campaign_enabled = true
        campaign_review_interval_ms = 1200000
        # Session chronicle: when a player logs out the director writes a short
        # in-fiction journal entry of their session; on their next login it is
        # delivered as a written book. A persistent, illustrated-by-prose log.
        chronicle_enabled = true
        # Director persona — the storyteller archetype, RimWorld-style. One of:
        #   balanced   — the default; balances all tools and tones
        #   trickster  — playful misdirection, riddles, harmless pranks
        #   loremaster — deep history, NPCs and written lore over combat
        #   cruel      — pressure, dread and costly choices (still fair)
        #   comforter  — warmth, support, rare frights
        director_preset = "balanced"
        # RAG: retrieve relevant lore/personality facts each tick to keep the LLM coherent.
        rag_enabled = true
        rag_max_retrieved = 4
        # Context tuning. The director does NOT dump all memory into the prompt —
        # that triggers the "lost in the middle" effect where models ignore the
        # bulk of a long context. Instead:
        #   - pinned_facts_count : the N highest-importance facts (narrative arc,
        #     core lore, NPC personalities) are ALWAYS included — the canon backbone.
        #   - rag_max_retrieved  : additional detail facts pulled by relevance.
        #   - recent_events_shown: how many recent events to surface.
        #   - context_budget_chars: hard cap on total prompt size (~4 chars/token).
        # If you run a large-context model, you can raise these — but more is not
        # always better; relevance beats volume.
        pinned_facts_count = 12
        recent_events_shown = 20
        context_budget_chars = 40000
        # Destructive tools (set_time, place_block, teleport_player) require this to be true.
        allow_destructive_tools = false
        # On first server start, scan all non-vanilla registries (items, mobs, blocks,
        # effects, particles) and ingest a summarised "modpack.registry_summary" fact
        # into RAG so the director knows what mods are installed and can use modded
        # content (e.g. an Iron's Spells fire scroll for a fitting moment). If false,
        # the director only ever uses minecraft:* content.
        dump_mod_registry = true
        # Profile of your modpack / RP setting. Injected into every prompt so the
        # director adapts tone (apocalypse, high-fantasy, comfy farming, etc.).
        # Leave commented for default behaviour. For multi-line, use TOML's
        # triple-quoted strings in your config — example shown on one line here:
        # modpack_profile = "Post-apocalypse, year 2087. The Old World fell to a plague. Tone: Fallout meets STALKER, not heroic — survival, scarcity, dread."
        # Optional list of canonical facts ingested into RAG on first run. Use to
        # bootstrap your world's lore — places, names, dangers, factions.
        # seed_facts = [
        #     "The Quarantine Zone east of spawn is patrolled by infected raiders.",
        #     "Clean water is the rarest resource; rainfall is mildly radioactive.",
        # ]
        # Language the director writes player-facing text in (narration, NPC dialogue,
        # lore notes, quest titles/descriptions, narrative arc summaries, advancement
        # text, boss-bar labels). Tool argument values like "minecraft:bread" stay in
        # English. Common values: "English", "Russian", "Spanish", "Portuguese",
        # "French", "German", "Japanese", "Chinese", "Polish", "Ukrainian".
        output_language = "English"
        # system_prompt = "Custom system prompt here. Leave commented to use the built-in default."

        [guardrails]
        # Per-player per-minute caps. The director can never exceed these.
        max_spawns_per_minute = 2
        max_effects_per_minute = 3
        max_sounds_per_minute = 6
        max_items_per_minute = 2
        # Hard rate cap. NarrationDedup + prompt rules already keep this low;
        # this is a backstop for runaway loops.
        max_narrations_per_minute = 2
        max_weather_per_hour = 2
        # Highest rarity the director may give as an item: common, uncommon, rare, epic.
        item_rarity_cap = "rare"

        [memory]
        db_file_name = "aidirector.db"
        max_event_log_rows = 10000
        retention_days = 90
    """.trimIndent() + "\n"
}

class ConfigNotInitializedException(val configFile: Path) :
    RuntimeException("Default config written to $configFile. Edit it, then reload the world or run /aidirector reload.")

class ConfigParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
