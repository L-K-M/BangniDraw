package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RailSlotPolicyTest {

    private val paints = ('a'..'f').map { BrushPreset(id = "builtin.$it", name = it.toString()) }
    private val paintPresetIds = paints.map { it.id }

    @Test
    fun `a catalogue within the budget is shown whole`() {
        val assignments = PaintSlotAssignments.restore(paintPresetIds)

        assertEquals(paintPresetIds, visiblePresetIds(assignments, budget = 6))
        assertEquals(paintPresetIds, visiblePresetIds(assignments, budget = 10))
    }

    @Test
    fun `overflow takes the first budgeted slots in assignment order`() {
        val assignments = PaintSlotAssignments.restore(paintPresetIds)

        assertEquals(
            listOf("builtin.a", "builtin.b", "builtin.c", "builtin.d"),
            visiblePresetIds(assignments, budget = 4),
        )
    }

    @Test
    fun `an active assignment past the budget keeps the last visible slot`() {
        val assignments = PaintSlotAssignments.restore(paintPresetIds).activate(5)

        assertEquals(
            listOf("builtin.a", "builtin.b", "builtin.c", "builtin.f"),
            visiblePresetIds(assignments, budget = 4),
        )
        assertEquals(paintPresetIds, assignments.presetIds)
    }

    @Test
    fun `an assigned preset survives activating another slot`() {
        val assigned = PaintSlotAssignments.restore(paintPresetIds)
            .activate(3)
            .assign("builtin.f")

        assertEquals(
            listOf("builtin.a", "builtin.b", "builtin.c", "builtin.f"),
            visiblePresetIds(assigned, budget = 4),
        )

        val switched = assigned.activate(1)
        val returned = switched.activate(3)

        assertEquals(
            listOf("builtin.a", "builtin.b", "builtin.c", "builtin.f"),
            visiblePresetIds(switched, budget = 4),
        )
        assertEquals("builtin.f", returned.activePresetId)
        assertEquals("builtin.f", returned.presetIds[3])
    }

    @Test
    fun `assigning an existing preset swaps its previous slot`() {
        val assignments = PaintSlotAssignments.restore(paintPresetIds)
            .activate(3)
            .assign("builtin.f")

        assertEquals(
            listOf(
                "builtin.a",
                "builtin.b",
                "builtin.c",
                "builtin.f",
                "builtin.e",
                "builtin.d",
            ),
            assignments.presetIds,
        )
    }

    @Test
    fun `restore drops stale duplicates and appends new catalogue entries`() {
        val assignments = PaintSlotAssignments.restore(
            cataloguePresetIds = paintPresetIds,
            storedPresetIds = listOf(
                "builtin.f",
                "missing.brush",
                "builtin.f",
                "builtin.b",
            ),
        )

        assertEquals(
            listOf(
                "builtin.f",
                "builtin.b",
                "builtin.a",
                "builtin.c",
                "builtin.d",
                "builtin.e",
            ),
            assignments.presetIds,
        )
        assertEquals(0, assignments.activeIndex)
    }

    @Test
    fun `stored assignments preserve order and punctuation`() {
        val presetIds = listOf("builtin.pencil", "user.brush,comma", "user.brush space")

        assertEquals(
            presetIds,
            StoredPaintSlots.decode(StoredPaintSlots.encode(presetIds)),
        )
        assertEquals(emptyList(), StoredPaintSlots.decode(null))
    }

    @Test
    fun `assigned slots survive restart while the active index resets`() {
        val assigned = PaintSlotAssignments.restore(paintPresetIds)
            .activate(3)
            .assign("builtin.f")

        val restored = PaintSlotAssignments.restore(
            cataloguePresetIds = paintPresetIds,
            storedPresetIds = StoredPaintSlots.decode(StoredPaintSlots.encode(assigned.presetIds)),
        )

        assertEquals(assigned.presetIds, restored.presetIds)
        assertEquals(0, restored.activeIndex)
    }

    @Test
    fun `the catalogue is nonempty and unique`() {
        assertFailsWith<IllegalArgumentException> {
            PaintSlotAssignments.restore(emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            PaintSlotAssignments.restore(listOf("builtin.a", "builtin.a"))
        }
    }

    @Test
    fun `the budget is at least one slot`() {
        val assignments = PaintSlotAssignments.restore(paintPresetIds)

        assertFailsWith<IllegalArgumentException> {
            RailSlotPolicy.visibleIndices(assignments, budget = 0)
        }
    }

    private fun visiblePresetIds(
        assignments: PaintSlotAssignments,
        budget: Int,
    ): List<String> = RailSlotPolicy.visibleIndices(assignments, budget)
        .map(assignments.presetIds::get)
}
