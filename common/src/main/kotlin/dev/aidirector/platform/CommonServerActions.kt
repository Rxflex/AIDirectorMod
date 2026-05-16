package dev.aidirector.platform

import dev.aidirector.AIDirector
import dev.aidirector.actions.ActionOutcome
import dev.aidirector.actions.AdvancementFrame
import dev.aidirector.actions.BossBarColor
import dev.aidirector.actions.BossBarOverlay
import dev.aidirector.actions.EquipmentSlotName
import dev.aidirector.actions.NarrationStyle
import dev.aidirector.actions.ServerActions
import dev.aidirector.actions.TimeLabel
import dev.aidirector.actions.WeatherKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.ChatFormatting
import net.minecraft.advancements.Advancement
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.advancements.AdvancementRequirements
import net.minecraft.advancements.AdvancementRewards
import net.minecraft.advancements.AdvancementType
import net.minecraft.advancements.Criterion
import net.minecraft.advancements.CriteriaTriggers
import net.minecraft.advancements.DisplayInfo
import net.minecraft.advancements.critereon.ImpossibleTrigger
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.BossEvent
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.WrittenBookContent
import net.minecraft.server.network.Filterable
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Platform-agnostic implementation of [ServerActions]. Lives in `common`
 * because Architectury's common module compiles against MC. Platform modules
 * (`fabric`, `neoforge`) construct it with a supplier pointing at the live
 * server and a coroutine scope for delayed cleanup tasks (boss bars, mob TTLs).
 */
class CommonServerActions(
    private val serverSupplier: () -> MinecraftServer?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ServerActions {

    /** Active per-player boss bars, keyed by random id. Removed by scheduled cleanup. */
    private val bossBars = ConcurrentHashMap<UUID, ServerBossEvent>()

    private fun server(): MinecraftServer? = serverSupplier()
    private fun player(uuid: UUID): ServerPlayer? = server()?.playerList?.getPlayer(uuid)
    private fun levelOf(dimensionId: String): ServerLevel? {
        val server = server() ?: return null
        val rl = ResourceLocation.tryParse(dimensionId) ?: return null
        return server.allLevels.firstOrNull { it.dimension().location() == rl }
    }
    private fun entityByUuid(uuid: UUID): net.minecraft.world.entity.Entity? {
        val server = server() ?: return null
        for (level in server.allLevels) {
            level.getEntity(uuid)?.let { return it }
        }
        return null
    }

    /**
     * `EntityType.create` gained a mandatory `EntitySpawnReason` parameter
     * after 1.21.1 (the enum did not exist before), so the call cannot be
     * written once in source. We resolve the right overload reflectively and
     * cache it — a single contained shim that keeps the whole module
     * single-source across adjacent 1.21.x releases.
     */
    private val entityCreateMethod: java.lang.reflect.Method by lazy {
        val candidates = EntityType::class.java.methods.filter { it.name == "create" }
        candidates.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(ServerLevel::class.java)
        } ?: candidates.first {
            it.parameterCount == 2 &&
                it.parameterTypes[0].isAssignableFrom(ServerLevel::class.java) &&
                it.parameterTypes[1].isEnum
        }
    }

    /** The `EntitySpawnReason` constant to pass on 1.21.2+ — null on 1.21.1. */
    private val entityCreateReason: Any? by lazy {
        val m = entityCreateMethod
        if (m.parameterCount < 2) {
            null
        } else {
            val constants = m.parameterTypes[1].enumConstants
            constants?.firstOrNull { (it as Enum<*>).name == "COMMAND" } ?: constants?.firstOrNull()
        }
    }

    private fun createEntity(
        type: EntityType<*>,
        level: ServerLevel,
    ): net.minecraft.world.entity.Entity? {
        val m = entityCreateMethod
        val result = if (m.parameterCount == 1) {
            m.invoke(type, level)
        } else {
            m.invoke(type, level, entityCreateReason)
        }
        return result as? net.minecraft.world.entity.Entity
    }

    /**
     * The cross-dimension `ServerPlayer.teleportTo(ServerLevel, ...)` overload
     * shifted between 1.21.x: the relative-movement enum was renamed and a
     * trailing boolean parameter was added after 1.21.1. We bind it
     * reflectively so the call compiles once across every target.
     */
    private val teleportMethod: java.lang.reflect.Method by lazy {
        ServerPlayer::class.java.methods.first {
            it.name == "teleportTo" &&
                it.parameterCount >= 7 &&
                it.parameterTypes[0].isAssignableFrom(ServerLevel::class.java)
        }
    }

    private fun teleportAcrossDimensions(
        player: ServerPlayer,
        level: ServerLevel,
        x: Double, y: Double, z: Double,
    ) {
        val m = teleportMethod
        val args = arrayListOf<Any?>(level, x, y, z, emptySet<Any>(), player.yRot, player.xRot)
        // 1.21.2+ appended a trailing boolean flag — pad with `false`.
        while (args.size < m.parameterCount) args.add(false)
        m.invoke(player, *args.toTypedArray())
    }

    override fun isPlayerOnline(playerUuid: UUID): Boolean = player(playerUuid) != null

    override fun getPlayerDimensionId(playerUuid: UUID): String? =
        player(playerUuid)?.serverLevel()?.dimension()?.location()?.toString()

    override fun getPlayerPosition(playerUuid: UUID): IntArray? {
        val p = player(playerUuid) ?: return null
        return intArrayOf(p.blockX, p.blockY, p.blockZ)
    }

    // ---- Atmosphere -----------------------------------------------------

    override fun sendNarration(playerUuid: UUID, message: String, style: NarrationStyle): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        server.execute {
            when (style) {
                NarrationStyle.NARRATOR -> {
                    player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GOLD))
                }
                NarrationStyle.WHISPER -> {
                    player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY))
                }
                NarrationStyle.ANNOUNCEMENT -> {
                    val title = Component.literal(message).withStyle(ChatFormatting.AQUA)
                    player.connection.send(ClientboundSetTitlesAnimationPacket(10, 60, 20))
                    player.connection.send(ClientboundSetTitleTextPacket(title))
                }
                NarrationStyle.SUBTITLE -> {
                    val sub = Component.literal(message).withStyle(ChatFormatting.WHITE)
                    player.connection.send(ClientboundSetTitlesAnimationPacket(10, 60, 20))
                    player.connection.send(ClientboundSetSubtitleTextPacket(sub))
                }
            }
        }
        return ActionOutcome.Success("Narrated (${style.name.lowercase()}) to ${player.gameProfile.name}")
    }

    override fun playSound(playerUuid: UUID, soundId: String, volume: Float, pitch: Float): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val rl = ResourceLocation.tryParse(soundId) ?: return ActionOutcome.Failure("Invalid sound id '$soundId'")
        val sound = BuiltInRegistries.SOUND_EVENT.getOptional(rl).orElse(null)
            ?: return ActionOutcome.Failure("Sound '$soundId' not registered")
        server.execute {
            player.serverLevel().playSound(null, player.x, player.y, player.z, sound, SoundSource.AMBIENT, volume, pitch)
        }
        return ActionOutcome.Success("Played $soundId")
    }

    override fun spawnParticle(
        playerUuid: UUID,
        particleId: String,
        x: Double, y: Double, z: Double,
        count: Int,
        deltaX: Double, deltaY: Double, deltaZ: Double,
        speed: Double,
    ): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val rl = ResourceLocation.tryParse(particleId) ?: return ActionOutcome.Failure("Invalid particle id")
        val type = BuiltInRegistries.PARTICLE_TYPE.getOptional(rl).orElse(null)
            ?: return ActionOutcome.Failure("Particle '$particleId' not registered")
        val opts: ParticleOptions = (type as? SimpleParticleType)
            ?: return ActionOutcome.Failure("Particle '$particleId' requires custom args (not supported)")
        server.execute {
            player.serverLevel().sendParticles(
                opts, x, y, z, count.coerceIn(1, 200),
                deltaX, deltaY, deltaZ, speed,
            )
        }
        return ActionOutcome.Success("Spawned $count $particleId")
    }

    override fun strikeLightning(
        dimensionId: String,
        x: Double, y: Double, z: Double,
        cosmeticOnly: Boolean,
    ): ActionOutcome {
        val level = levelOf(dimensionId) ?: return ActionOutcome.Failure("Dimension '$dimensionId' not loaded")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        server.execute {
            val bolt = LightningBolt(EntityType.LIGHTNING_BOLT, level)
            bolt.moveTo(x, y, z)
            bolt.setVisualOnly(cosmeticOnly)
            level.addFreshEntity(bolt)
        }
        return ActionOutcome.Success("Lightning at $x,$y,$z (cosmetic=$cosmeticOnly)")
    }

    override fun createBossBar(
        playerUuid: UUID,
        name: String,
        color: BossBarColor,
        overlay: BossBarOverlay,
        durationSeconds: Int,
    ): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val seconds = durationSeconds.coerceIn(5, 600)
        val bbColor = when (color) {
            BossBarColor.PINK -> BossEvent.BossBarColor.PINK
            BossBarColor.BLUE -> BossEvent.BossBarColor.BLUE
            BossBarColor.RED -> BossEvent.BossBarColor.RED
            BossBarColor.GREEN -> BossEvent.BossBarColor.GREEN
            BossBarColor.YELLOW -> BossEvent.BossBarColor.YELLOW
            BossBarColor.PURPLE -> BossEvent.BossBarColor.PURPLE
            BossBarColor.WHITE -> BossEvent.BossBarColor.WHITE
        }
        val bbOverlay = when (overlay) {
            BossBarOverlay.PROGRESS -> BossEvent.BossBarOverlay.PROGRESS
            BossBarOverlay.NOTCHED_6 -> BossEvent.BossBarOverlay.NOTCHED_6
            BossBarOverlay.NOTCHED_10 -> BossEvent.BossBarOverlay.NOTCHED_10
            BossBarOverlay.NOTCHED_12 -> BossEvent.BossBarOverlay.NOTCHED_12
            BossBarOverlay.NOTCHED_20 -> BossEvent.BossBarOverlay.NOTCHED_20
        }
        val id = UUID.randomUUID()
        val event = ServerBossEvent(Component.literal(name), bbColor, bbOverlay).apply {
            progress = 1.0f
        }
        bossBars[id] = event
        server.execute {
            event.addPlayer(player)
        }
        // Animate progress from 1.0 → 0.0 over the duration, then remove.
        scope.launch {
            val step = 200L
            val steps = (seconds * 1000L / step).toInt().coerceAtLeast(1)
            var remaining = steps
            while (remaining > 0) {
                delay(step)
                remaining--
                val p = remaining.toFloat() / steps
                server.execute { event.progress = p }
            }
            server.execute {
                event.removeAllPlayers()
                bossBars.remove(id)
            }
        }
        return ActionOutcome.Success("Boss bar '$name' for ${seconds}s")
    }

    // ---- Player gifts / effects ----------------------------------------

    override fun giveItem(playerUuid: UUID, itemId: String, count: Int, customName: String?): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val rl = ResourceLocation.tryParse(itemId) ?: return ActionOutcome.Failure("Invalid item id")
        val item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null)
            ?: return ActionOutcome.Failure("Item '$itemId' not registered")
        server.execute {
            val stack = ItemStack(item, count.coerceIn(1, 64))
            if (!customName.isNullOrBlank()) {
                stack.set(
                    net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal(customName),
                )
            }
            if (!player.inventory.add(stack)) player.drop(stack, false)
        }
        return ActionOutcome.Success("Gave $count x $itemId")
    }

    override fun giveBook(
        playerUuid: UUID,
        title: String,
        author: String,
        pages: List<String>,
    ): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        if (pages.isEmpty()) return ActionOutcome.Failure("Book must have at least one page")
        val safeTitle = title.take(32).ifBlank { "Untitled" }
        val safeAuthor = author.take(32).ifBlank { "Unknown" }
        val filteredPages = pages.take(50).map { page ->
            val pageComponent: net.minecraft.network.chat.Component = Component.literal(page.take(1024))
            Filterable.passThrough(pageComponent)
        }
        server.execute {
            val book = ItemStack(Items.WRITTEN_BOOK)
            book.set(
                net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT,
                WrittenBookContent(
                    Filterable.passThrough(safeTitle),
                    safeAuthor,
                    0,
                    filteredPages,
                    true,
                ),
            )
            if (!player.inventory.add(book)) player.drop(book, false)
        }
        return ActionOutcome.Success("Gave book '$safeTitle' (${pages.size} pages)")
    }

    override fun applyEffect(
        playerUuid: UUID,
        effectId: String,
        durationTicks: Int,
        amplifier: Int,
        showParticles: Boolean,
    ): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val rl = ResourceLocation.tryParse(effectId) ?: return ActionOutcome.Failure("Invalid effect id")
        // getOptional + wrapAsHolder is stable across 1.21.x; getHolder(ResourceLocation)
        // was removed after 1.21.1.
        val effectValue = BuiltInRegistries.MOB_EFFECT.getOptional(rl).orElse(null)
            ?: return ActionOutcome.Failure("Effect '$effectId' not registered")
        val effect = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effectValue)
        server.execute {
            player.addEffect(
                MobEffectInstance(
                    effect,
                    durationTicks.coerceIn(20, 2_400),
                    amplifier.coerceIn(0, 2),
                    /* ambient = */ false,
                    /* visible = */ showParticles,
                    /* showIcon = */ true,
                ),
            )
        }
        return ActionOutcome.Success("Applied $effectId")
    }

    override fun grantAdvancement(
        playerUuid: UUID,
        holderId: String,
        title: String,
        description: String,
        iconItem: String,
        frame: AdvancementFrame,
        announceToChat: Boolean,
    ): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val holderRl = ResourceLocation.tryParse(holderId) ?: return ActionOutcome.Failure("Invalid holder id")
        val iconRl = ResourceLocation.tryParse(iconItem) ?: return ActionOutcome.Failure("Invalid icon item id")
        val item = BuiltInRegistries.ITEM.getOptional(iconRl).orElse(Items.PAPER)
        val type = when (frame) {
            AdvancementFrame.TASK -> AdvancementType.TASK
            AdvancementFrame.GOAL -> AdvancementType.GOAL
            AdvancementFrame.CHALLENGE -> AdvancementType.CHALLENGE
        }
        val criteriaName = "ai_director_granted"
        val criterion: Criterion<*> = Criterion(CriteriaTriggers.IMPOSSIBLE, ImpossibleTrigger.TriggerInstance())
        val criteria: Map<String, Criterion<*>> = mapOf(criteriaName to criterion)
        val requirements = AdvancementRequirements(listOf(listOf(criteriaName)))
        val display = DisplayInfo(
            ItemStack(item),
            Component.literal(title),
            Component.literal(description),
            Optional.empty(), // background
            type,
            true,           // showToast
            announceToChat,
            false,          // hidden
        )
        val advancement = Advancement(
            /* parent = */ Optional.empty(),
            /* display = */ Optional.of(display),
            AdvancementRewards.EMPTY,
            criteria,
            requirements,
            false,          // sendsTelemetry
        )
        val holder = AdvancementHolder(holderRl, advancement)
        server.execute {
            // Inject and immediately award.
            player.connection.send(
                ClientboundUpdateAdvancementsPacket(
                    false,
                    listOf(holder),
                    emptySet(),
                    emptyMap(),
                ),
            )
            player.advancements.award(holder, criteriaName)
        }
        return ActionOutcome.Success("Granted advancement '$title'")
    }

    // ---- World ----------------------------------------------------------

    override fun modifyWeather(playerUuid: UUID, weather: WeatherKind, durationTicks: Int): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val level = player.serverLevel()
        server.execute {
            val ticks = durationTicks.coerceIn(1_200, 72_000)
            when (weather) {
                WeatherKind.CLEAR -> level.setWeatherParameters(ticks, 0, false, false)
                WeatherKind.RAIN -> level.setWeatherParameters(0, ticks, true, false)
                WeatherKind.THUNDER -> level.setWeatherParameters(0, ticks, true, true)
            }
        }
        return ActionOutcome.Success("Weather → ${weather.name.lowercase()} for ${durationTicks / 20}s")
    }

    override fun setTime(dimensionId: String, label: TimeLabel): ActionOutcome {
        val level = levelOf(dimensionId) ?: return ActionOutcome.Failure("Dimension '$dimensionId' not loaded")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val target = when (label) {
            TimeLabel.DAWN -> 23_000L
            TimeLabel.DAY -> 1_000L
            TimeLabel.NOON -> 6_000L
            TimeLabel.DUSK -> 12_000L
            TimeLabel.NIGHT -> 13_000L
            TimeLabel.MIDNIGHT -> 18_000L
        }
        server.execute {
            // Use setDayTime to skip directly to the chosen time of day.
            val current = level.dayTime
            val dayBase = current - (current % 24_000)
            level.setDayTime(dayBase + target)
            // doDaylightCycle keeps running afterward — we don't disable it.
        }
        return ActionOutcome.Success("Time → ${label.name.lowercase()}")
    }

    override fun placeBlock(dimensionId: String, x: Int, y: Int, z: Int, blockId: String): ActionOutcome {
        val level = levelOf(dimensionId) ?: return ActionOutcome.Failure("Dimension '$dimensionId' not loaded")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val rl = ResourceLocation.tryParse(blockId) ?: return ActionOutcome.Failure("Invalid block id")
        if (blockId in DESTRUCTIVE_BLOCK_DENYLIST) {
            return ActionOutcome.Failure("Block '$blockId' is denylisted from director use")
        }
        val block = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null)
            ?: return ActionOutcome.Failure("Block '$blockId' not registered")
        val pos = BlockPos(x, y, z)
        server.execute {
            level.setBlock(pos, block.defaultBlockState(), 3)
        }
        return ActionOutcome.Success("Placed $blockId at $x,$y,$z")
    }

    override fun placeSign(dimensionId: String, x: Int, y: Int, z: Int, lines: List<String>): ActionOutcome {
        val level = levelOf(dimensionId) ?: return ActionOutcome.Failure("Dimension '$dimensionId' not loaded")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        if (lines.isEmpty()) return ActionOutcome.Failure("Sign needs at least one line")
        val pos = BlockPos(x, y, z)
        server.execute {
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState(), 3)
            val be = level.getBlockEntity(pos)
            if (be is net.minecraft.world.level.block.entity.SignBlockEntity) {
                var text = net.minecraft.world.level.block.entity.SignText()
                lines.take(4).forEachIndexed { i, line ->
                    text = text.setMessage(i, Component.literal(line.take(90)))
                }
                be.setText(text, true)
                be.setText(text, false)
                be.setChanged()
                level.sendBlockUpdated(pos, be.blockState, be.blockState, 3)
            }
        }
        return ActionOutcome.Success("Placed sign at $x,$y,$z (${lines.size} lines)")
    }

    override fun buildStructure(dimensionId: String, blocks: List<dev.aidirector.actions.PlacedBlock>): ActionOutcome {
        val level = levelOf(dimensionId) ?: return ActionOutcome.Failure("Dimension '$dimensionId' not loaded")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        if (blocks.isEmpty()) return ActionOutcome.Failure("No blocks to place")
        var placed = 0
        server.execute {
            for (pb in blocks) {
                if (pb.blockId in DESTRUCTIVE_BLOCK_DENYLIST) continue
                val rl = ResourceLocation.tryParse(pb.blockId) ?: continue
                val block = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null) ?: continue
                level.setBlock(BlockPos(pb.x, pb.y, pb.z), block.defaultBlockState(), 3)
                placed++
            }
        }
        return ActionOutcome.Success("Built structure: ${blocks.size} blocks placed")
    }

    override fun giveTreasureMap(playerUuid: UUID, targetX: Int, targetZ: Int, label: String): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        server.execute {
            val level = player.serverLevel()
            // scale 1 (byte) covers a decent area; trackingPosition=true so a marker
            // for the player appears, unlimitedTracking=true so it fills as explored.
            val stack = net.minecraft.world.item.MapItem.create(
                level, targetX, targetZ, /* scale = */ 1.toByte(),
                /* trackingPosition = */ true, /* unlimitedTracking = */ true,
            )
            net.minecraft.world.item.MapItem.renderBiomePreviewMap(level, stack)
            stack.set(
                net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(label.take(48)),
            )
            if (!player.inventory.add(stack)) player.drop(stack, false)
        }
        return ActionOutcome.Success("Gave map '$label' centred on $targetX,$targetZ")
    }

    override fun teleportPlayer(
        playerUuid: UUID,
        x: Double, y: Double, z: Double,
        dimensionId: String?,
    ): ActionOutcome {
        val player = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val target = dimensionId?.let { levelOf(it) } ?: player.serverLevel()
        server.execute {
            teleportAcrossDimensions(player, target, x, y, z)
        }
        return ActionOutcome.Success("Teleported ${player.gameProfile.name} → $x,$y,$z")
    }

    // ---- Entities -------------------------------------------------------

    override fun spawnNpc(
        ownerPlayer: UUID?,
        dimensionId: String,
        entityType: String,
        customName: String,
        x: Double, y: Double, z: Double,
        invulnerable: Boolean,
        ambientMovement: Boolean,
    ): ActionOutcome {
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val level = levelOf(dimensionId) ?: return ActionOutcome.Failure("Dimension '$dimensionId' not loaded")
        if (entityType !in NPC_ALLOWED_TYPES) {
            return ActionOutcome.Failure("Entity type '$entityType' not allowed for NPC role")
        }
        val rl = ResourceLocation.tryParse(entityType) ?: return ActionOutcome.Failure("Invalid entity id")
        val type = BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null)
            ?: return ActionOutcome.Failure("Entity '$entityType' not registered")
        val uuid = onServer(server) {
            val entity = createEntity(type, level) ?: return@onServer null
            entity.moveTo(x, y, z, 0f, 0f)
            entity.customName = Component.literal(customName)
            entity.isCustomNameVisible = true
            entity.isInvulnerable = invulnerable
            if (entity is Mob) {
                entity.setPersistenceRequired()
                if (!ambientMovement) {
                    entity.setNoAi(true)
                }
                entity.addTag(NPC_TAG)
            }
            level.addFreshEntity(entity)
            entity.uuid
        } ?: return ActionOutcome.Failure("Failed to spawn $entityType")
        return ActionOutcome.Success("Spawned NPC '$customName' ($entityType)", entityUuid = uuid)
    }

    override fun spawnMob(
        dimensionId: String,
        entityType: String,
        x: Double, y: Double, z: Double,
        count: Int,
        customName: String?,
        persistent: Boolean,
        hostileToPlayer: UUID?,
    ): ActionOutcome {
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val level = levelOf(dimensionId) ?: return ActionOutcome.Failure("Dimension '$dimensionId' not loaded")
        val rl = ResourceLocation.tryParse(entityType) ?: return ActionOutcome.Failure("Invalid entity id")
        val type = BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null)
            ?: return ActionOutcome.Failure("Entity '$entityType' not registered")
        if (entityType in MOB_DENYLIST) {
            return ActionOutcome.Failure("Entity '$entityType' is denylisted (boss/dangerous)")
        }
        val n = count.coerceIn(1, 5)
        val uuids = onServer(server) {
            val out = ArrayList<UUID>(n)
            repeat(n) {
                val entity = createEntity(type, level) ?: return@repeat
                entity.moveTo(x, y, z, 0f, 0f)
                if (!customName.isNullOrBlank()) {
                    entity.customName = Component.literal(customName)
                    entity.isCustomNameVisible = true
                }
                if (entity is Mob) {
                    if (persistent) entity.setPersistenceRequired()
                    entity.addTag(DIRECTOR_TAG)
                    if (hostileToPlayer != null) {
                        val target = server.playerList.getPlayer(hostileToPlayer)
                        if (target != null) entity.setTarget(target)
                    }
                }
                level.addFreshEntity(entity)
                out += entity.uuid
            }
            out
        } ?: return ActionOutcome.Failure("Failed to spawn $entityType")
        return ActionOutcome.Success(
            "Spawned ${uuids.size} x $entityType",
            entityUuid = uuids.firstOrNull(),
            entityUuids = uuids,
        )
    }

    override fun setMobEquipment(entityUuid: UUID, slot: EquipmentSlotName, itemId: String): ActionOutcome {
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val entity = entityByUuid(entityUuid) ?: return ActionOutcome.Failure("Entity not found")
        if (entity !is LivingEntity) return ActionOutcome.Failure("Entity is not a LivingEntity")
        val rl = ResourceLocation.tryParse(itemId) ?: return ActionOutcome.Failure("Invalid item id")
        val item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null)
            ?: return ActionOutcome.Failure("Item '$itemId' not registered")
        val mcSlot = when (slot) {
            EquipmentSlotName.MAINHAND -> EquipmentSlot.MAINHAND
            EquipmentSlotName.OFFHAND -> EquipmentSlot.OFFHAND
            EquipmentSlotName.HEAD -> EquipmentSlot.HEAD
            EquipmentSlotName.CHEST -> EquipmentSlot.CHEST
            EquipmentSlotName.LEGS -> EquipmentSlot.LEGS
            EquipmentSlotName.FEET -> EquipmentSlot.FEET
        }
        server.execute {
            entity.setItemSlot(mcSlot, ItemStack(item))
        }
        return ActionOutcome.Success("Equipped $itemId on ${slot.name.lowercase()}")
    }

    override fun setMobTarget(entityUuid: UUID, targetPlayer: UUID): ActionOutcome {
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val entity = entityByUuid(entityUuid) ?: return ActionOutcome.Failure("Entity not found")
        if (entity !is Mob) return ActionOutcome.Failure("Entity is not a Mob")
        val target = server.playerList.getPlayer(targetPlayer) ?: return ActionOutcome.Failure("Target offline")
        server.execute { entity.target = target }
        return ActionOutcome.Success("Set target to ${target.gameProfile.name}")
    }

    override fun killEntity(entityUuid: UUID): ActionOutcome {
        val server = server() ?: return ActionOutcome.Failure("Server unavailable")
        val entity = entityByUuid(entityUuid) ?: return ActionOutcome.Failure("Entity not found")
        server.execute {
            entity.discard()
        }
        return ActionOutcome.Success("Removed entity $entityUuid")
    }

    // ---- Registry checks -----------------------------------------------

    override fun isItemRegistered(itemId: String): Boolean =
        ResourceLocation.tryParse(itemId)?.let { BuiltInRegistries.ITEM.containsKey(it) } ?: false

    override fun isSoundRegistered(soundId: String): Boolean =
        ResourceLocation.tryParse(soundId)?.let { BuiltInRegistries.SOUND_EVENT.containsKey(it) } ?: false

    override fun isEffectRegistered(effectId: String): Boolean =
        ResourceLocation.tryParse(effectId)?.let { BuiltInRegistries.MOB_EFFECT.containsKey(it) } ?: false

    override fun isEntityTypeRegistered(entityTypeId: String): Boolean =
        ResourceLocation.tryParse(entityTypeId)?.let { BuiltInRegistries.ENTITY_TYPE.containsKey(it) } ?: false

    override fun isBlockRegistered(blockId: String): Boolean =
        ResourceLocation.tryParse(blockId)?.let { BuiltInRegistries.BLOCK.containsKey(it) } ?: false

    override fun isParticleRegistered(particleId: String): Boolean =
        ResourceLocation.tryParse(particleId)?.let { BuiltInRegistries.PARTICLE_TYPE.containsKey(it) } ?: false

    // ---- Helpers --------------------------------------------------------

    /**
     * Runs [block] on the server thread and blocks the caller for up to 2s
     * for the result. Caller must NOT be on the server thread.
     */
    private fun <T> onServer(server: MinecraftServer, block: () -> T?): T? {
        val future = CompletableFuture<T?>()
        server.execute {
            try {
                future.complete(block())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return try {
            future.get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            AIDirector.log.warn("onServer call failed: ${e.message}")
            null
        }
    }

    companion object {
        internal const val NPC_TAG = "aidirector_npc"
        internal const val DIRECTOR_TAG = "aidirector_managed"

        /** Mobs the director can never spawn — bosses, super-dangerous, or trolling-prone. */
        private val MOB_DENYLIST = setOf(
            "minecraft:wither", "minecraft:ender_dragon", "minecraft:warden",
            "minecraft:elder_guardian", "minecraft:giant",
        )

        /** Entity types acceptable for NPC role — peaceful, named-friendly. */
        private val NPC_ALLOWED_TYPES = setOf(
            "minecraft:villager",
            "minecraft:wandering_trader",
            "minecraft:cleric", // not a real entity, but accepted via villager+profession later
            "minecraft:librarian",
        ).intersect(
            // Filter to actually registered ones at runtime — done lazily where used.
            setOf("minecraft:villager", "minecraft:wandering_trader"),
        )

        private val DESTRUCTIVE_BLOCK_DENYLIST = setOf(
            "minecraft:tnt", "minecraft:bedrock", "minecraft:command_block",
            "minecraft:repeating_command_block", "minecraft:chain_command_block",
            "minecraft:structure_block", "minecraft:end_portal_frame",
            "minecraft:lava", "minecraft:flowing_lava",
        )
    }
}
