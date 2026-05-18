package dev.aidirector.config

/**
 * Fully validated runtime configuration. Constructed via [ConfigLoader] from a
 * TOML file in the loader's config directory. Treat instances as immutable
 * snapshots — a reload produces a new instance.
 */
data class DirectorConfig(
    val llm: LlmConfig,
    val director: DirectorRuntimeConfig,
    val guardrails: GuardrailsConfig,
    val memory: MemoryConfig,
) {
    companion object {
        const val FILE_NAME = "aidirector.toml"
    }
}

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val embedModel: String?,
    /** Optional separate endpoint for embeddings. Falls back to [baseUrl] when null. */
    val embedBaseUrl: String?,
    /** Optional separate key for the embeddings endpoint. Falls back to [apiKey] when null. */
    val embedApiKey: String?,
    val timeoutSeconds: Long,
    val maxRetries: Int,
    val temperature: Double,
    val maxTokens: Int,
) {
    /** Endpoint actually used for `/embeddings` — the dedicated one if set. */
    val effectiveEmbedBaseUrl: String get() = embedBaseUrl?.takeIf { it.isNotBlank() } ?: baseUrl

    /** Key actually used for `/embeddings` — the dedicated one if set. */
    val effectiveEmbedApiKey: String get() = embedApiKey?.takeIf { it.isNotBlank() } ?: apiKey

    init {
        require(baseUrl.isNotBlank()) { "llm.base_url must not be blank" }
        require(timeoutSeconds in 1..600) { "llm.timeout_seconds must be in 1..600" }
        require(maxRetries in 0..10) { "llm.max_retries must be in 0..10" }
        require(temperature in 0.0..2.0) { "llm.temperature must be in 0.0..2.0" }
        require(maxTokens in 16..16_384) { "llm.max_tokens must be in 16..16384" }
    }
}

data class DirectorRuntimeConfig(
    val tickIntervalMs: Long,
    val maxToolCallsPerIteration: Int,
    val maxAgentIterations: Int,
    val enabled: Boolean,
    val systemPromptOverride: String?,
    val minSecondsBetweenLlmCalls: Long,
    /** Throttle floor while the player is under high tension — lets the director act sooner. */
    val minSecondsBetweenLlmCallsTense: Long,
    val reflectionIntervalMs: Long,
    val ragEnabled: Boolean,
    val ragMaxRetrieved: Int,
    val allowDestructiveTools: Boolean,
    val outputLanguage: String,
    val requireSignificantChange: Boolean,
    val cooldownTicksAfterAction: Int,
    val modpackProfile: String?,
    val seedFacts: List<String>,
    val dumpModRegistry: Boolean,
    val contextBudgetChars: Int,
    val recentEventsShown: Int,
    val pinnedFactsCount: Int,
    val campaignEnabled: Boolean,
    val campaignReviewIntervalMs: Long,
    val chronicleEnabled: Boolean,
    val directorPreset: String,
    /** When true, every director tick logs its decision and the LLM's outcome. */
    val debugLogging: Boolean,
) {
    init {
        require(tickIntervalMs in 1_000..600_000) { "director.tick_interval_ms must be in 1000..600000" }
        require(maxToolCallsPerIteration in 1..10) { "director.max_tool_calls_per_iteration must be in 1..10" }
        require(maxAgentIterations in 1..8) { "director.max_agent_iterations must be in 1..8" }
        require(minSecondsBetweenLlmCalls >= 0) { "director.min_seconds_between_llm_calls must be >= 0" }
        require(minSecondsBetweenLlmCallsTense >= 0) {
            "director.min_seconds_between_llm_calls_tense must be >= 0"
        }
        require(reflectionIntervalMs in 60_000..86_400_000) {
            "director.reflection_interval_ms must be in 60000..86400000"
        }
        require(ragMaxRetrieved in 0..128) { "director.rag_max_retrieved must be in 0..128" }
        require(outputLanguage.length in 2..32) { "director.output_language must be 2..32 chars" }
        require(cooldownTicksAfterAction in 0..20) { "director.cooldown_ticks_after_action must be in 0..20" }
        require((modpackProfile?.length ?: 0) <= 2_000) { "director.modpack_profile must be ≤ 2000 chars" }
        require(seedFacts.size <= 50) { "director.seed_facts must have ≤ 50 entries" }
        seedFacts.forEach { require(it.length in 1..500) { "each seed_fact must be 1..500 chars" } }
        require(contextBudgetChars in 8_000..400_000) {
            "director.context_budget_chars must be in 8000..400000"
        }
        require(recentEventsShown in 5..200) { "director.recent_events_shown must be in 5..200" }
        require(pinnedFactsCount in 0..64) { "director.pinned_facts_count must be in 0..64" }
        require(campaignReviewIntervalMs in 60_000..86_400_000) {
            "director.campaign_review_interval_ms must be in 60000..86400000"
        }
        require(directorPreset.length in 2..32) { "director.director_preset must be 2..32 chars" }
    }
}

data class GuardrailsConfig(
    val maxSpawnsPerMinute: Int,
    val maxEffectsPerMinute: Int,
    val maxSoundsPerMinute: Int,
    val maxItemsPerMinute: Int,
    val maxNarrationsPerMinute: Int,
    val maxWeatherPerHour: Int,
    val itemRarityCap: ItemRarityCap,
) {
    init {
        require(maxSpawnsPerMinute in 0..30) { "guardrails.max_spawns_per_minute must be in 0..30" }
        require(maxEffectsPerMinute in 0..30) { "guardrails.max_effects_per_minute must be in 0..30" }
        require(maxSoundsPerMinute in 0..60) { "guardrails.max_sounds_per_minute must be in 0..60" }
        require(maxItemsPerMinute in 0..30) { "guardrails.max_items_per_minute must be in 0..30" }
        require(maxNarrationsPerMinute in 0..60) { "guardrails.max_narrations_per_minute must be in 0..60" }
        require(maxWeatherPerHour in 0..10) { "guardrails.max_weather_per_hour must be in 0..10" }
    }
}

enum class ItemRarityCap(val severity: Int) {
    COMMON(0),
    UNCOMMON(1),
    RARE(2),
    EPIC(3);

    companion object {
        fun fromString(value: String): ItemRarityCap =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown rarity cap '$value'. Allowed: ${entries.joinToString { it.name.lowercase() }}")
    }
}

data class MemoryConfig(
    val dbFileName: String,
    val maxEventLogRows: Int,
    val retentionDays: Int,
) {
    init {
        require(dbFileName.isNotBlank()) { "memory.db_file_name must not be blank" }
        require(!dbFileName.contains('/') && !dbFileName.contains('\\')) {
            "memory.db_file_name must be a plain file name, not a path"
        }
        require(maxEventLogRows in 100..1_000_000) {
            "memory.max_event_log_rows must be in 100..1000000"
        }
        require(retentionDays in 1..3650) {
            "memory.retention_days must be in 1..3650"
        }
    }
}
