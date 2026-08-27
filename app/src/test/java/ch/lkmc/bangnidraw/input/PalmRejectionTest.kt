package ch.lkmc.bangnidraw.input

import ch.lkmc.bangnidraw.engine.core.PointerTool
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `docs/plan/11-testing.md` §3.3 / `07-input-and-stylus.md` §5. */
class PalmRejectionTest {

    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun `a finger is rejected while the pen is down`() {
        val s = StylusState()
        s.onDown(0f, 0f, PointerTool.STYLUS)
        assertTrue(PalmRejection.rejects(PointerTool.FINGER, s, ms(0)))
    }

    @Test
    fun `a finger is rejected while the pen merely hovers`() {
        // The palm arrives before the pen touches down — this is the case the
        // hover rule exists for, not the one where the pen is already drawing.
        val s = StylusState()
        s.onHoverEnter(0f, 0f, 5f, PointerTool.STYLUS)
        assertTrue(PalmRejection.rejects(PointerTool.FINGER, s, ms(0)))
    }

    @Test
    fun `a finger is rejected inside the grace and admitted after it`() {
        val s = StylusState()
        s.onHoverEnter(0f, 0f, 0f, PointerTool.STYLUS)
        s.onHoverExit(ms(0))
        assertTrue(PalmRejection.rejects(PointerTool.FINGER, s, ms(StylusState.HOVER_GRACE_MS - 1)))
        assertFalse(PalmRejection.rejects(PointerTool.FINGER, s, ms(StylusState.HOVER_GRACE_MS)))
    }

    @Test
    fun `a finger is admitted when no pen has ever been seen`() {
        // Finger drawing on a phone has no palm to reject, and this is the
        // common case — it must not cost anything.
        val s = StylusState()
        assertFalse(PalmRejection.rejects(PointerTool.FINGER, s, ms(0)))
    }

    @Test
    fun `mouse hover does not reject a finger`() {
        val s = StylusState()
        s.onHoverEnter(10f, 20f, 0f, PointerTool.MOUSE)

        assertTrue(s.isHovering, "mouse hover still drives the cursor")
        assertFalse(s.isNear(ms(0)))
        assertFalse(PalmRejection.rejects(PointerTool.FINGER, s, ms(0)))
    }

    @Test
    fun `a stylus is never rejected — it is the thing being protected`() {
        val s = StylusState()
        s.onDown(0f, 0f, PointerTool.STYLUS)
        assertFalse(PalmRejection.rejects(PointerTool.STYLUS, s, ms(0)))
        assertFalse(PalmRejection.rejects(PointerTool.ERASER, s, ms(0)))
    }

    @Test
    fun `a mouse is never rejected`() {
        // A mouse cannot be a palm and does not share a surface with a pen;
        // rejecting it would break §9's desktop path for no gain.
        val s = StylusState()
        s.onDown(0f, 0f, PointerTool.STYLUS)
        assertFalse(PalmRejection.rejects(PointerTool.MOUSE, s, ms(0)))
    }

    @Test
    fun `hover does not cancel a finger stroke already in progress, contact does`() {
        // §5's last paragraph: the user may be drawing with a finger with the
        // pen in the other hand, and yanking the stroke away would be worse
        // than the palm risk. Once the pen touches down, the pen is the intent.
        val s = StylusState()
        s.onHoverEnter(0f, 0f, 5f, PointerTool.STYLUS)
        assertFalse(
            PalmRejection.cancelsLiveFingerStroke(s),
            "hover alone must not cancel a live finger stroke",
        )
        s.onDown(0f, 0f, PointerTool.STYLUS)
        assertTrue(PalmRejection.cancelsLiveFingerStroke(s))
    }

    @Test
    fun `the two questions are genuinely different, not one flag read twice`() {
        // Hover rejects a NEW finger but does not cancel a live stroke. If
        // these ever collapse into one predicate, one of the two behaviours is
        // silently wrong — and it is the kind of wrong nobody notices until a
        // user complains their finger drawing keeps dying.
        val s = StylusState()
        s.onHoverEnter(0f, 0f, 5f, PointerTool.STYLUS)
        assertTrue(PalmRejection.rejects(PointerTool.FINGER, s, ms(0)))
        assertFalse(PalmRejection.cancelsLiveFingerStroke(s))
    }
}
