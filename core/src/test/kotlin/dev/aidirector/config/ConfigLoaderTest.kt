package dev.aidirector.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConfigLoaderTest {

    @Test
    fun `writes default config on first run and reports not initialized`(@TempDir tmp: Path) {
        assertThatThrownBy { ConfigLoader.load(tmp) }
            .isInstanceOf(ConfigNotInitializedException::class.java)

        val file = tmp.resolve(DirectorConfig.FILE_NAME)
        assertThat(Files.exists(file)).isTrue
        val text = Files.readString(file)
        assertThat(text).contains("base_url", "api_key", "model")
    }

    @Test
    fun `parses a valid config`(@TempDir tmp: Path) {
        val file = tmp.resolve(DirectorConfig.FILE_NAME)
        Files.writeString(
            file,
            """
            [llm]
            base_url = "https://nim.example.com/v1"
            api_key = "secret"
            model = "meta/llama-3.3-70b-instruct"
            timeout_seconds = 30

            [director]
            enabled = true
            tick_interval_ms = 10000
            max_tool_calls_per_tick = 2
            min_seconds_between_llm_calls = 20

            [guardrails]
            max_spawns_per_minute = 1
            max_effects_per_minute = 2
            max_sounds_per_minute = 4
            max_items_per_minute = 1
            max_narrations_per_minute = 5
            max_weather_per_hour = 1
            item_rarity_cap = "uncommon"

            [memory]
            db_file_name = "ai.db"
            max_event_log_rows = 500
            retention_days = 14
            """.trimIndent(),
        )
        val cfg = ConfigLoader.load(tmp)
        assertThat(cfg.llm.baseUrl).isEqualTo("https://nim.example.com/v1")
        assertThat(cfg.llm.timeoutSeconds).isEqualTo(30)
        assertThat(cfg.director.tickIntervalMs).isEqualTo(10_000)
        assertThat(cfg.guardrails.itemRarityCap).isEqualTo(ItemRarityCap.UNCOMMON)
        assertThat(cfg.memory.dbFileName).isEqualTo("ai.db")
    }

    @Test
    fun `rejects missing required section`(@TempDir tmp: Path) {
        val file = tmp.resolve(DirectorConfig.FILE_NAME)
        Files.writeString(
            file,
            """
            [llm]
            base_url = "x"
            api_key = "y"
            model = "z"
            """.trimIndent(),
        )
        assertThatThrownBy { ConfigLoader.load(tmp) }
            .isInstanceOf(ConfigParseException::class.java)
            .hasMessageContaining("[director]")
    }

    @Test
    fun `rejects out-of-range timeout`(@TempDir tmp: Path) {
        val file = tmp.resolve(DirectorConfig.FILE_NAME)
        Files.writeString(
            file,
            minimalTomlWithLlm("""timeout_seconds = 99999"""),
        )
        assertThatThrownBy { ConfigLoader.load(tmp) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("llm.timeout_seconds")
    }

    @Test
    fun `rejects unknown rarity cap`(@TempDir tmp: Path) {
        val file = tmp.resolve(DirectorConfig.FILE_NAME)
        Files.writeString(
            file,
            """
            [llm]
            base_url = "x"
            api_key = "y"
            model = "z"

            [director]

            [guardrails]
            item_rarity_cap = "legendary"

            [memory]
            """.trimIndent(),
        )
        assertThatThrownBy { ConfigLoader.load(tmp) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unknown rarity cap")
    }

    private fun minimalTomlWithLlm(extraLlm: String): String = """
        [llm]
        base_url = "x"
        api_key = "y"
        model = "z"
        $extraLlm

        [director]

        [guardrails]

        [memory]
    """.trimIndent()
}
