package dev.aidirector.tools

import dev.aidirector.config.ItemRarityCap
import dev.aidirector.tools.impl.ApplyEffectTool
import dev.aidirector.tools.impl.GiveItemTool
import dev.aidirector.tools.impl.ModifyWeatherTool
import dev.aidirector.tools.impl.PlaySoundTool
import dev.aidirector.tools.impl.SendNarrationTool
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ToolJsonTest {

    private val registry = ToolRegistry(
        listOf(
            SendNarrationTool(),
            PlaySoundTool(),
            GiveItemTool { ItemRarityCap.RARE },
            ApplyEffectTool(),
            ModifyWeatherTool(),
        ),
    )

    @Test
    fun `each tool emits a valid spec`() {
        val specs = registry.specs()
        assertThat(specs).hasSize(5)
        specs.forEach { spec ->
            assertThat(spec.function.name).matches("^[a-z][a-z0-9_]{0,63}$")
            assertThat(spec.function.description).isNotBlank
            assertThat(spec.function.parameters["type"]?.toString()).isEqualTo("\"object\"")
            assertThat(spec.function.parameters["properties"]).isNotNull
        }
    }

    @Test
    fun `send_narration decodes valid args`() {
        val tool = registry["send_narration"]!! as SendNarrationTool
        val args = tool.decodeArguments("""{"message":"hello","style":"whisper"}""")
        assertThat(args.style).isEqualTo("whisper")
        assertThat(args.message).isEqualTo("hello")
    }

    @Test
    fun `send_narration rejects malformed json`() {
        val tool = registry["send_narration"]!!
        assertThatThrownBy { tool.decodeArguments("{not-json}") }
            .isInstanceOf(ToolArgumentException::class.java)
    }

    @Test
    fun `give_item handles optional fields`() {
        val tool = registry["give_item"]!! as GiveItemTool
        val args = tool.decodeArguments("""{"item_id":"minecraft:bread"}""")
        assertThat(args.itemId).isEqualTo("minecraft:bread")
        assertThat(args.count).isNull()
        assertThat(args.customName).isNull()
    }

    @Test
    fun `play_sound parses volume and pitch`() {
        val tool = registry["play_sound"]!! as PlaySoundTool
        val args = tool.decodeArguments("""{"sound_id":"minecraft:entity.wolf.howl","volume":1.5,"pitch":0.8}""")
        assertThat(args.volume).isEqualTo(1.5)
        assertThat(args.pitch).isEqualTo(0.8)
    }

    @Test
    fun `registry rejects duplicate tool names`() {
        assertThatThrownBy {
            ToolRegistry(listOf(SendNarrationTool(), SendNarrationTool()))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Duplicate")
    }

    @Test
    fun `registry rejects bad tool names`() {
        val badTool = object : Tool<Unit>() {
            override val name: String = "BadCamelCase"
            override val description: String = "x"
            override val parametersSchema = kotlinx.serialization.json.buildJsonObject {}
            override val serializer = kotlinx.serialization.serializer<Unit>()
            override suspend fun execute(args: Unit, ctx: ToolContext) = ToolResult.Success("")
        }
        assertThatThrownBy { ToolRegistry(listOf(badTool)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
