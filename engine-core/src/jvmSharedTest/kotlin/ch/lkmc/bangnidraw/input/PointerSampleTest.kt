package ch.lkmc.bangnidraw.input

import ch.lkmc.bangnidraw.engine.core.PointerTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reused record's two fills that do not read the platform's tool clear
 * the field rather than leaving it.
 *
 * One `PointerSample` serves every pointer of every event, so a leftover
 * value is not "this gesture's tool": with a palm down as pointer 0 and a pen
 * drawing as pointer 1, pointer 0's moves would carry `STYLUS`. The tests
 * below are that scenario, and the reason [PointerSample.tool] is nullable.
 */
class PointerSampleTest {

    @Test
    fun `a tool-less contact fill clears the tool the last down left`() {
        val sample = PointerSample()
        sample.set(1, PointerTool.STYLUS, 1f, 2f, 0.5f, 0.1f, 0.2f, 10L)
        assertEquals(PointerTool.STYLUS, sample.tool)
        // Dirty every field the fill under test is supposed to clear, or the
        // assertions below only re-check the record's initial values and
        // would pass with the clearing deleted. A hover fill is the only way
        // `distance` becomes non-zero.
        sample.setHover(1, PointerTool.ERASER, 1f, 2f, 7f, 15L)

        sample.setWithoutTool(0, 3f, 4f, 0.25f, 0f, 0f, 20L)
        assertNull(sample.tool, "a move must not inherit another pointer's tool")
        // The axes it does fill still arrive.
        assertEquals(0, sample.pointerId)
        assertEquals(3f, sample.x)
        assertEquals(0.25f, sample.pressure)
        assertEquals(20L, sample.timeNs)
        // And the hover axis still does not survive contact.
        assertEquals(0f, sample.distance)
    }

    @Test
    fun `a tool-less hover fill clears it too`() {
        val sample = PointerSample()
        sample.setHover(1, PointerTool.ERASER, 1f, 2f, 3f, 10L)
        assertEquals(PointerTool.ERASER, sample.tool)
        // Same reason as above: the contact axes have to be non-neutral
        // before the hover fill runs, or asserting that it neutralizes them
        // asserts nothing.
        sample.set(1, PointerTool.STYLUS, 1f, 2f, 0.5f, 0.9f, 0.1f, 15L)

        sample.setHoverWithoutTool(1, 5f, 6f, 2f, 20L)
        assertNull(sample.tool, "a hover move must not inherit the hover enter's tool")
        assertEquals(2f, sample.distance)
        // The contact-only axes are still neutralized.
        assertEquals(1f, sample.pressure)
        assertEquals(0f, sample.tilt)
        assertEquals(0f, sample.orientation)
    }

    @Test
    fun `a fresh record has no tool until one is filled`() {
        // The predictor's samples are only ever filled tool-lessly, so this
        // is the value every predicted sample carries.
        assertNull(PointerSample().tool)
    }

    @Test
    fun `the full fills still write the tool after delegating`() {
        // The delegation order is load-bearing: the tool-less fill clears the
        // field, so a full fill that wrote the tool first would erase it.
        val sample = PointerSample()
        sample.setWithoutTool(0, 0f, 0f, 1f, 0f, 0f, 0L)
        assertEquals(
            PointerTool.MOUSE,
            sample.set(2, PointerTool.MOUSE, 1f, 1f, 1f, 0f, 0f, 1L).tool,
        )
        assertEquals(
            PointerTool.FINGER,
            sample.setHover(2, PointerTool.FINGER, 1f, 1f, 1f, 1L).tool,
        )
    }
}
