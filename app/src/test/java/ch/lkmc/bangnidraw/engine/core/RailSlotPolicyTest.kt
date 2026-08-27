package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RailSlotPolicyTest {

    private val paints = ('a'..'f').map { BrushPreset(id = "builtin.$it", name = it.toString()) }

    @Test
    fun `a catalogue within the budget is shown whole`() {
        assertEquals(paints, RailSlotPolicy.visible(paints, "builtin.a", budget = 6))
        assertEquals(paints, RailSlotPolicy.visible(paints, "builtin.a", budget = 10))
    }

    @Test
    fun `overflow takes the first budgeted slots in rail order`() {
        assertEquals(
            listOf("builtin.a", "builtin.b", "builtin.c", "builtin.d"),
            RailSlotPolicy.visible(paints, activePaintId = "builtin.a", budget = 4).map { it.id },
        )
    }

    @Test
    fun `an active preset past the budget keeps the last slot`() {
        assertEquals(
            listOf("builtin.a", "builtin.b", "builtin.c", "builtin.f"),
            RailSlotPolicy.visible(paints, activePaintId = "builtin.f", budget = 4).map { it.id },
        )
    }

    @Test
    fun `an unknown or absent active id changes nothing`() {
        assertEquals(
            listOf("builtin.a", "builtin.b", "builtin.c", "builtin.d"),
            RailSlotPolicy.visible(paints, activePaintId = "user.elsewhere", budget = 4).map { it.id },
        )
        assertEquals(
            listOf("builtin.a", "builtin.b", "builtin.c", "builtin.d"),
            RailSlotPolicy.visible(paints, activePaintId = null, budget = 4).map { it.id },
        )
    }

    @Test
    fun `the budget is at least one slot`() {
        assertFailsWith<IllegalArgumentException> {
            RailSlotPolicy.visible(paints, activePaintId = null, budget = 0)
        }
    }
}
