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

/**
 * Applies a status effect to the player. Hostile effects (poison, wither,
 * blindness) are limited in amplifier and duration; the most disruptive ones
 * are outright banned to prevent griefing-by-LLM.
 */
class ApplyEffectTool : Tool<ApplyEffectTool.Args>() {

    override val name: String = "apply_effect"

    override val description: String = """
        Apply a temporary status effect to the player. Use to create atmosphere
        (brief blindness in a haunted area, slowness when wading through mud)
        or as a small blessing (resistance, regeneration). Do NOT use to harm
        the player gratuitously.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("effect_id", "duration_seconds"),
    ) {
        string(
            name = "effect_id",
            description = "Namespaced effect id, e.g. 'minecraft:regeneration', 'minecraft:slowness'.",
            maxLength = 128,
        )
        integer(name = "duration_seconds", description = "Duration in seconds, 1..120.", min = 1, max = 120)
        integer(name = "amplifier", description = "Effect amplifier, 0..2 (i.e. tier I..III).", min = 0, max = 2)
        boolean(name = "show_particles", description = "Whether the effect emits particles around the player.")
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val effectId = args.effectId.trim()
        if (effectId in BANNED_EFFECTS) {
            return ToolResult.Refused("Effect '$effectId' is banned for the director")
        }
        val actions = ServerActionsHolder.require()
        if (!actions.isEffectRegistered(effectId)) {
            return ToolResult.Refused("Effect '$effectId' is not registered")
        }
        val durationSec = args.durationSeconds.coerceIn(1, 120)
        val amplifier = (args.amplifier ?: 0).coerceIn(0, 2)
        val showParticles = args.showParticles ?: true
        val durationTicks = durationSec * 20
        return actions.applyEffect(ctx.playerUuid, effectId, durationTicks, amplifier, showParticles)
            .toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("effect_id") val effectId: String,
        @SerialName("duration_seconds") val durationSeconds: Int,
        @SerialName("amplifier") val amplifier: Int? = null,
        @SerialName("show_particles") val showParticles: Boolean? = null,
    )

    companion object {
        /** Effects the director must never apply. Permanent or instant-death potential. */
        private val BANNED_EFFECTS = setOf(
            "minecraft:wither",
            "minecraft:instant_damage",
            "minecraft:harming",
            "minecraft:bad_omen",
            "minecraft:trial_omen",
            "minecraft:raid_omen",
        )
    }
}
