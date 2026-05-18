package dev.aidirector.demo

import dev.aidirector.actions.ActionOutcome
import dev.aidirector.actions.AdvancementFrame
import dev.aidirector.actions.BossBarColor
import dev.aidirector.actions.BossBarOverlay
import dev.aidirector.actions.EquipmentSlotName
import dev.aidirector.actions.NarrationStyle
import dev.aidirector.actions.ServerActions
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.actions.TimeLabel
import dev.aidirector.actions.WeatherKind
import dev.aidirector.config.LlmConfig
import dev.aidirector.director.AgentLoop
import dev.aidirector.director.PromptBuilder
import dev.aidirector.director.PromptInput
import dev.aidirector.director.TensionCurve
import dev.aidirector.guardrails.Guardrails
import dev.aidirector.llm.EmbeddingClient
import dev.aidirector.llm.LlmClient
import dev.aidirector.memory.EventKind
import dev.aidirector.memory.FactKinds
import dev.aidirector.memory.Memory
import dev.aidirector.memory.NpcRecord
import dev.aidirector.memory.NpcStatus
import dev.aidirector.memory.QuestRecord
import dev.aidirector.memory.QuestStatus
import dev.aidirector.memory.WorldStateKeys
import dev.aidirector.rag.Rag
import dev.aidirector.sensors.InventoryEntry
import dev.aidirector.sensors.PlayerSnapshot
import dev.aidirector.sensors.Position
import dev.aidirector.sensors.TimeOfDay
import dev.aidirector.sensors.Vital
import dev.aidirector.sensors.Weather
import dev.aidirector.support.Fixtures
import dev.aidirector.tools.ToolRegistry
import dev.aidirector.tools.impl.ApplyEffectTool
import dev.aidirector.tools.impl.AssignQuestTool
import dev.aidirector.tools.impl.CompleteQuestTool
import dev.aidirector.tools.impl.CreateBossBarTool
import dev.aidirector.tools.impl.GiveBookTool
import dev.aidirector.tools.impl.GiveItemTool
import dev.aidirector.tools.impl.GrantAdvancementTool
import dev.aidirector.tools.impl.KillMobTool
import dev.aidirector.tools.impl.ModifyWeatherTool
import dev.aidirector.tools.impl.PlaySoundTool
import dev.aidirector.tools.impl.RecordFactTool
import dev.aidirector.tools.impl.SendNarrationTool
import dev.aidirector.tools.impl.SetMobEquipmentTool
import dev.aidirector.tools.impl.SetMobTargetTool
import dev.aidirector.tools.impl.SpawnMobTool
import dev.aidirector.tools.impl.SpawnNpcTool
import dev.aidirector.tools.impl.SpawnParticleTool
import dev.aidirector.tools.impl.StrikeLightningTool
import dev.aidirector.tools.impl.UpdateQuestTool
import dev.aidirector.util.Clock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

/**
 * Live demos against a real OpenAI-compatible endpoint (NVIDIA NIM by default).
 * Skipped unless `AIDIRECTOR_LLM_API_KEY` is set. Each test boots the full
 * director stack against a fake player and writes the trace to a separate
 * file in the OS temp dir.
 */
class LiveDirectorDemo {

    @Test
    fun `scenario 1 dramatic near-death English`() = runScenario(
        outFileName = "aidirector-demo-1-dramatic-en.txt",
        outputLanguage = "English",
        scene = ::buildDramaticScene,
    )

    @Test
    fun `scenario 2 quiet mining English`() = runScenario(
        outFileName = "aidirector-demo-2-mining-en.txt",
        outputLanguage = "English",
        scene = ::buildMiningScene,
    )

    @Test
    fun `scenario 3 quiet mining Russian`() = runScenario(
        outFileName = "aidirector-demo-3-mining-ru.txt",
        outputLanguage = "Russian",
        scene = ::buildMiningScene,
    )

    @Test
    fun `scenario 4 horror at midnight Russian`() = runScenario(
        outFileName = "aidirector-demo-4-horror-ru.txt",
        outputLanguage = "Russian",
        scene = ::buildHorrorScene,
    )

    @Test
    fun `scenario 5 horror at midnight English`() = runScenario(
        outFileName = "aidirector-demo-5-horror-en.txt",
        outputLanguage = "English",
        scene = ::buildHorrorScene,
    )

    private data class Scene(
        val playerUuid: UUID,
        val snapshot: PlayerSnapshot,
        val seedFacts: List<Triple<String, String, Int>>,
        val npcs: List<NpcRecord>,
        val quests: List<QuestRecord>,
        val recentEvents: List<Pair<String, String>>,
        val initialArc: String?,
        val recentlyTookDamage: Boolean,
        val retrievalQuery: String,
    )

    private fun buildDramaticScene(now: Long): Scene {
        val pid = UUID.fromString("a0a0a0a0-1111-2222-3333-aaaaaaaaaaaa")
        return Scene(
            playerUuid = pid,
            snapshot = PlayerSnapshot(
                playerUuid = pid.toString(),
                playerName = "Anya",
                dimensionId = "minecraft:overworld",
                biomeId = "minecraft:plains",
                position = Position(48, 40, 28),
                lightLevel = 3,
                timeOfDay = TimeOfDay.NIGHT,
                weather = Weather(raining = true, thundering = false),
                health = Vital(4, 20),
                food = Vital(8, 20),
                inventorySummary = listOf(
                    InventoryEntry("minecraft:coal", 12),
                    InventoryEntry("minecraft:iron_pickaxe", 1),
                    InventoryEntry("minecraft:bread", 2),
                    InventoryEntry("minecraft:torch", 4),
                ),
                nearbyHostileCount = 2,
                nearbyPeacefulCount = 0,
                capturedAtMs = now,
            ),
            seedFacts = defaultLore(),
            npcs = listOf(borinNpc(pid, now)),
            quests = listOf(lanternQuest(pid, now)),
            recentEvents = listOf(
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:coal_ore","x":50,"y":40,"z":30}""",
                EventKind.PLAYER_HURT to """{"source":"zombie.attack","amount":4.0,"hp_after":7.0,"attacker":"Zombie"}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:cobblestone","x":48,"y":40,"z":29}""",
                EventKind.PLAYER_HURT to """{"source":"zombie.attack","amount":3.0,"hp_after":4.0,"attacker":"Zombie"}""",
            ),
            initialArc = "A creeping darkness is leaking from the abandoned mines east of spawn. Anya has accepted Borin's quest to find his daughter's lantern.",
            recentlyTookDamage = true,
            retrievalQuery = "Anya in a cave at night with low HP zombies cursed mine Borin",
        )
    }

    private fun buildMiningScene(now: Long): Scene {
        val pid = UUID.fromString("b0b0b0b0-2222-3333-4444-bbbbbbbbbbbb")
        return Scene(
            playerUuid = pid,
            snapshot = PlayerSnapshot(
                playerUuid = pid.toString(),
                playerName = "Anya",
                dimensionId = "minecraft:overworld",
                biomeId = "minecraft:plains",
                position = Position(102, 12, -65),
                lightLevel = 8,
                timeOfDay = TimeOfDay.DAY,
                weather = Weather(raining = false, thundering = false),
                health = Vital(20, 20),
                food = Vital(17, 20),
                inventorySummary = listOf(
                    InventoryEntry("minecraft:cobblestone", 128),
                    InventoryEntry("minecraft:coal", 41),
                    InventoryEntry("minecraft:raw_iron", 18),
                    InventoryEntry("minecraft:torch", 23),
                    InventoryEntry("minecraft:iron_pickaxe", 1),
                    InventoryEntry("minecraft:bread", 5),
                ),
                nearbyHostileCount = 0,
                nearbyPeacefulCount = 0,
                capturedAtMs = now,
            ),
            seedFacts = defaultLore(),
            npcs = listOf(borinNpc(pid, now)),
            quests = listOf(lanternQuest(pid, now)),
            recentEvents = listOf(
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:stone","x":102,"y":12,"z":-65}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:iron_ore","x":102,"y":12,"z":-66}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:stone","x":103,"y":12,"z":-66}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:coal_ore","x":103,"y":11,"z":-67}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:stone","x":104,"y":11,"z":-67}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:cobblestone","x":105,"y":11,"z":-67}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:iron_ore","x":105,"y":10,"z":-68}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:stone","x":106,"y":10,"z":-68}""",
            ),
            initialArc = "Anya has accepted Borin's quest to find his daughter's lantern. She is deep underground east of spawn, slowly tunnelling toward the sealed Old Mine.",
            recentlyTookDamage = false,
            retrievalQuery = "Anya mining cobblestone iron ore deep underground quiet alone Borin lantern",
        )
    }

    private fun buildHorrorScene(now: Long): Scene {
        val pid = UUID.fromString("c0c0c0c0-3333-4444-5555-cccccccccccc")
        return Scene(
            playerUuid = pid,
            snapshot = PlayerSnapshot(
                playerUuid = pid.toString(),
                playerName = "Anya",
                dimensionId = "minecraft:overworld",
                biomeId = "minecraft:plains",
                position = Position(140, 14, -82),
                lightLevel = 2,
                timeOfDay = TimeOfDay.NIGHT,
                weather = Weather(raining = false, thundering = false),
                health = Vital(20, 20),
                food = Vital(14, 20),
                inventorySummary = listOf(
                    InventoryEntry("minecraft:cobblestone", 196),
                    InventoryEntry("minecraft:coal", 38),
                    InventoryEntry("minecraft:raw_iron", 22),
                    InventoryEntry("minecraft:torch", 2),
                    InventoryEntry("minecraft:iron_pickaxe", 1),
                ),
                nearbyHostileCount = 0,
                nearbyPeacefulCount = 0,
                capturedAtMs = now,
            ),
            seedFacts = defaultLore() + listOf(
                Triple(
                    FactKinds.PLAYER_TRAIT,
                    "In the last week Anya has begun digging past midnight despite her usual " +
                        "caution. She is obsessed with finding the lantern and Borin's daughter. " +
                        "She is not herself.",
                    4,
                ),
                Triple(
                    FactKinds.WORLD_EVENT,
                    "Three nights ago the sealed door of the Old Mine warmed to the touch from " +
                        "the inside. Borin felt it and went home early without speaking.",
                    4,
                ),
            ),
            npcs = listOf(borinNpc(pid, now)),
            quests = listOf(lanternQuest(pid, now)),
            recentEvents = listOf(
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:stone","x":140,"y":14,"z":-82}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:stone","x":140,"y":14,"z":-83}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:cobblestone","x":140,"y":14,"z":-84}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:stone","x":141,"y":14,"z":-84}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:deepslate","x":141,"y":13,"z":-85}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:deepslate","x":141,"y":13,"z":-86}""",
                EventKind.PLAYER_BREAK_BLOCK to """{"block":"minecraft:deepslate","x":141,"y":13,"z":-87}""",
            ),
            initialArc = "Anya is tunnelling toward the sealed Old Mine alone, past midnight, " +
                "ignoring every warning Borin tried to give her. The mine wants her.",
            recentlyTookDamage = false,
            retrievalQuery = "Anya alone deep dark night Old Mine cursed lantern darkness whispers",
        )
    }

    private fun defaultLore(): List<Triple<String, String, Int>> = listOf(
        Triple(FactKinds.LORE, "The Old Mine east of spawn is rumored to be cursed. Miners who entered after dark in the spring of the last Cold Winter never returned.", 4),
        Triple(FactKinds.PLAYER_TRAIT, "Anya is a cautious explorer who prefers gathering and crafting over open combat. She tends to retreat from fights and rebuilds quietly.", 3),
        Triple(FactKinds.NARRATIVE_ARC, "A creeping darkness has begun to spread from the abandoned mines. Travellers speak of whispers in the dark and lanterns that burn green.", 5),
        Triple(FactKinds.NPC_RELATIONSHIP, "Borin the blacksmith lost his daughter Mira to the Old Mine years ago. He has never spoken her name to a stranger.", 4),
    )

    private fun borinNpc(pid: UUID, now: Long): NpcRecord = NpcRecord(
        id = "borin",
        entityUuid = UUID.randomUUID(),
        associatedPlayer = pid,
        name = "Borin",
        role = "old blacksmith",
        personality = "Gruff but kind. Speaks in clipped sentences. Carries grief like a closed door.",
        dimensionId = "minecraft:overworld",
        x = 100, y = 64, z = -50,
        status = NpcStatus.ACTIVE,
        createdAtMs = now - 7 * 24 * 3_600_000L,
        lastSeenAtMs = now - 3_600_000L,
    )

    private fun lanternQuest(pid: UUID, now: Long): QuestRecord = QuestRecord(
        id = "lantern",
        playerUuid = pid,
        npcId = "borin",
        title = "The Missing Lantern",
        description = "Borin asked Anya to find his daughter's lantern, lost the day the mines were sealed.",
        objectivesJson = """[{"text":"find the lantern inside the Old Mine","done":false}]""",
        rewardItem = "minecraft:enchanted_book",
        status = QuestStatus.ACTIVE,
        createdAtMs = now - 3_600_000L,
        completedAtMs = null,
    )

    private fun runScenario(
        outFileName: String,
        outputLanguage: String,
        scene: (Long) -> Scene,
    ) = runBlocking {
        val key = System.getenv("AIDIRECTOR_LLM_API_KEY")
        assumeTrue(key != null, "AIDIRECTOR_LLM_API_KEY not set — skipping live demo")

        val outDir = System.getProperty("java.io.tmpdir")
        val modelSlug = (System.getenv("AIDIRECTOR_LLM_MODEL") ?: "default")
            .replace("/", "_").replace(":", "_").take(28)
        val outFile = Path.of(outDir, outFileName.replace(".txt", "-$modelSlug.txt"))
        Files.deleteIfExists(outFile)
        val log = PrintStream(Files.newOutputStream(outFile), true)
        fun emit(s: String) { println(s); log.println(s) }

        val baseUrl = System.getenv("AIDIRECTOR_LLM_BASE_URL")
            ?: "https://integrate.api.nvidia.com/v1"
        val chatModel = System.getenv("AIDIRECTOR_LLM_MODEL")
            ?: "meta/llama-3.3-70b-instruct"
        val embedModel = System.getenv("AIDIRECTOR_LLM_EMBED_MODEL")
            ?: "nvidia/nv-embedqa-e5-v5"
        val cfg = LlmConfig(
            baseUrl = baseUrl, apiKey = key!!, model = chatModel,
            embedModel = embedModel, embedBaseUrl = null, embedApiKey = null,
            timeoutSeconds = 120, maxRetries = 2,
            temperature = 0.7, maxTokens = 700,
        )

        val tmp = Files.createTempDirectory("aidirector-demo")
        val memory = Memory.open(tmp.resolve("demo.db"))
        val now = System.currentTimeMillis()
        val s = scene(now)
        val actions = PermissiveActions()
        ServerActionsHolder.install(actions)

        emit("================================================================")
        emit("SCENARIO: $outFileName")
        emit("LANGUAGE: $outputLanguage")
        emit("PLAYER:   ${s.snapshot.playerName} @ ${s.snapshot.dimensionId} " +
            "(${s.snapshot.position.x},${s.snapshot.position.y},${s.snapshot.position.z})")
        emit("================================================================")

        try {
            val llm = LlmClient(cfg)
            val embedder = EmbeddingClient(cfg)
            val rag = Rag(memory.facts, embedder, model = embedModel)

            emit("")
            emit("--- Seeding RAG facts ---")
            for ((kind, content, importance) in s.seedFacts) {
                rag.ingestAndEmbed(kind, content, importance)
                emit("  [$kind] $content")
            }

            for (npc in s.npcs) memory.npcs.upsert(npc)
            for (q in s.quests) memory.quests.create(q)
            s.initialArc?.let { memory.worldState.put(WorldStateKeys.NARRATIVE_ARC, it) }
            for ((kind, payload) in s.recentEvents) {
                memory.events.record(s.playerUuid, kind, payload)
            }

            val tension = TensionCurve.compute(s.snapshot, recentlyTookDamage = s.recentlyTookDamage)
            val activeNpcs = memory.npcs.activeForPlayer(s.playerUuid)
            val activeQuests = memory.quests.activeForPlayer(s.playerUuid)
            val recent = memory.events.recent(s.playerUuid, 20)
            val arc = memory.worldState.get(WorldStateKeys.NARRATIVE_ARC)
            val retrieved = rag.retrieve(query = s.retrievalQuery, k = 5, minSimilarity = 0.0)

            emit("")
            emit("--- Tension: ${String.format(Locale.ROOT, "%.3f", tension)} " +
                "(${TensionCurve.describe(tension)}) ---")
            emit("")
            emit("--- RAG retrieved (cosine top-k) ---")
            retrieved.forEachIndexed { i, sf ->
                emit("  ${i + 1}. [${String.format(Locale.ROOT, "%.2f", sf.similarity)}] " +
                    "${sf.fact.kind}: ${sf.fact.content}")
            }

            val messages = PromptBuilder(outputLanguage = outputLanguage).build(
                PromptInput(
                    snapshot = s.snapshot, tensionScore = tension,
                    recentEvents = recent, activeNpcs = activeNpcs,
                    activeQuests = activeQuests, narrativeArc = arc,
                    retrievedFacts = retrieved, campaign = null, nowMs = now,
                ),
            )

            emit("")
            emit("--- SYSTEM message (OUTPUT LANGUAGE block at end if non-English) ---")
            emit(messages[0].content.orEmpty())
            emit("")
            emit("--- USER message ---")
            emit(messages[1].content.orEmpty())

            val guardrails = Guardrails(Fixtures.defaultConfig().guardrails, Clock.System)
            val tools = ToolRegistry(
                listOf(
                    SendNarrationTool(),
                    PlaySoundTool(),
                    GiveItemTool { Fixtures.defaultConfig().guardrails.itemRarityCap },
                    ApplyEffectTool(),
                    ModifyWeatherTool(),
                    SpawnNpcTool(),
                    SpawnMobTool(),
                    SetMobEquipmentTool(),
                    SetMobTargetTool(),
                    KillMobTool(),
                    SpawnParticleTool(),
                    StrikeLightningTool(),
                    CreateBossBarTool(),
                    GiveBookTool(),
                    GrantAdvancementTool(),
                    RecordFactTool(),
                    AssignQuestTool(),
                    UpdateQuestTool(),
                    CompleteQuestTool(),
                ),
            )
            val agent = AgentLoop(
                llm = llm, tools = tools, guardrails = guardrails,
                memory = memory, rag = rag,
                narrationDedup = dev.aidirector.dedup.NarrationDedup(),
                maxIterations = 3, maxToolCallsPerIteration = 3,
            )

            emit("")
            emit("--- Calling NIM (${cfg.model}) — running agent loop ---")
            val report = agent.run(
                playerUuid = s.playerUuid, nowMs = now,
                initialMessages = messages, model = cfg.model,
                temperature = cfg.temperature, maxTokens = cfg.maxTokens,
            )

            emit("")
            emit("--- Agent report ---")
            emit("Iterations:        ${report.iterations}")
            emit("Tools attempted:   ${report.toolsAttempted}")
            emit("Tools executed:    ${report.toolsExecuted}")
            emit("Tools refused:     ${report.toolsRefused}")
            emit("Tools failed:      ${report.toolsFailed}")
            emit("Final assistant:   ${report.finalAssistantText?.trim() ?: "(empty)"}")
            emit("LLM error:         ${report.llmError ?: "(none)"}")
            emit("")
            emit("--- Tool calls observed at the platform layer ---")
            if (actions.calls.isEmpty()) emit("(none — director chose not to act this tick)")
            actions.calls.forEachIndexed { i, c -> emit("  ${i + 1}. $c") }
        } finally {
            log.flush()
            log.close()
            memory.close()
            ServerActionsHolder.reset()
        }
    }
}

/**
 * Wide-open ServerActions for the demo: every namespaced id is treated as
 * registered, every call records itself.
 */
private class PermissiveActions : ServerActions {
    val calls: MutableList<Any> = java.util.concurrent.CopyOnWriteArrayList()

    override fun isPlayerOnline(playerUuid: UUID) = true
    override fun getPlayerDimensionId(playerUuid: UUID) = "minecraft:overworld"
    override fun getPlayerPosition(playerUuid: UUID) = intArrayOf(48, 40, 28)

    override fun sendNarration(playerUuid: UUID, message: String, style: NarrationStyle): ActionOutcome {
        calls += "send_narration[${style.name.lowercase()}]: \"$message\""
        return ActionOutcome.Success("ok")
    }
    override fun playSound(playerUuid: UUID, soundId: String, volume: Float, pitch: Float): ActionOutcome {
        calls += "play_sound: $soundId (vol=$volume pitch=$pitch)"
        return ActionOutcome.Success("ok")
    }
    override fun spawnParticle(playerUuid: UUID, particleId: String, x: Double, y: Double, z: Double, count: Int, deltaX: Double, deltaY: Double, deltaZ: Double, speed: Double): ActionOutcome {
        calls += "spawn_particle: $particleId x$count at ($x,$y,$z)"
        return ActionOutcome.Success("ok")
    }
    override fun strikeLightning(dimensionId: String, x: Double, y: Double, z: Double, cosmeticOnly: Boolean): ActionOutcome {
        calls += "strike_lightning: at ($x,$y,$z) cosmetic=$cosmeticOnly"
        return ActionOutcome.Success("ok")
    }
    override fun createBossBar(playerUuid: UUID, name: String, color: BossBarColor, overlay: BossBarOverlay, durationSeconds: Int): ActionOutcome {
        calls += "create_boss_bar: '$name' ${color.name.lowercase()} for ${durationSeconds}s"
        return ActionOutcome.Success("ok")
    }
    override fun giveItem(playerUuid: UUID, itemId: String, count: Int, customName: String?): ActionOutcome {
        calls += "give_item: $count x $itemId${customName?.let { " (\"$it\")" } ?: ""}"
        return ActionOutcome.Success("ok")
    }
    override fun giveBook(playerUuid: UUID, title: String, author: String, pages: List<String>): ActionOutcome {
        calls += "give_lore_note: '$title' by $author (${pages.size} pages)\n        -- page 1: \"${pages.firstOrNull()?.take(160)}\""
        return ActionOutcome.Success("ok")
    }
    override fun applyEffect(playerUuid: UUID, effectId: String, durationTicks: Int, amplifier: Int, showParticles: Boolean): ActionOutcome {
        calls += "apply_effect: $effectId tier=$amplifier duration=${durationTicks / 20}s"
        return ActionOutcome.Success("ok")
    }
    override fun grantAdvancement(playerUuid: UUID, holderId: String, title: String, description: String, iconItem: String, frame: AdvancementFrame, announceToChat: Boolean): ActionOutcome {
        calls += "grant_advancement[${frame.name.lowercase()}]: '$title' — $description (icon=$iconItem)"
        return ActionOutcome.Success("ok")
    }
    override fun modifyWeather(playerUuid: UUID, weather: WeatherKind, durationTicks: Int): ActionOutcome {
        calls += "modify_weather: ${weather.name.lowercase()} for ${durationTicks / 20}s"
        return ActionOutcome.Success("ok")
    }
    override fun setTime(dimensionId: String, label: TimeLabel): ActionOutcome {
        calls += "set_time: ${label.name.lowercase()}"
        return ActionOutcome.Success("ok")
    }
    override fun placeBlock(dimensionId: String, x: Int, y: Int, z: Int, blockId: String): ActionOutcome {
        calls += "place_block: $blockId at ($x,$y,$z)"
        return ActionOutcome.Success("ok")
    }
    override fun placeSign(dimensionId: String, x: Int, y: Int, z: Int, lines: List<String>): ActionOutcome {
        calls += "place_sign: at ($x,$y,$z) lines=$lines"
        return ActionOutcome.Success("ok")
    }
    override fun giveTreasureMap(playerUuid: UUID, targetX: Int, targetZ: Int, label: String): ActionOutcome {
        calls += "give_treasure_map: '$label' → ($targetX,$targetZ)"
        return ActionOutcome.Success("ok")
    }
    override fun buildStructure(dimensionId: String, blocks: List<dev.aidirector.actions.PlacedBlock>): ActionOutcome {
        calls += "build_structure: ${blocks.size} blocks"
        return ActionOutcome.Success("ok")
    }
    override fun teleportPlayer(playerUuid: UUID, x: Double, y: Double, z: Double, dimensionId: String?): ActionOutcome {
        calls += "teleport_player: ($x,$y,$z) dim=${dimensionId ?: "(same)"}"
        return ActionOutcome.Success("ok")
    }
    override fun spawnNpc(ownerPlayer: UUID?, dimensionId: String, entityType: String, customName: String, x: Double, y: Double, z: Double, invulnerable: Boolean, ambientMovement: Boolean): ActionOutcome {
        val uuid = UUID.randomUUID()
        calls += "spawn_npc: '$customName' as $entityType at ($x,$y,$z)"
        return ActionOutcome.Success("ok", entityUuid = uuid)
    }
    override fun spawnMob(dimensionId: String, entityType: String, x: Double, y: Double, z: Double, count: Int, customName: String?, persistent: Boolean, hostileToPlayer: UUID?): ActionOutcome {
        val uuids = (1..count).map { UUID.randomUUID() }
        calls += "spawn_mob: $count x $entityType at ($x,$y,$z)${if (hostileToPlayer != null) " — targeting player" else ""}"
        return ActionOutcome.Success("ok", entityUuid = uuids.firstOrNull(), entityUuids = uuids)
    }
    override fun setMobEquipment(entityUuid: UUID, slot: EquipmentSlotName, itemId: String): ActionOutcome {
        calls += "set_mob_equipment: ${slot.name.lowercase()} = $itemId"
        return ActionOutcome.Success("ok")
    }
    override fun setMobTarget(entityUuid: UUID, targetPlayer: UUID): ActionOutcome {
        calls += "set_mob_target: $entityUuid -> $targetPlayer"
        return ActionOutcome.Success("ok")
    }
    override fun killEntity(entityUuid: UUID): ActionOutcome {
        calls += "kill_entity: $entityUuid"
        return ActionOutcome.Success("ok")
    }

    override fun isItemRegistered(itemId: String) = itemId.startsWith("minecraft:")
    override fun isSoundRegistered(soundId: String) = soundId.startsWith("minecraft:")
    override fun isEffectRegistered(effectId: String) = effectId.startsWith("minecraft:")
    override fun isEntityTypeRegistered(entityTypeId: String) = entityTypeId.startsWith("minecraft:")
    override fun isBlockRegistered(blockId: String) = blockId.startsWith("minecraft:")
    override fun isParticleRegistered(particleId: String) = particleId.startsWith("minecraft:")
}
