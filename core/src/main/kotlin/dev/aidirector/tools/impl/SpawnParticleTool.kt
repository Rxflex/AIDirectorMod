package dev.aidirector.tools.impl

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class SpawnParticleTool : Tool<SpawnParticleTool.Args>() {

    override val name: String = "spawn_particle"

    override val description: String = """
        Spawn a burst of a vanilla particle at coordinates. Use for visual
        cues — soul flames before a haunting, smoke at a destroyed campfire,
        portal sparks to hint at a path. Only simple particles supported
        (e.g. 'minecraft:soul_fire_flame', 'minecraft:smoke',
        'minecraft:end_rod'). Particles requiring custom args (dust, block)
        will be refused.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("particle_id", "x", "y", "z"),
    ) {
        string("particle_id", "Namespaced particle id.", maxLength = 96)
        number("x", "X")
        number("y", "Y")
        number("z", "Z")
        integer("count", "Number of particles (1..200).", min = 1, max = 200)
        number("spread", "Spread radius (0.0..3.0).", min = 0.0, max = 3.0)
        number("speed", "Particle speed (0.0..1.0).", min = 0.0, max = 1.0)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val actions = ServerActionsHolder.require()
        if (!actions.isParticleRegistered(args.particleId)) {
            return ToolResult.Refused("Particle '${args.particleId}' not registered")
        }
        val spread = args.spread ?: 0.5
        return actions.spawnParticle(
            playerUuid = ctx.playerUuid,
            particleId = args.particleId,
            x = args.x, y = args.y, z = args.z,
            count = args.count ?: 20,
            deltaX = spread, deltaY = spread, deltaZ = spread,
            speed = args.speed ?: 0.05,
        ).toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("particle_id") val particleId: String,
        @SerialName("x") val x: Double,
        @SerialName("y") val y: Double,
        @SerialName("z") val z: Double,
        @SerialName("count") val count: Int? = null,
        @SerialName("spread") val spread: Double? = null,
        @SerialName("speed") val speed: Double? = null,
    )
}
