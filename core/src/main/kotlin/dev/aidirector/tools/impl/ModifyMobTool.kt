package dev.aidirector.tools.impl

import dev.aidirector.actions.MobModifiers
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Re-shapes a tracked mob: glow, body scale, movement speed, silence,
 * gravity, name, and an optional status effect on the mob itself. The
 * director uses this to make a spawned creature genuinely distinct — a giant
 * silent glowing skeleton that drifts without gravity reads very differently
 * from a plain one.
 *
 * Takes the entity UUID returned by spawn_mob.
 */
class ModifyMobTool : Tool<ModifyMobTool.Args>() {

    override val name: String = "modify_mob"

    override val description: String = """
        Modify a mob spawned earlier (use the entity_uuid from spawn_mob).
        Set any of: glowing, scale (0.25-4.0), speed_multiplier (0.25-4.0),
        silent, no_gravity, custom_name, or a status effect on the mob
        (effect + effect_seconds + effect_amplifier). Only the fields you pass
        change. Use it to give a creature a distinct, memorable presence.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("entity_uuid"),
    ) {
        string("entity_uuid", "Entity UUID from a previous spawn_mob call.")
        boolean("glowing", "Outline the mob with a glow visible through walls.")
        number("scale", "Body scale, 0.25 (tiny) to 4.0 (huge).")
        number("speed_multiplier", "Movement-speed multiplier, 0.25 to 4.0.")
        boolean("silent", "Mute the mob's sounds.")
        boolean("no_gravity", "Make the mob float, ignoring gravity.")
        string("custom_name", "A name shown above the mob.", maxLength = 48)
        string("effect", "Optional status effect id to apply to the mob, e.g. minecraft:speed.")
        integer("effect_seconds", "Effect duration in seconds (used with effect).")
        integer("effect_amplifier", "Effect tier 0-4 (used with effect).")
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val uuid = runCatching { UUID.fromString(args.entityUuid.trim()) }.getOrNull()
            ?: return ToolResult.Refused("entity_uuid '${args.entityUuid}' is not a valid UUID")

        val hasAny = listOf(
            args.glowing, args.scale, args.speedMultiplier, args.silent,
            args.noGravity, args.customName, args.effect,
        ).any { it != null }
        if (!hasAny) return ToolResult.Refused("No modifiers supplied — set at least one field")

        val mods = MobModifiers(
            glowing = args.glowing,
            scale = args.scale,
            speedMultiplier = args.speedMultiplier,
            silent = args.silent,
            noGravity = args.noGravity,
            customName = args.customName?.trim()?.take(48)?.takeIf { it.isNotEmpty() },
            effectId = args.effect?.trim()?.takeIf { it.isNotEmpty() },
            effectDurationTicks = ((args.effectSeconds ?: 30).coerceIn(1, 600)) * 20,
            effectAmplifier = (args.effectAmplifier ?: 0).coerceIn(0, 4),
        )
        val outcome = ServerActionsHolder.require().modifyMob(uuid, mods)
        return if (outcome.ok) ToolResult.Success(outcome.message) else ToolResult.Failed(outcome.message)
    }

    @Serializable
    data class Args(
        @SerialName("entity_uuid") val entityUuid: String,
        @SerialName("glowing") val glowing: Boolean? = null,
        @SerialName("scale") val scale: Double? = null,
        @SerialName("speed_multiplier") val speedMultiplier: Double? = null,
        @SerialName("silent") val silent: Boolean? = null,
        @SerialName("no_gravity") val noGravity: Boolean? = null,
        @SerialName("custom_name") val customName: String? = null,
        @SerialName("effect") val effect: String? = null,
        @SerialName("effect_seconds") val effectSeconds: Int? = null,
        @SerialName("effect_amplifier") val effectAmplifier: Int? = null,
    )
}
