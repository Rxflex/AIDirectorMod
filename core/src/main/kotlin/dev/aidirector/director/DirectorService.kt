package dev.aidirector.director

import dev.aidirector.AIDirector
import dev.aidirector.actions.ServerActions
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.config.ConfigService
import dev.aidirector.guardrails.Guardrails
import dev.aidirector.interaction.NpcDialogue
import dev.aidirector.llm.EmbeddingClient
import dev.aidirector.llm.LlmClient
import dev.aidirector.memory.Memory
import dev.aidirector.memory.NpcStatus
import dev.aidirector.rag.Rag
import dev.aidirector.sensors.Sensors
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolRegistry
import dev.aidirector.tools.impl.ApplyEffectTool
import dev.aidirector.tools.impl.AssignQuestTool
import dev.aidirector.tools.impl.BuildStructureTool
import dev.aidirector.tools.impl.CompleteQuestTool
import dev.aidirector.tools.impl.CreateBossBarTool
import dev.aidirector.tools.impl.DressSceneTool
import dev.aidirector.tools.impl.EvolveNpcTool
import dev.aidirector.tools.impl.GiveBookTool
import dev.aidirector.tools.impl.GiveItemTool
import dev.aidirector.tools.impl.GiveLootTool
import dev.aidirector.tools.impl.GiveTreasureMapTool
import dev.aidirector.tools.impl.GrantAdvancementTool
import dev.aidirector.tools.impl.KillMobTool
import dev.aidirector.tools.impl.ModifyMobTool
import dev.aidirector.tools.impl.ModifyWeatherTool
import dev.aidirector.tools.impl.PhantomJoinTool
import dev.aidirector.tools.impl.PhantomLeaveTool
import dev.aidirector.tools.impl.PhantomSayTool
import dev.aidirector.tools.impl.PlaceBlockTool
import dev.aidirector.tools.impl.PlaceSignTool
import dev.aidirector.tools.impl.PlaySoundTool
import dev.aidirector.tools.impl.RecordFactTool
import dev.aidirector.tools.impl.SendNarrationTool
import dev.aidirector.tools.impl.SetMobEquipmentTool
import dev.aidirector.tools.impl.SetMobTargetTool
import dev.aidirector.tools.impl.SetTimeTool
import dev.aidirector.tools.impl.SpawnMobTool
import dev.aidirector.tools.impl.SpawnNpcTool
import dev.aidirector.tools.impl.SpawnParticleTool
import dev.aidirector.tools.impl.StrikeLightningTool
import dev.aidirector.tools.impl.TeleportPlayerTool
import dev.aidirector.tools.impl.UpdateQuestTool
import dev.aidirector.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Lifecycle wrapper. Owns the per-tick coroutine, the periodic reflection
 * coroutine, and all the long-lived collaborators (Memory, Rag, AgentLoop,
 * Director). Constructed once on server start and closed on server stop.
 */
class DirectorService private constructor(
    private val configService: ConfigService,
    private val memory: Memory,
    private val rag: Rag,
    private val director: Director,
    private val reflection: Reflection,
    private val showrunner: dev.aidirector.campaign.Showrunner,
    private val npcDialogue: NpcDialogue,
    private val chronicle: dev.aidirector.chronicle.Chronicle,
    private val scope: CoroutineScope,
) : AutoCloseable {

    @Volatile
    private var tickJob: Job? = null

    @Volatile
    private var reflectionJob: Job? = null

    @Volatile
    private var showrunnerJob: Job? = null

    val isRunning: Boolean get() = tickJob?.isActive == true
    val isPaused: Boolean get() = director.paused

    fun recordEvent(playerUuid: java.util.UUID, kind: String, payloadJson: String) =
        memory.events.record(playerUuid, kind, payloadJson)

    /**
     * Optional platform-supplied callback to populate RAG with platform-
     * specific context (e.g. the MC `RegistryDumper` summarising modded items
     * for the mod build, or a Bukkit-Material registry summary for Paper).
     * Defaults to no-op so a pure-JVM use of [DirectorService] is fine.
     */
    @Volatile
    var platformStartupTask: suspend (Memory, Rag) -> Unit = { _, _ -> }

    fun start() {
        val intervalMs = configService.current.director.tickIntervalMs
        AIDirector.log.info("Starting director tick loop (interval=${intervalMs}ms)")
        // One-shot startup tasks: platform-specific (e.g. registry dump) + seed facts.
        // Both are idempotent (hash/marker-gated) so re-running on reload is cheap.
        scope.launch {
            try {
                platformStartupTask(memory, rag)
                dev.aidirector.bootstrap.SeedFactsService.ingestIfNeeded(memory, rag, configService.current.director.seedFacts)
            } catch (e: Exception) {
                AIDirector.log.warn("Startup RAG ingest failed: ${e.message}")
            }
        }
        tickJob = scope.launch {
            while (isActive) {
                runOneTick()
                delay(intervalMs)
            }
        }
        // Reflection runs less often; it checks its own due time so cheap to call frequently.
        reflectionJob = scope.launch {
            while (isActive) {
                delay(REFLECTION_POLL_MS)
                try {
                    reflection.runIfDue()
                } catch (e: Exception) {
                    AIDirector.log.error("Reflection error: ${e.message}", e)
                }
            }
        }
        // Showrunner — campaign bootstrap + slow review. Polls cheaply; the
        // Showrunner itself decides when a real (LLM-backed) pass is due.
        showrunnerJob = scope.launch {
            while (isActive) {
                delay(SHOWRUNNER_POLL_MS)
                try {
                    showrunner.runIfDue()
                } catch (e: Exception) {
                    AIDirector.log.error("Showrunner error: ${e.message}", e)
                }
            }
        }
    }

    fun pause() { director.paused = true }
    fun resume() { director.paused = false }

    suspend fun triggerNow(): List<Director.TickReport> = runOneTick(ignoreThrottle = true)

    suspend fun triggerReflection(): Reflection.RunReport = reflection.runNow()

    suspend fun triggerShowrunner(): dev.aidirector.campaign.Showrunner.RunReport = showrunner.runNow()

    /**
     * Called by platform event hooks when a player right-clicks an entity.
     * If the entity is a tracked active NPC, fires an NPC dialogue agent
     * loop in the background and returns true (the caller should cancel
     * the default interact UI). Otherwise returns false and lets vanilla
     * handling proceed.
     */
    fun handleNpcInteraction(playerUuid: java.util.UUID, entityUuid: java.util.UUID): Boolean {
        val npc = memory.npcs.byEntityUuid(entityUuid) ?: return false
        if (npc.status != NpcStatus.ACTIVE) return false
        if (npc.associatedPlayer != null && npc.associatedPlayer != playerUuid) return false
        scope.launch {
            try {
                npcDialogue.handle(playerUuid, npc)
            } catch (e: Exception) {
                AIDirector.log.warn("NPC dialogue failed: ${e.message}")
            }
        }
        return true
    }

    /**
     * Called by platform event hooks when a player logs out. Fires the
     * session chronicler in the background — it composes an in-fiction journal
     * entry for the session that just ended.
     */
    fun onPlayerLogout(playerUuid: java.util.UUID) {
        scope.launch {
            try {
                chronicle.writeForPlayer(playerUuid)
            } catch (e: Exception) {
                AIDirector.log.warn("Chronicle write failed for $playerUuid: ${e.message}")
            }
        }
    }

    /**
     * Called by platform event hooks shortly after a player logs in. Delivers
     * any chronicle entries the player has not yet received as a written book.
     * A short delay lets the client finish loading before the book lands.
     */
    fun onPlayerLogin(playerUuid: java.util.UUID) {
        scope.launch {
            try {
                delay(LOGIN_DELIVERY_DELAY_MS)
                chronicle.deliverPendingForPlayer(playerUuid)
            } catch (e: Exception) {
                AIDirector.log.warn("Chronicle delivery failed for $playerUuid: ${e.message}")
            }
        }
    }

    suspend fun triggerChronicle(playerUuid: java.util.UUID): dev.aidirector.chronicle.Chronicle.RunReport =
        chronicle.writeForPlayer(playerUuid)

    /** Delivers any pending chronicle entries immediately. Returns the count delivered. */
    fun deliverChronicleNow(playerUuid: java.util.UUID): Int =
        chronicle.deliverPendingForPlayer(playerUuid)

    private suspend fun runOneTick(ignoreThrottle: Boolean = false): List<Director.TickReport> {
        val sensors = dev.aidirector.sensors.SensorsHolder.require()
        val debug = configService.current.director.debugLogging
        val reports = mutableListOf<Director.TickReport>()
        for (uuid in sensors.onlinePlayers()) {
            try {
                val report = director.tickPlayer(uuid, ignoreThrottle = ignoreThrottle)
                reports += report
                if (debug) logTick(report)
            } catch (e: Exception) {
                AIDirector.log.error("Tick failed for $uuid: ${e.message}", e)
            }
        }
        return reports
    }

    /** One readable line per tick — enabled by director.debug_logging. */
    private fun logTick(r: Director.TickReport) {
        val who = r.playerUuid.toString().take(8)
        val outcome = when {
            r.skipped != null -> "skipped — ${r.skipped}"
            r.agentReport != null -> {
                val a = r.agentReport!!
                val err = a.llmError?.let { " [LLM error: $it]" } ?: ""
                if (a.toolsAttempted == 0) {
                    "LLM ran, no action this tick$err"
                } else {
                    "acted — iterations=${a.iterations}, tools ${a.toolsExecuted}/${a.toolsAttempted} ok, " +
                        "${a.toolsRefused} refused, ${a.toolsFailed} failed$err"
                }
            }
            else -> "no result"
        }
        AIDirector.log.info("[debug] tick player=$who: $outcome")
    }

    override fun close() {
        tickJob?.cancel()
        reflectionJob?.cancel()
        showrunnerJob?.cancel()
        scope.cancel()
        memory.close()
    }

    companion object {
        private const val REFLECTION_POLL_MS = 30_000L
        private const val SHOWRUNNER_POLL_MS = 60_000L
        private const val LOGIN_DELIVERY_DELAY_MS = 4_000L

        @JvmStatic
        fun create(
            configService: ConfigService,
            sensors: Sensors,
            actions: ServerActions = ServerActionsHolder.require(),
            saveDir: Path,
            clock: Clock = Clock.System,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): DirectorService {
            val cfg = configService.current
            val memory = Memory.open(saveDir.resolve(cfg.memory.dbFileName), clock)
            val llm = LlmClient(cfg.llm)
            val embeddings = EmbeddingClient(cfg.llm)
            val rag = Rag(
                facts = memory.facts,
                embeddings = embeddings,
                model = cfg.llm.embedModel ?: DEFAULT_EMBED_MODEL,
                scope = scope,
            )
            val guardrails = Guardrails(cfg.guardrails, clock)
            val narrationDedup = dev.aidirector.dedup.NarrationDedup()
            val phantoms = dev.aidirector.phantom.PhantomRegistry()
            val campaignStore = dev.aidirector.campaign.CampaignStore(memory.worldState)
            val tools = ToolRegistry(buildToolList(cfg.director.allowDestructiveTools, configService))
            val agentLoop = AgentLoop(
                llm = llm,
                tools = tools,
                guardrails = guardrails,
                memory = memory,
                rag = rag,
                narrationDedup = narrationDedup,
                phantoms = phantoms,
                maxIterations = cfg.director.maxAgentIterations,
                maxToolCallsPerIteration = cfg.director.maxToolCallsPerIteration,
                debugLogging = cfg.director.debugLogging,
            )
            val director = Director(
                configService = configService,
                sensors = sensors,
                memory = memory,
                rag = rag,
                agentLoop = agentLoop,
                campaignStore = campaignStore,
                phantoms = phantoms,
                clock = clock,
            )
            val showrunner = dev.aidirector.campaign.Showrunner(
                configService = configService,
                sensors = sensors,
                memory = memory,
                rag = rag,
                campaignStore = campaignStore,
                llm = llm,
                clock = clock,
            )
            val reflection = Reflection(
                configService = configService,
                sensors = sensors,
                actions = actions,
                memory = memory,
                rag = rag,
                llm = llm,
                clock = clock,
            )
            val npcDialogue = NpcDialogue(
                configService = configService,
                sensors = sensors,
                memory = memory,
                rag = rag,
                agentLoop = agentLoop,
                clock = clock,
            )
            val chronicle = dev.aidirector.chronicle.Chronicle(
                configService = configService,
                memory = memory,
                campaignStore = campaignStore,
                actions = actions,
                rag = rag,
                llm = llm,
                clock = clock,
            )
            return DirectorService(
                configService, memory, rag, director, reflection,
                showrunner, npcDialogue, chronicle, scope,
            )
        }

        private fun buildToolList(allowDestructive: Boolean, cs: ConfigService): List<Tool<*>> {
            val safe = mutableListOf<Tool<*>>(
                SendNarrationTool(),
                PlaySoundTool(),
                GiveItemTool(rarityCapProvider = { cs.current.guardrails.itemRarityCap }),
                GiveLootTool(),
                ApplyEffectTool(),
                ModifyWeatherTool(),
                SpawnNpcTool(),
                EvolveNpcTool(),
                SpawnMobTool(),
                SetMobEquipmentTool(),
                SetMobTargetTool(),
                ModifyMobTool(),
                KillMobTool(),
                SpawnParticleTool(),
                StrikeLightningTool(),
                CreateBossBarTool(),
                GiveBookTool(),
                GiveTreasureMapTool(),
                GrantAdvancementTool(),
                RecordFactTool(),
                AssignQuestTool(),
                UpdateQuestTool(),
                CompleteQuestTool(),
                // place_sign / build_structure modify blocks but are benign,
                // decorative artifacts — the whole point of "visible marks the
                // director leaves". Kept in the default set, not behind the
                // destructive-tools opt-in.
                PlaceSignTool(),
                BuildStructureTool(),
                // Atmospheric block placement — only ever fills air, so safe
                // outside the destructive opt-in.
                DressSceneTool(),
                // Phantom player — tab-list + chat presence, no world entity.
                PhantomJoinTool(),
                PhantomSayTool(),
                PhantomLeaveTool(),
            )
            if (allowDestructive) {
                safe += SetTimeTool()
                safe += PlaceBlockTool()
                safe += TeleportPlayerTool()
            }
            return safe
        }

        // Symmetric model — needs no `input_type` field, so it works through
        // any OpenAI-compatible endpoint including proxies. See aidirector.toml.
        const val DEFAULT_EMBED_MODEL = "nvidia/nv-embed-v1"
    }
}
