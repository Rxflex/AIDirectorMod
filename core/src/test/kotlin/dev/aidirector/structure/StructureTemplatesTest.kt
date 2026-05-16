package dev.aidirector.structure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StructureTemplatesTest {

    @Test
    fun `all named templates resolve`() {
        for (name in StructureTemplates.names) {
            assertThat(StructureTemplates.resolve(name)).isNotNull
        }
    }

    @Test
    fun `resolve is case-insensitive`() {
        assertThat(StructureTemplates.resolve("GRAVE")).isNotNull
        assertThat(StructureTemplates.resolve("Grave")).isNotNull
    }

    @Test
    fun `unknown template resolves to null`() {
        assertThat(StructureTemplates.resolve("space_station")).isNull()
    }

    @Test
    fun `every template has at least one block and a vanilla footprint`() {
        for (name in StructureTemplates.names) {
            val t = StructureTemplates.resolve(name)!!
            assertThat(t.blocks).isNotEmpty
            // Footprint stays within a small box — never griefs a base.
            t.blocks.forEach {
                assertThat(it.dx).isBetween(-3, 3)
                assertThat(it.dy).isBetween(0, 5)
                assertThat(it.dz).isBetween(-3, 3)
                assertThat(it.blockId).startsWith("minecraft:")
            }
        }
    }

    @Test
    fun `every sign slot sits directly above a solid template block`() {
        // A standing sign needs a block beneath it — verify each slot has one.
        for (name in StructureTemplates.names) {
            val t = StructureTemplates.resolve(name)!!
            for (slot in t.signSlots) {
                val hasBlockBelow = t.blocks.any {
                    it.dx == slot.dx && it.dy == slot.dy - 1 && it.dz == slot.dz
                }
                assertThat(hasBlockBelow)
                    .describedAs("template '$name' sign slot at ${slot.dx},${slot.dy},${slot.dz} has support")
                    .isTrue
            }
        }
    }

    @Test
    fun `grave and memorial and waystone carry a sign slot`() {
        assertThat(StructureTemplates.resolve("grave")!!.signSlots).isNotEmpty
        assertThat(StructureTemplates.resolve("memorial")!!.signSlots).isNotEmpty
        assertThat(StructureTemplates.resolve("waystone")!!.signSlots).isNotEmpty
    }
}
