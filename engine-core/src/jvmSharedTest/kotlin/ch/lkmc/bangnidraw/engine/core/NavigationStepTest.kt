package ch.lkmc.bangnidraw.engine.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `docs/plan/07-input-and-stylus.md` §7's two-pointer formulation. */
class NavigationStepTest {

    private val step = NavigationStep()

    @Test
    fun `a parallel drag is pure pan`() {
        step.fromPointers(0f, 0f, 100f, 0f, 30f, 40f, 130f, 40f)
        assertEquals(30f, step.panX, 1e-4f)
        assertEquals(40f, step.panY, 1e-4f)
        assertEquals(1f, step.zoom, 1e-4f)
        assertEquals(0f, step.rotation, 1e-4f)
    }

    @Test
    fun `a symmetric spread is pure zoom about the centroid`() {
        step.fromPointers(100f, 0f, 200f, 0f, 50f, 0f, 250f, 0f)
        assertEquals(2f, step.zoom, 1e-4f)
        assertEquals(0f, step.panX, 1e-4f)
        assertEquals(150f, step.centroidX, 1e-4f)
    }

    @Test
    fun `a quarter turn is a quarter turn`() {
        step.fromPointers(0f, 0f, 100f, 0f, 0f, 0f, 0f, 100f)
        assertEquals((PI / 2.0).toFloat(), step.rotation, 1e-4f)
        assertEquals(1f, step.zoom, 1e-3f)
    }

    @Test
    fun `rotation wraps the short way round`() {
        // Crossing pi must not report nearly a full turn backwards: the canvas
        // would spin the long way for a few degrees of finger movement.
        // This pair spans about +/-0.01 rad either side of ZERO, not pi, under
        // this class's p1 - p0 convention — so on its own it passes even with
        // no wrap logic at all. Kept as the near-zero control.
        step.fromPointers(-100f, -1f, 100f, 1f, -100f, 1f, 100f, -1f)
        assertTrue(abs(step.rotation) < 0.1f, "expected a small delta, got ${step.rotation}")
        // The mirrored pair is the one that crosses pi: the separation angles
        // are +/-(pi - 0.01), so the raw difference is about -2pi and only the
        // wrap brings it back to +0.02.
        step.fromPointers(100f, -1f, -100f, 1f, 100f, 1f, -100f, -1f)
        assertTrue(abs(step.rotation) < 0.1f, "expected a small delta, got ${step.rotation}")
    }

    @Test
    fun `fingers the digitizer split in two do not send the zoom to infinity`() {
        // Dividing by a separation of a fraction of a pixel gives an enormous
        // zoom, and the angle between two such points is noise. This is the
        // guard, and without it one bad frame throws the canvas off screen.
        step.fromPointers(100f, 100f, 100.1f, 100f, 100f, 100f, 140f, 100f)
        assertEquals(1f, step.zoom, "a degenerate previous span must not scale")
        assertEquals(0f, step.rotation)

        step.fromPointers(100f, 100f, 140f, 100f, 100f, 100f, 100.1f, 100f)
        assertEquals(1f, step.zoom, "a degenerate current span must not scale either")
        assertEquals(0f, step.rotation)
    }

    @Test
    fun `a single pointer pans and never zooms or rotates`() {
        // Stylus-only mode, and a two-finger gesture whose second finger
        // lifted: §7 and §3 both say pan only, and there is no second point to
        // measure a zoom or an angle against anyway.
        step.fromSinglePointer(10f, 10f, 60f, 30f)
        assertEquals(50f, step.panX, 1e-4f)
        assertEquals(20f, step.panY, 1e-4f)
        assertEquals(1f, step.zoom)
        assertEquals(0f, step.rotation)
    }

    @Test
    fun `the point under the fingers stays under the fingers`() {
        // The property the whole formulation exists for, over random gestures.
        //
        // A FRESH view each iteration, deliberately. Accumulating 200 random
        // pinches walks the scale into the MIN/MAX clamp, where ViewTransform
        // adjusts the effective zoom and the anchor moves *by design* — so a
        // version of this test that accumulated would be measuring the clamp
        // and calling it a drift.
        val random = Random(4)
        repeat(200) {
            val view = ViewTransform(
                scale = 0.5f + random.nextFloat() * 2f,
                rotation = (random.nextFloat() - 0.5f) * 2f,
                tx = (random.nextFloat() - 0.5f) * 200f,
                ty = (random.nextFloat() - 0.5f) * 200f,
            )
            val p0x = random.nextFloat() * 800f
            val p0y = random.nextFloat() * 800f
            val p1x = p0x + 50f + random.nextFloat() * 300f
            val p1y = p0y + 50f + random.nextFloat() * 300f
            val dx = (random.nextFloat() - 0.5f) * 60f
            val dy = (random.nextFloat() - 0.5f) * 60f
            val q0x = p0x + dx
            val q0y = p0y + dy
            val q1x = p1x + dx * 1.2f
            val q1y = p1y + dy * 0.8f

            val anchorBefore = view.invert((p0x + p1x) / 2f, (p0y + p1y) / 2f)
            step.fromPointers(p0x, p0y, p1x, p1y, q0x, q0y, q1x, q1y)
            val after = step.applyTo(view)
            val anchorAfter = after.invert(step.centroidX, step.centroidY)
            if (after.scale <= ViewTransform.MIN_SCALE || after.scale >= ViewTransform.MAX_SCALE) {
                return@repeat
            }
            // Tolerance in CANVAS px, so it is scaled by the zoom: at 0.5x a
            // screen pixel is two canvas px, and a fixed epsilon would be
            // testing the float format rather than the algebra.
            // Tight, because the property is EXACT once the step pivots about
            // the previous centroid: what is left is float rounding, not a
            // systematic drift. A loose tolerance here would have accepted the
            // ~2 px per step that anchoring at the current centroid produced.
            val tolerance = 0.01f / after.scale
            assertTrue(
                abs(anchorAfter.first - anchorBefore.first) < tolerance &&
                    abs(anchorAfter.second - anchorBefore.second) < tolerance,
                "anchor moved from $anchorBefore to $anchorAfter (tolerance $tolerance)",
            )
        }
    }

    @Test
    fun `pinching out and back in returns to where it started`() {
        // Similarities compose exactly, so this must land where it began rather
        // than accumulating float error into a canvas that is slightly rotated
        // and slightly off-centre.
        //
        // TWENTY each way, not a hundred: at 1.1x a step, a hundred spreads
        // saturate MAX_SCALE, and the clamp is deliberately not reversible —
        // ViewTransform adjusts the effective zoom to hold the anchor at the
        // boundary. A hundred would be testing the clamp and reporting drift.
        // Twenty peaks at ~6.7x, comfortably inside 0.25..64.
        var view = ViewTransform()
        repeat(20) {
            step.fromPointers(100f, 100f, 300f, 100f, 90f, 100f, 310f, 110f)
            view = step.applyTo(view)
        }
        assertTrue(view.scale > 5f, "the fixture must actually zoom: ${view.scale}")
        repeat(20) {
            step.fromPointers(90f, 100f, 310f, 110f, 100f, 100f, 300f, 100f)
            view = step.applyTo(view)
        }
        assertEquals(1f, view.scale, 1e-3f)
        assertEquals(0f, view.rotation, 1e-4f)
        assertEquals(0f, view.tx, 1e-2f)
        assertEquals(0f, view.ty, 1e-2f)
    }
}
