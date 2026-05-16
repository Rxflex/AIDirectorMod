package dev.aidirector.support

import dev.aidirector.config.DirectorConfig
import dev.aidirector.config.DirectorRuntimeConfig
import dev.aidirector.config.GuardrailsConfig
import dev.aidirector.config.ItemRarityCap
import dev.aidirector.config.LlmConfig
import dev.aidirector.config.MemoryConfig
import dev.aidirector.sensors.InventoryEntry
import dev.aidirector.sensors.PlayerSnapshot
import dev.aidirector.sensors.Position
import dev.aidirector.sensors.TimeOfDay
import dev.aidirector.sensors.Vital
import dev.aidirector.sensors.Weather
import java.util.UUID

object Fixtures {

    fun defaultConfig(
        baseUrl: String = "https://example.test/v1",
        apiKey: String = "test-key",
        model: String = "test-model",
    ): DirectorConfig = DirectorConfig(
        llm = LlmConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            embedModel = null,
            timeoutSeconds = 5,
            maxRetries = 2,
            temperature = 0.7,
            maxTokens = 512,
        ),
        director = DirectorRuntimeConfig(
            tickIntervalMs = 15_000,
            maxToolCallsPerIteration = 3,
            maxAgentIterations = 2,
            enabled = true,
            systemPromptOverride = null,
            minSecondsBetweenLlmCalls = 30,
            reflectionIntervalMs = 600_000,
            ragEnabled = false,
            ragMaxRetrieved = 0,
            allowDestructiveTools = false,
            outputLanguage = "English",
            requireSignificantChange = false,
            cooldownTicksAfterAction = 0,
            modpackProfile = null,
            seedFacts = emptyList(),
            dumpModRegistry = false,
            contextBudgetChars = 40_000,
            recentEventsShown = 20,
            pinnedFactsCount = 12,
            campaignEnabled = false,
            campaignReviewIntervalMs = 1_200_000,
            chronicleEnabled = false,
            directorPreset = "balanced",
        ),
        guardrails = GuardrailsConfig(
            maxSpawnsPerMinute = 2,
            maxEffectsPerMinute = 3,
            maxSoundsPerMinute = 6,
            maxItemsPerMinute = 2,
            maxNarrationsPerMinute = 6,
            maxWeatherPerHour = 2,
            itemRarityCap = ItemRarityCap.RARE,
        ),
        memory = MemoryConfig(
            dbFileName = "test.db",
            maxEventLogRows = 1_000,
            retentionDays = 30,
        ),
    )

    fun snapshot(
        uuid: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        name: String = "tester",
        health: Int = 20,
        nearbyHostile: Int = 0,
        timeOfDay: TimeOfDay = TimeOfDay.DAY,
        weather: Weather = Weather(raining = false, thundering = false),
        nowMs: Long = 1_700_000_000_000L,
    ): PlayerSnapshot = PlayerSnapshot(
        playerUuid = uuid.toString(),
        playerName = name,
        dimensionId = "minecraft:overworld",
        biomeId = "minecraft:plains",
        position = Position(0, 64, 0),
        lightLevel = 12,
        timeOfDay = timeOfDay,
        weather = weather,
        health = Vital(health, 20),
        food = Vital(18, 20),
        inventorySummary = listOf(InventoryEntry("minecraft:bread", 3)),
        nearbyHostileCount = nearbyHostile,
        nearbyPeacefulCount = 1,
        capturedAtMs = nowMs,
    )
}
