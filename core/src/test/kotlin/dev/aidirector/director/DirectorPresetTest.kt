package dev.aidirector.director

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DirectorPresetTest {

    @Test
    fun `known wires resolve case-insensitively`() {
        assertThat(DirectorPreset.fromWireOrDefault("trickster")).isEqualTo(DirectorPreset.TRICKSTER)
        assertThat(DirectorPreset.fromWireOrDefault("CRUEL")).isEqualTo(DirectorPreset.CRUEL_GM)
        assertThat(DirectorPreset.fromWireOrDefault("Loremaster")).isEqualTo(DirectorPreset.LOREMASTER)
        assertThat(DirectorPreset.fromWireOrDefault(" comforter ")).isEqualTo(DirectorPreset.COMFORTER)
    }

    @Test
    fun `enum name also matches`() {
        assertThat(DirectorPreset.fromWireOrDefault("CRUEL_GM")).isEqualTo(DirectorPreset.CRUEL_GM)
    }

    @Test
    fun `unknown or blank input falls back to balanced`() {
        assertThat(DirectorPreset.fromWireOrDefault("chaos")).isEqualTo(DirectorPreset.BALANCED)
        assertThat(DirectorPreset.fromWireOrDefault(null)).isEqualTo(DirectorPreset.BALANCED)
        assertThat(DirectorPreset.fromWireOrDefault("   ")).isEqualTo(DirectorPreset.BALANCED)
    }

    @Test
    fun `balanced carries no directive, every other preset does`() {
        assertThat(DirectorPreset.BALANCED.directive).isEmpty()
        DirectorPreset.entries
            .filter { it != DirectorPreset.BALANCED }
            .forEach {
                assertThat(it.directive).describedAs("${it.wire} directive").isNotBlank()
                assertThat(it.displayName).isNotBlank()
            }
    }
}
