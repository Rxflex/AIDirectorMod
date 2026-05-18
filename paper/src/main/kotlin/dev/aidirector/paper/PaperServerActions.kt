package dev.aidirector.paper

import dev.aidirector.actions.ActionOutcome
import dev.aidirector.actions.AdvancementFrame
import dev.aidirector.actions.BossBarColor
import dev.aidirector.actions.BossBarOverlay
import dev.aidirector.actions.EquipmentSlotName
import dev.aidirector.actions.NarrationStyle
import dev.aidirector.actions.ServerActions
import dev.aidirector.actions.TimeLabel
import dev.aidirector.actions.WeatherKind
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bukkit/Paper implementation of [ServerActions]. Every method is safe to call
 * from any thread — async actions are scheduled back onto the main thread via
 * [Bukkit.getScheduler]. Identifiers stay in the `minecraft:namespace:id`
 * convention everywhere else in the system; here we translate them to
 * Bukkit's enums (Material, EntityType, Sound, etc.) by `NamespacedKey`.
 */
class PaperServerActions(private val plugin: JavaPlugin) : ServerActions {

    private val activeBossBars = ConcurrentHashMap<UUID, MutableList<BossBar>>()

    private val server get() = plugin.server

    private fun player(uuid: UUID): Player? = server.getPlayer(uuid)

    private fun runSync(block: () -> Unit) {
        if (server.isPrimaryThread) block()
        else server.scheduler.runTask(plugin, Runnable { block() })
    }

    override fun isPlayerOnline(playerUuid: UUID): Boolean = player(playerUuid) != null

    override fun getPlayerDimensionId(playerUuid: UUID): String? =
        player(playerUuid)?.world?.key?.toString()

    override fun getPlayerPosition(playerUuid: UUID): IntArray? {
        val p = player(playerUuid) ?: return null
        return intArrayOf(p.location.blockX, p.location.blockY, p.location.blockZ)
    }

    override fun sendNarration(playerUuid: UUID, message: String, style: NarrationStyle): ActionOutcome {
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        runSync {
            when (style) {
                NarrationStyle.NARRATOR -> p.sendMessage(Component.text(message, NamedTextColor.GOLD))
                NarrationStyle.WHISPER -> p.sendMessage(
                    Component.text(message)
                        .color(NamedTextColor.GRAY)
                        .decorate(TextDecoration.ITALIC),
                )
                NarrationStyle.ANNOUNCEMENT -> p.showTitle(
                    Title.title(
                        Component.text(message, NamedTextColor.AQUA),
                        Component.empty(),
                        Title.Times.times(
                            Duration.ofMillis(500),
                            Duration.ofSeconds(3),
                            Duration.ofSeconds(1),
                        ),
                    ),
                )
                NarrationStyle.SUBTITLE -> p.showTitle(
                    Title.title(
                        Component.empty(),
                        Component.text(message),
                        Title.Times.times(
                            Duration.ofMillis(500),
                            Duration.ofSeconds(3),
                            Duration.ofSeconds(1),
                        ),
                    ),
                )
            }
        }
        return ActionOutcome.Success("Narrated to ${p.name}")
    }

    override fun playSound(playerUuid: UUID, soundId: String, volume: Float, pitch: Float): ActionOutcome {
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val key = NamespacedKey.fromString(soundId) ?: return ActionOutcome.Failure("Invalid sound id")
        runSync {
            p.playSound(p.location, key.toString(), SoundCategory.AMBIENT, volume, pitch)
        }
        return ActionOutcome.Success("Played $soundId")
    }

    override fun spawnParticle(
        playerUuid: UUID, particleId: String,
        x: Double, y: Double, z: Double, count: Int,
        deltaX: Double, deltaY: Double, deltaZ: Double, speed: Double,
    ): ActionOutcome {
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val particle = particleFromId(particleId)
            ?: return ActionOutcome.Failure("Particle '$particleId' not registered")
        runSync {
            p.world.spawnParticle(particle, x, y, z, count.coerceIn(1, 200), deltaX, deltaY, deltaZ, speed)
        }
        return ActionOutcome.Success("Spawned $count $particleId")
    }

    override fun strikeLightning(dimensionId: String, x: Double, y: Double, z: Double, cosmeticOnly: Boolean): ActionOutcome {
        val world = world(dimensionId) ?: return ActionOutcome.Failure("Dimension not loaded")
        runSync {
            val loc = org.bukkit.Location(world, x, y, z)
            if (cosmeticOnly) world.strikeLightningEffect(loc) else world.strikeLightning(loc)
        }
        return ActionOutcome.Success("Lightning at $x,$y,$z (cosmetic=$cosmeticOnly)")
    }

    override fun createBossBar(
        playerUuid: UUID, name: String,
        color: BossBarColor, overlay: BossBarOverlay, durationSeconds: Int,
    ): ActionOutcome {
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val seconds = durationSeconds.coerceIn(5, 600)
        val bar = BossBar.bossBar(
            Component.text(name),
            1.0f,
            adventureColor(color),
            adventureOverlay(overlay),
        )
        activeBossBars.computeIfAbsent(playerUuid) { mutableListOf() } += bar
        runSync { p.showBossBar(bar) }

        val totalTicks = (seconds * 20L)
        val steps = 100
        val stepTicks = (totalTicks / steps).coerceAtLeast(1)
        // Animate progress down each step; remove on completion.
        for (i in 1..steps) {
            server.scheduler.runTaskLater(
                plugin,
                Runnable {
                    val progress = (1.0f - i.toFloat() / steps).coerceAtLeast(0.0f)
                    bar.progress(progress)
                },
                i * stepTicks,
            )
        }
        server.scheduler.runTaskLater(
            plugin,
            Runnable {
                player(playerUuid)?.hideBossBar(bar)
                activeBossBars[playerUuid]?.remove(bar)
            },
            totalTicks,
        )
        return ActionOutcome.Success("Boss bar '$name' for ${seconds}s")
    }

    override fun giveItem(playerUuid: UUID, itemId: String, count: Int, customName: String?): ActionOutcome {
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val material = materialFromId(itemId)
            ?: return ActionOutcome.Failure("Item '$itemId' not a valid material")
        runSync {
            val stack = ItemStack(material, count.coerceIn(1, 64))
            if (!customName.isNullOrBlank()) {
                stack.editMeta { meta ->
                    meta.displayName(Component.text(customName))
                }
            }
            val leftover = p.inventory.addItem(stack)
            for (overflow in leftover.values) {
                p.world.dropItemNaturally(p.location, overflow)
            }
        }
        return ActionOutcome.Success("Gave $count x $itemId to ${p.name}")
    }

    override fun giveBook(playerUuid: UUID, title: String, author: String, pages: List<String>): ActionOutcome {
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        if (pages.isEmpty()) return ActionOutcome.Failure("Book must have at least one page")
        runSync {
            val book = ItemStack(Material.WRITTEN_BOOK)
            book.editMeta(org.bukkit.inventory.meta.BookMeta::class.java) { meta ->
                meta.title(Component.text(title.take(32).ifBlank { "Untitled" }))
                meta.author(Component.text(author.take(32).ifBlank { "Unknown" }))
                pages.take(50).forEach { meta.addPages(Component.text(it.take(1024))) }
            }
            val leftover = p.inventory.addItem(book)
            for (overflow in leftover.values) p.world.dropItemNaturally(p.location, overflow)
        }
        return ActionOutcome.Success("Gave book '$title' (${pages.size} pages)")
    }

    override fun applyEffect(
        playerUuid: UUID, effectId: String,
        durationTicks: Int, amplifier: Int, showParticles: Boolean,
    ): ActionOutcome {
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val type = potionEffectFromId(effectId)
            ?: return ActionOutcome.Failure("Effect '$effectId' not registered")
        runSync {
            p.addPotionEffect(
                PotionEffect(
                    type,
                    durationTicks.coerceIn(20, 2_400),
                    amplifier.coerceIn(0, 2),
                    false,
                    showParticles,
                    true,
                ),
            )
        }
        return ActionOutcome.Success("Applied $effectId tier=$amplifier")
    }

    override fun grantAdvancement(
        playerUuid: UUID, holderId: String,
        title: String, description: String, iconItem: String,
        frame: AdvancementFrame, announceToChat: Boolean,
    ): ActionOutcome {
        // Paper's Bukkit API does not expose dynamic Advancement creation without
        // an NMS dependency. We approximate with a title overlay + chat message so
        // the player still gets a clear "achievement-like" notification.
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        runSync {
            p.showTitle(
                Title.title(
                    Component.text("🏆 $title", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text(description, NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1)),
                ),
            )
            if (announceToChat) {
                server.broadcast(
                    Component.text("${p.name} earned ", NamedTextColor.GRAY)
                        .append(Component.text("[$title]", NamedTextColor.GOLD)),
                )
            }
        }
        return ActionOutcome.Success("Faux-advancement '$title' shown")
    }

    override fun modifyWeather(playerUuid: UUID, weather: WeatherKind, durationTicks: Int): ActionOutcome {
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val world = p.world
        runSync {
            val ticks = durationTicks.coerceIn(1_200, 72_000)
            when (weather) {
                WeatherKind.CLEAR -> { world.setStorm(false); world.isThundering = false; world.weatherDuration = ticks }
                WeatherKind.RAIN -> { world.setStorm(true); world.isThundering = false; world.weatherDuration = ticks }
                WeatherKind.THUNDER -> { world.setStorm(true); world.isThundering = true; world.thunderDuration = ticks }
            }
        }
        return ActionOutcome.Success("Weather → ${weather.name.lowercase()} for ${durationTicks / 20}s")
    }

    override fun setTime(dimensionId: String, label: TimeLabel): ActionOutcome {
        val world = world(dimensionId) ?: return ActionOutcome.Failure("Dimension not loaded")
        val target = when (label) {
            TimeLabel.DAWN -> 23_000L
            TimeLabel.DAY -> 1_000L
            TimeLabel.NOON -> 6_000L
            TimeLabel.DUSK -> 12_000L
            TimeLabel.NIGHT -> 13_000L
            TimeLabel.MIDNIGHT -> 18_000L
        }
        runSync { world.time = target }
        return ActionOutcome.Success("Time → ${label.name.lowercase()}")
    }

    override fun placeBlock(dimensionId: String, x: Int, y: Int, z: Int, blockId: String): ActionOutcome {
        val world = world(dimensionId) ?: return ActionOutcome.Failure("Dimension not loaded")
        val material = materialFromId(blockId)
            ?: return ActionOutcome.Failure("Block '$blockId' invalid")
        if (!material.isBlock) return ActionOutcome.Failure("'$blockId' is not a block")
        runSync { world.getBlockAt(x, y, z).type = material }
        return ActionOutcome.Success("Placed $blockId at $x,$y,$z")
    }

    override fun placeSign(dimensionId: String, x: Int, y: Int, z: Int, lines: List<String>): ActionOutcome {
        val world = world(dimensionId) ?: return ActionOutcome.Failure("Dimension not loaded")
        if (lines.isEmpty()) return ActionOutcome.Failure("Sign needs at least one line")
        runSync {
            val block = world.getBlockAt(x, y, z)
            block.type = Material.OAK_SIGN
            val state = block.state
            if (state is org.bukkit.block.Sign) {
                val side = state.getSide(org.bukkit.block.sign.Side.FRONT)
                lines.take(4).forEachIndexed { i, line ->
                    side.line(i, Component.text(line.take(90)))
                }
                state.update(true, false)
            }
        }
        return ActionOutcome.Success("Placed sign at $x,$y,$z (${lines.size} lines)")
    }

    override fun buildStructure(dimensionId: String, blocks: List<dev.aidirector.actions.PlacedBlock>): ActionOutcome {
        val world = world(dimensionId) ?: return ActionOutcome.Failure("Dimension not loaded")
        if (blocks.isEmpty()) return ActionOutcome.Failure("No blocks to place")
        runSync {
            for (pb in blocks) {
                val mat = materialFromId(pb.blockId) ?: continue
                if (!mat.isBlock) continue
                world.getBlockAt(pb.x, pb.y, pb.z).type = mat
            }
        }
        return ActionOutcome.Success("Built structure: ${blocks.size} blocks placed")
    }

    override fun giveTreasureMap(playerUuid: UUID, targetX: Int, targetZ: Int, label: String): ActionOutcome {
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        runSync {
            val view = p.server.createMap(p.world)
            view.centerX = targetX
            view.centerZ = targetZ
            view.scale = org.bukkit.map.MapView.Scale.NORMAL
            view.isTrackingPosition = true
            view.isUnlimitedTracking = true
            val stack = ItemStack(Material.FILLED_MAP)
            stack.editMeta(org.bukkit.inventory.meta.MapMeta::class.java) { meta ->
                meta.mapView = view
                meta.displayName(Component.text(label.take(48)))
            }
            val leftover = p.inventory.addItem(stack)
            for (overflow in leftover.values) p.world.dropItemNaturally(p.location, overflow)
        }
        return ActionOutcome.Success("Gave map '$label' centred on $targetX,$targetZ")
    }

    override fun teleportPlayer(playerUuid: UUID, x: Double, y: Double, z: Double, dimensionId: String?): ActionOutcome {
        val p = player(playerUuid) ?: return ActionOutcome.Failure("Player offline")
        val world = dimensionId?.let { world(it) } ?: p.world
        runSync { p.teleport(org.bukkit.Location(world, x, y, z, p.location.yaw, p.location.pitch)) }
        return ActionOutcome.Success("Teleported ${p.name} → $x,$y,$z")
    }

    override fun spawnNpc(
        ownerPlayer: UUID?, dimensionId: String, entityType: String,
        customName: String, x: Double, y: Double, z: Double,
        invulnerable: Boolean, ambientMovement: Boolean,
    ): ActionOutcome {
        val world = world(dimensionId) ?: return ActionOutcome.Failure("Dimension not loaded")
        val type = entityTypeFromId(entityType)
            ?: return ActionOutcome.Failure("Entity '$entityType' invalid")
        if (type != EntityType.VILLAGER && type != EntityType.WANDERING_TRADER) {
            return ActionOutcome.Failure("NPC must be villager or wandering_trader")
        }
        var entityUuid: UUID? = null
        runSyncBlocking {
            val entity = world.spawnEntity(org.bukkit.Location(world, x, y, z), type)
            entity.customName(Component.text(customName))
            entity.isCustomNameVisible = true
            entity.isInvulnerable = invulnerable
            if (entity is Villager) {
                entity.setRemoveWhenFarAway(false)
                entity.setAI(ambientMovement)
                entity.addScoreboardTag("aidirector_npc")
            }
            entityUuid = entity.uniqueId
        }
        return if (entityUuid != null) {
            ActionOutcome.Success("Spawned NPC '$customName' ($entityType)", entityUuid = entityUuid)
        } else {
            ActionOutcome.Failure("Failed to spawn NPC")
        }
    }

    override fun spawnMob(
        dimensionId: String, entityType: String,
        x: Double, y: Double, z: Double, count: Int,
        customName: String?, persistent: Boolean, hostileToPlayer: UUID?,
    ): ActionOutcome {
        val world = world(dimensionId) ?: return ActionOutcome.Failure("Dimension not loaded")
        val type = entityTypeFromId(entityType)
            ?: return ActionOutcome.Failure("Entity '$entityType' invalid")
        if (entityType in MOB_DENYLIST) return ActionOutcome.Failure("Entity '$entityType' denylisted")
        val uuids = mutableListOf<UUID>()
        runSyncBlocking {
            repeat(count.coerceIn(1, 5)) {
                val entity = world.spawnEntity(org.bukkit.Location(world, x, y, z), type)
                if (!customName.isNullOrBlank()) {
                    entity.customName(Component.text(customName))
                    entity.isCustomNameVisible = true
                }
                if (entity is Mob) {
                    if (persistent) entity.setRemoveWhenFarAway(false)
                    entity.addScoreboardTag("aidirector_managed")
                    if (hostileToPlayer != null) {
                        val target = player(hostileToPlayer)
                        if (target != null) entity.target = target
                    }
                }
                uuids += entity.uniqueId
            }
        }
        return ActionOutcome.Success(
            "Spawned ${uuids.size} x $entityType",
            entityUuid = uuids.firstOrNull(),
            entityUuids = uuids,
        )
    }

    override fun setMobEquipment(entityUuid: UUID, slot: EquipmentSlotName, itemId: String): ActionOutcome {
        val entity = server.getEntity(entityUuid)
            ?: return ActionOutcome.Failure("Entity not found")
        if (entity !is LivingEntity) return ActionOutcome.Failure("Entity is not LivingEntity")
        val mat = materialFromId(itemId) ?: return ActionOutcome.Failure("Item '$itemId' invalid")
        val mcSlot = when (slot) {
            EquipmentSlotName.MAINHAND -> EquipmentSlot.HAND
            EquipmentSlotName.OFFHAND -> EquipmentSlot.OFF_HAND
            EquipmentSlotName.HEAD -> EquipmentSlot.HEAD
            EquipmentSlotName.CHEST -> EquipmentSlot.CHEST
            EquipmentSlotName.LEGS -> EquipmentSlot.LEGS
            EquipmentSlotName.FEET -> EquipmentSlot.FEET
        }
        runSync { entity.equipment?.setItem(mcSlot, ItemStack(mat)) }
        return ActionOutcome.Success("Equipped $itemId on ${slot.name.lowercase()}")
    }

    override fun setMobTarget(entityUuid: UUID, targetPlayer: UUID): ActionOutcome {
        val entity = server.getEntity(entityUuid) ?: return ActionOutcome.Failure("Entity not found")
        if (entity !is Mob) return ActionOutcome.Failure("Entity not Mob")
        val target = player(targetPlayer) ?: return ActionOutcome.Failure("Target offline")
        runSync { entity.target = target }
        return ActionOutcome.Success("Set target to ${target.name}")
    }

    override fun killEntity(entityUuid: UUID): ActionOutcome {
        val entity = server.getEntity(entityUuid) ?: return ActionOutcome.Failure("Entity not found")
        runSync { entity.remove() }
        return ActionOutcome.Success("Removed entity")
    }

    // ----- Phantom player -----
    // The Bukkit API has no clean way to inject a fake tab-list entry without
    // NMS or a packet library, so the Paper phantom is a chat presence: a
    // believable join message, spoken lines, and a leave message.

    override fun phantomJoin(phantomUuid: UUID, name: String): ActionOutcome {
        runSync {
            server.broadcast(
                Component.text("$name joined the game", NamedTextColor.YELLOW),
            )
        }
        return ActionOutcome.Success("Phantom '$name' joined")
    }

    override fun phantomSay(name: String, message: String): ActionOutcome {
        runSync {
            server.broadcast(
                Component.text("<$name> ", NamedTextColor.WHITE)
                    .append(Component.text(message, NamedTextColor.WHITE)),
            )
        }
        return ActionOutcome.Success("Phantom '$name' spoke")
    }

    override fun phantomLeave(phantomUuid: UUID, name: String): ActionOutcome {
        runSync {
            server.broadcast(
                Component.text("$name left the game", NamedTextColor.YELLOW),
            )
        }
        return ActionOutcome.Success("Phantom '$name' left")
    }

    // ----- Mob modifiers -----

    override fun modifyMob(
        entityUuid: UUID,
        mods: dev.aidirector.actions.MobModifiers,
    ): ActionOutcome {
        val entity = server.getEntity(entityUuid) ?: return ActionOutcome.Failure("Entity not found")
        val effectType = mods.effectId?.let { potionEffectFromId(it) }
        if (mods.effectId != null && effectType == null) {
            return ActionOutcome.Failure("Effect '${mods.effectId}' not registered")
        }
        runSync {
            mods.glowing?.let { entity.isGlowing = it }
            mods.silent?.let { entity.isSilent = it }
            mods.noGravity?.let { entity.setGravity(!it) }
            mods.customName?.let {
                entity.customName(Component.text(it.take(48)))
                entity.isCustomNameVisible = true
            }
            if (entity is LivingEntity) {
                mods.scale?.let { s ->
                    entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_SCALE)
                        ?.baseValue = s.coerceIn(0.25, 4.0)
                }
                mods.speedMultiplier?.let { m ->
                    entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED)
                        ?.let { attr -> attr.baseValue = attr.baseValue * m.coerceIn(0.25, 4.0) }
                }
                if (effectType != null) {
                    entity.addPotionEffect(
                        PotionEffect(
                            effectType,
                            mods.effectDurationTicks.coerceIn(20, 12_000),
                            mods.effectAmplifier.coerceIn(0, 4),
                            false, true, true,
                        ),
                    )
                }
            }
        }
        return ActionOutcome.Success("Modified mob")
    }

    // ----- Scene dressing -----

    override fun placeDecoration(
        dimensionId: String,
        blocks: List<dev.aidirector.actions.PlacedBlock>,
    ): ActionOutcome {
        val world = world(dimensionId) ?: return ActionOutcome.Failure("Dimension not loaded")
        if (blocks.isEmpty()) return ActionOutcome.Failure("No blocks to place")
        runSync {
            for (pb in blocks) {
                val mat = materialFromId(pb.blockId) ?: continue
                if (!mat.isBlock) continue
                val block = world.getBlockAt(pb.x, pb.y, pb.z)
                // Non-destructive: only fill empty space.
                if (!block.isEmpty) continue
                block.type = mat
            }
        }
        return ActionOutcome.Success("Placed up to ${blocks.size} decoration(s) into empty space")
    }

    // ----- Registry checks via Bukkit/Material -----

    override fun isItemRegistered(itemId: String): Boolean = materialFromId(itemId) != null
    override fun isSoundRegistered(soundId: String): Boolean {
        val key = NamespacedKey.fromString(soundId) ?: return false
        return org.bukkit.Registry.SOUNDS.get(key) != null
    }
    override fun isEffectRegistered(effectId: String): Boolean = potionEffectFromId(effectId) != null
    override fun isEntityTypeRegistered(entityTypeId: String): Boolean = entityTypeFromId(entityTypeId) != null
    override fun isBlockRegistered(blockId: String): Boolean = materialFromId(blockId)?.isBlock == true
    override fun isParticleRegistered(particleId: String): Boolean = particleFromId(particleId) != null

    // ----- Helpers -----

    private fun world(dimensionId: String) =
        NamespacedKey.fromString(dimensionId)?.let { server.getWorld(it) }

    private fun materialFromId(id: String): Material? {
        val key = NamespacedKey.fromString(id) ?: return null
        return org.bukkit.Registry.MATERIAL.get(key)
    }

    private fun entityTypeFromId(id: String): EntityType? {
        val key = NamespacedKey.fromString(id) ?: return null
        return org.bukkit.Registry.ENTITY_TYPE.get(key)
    }

    private fun potionEffectFromId(id: String): PotionEffectType? {
        val key = NamespacedKey.fromString(id) ?: return null
        return org.bukkit.Registry.EFFECT.get(key)
    }

    private fun particleFromId(id: String): Particle? {
        val key = NamespacedKey.fromString(id) ?: return null
        return org.bukkit.Registry.PARTICLE_TYPE.get(key)
    }

    /** Blocking variant for spawn-then-return-uuid paths. Caller MUST be off the main thread. */
    private fun runSyncBlocking(block: () -> Unit) {
        if (server.isPrimaryThread) {
            block()
        } else {
            val future = java.util.concurrent.CompletableFuture<Unit>()
            server.scheduler.runTask(plugin, Runnable {
                try { block(); future.complete(Unit) } catch (e: Throwable) { future.completeExceptionally(e) }
            })
            future.get(2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun adventureColor(c: BossBarColor): BossBar.Color = when (c) {
        BossBarColor.PINK -> BossBar.Color.PINK
        BossBarColor.BLUE -> BossBar.Color.BLUE
        BossBarColor.RED -> BossBar.Color.RED
        BossBarColor.GREEN -> BossBar.Color.GREEN
        BossBarColor.YELLOW -> BossBar.Color.YELLOW
        BossBarColor.PURPLE -> BossBar.Color.PURPLE
        BossBarColor.WHITE -> BossBar.Color.WHITE
    }

    private fun adventureOverlay(o: BossBarOverlay): BossBar.Overlay = when (o) {
        BossBarOverlay.PROGRESS -> BossBar.Overlay.PROGRESS
        BossBarOverlay.NOTCHED_6 -> BossBar.Overlay.NOTCHED_6
        BossBarOverlay.NOTCHED_10 -> BossBar.Overlay.NOTCHED_10
        BossBarOverlay.NOTCHED_12 -> BossBar.Overlay.NOTCHED_12
        BossBarOverlay.NOTCHED_20 -> BossBar.Overlay.NOTCHED_20
    }

    companion object {
        private val MOB_DENYLIST = setOf(
            "minecraft:wither", "minecraft:ender_dragon", "minecraft:warden",
            "minecraft:elder_guardian", "minecraft:giant",
        )
    }
}
