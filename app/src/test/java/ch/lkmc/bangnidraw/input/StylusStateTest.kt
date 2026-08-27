package ch.lkmc.bangnidraw.input

import ch.lkmc.bangnidraw.engine.core.ButtonState
import ch.lkmc.bangnidraw.engine.core.PenButtonAction
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.StylusToolPolicy
import ch.lkmc.bangnidraw.engine.core.TemporaryReason
import ch.lkmc.bangnidraw.engine.core.TemporaryToolRequest
import ch.lkmc.bangnidraw.engine.core.TemporaryToolTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `docs/plan/11-testing.md` §3.4 / `07-input-and-stylus.md` §5, §6. */
class StylusStateTest {

    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun `a fresh state knows nothing and rejects nothing`() {
        val s = StylusState()
        assertFalse(s.isHovering)
        assertFalse(s.isDown)
        assertFalse(s.isNear(ms(0)))
        assertEquals(StylusState.NEVER, s.exitedAtNs)
    }

    @Test
    fun `hover makes the pen near, and tracks where it is`() {
        val s = StylusState()
        s.onHoverEnter(x = 12f, y = 34f, distance = 3f, tool = PointerTool.STYLUS)
        assertTrue(s.isNear(ms(0)))
        assertEquals(12f, s.hoverX)
        assertEquals(34f, s.hoverY)
        assertEquals(3f, s.hoverDistance)
        s.onHoverMove(20f, 40f, 2f)
        assertEquals(20f, s.hoverX)
        assertTrue(s.isNear(ms(10)))
    }

    @Test
    fun `the pen stays near for the hover grace after it leaves`() {
        // §5: covers "lift the pen a little too high between two strokes, palm
        // still resting" — the sequence that otherwise puts a mark on the page.
        val s = StylusState()
        s.onHoverEnter(0f, 0f, 0f, PointerTool.STYLUS)
        s.onHoverExit(ms(1000))
        assertFalse(s.isHovering)
        assertTrue(s.isNear(ms(1000)), "the moment it leaves")
        assertTrue(s.isNear(ms(1000 + StylusState.HOVER_GRACE_MS - 1)), "one ms inside the grace")
        assertFalse(s.isNear(ms(1000 + StylusState.HOVER_GRACE_MS)), "the grace has expired")
    }

    @Test
    fun `lifting the pen starts the grace even without a hover-exit event`() {
        // A pen leaving the glass is usually still in hover range, but that
        // event may never arrive. Without starting the grace here the palm
        // would be admitted the instant the pen lifted.
        val s = StylusState()
        s.onDown(5f, 5f, PointerTool.STYLUS)
        assertTrue(s.isNear(ms(0)))
        s.onUp(ms(500))
        assertFalse(s.isDown)
        assertTrue(s.isNear(ms(500 + StylusState.HOVER_GRACE_MS - 1)))
        assertFalse(s.isNear(ms(500 + StylusState.HOVER_GRACE_MS)))
    }

    @Test
    fun `contact overrides an expiring grace`() {
        val s = StylusState()
        s.onHoverExit(ms(0))
        s.onDown(1f, 1f, PointerTool.STYLUS)
        // Far past the grace, but the pen is on the glass.
        assertTrue(s.isNear(ms(10_000)))
    }

    @Test
    fun `a clock that goes backwards reads as just-left, not as expired`() {
        // Rejecting a palm for too long is recoverable; letting one through is
        // a mark on the painting. A caller mixing time bases must fail safe.
        val s = StylusState()
        s.onHoverExit(ms(5000))
        assertTrue(s.isNear(ms(0)), "a negative elapsed time must not expire the grace")
    }

    @Test
    fun `the eraser end is tracked as its own tool`() {
        val s = StylusState()
        s.onDown(0f, 0f, PointerTool.ERASER)
        assertEquals(PointerTool.ERASER, s.tool)
        assertTrue(s.isNear(ms(0)))
    }

    @Test
    fun `the barrel button latches and releases`() {
        val s = StylusState()
        assertFalse(s.buttonPressed)
        s.onButton(true)
        assertTrue(s.buttonPressed)
        s.onButton(false)
        assertFalse(s.buttonPressed)
    }

    @Test
    fun `the eraser end takes precedence over the barrel button`() {
        val request = StylusToolPolicy.resolve(
            pointer = PointerTool.ERASER,
            button = ButtonState.Pressed,
            buttonAction = PenButtonAction.Eyedropper,
        )

        assertEquals(
            TemporaryToolRequest(TemporaryToolTarget.Eraser, TemporaryReason.EraserEnd),
            request,
        )
    }

    @Test
    fun `the barrel button action applies only to a pressed stylus`() {
        assertEquals(
            TemporaryToolRequest(TemporaryToolTarget.Eyedropper, TemporaryReason.PenButton),
            StylusToolPolicy.resolve(
                PointerTool.STYLUS,
                ButtonState.Pressed,
                PenButtonAction.Eyedropper,
            ),
        )
        assertEquals(
            null,
            StylusToolPolicy.resolve(
                PointerTool.STYLUS,
                ButtonState.Released,
                PenButtonAction.Eraser,
            ),
        )
        assertEquals(
            null,
            StylusToolPolicy.resolve(
                PointerTool.MOUSE,
                ButtonState.Pressed,
                PenButtonAction.Eraser,
            ),
        )
    }

    @Test
    fun `reset forgets the pen entirely`() {
        val s = StylusState()
        s.onDown(1f, 2f, PointerTool.STYLUS)
        s.onButton(true)
        s.reset()
        assertFalse(s.isDown)
        assertFalse(s.buttonPressed)
        assertFalse(s.isNear(ms(0)), "a reset must not leave a grace running")
    }
}
