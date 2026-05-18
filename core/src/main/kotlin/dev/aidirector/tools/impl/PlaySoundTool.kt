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

class PlaySoundTool : Tool<PlaySoundTool.Args>() {

    override val name: String = "play_sound"

    override val description: String = """
        Play a sound near the player to build atmosphere — distant rumble, a
        wolf howl in the woods, a faint whisper in a cave. Use for ambience,
        never for jumpscares or to interrupt gameplay. The sound must exist in
        the running game's sound registry (e.g. minecraft:entity.wolf.howl).
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("sound_id"),
    ) {
        string(
            name = "sound_id",
            description = "Namespaced sound id, e.g. 'minecraft:entity.wolf.howl' or 'minecraft:ambient.cave'.",
            maxLength = 128,
        )
        number(name = "volume", description = "0.0 silent, 1.0 normal, up to 2.0 louder.", min = 0.0, max = 2.0)
        number(name = "pitch", description = "0.5 deep, 1.0 normal, 2.0 high-pitched.", min = 0.5, max = 2.0)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val actions = ServerActionsHolder.require()
        if (!actions.isSoundRegistered(args.soundId)) {
            return ToolResult.Refused("Sound '${args.soundId}' is not registered in this server")
        }
        // Refuse a sound already played for this player recently — stops the
        // director from settling on one sound as a predictable "tell".
        val dedup = ctx.narrationDedup.checkSound(ctx.playerUuid, args.soundId, ctx.nowMs)
        if (dedup is dev.aidirector.dedup.NarrationDedup.Result.TooSimilar) {
            return ToolResult.Refused(
                "Sound '${args.soundId}' was already played ${dedup.sinceMs / 1000}s ago — " +
                    "pick a different sound; do not reuse one as a routine cue.",
            )
        }
        val volume = args.volume?.toFloat()?.coerceIn(0f, 2f) ?: 1f
        val pitch = args.pitch?.toFloat()?.coerceIn(0.5f, 2f) ?: 1f
        return actions.playSound(ctx.playerUuid, args.soundId, volume, pitch).toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("sound_id") val soundId: String,
        @SerialName("volume") val volume: Double? = null,
        @SerialName("pitch") val pitch: Double? = null,
    )
}
