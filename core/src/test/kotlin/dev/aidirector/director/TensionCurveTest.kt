package dev.aidirector.director

import dev.aidirector.sensors.TimeOfDay
import dev.aidirector.sensors.Weather
import dev.aidirector.support.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TensionCurveTest {

    @Test
    fun `safe day baseline is low`() {
        val s = Fixtures.snapshot()
        val score = TensionCurve.compute(s, recentlyTookDamage = false)
        assertThat(score).isLessThan(0.20)
    }

    @Test
    fun `recent damage forces high tension`() {
        val s = Fixtures.snapshot(health = 16)
        val score = TensionCurve.compute(s, recentlyTookDamage = true)
        assertThat(score).isGreaterThanOrEqualTo(0.65)
    }

    @Test
    fun `night with hostiles is high`() {
        val s = Fixtures.snapshot(timeOfDay = TimeOfDay.NIGHT, nearbyHostile = 3, health = 8)
        val score = TensionCurve.compute(s, recentlyTookDamage = false)
        assertThat(score).isGreaterThan(0.50)
    }

    @Test
    fun `thunderstorm bumps tension`() {
        val a = TensionCurve.compute(
            Fixtures.snapshot(weather = Weather(raining = false, thundering = false)),
            recentlyTookDamage = false,
        )
        val b = TensionCurve.compute(
            Fixtures.snapshot(weather = Weather(raining = true, thundering = true)),
            recentlyTookDamage = false,
        )
        assertThat(b).isGreaterThan(a)
    }

    @Test
    fun `describe returns plain text labels`() {
        assertThat(TensionCurve.describe(0.0)).contains("very low")
        assertThat(TensionCurve.describe(0.85)).contains("extreme")
    }
}
