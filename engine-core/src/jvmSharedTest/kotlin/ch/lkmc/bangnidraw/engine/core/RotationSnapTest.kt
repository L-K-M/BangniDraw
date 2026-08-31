package ch.lkmc.bangnidraw.engine.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `docs/plan/07-input-and-stylus.md` §7's rotation snap, and `11-testing.md` §3.1. */
class RotationSnapTest {

    @Test
    fun `rotation snaps to exactly zero inside the entry band`() {
        // Exactly 0f, not nearly: ViewTransform.isIdentity decides whether the
        // reset-view pill is shown, so 1e-8 would leave it on screen for a
        // canvas that is visibly straight.
        val snap = RotationSnap()
        assertEquals(0f, snap.update(RotationSnap.SNAP_IN - 0.001f))
        assertTrue(snap.isSnapped)
        assertEquals(0f, snap.update(0.0f))
    }

    @Test
    fun `rotation outside the entry band is not snapped`() {
        val snap = RotationSnap()
        val angle = RotationSnap.SNAP_IN + 0.01f
        assertEquals(angle, snap.update(angle), 1e-6f)
        assertFalse(snap.isSnapped)
    }

    @Test
    fun `the exit band is wider than the entry band — that gap is the hysteresis`() {
        // With one threshold the rotation flickers in and out while a finger
        // holds still on the boundary, which reads as the paper twitching.
        assertTrue(RotationSnap.SNAP_OUT > RotationSnap.SNAP_IN)
        val snap = RotationSnap()
        snap.update(0f)
        assertTrue(snap.isSnapped)
        // Between the two thresholds: still snapped, because it is already.
        val between = (RotationSnap.SNAP_IN + RotationSnap.SNAP_OUT) / 2f
        assertEquals(0f, snap.update(between))
        assertTrue(snap.isSnapped, "must not release inside the exit band")
        // Past the exit band it releases.
        val out = RotationSnap.SNAP_OUT + 0.01f
        assertEquals(out, snap.update(out), 1e-6f)
        assertFalse(snap.isSnapped)
        // And re-entering needs the NARROWER band, not the wider one.
        assertEquals(between, snap.update(between), 1e-6f)
        assertFalse(snap.isSnapped, "re-entry uses SNAP_IN, so this must stay released")
    }

    @Test
    fun `raw keeps accumulating while snapped, so a small delta can leave the snap`() {
        // If the displayed value were the only state, snapping would erase the
        // evidence needed to un-snap and the canvas would stick at zero.
        val snap = RotationSnap()
        snap.update(0.01f)
        assertTrue(snap.isSnapped)
        assertEquals(0.01f, snap.raw, 1e-6f)
        snap.update(0.04f)
        assertEquals(0.04f, snap.raw, 1e-6f)
        assertEquals(0f, snap.update(0.04f), "still displayed as straight")
    }

    @Test
    fun `the haptic fires once, on entry only`() {
        // A tick on the way out would fire while the user is already rotating,
        // and reads as noise.
        val snap = RotationSnap()
        assertTrue(snap.updateAndDetectEntry(0.01f), "entering must report")
        assertFalse(snap.updateAndDetectEntry(0.005f), "staying snapped must not re-fire")
        assertFalse(snap.updateAndDetectEntry(0.5f), "leaving must be silent")
        assertTrue(snap.updateAndDetectEntry(0.0f), "re-entering reports again")
    }

    @Test
    fun `right-angle snapping is off by default`() {
        // A painter turning the canvas to suit a stroke does not want it
        // snapping at 90°; someone working on a rotated portrait does. Only the
        // user knows which they are, so the default must not guess.
        val snap = RotationSnap()
        assertFalse(snap.snapRightAngles)
        val nearRight = (PI / 2.0).toFloat()
        assertEquals(nearRight, snap.update(nearRight), 1e-6f)
        assertFalse(snap.isSnapped)
    }

    @Test
    fun `right-angle snapping, when on, snaps to each quarter turn`() {
        val snap = RotationSnap(snapRightAngles = true)
        val quarter = (PI / 2.0).toFloat()
        for (multiple in -2..2) {
            snap.reset()
            val target = ViewTransform.normalizeAngle(multiple * quarter)
            val displayed = snap.update(target + RotationSnap.SNAP_IN / 2f)
            assertTrue(snap.isSnapped, "must snap near ${multiple}x90°")
            assertEquals(target, displayed, 1e-5f)
        }
    }

    @Test
    fun `an angle wrapped past pi still snaps to zero`() {
        // ViewTransform.gesture normalises to (-pi, pi], but a caller
        // accumulating raw deltas can hand over 2pi - epsilon, which IS zero.
        // Comparing without wrapping would call that a half turn away.
        val snap = RotationSnap()
        val nearlyFullTurn = (2.0 * PI).toFloat() - 0.01f
        assertEquals(0f, snap.update(nearlyFullTurn))
        assertTrue(snap.isSnapped)
    }

    @Test
    fun `reset clears both the snap and the accumulator`() {
        val snap = RotationSnap()
        snap.update(0.01f)
        snap.reset()
        assertFalse(snap.isSnapped)
        assertEquals(0f, snap.raw)
    }
}
