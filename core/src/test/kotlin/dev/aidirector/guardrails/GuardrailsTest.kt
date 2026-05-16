package dev.aidirector.guardrails

import dev.aidirector.support.Fixtures
import dev.aidirector.support.TestClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class GuardrailsTest {

    private val cfg = Fixtures.defaultConfig().guardrails
    private val player = UUID.randomUUID()

    @Test
    fun `unknown tool is denied with explicit reason`() {
        val g = Guardrails(cfg, TestClock())
        val r = g.checkAndRecord(player, "totally_made_up")
        assertThat(r).isInstanceOf(GuardrailDecision.Denied::class.java)
        assertThat((r as GuardrailDecision.Denied).reason).contains("not allow-listed")
    }

    @Test
    fun `narration cap applies per minute`() {
        val clock = TestClock()
        val g = Guardrails(cfg, clock) // cfg defaults: 6 narrations/min
        repeat(6) { assertThat(g.checkAndRecord(player, "send_narration")).isEqualTo(GuardrailDecision.Allowed) }
        assertThat(g.checkAndRecord(player, "send_narration")).isInstanceOf(GuardrailDecision.Denied::class.java)
        clock.advance(60_001)
        assertThat(g.checkAndRecord(player, "send_narration")).isEqualTo(GuardrailDecision.Allowed)
    }

    @Test
    fun `weather uses hourly window`() {
        val clock = TestClock()
        val g = Guardrails(cfg.copy(maxWeatherPerHour = 2), clock)
        assertThat(g.checkAndRecord(player, "modify_weather")).isEqualTo(GuardrailDecision.Allowed)
        assertThat(g.checkAndRecord(player, "modify_weather")).isEqualTo(GuardrailDecision.Allowed)
        assertThat(g.checkAndRecord(player, "modify_weather")).isInstanceOf(GuardrailDecision.Denied::class.java)
        clock.advance(60 * 60 * 1000L + 1)
        assertThat(g.checkAndRecord(player, "modify_weather")).isEqualTo(GuardrailDecision.Allowed)
    }
}
