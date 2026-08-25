package ch.lkmc.bangnidraw.input

import ch.lkmc.bangnidraw.engine.core.GestureArbiter
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.RotationSnap
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §3.2's handler row, driven through the primitive
 * `handle*` path.
 *
 * **What this cannot cover:** `MotionEvent` cannot be constructed in a JVM unit
 * test, so `onTouch`'s translation — action masking, historical samples, tool
 * types, axis lookups — is device-only and is stated as such rather than
 * mocked. Everything the handler *decides* is reachable here, which is why the
 * logic lives on methods that take primitives.
 */
class CanvasTouchHandlerTest {

    private class Host : CanvasInputHost {
        var view = ViewTransform()
        val events = mutableListOf<String>()
        /** Muted during the allocation gate's measured window, so the harness costs nothing. */
        var record = true
        override fun onViewChanged(view: ViewTransform) { this.view = view; if (record) events += "view" }
        override fun onRotationSnapped() { events += "snap" }
        override fun onUndoRequested() { events += "undo" }
        override fun onRedoRequested() { events += "redo" }
        override fun onColorPick(x: Float, y: Float) { events += "pick" }
        override fun onStrokeBegin(pointerId: Int, source: StrokeSource) { events += "begin($source)" }
        override fun onStrokeEnd(pointerId: Int) { events += "end" }
        override fun onStrokeCancel() { events += "cancel" }
    }

    private fun ms(v: Long) = v * 1_000_000L
    private fun handler(host: Host) = CanvasTouchHandler(density = 2f, host = host)

    @Test
    fun `two fingers dragging pan the view`() {
        val host = Host()
        val h = handler(host)
        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 100f, ms(10))
        h.handleMove(1, 150f, 100f, ms(30))
        h.handleMove(2, 350f, 100f, ms(30))
        h.handleMoveEnd(ms(30))
        assertTrue(host.events.contains("view"), "a two-finger drag must move the view")
        assertTrue(host.view.tx > 0f, "panning right must move the view right: ${host.view}")
    }

    @Test
    fun `a pinch zooms about the point between the fingers`() {
        val host = Host()
        val h = handler(host)
        h.handleDown(1, PointerTool.FINGER, 100f, 200f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 200f, ms(10))
        // Spread symmetrically about x = 200: the span doubles.
        h.handleMove(1, 0f, 200f, ms(30))
        h.handleMove(2, 400f, 200f, ms(30))
        h.handleMoveEnd(ms(30))
        assertTrue(host.view.scale > 1.5f, "spreading must zoom in: ${host.view.scale}")
        // The centroid stayed put, so the canvas point under it must too.
        val (cx, cy) = host.view.invert(200f, 200f)
        val identity = ViewTransform().invert(200f, 200f)
        assertTrue(abs(cx - identity.first) < 1f && abs(cy - identity.second) < 1f,
            "the point under the fingers must not move: $cx,$cy vs $identity")
    }

    @Test
    fun `rotation snaps to exactly zero near straight, and reports it once`() {
        val host = Host()
        val h = handler(host)
        h.setView(ViewTransform(rotation = 0.3f))
        h.handleDown(1, PointerTool.FINGER, 100f, 200f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 200f, ms(10))
        // The pointer pair starts horizontal, so its separation angle is 0 and
        // the raw angle tracks the view's own 0.3. Rotating the pair to -0.28
        // lands raw at 0.02 — inside SNAP_IN, so this move enters the snap.
        h.handleMove(1, 100f, 200f, ms(30))
        h.handleMove(2, 292.2111f, 144.7289f, ms(30))
        h.handleMoveEnd(ms(30))
        // A second move to -0.26 puts raw at 0.04: past SNAP_IN but inside
        // SNAP_OUT, so it STAYS snapped and must not report a second entry.
        // The old fixture rotated the pair back to horizontal here, which put
        // raw back at 0.3 and left the snap — which is why every assertion
        // below used to sit behind an `if` that never ran.
        h.handleMove(2, 293.2780f, 148.5839f, ms(50))
        h.handleMoveEnd(ms(50))
        assertEquals(0f, host.view.rotation, "near-straight fingers must snap to exactly zero")
        assertTrue("snap" in host.events, "entering the snap must report, for the haptic")
        assertEquals(1, host.events.count { it == "snap" }, "the tick fires once, not per event")
    }

    @Test
    fun `a stylus begins a stroke and a resting finger never does`() {
        val host = Host()
        val h = handler(host)
        h.handleDown(9, PointerTool.STYLUS, 50f, 50f, ms(0))
        assertEquals(listOf("begin(STYLUS)"), host.events)
        h.handleDown(1, PointerTool.FINGER, 400f, 400f, ms(20))
        assertEquals(listOf("begin(STYLUS)"), host.events, "a palm must produce nothing at all")
        h.handleUp(9, ms(300))
        assertTrue("end" in host.events)
    }

    @Test
    fun `a finger is rejected while the pen hovers, and admitted after the grace`() {
        val host = Host()
        val h = handler(host)
        h.stylus.onHoverEnter(10f, 10f, 4f, PointerTool.STYLUS)
        h.stylus.onHoverExit(ms(0))
        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(StylusState.HOVER_GRACE_MS - 10))
        h.handleTick(ms(StylusState.HOVER_GRACE_MS - 10 + GestureArbiter.PENDING_MS))
        assertEquals(emptyList(), host.events, "inside the grace the finger is a palm")

        val host2 = Host()
        val h2 = handler(host2)
        h2.stylus.onHoverEnter(10f, 10f, 4f, PointerTool.STYLUS)
        h2.stylus.onHoverExit(ms(0))
        val after = StylusState.HOVER_GRACE_MS + 10
        h2.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(after))
        h2.handleTick(ms(after + GestureArbiter.PENDING_MS))
        assertEquals(listOf("begin(FINGER)"), host2.events, "past the grace the finger draws")
    }

    @Test
    fun `a two-finger tap asks for undo and moves nothing`() {
        val host = Host()
        val h = handler(host)
        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 100f, ms(10))
        h.handleUp(1, ms(80))
        h.handleUp(2, ms(90))
        assertEquals(
            listOf("undo"),
            host.events,
            "a tap must not tell the host to roll back a stroke that never began",
        )
        assertTrue(host.view.isIdentity, "a tap must not nudge the view")
    }

    @Test
    fun `cancel rolls the stroke back and leaves the view alone`() {
        val host = Host()
        val h = handler(host)
        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(0))
        h.handleTick(ms(GestureArbiter.PENDING_MS))
        host.events.clear()
        h.handleCancel(ms(200))
        assertEquals(listOf("cancel"), host.events)
    }

    @Test
    fun `setView resets the snap accumulator, so the pill's reset does not stick`() {
        // The reset pill springs the view to identity. If the snap kept the old
        // raw angle, the next gesture would start measuring from it and the
        // canvas would jump.
        val host = Host()
        val h = handler(host)
        h.setView(ViewTransform(rotation = 1.2f))
        h.setView(ViewTransform())
        h.handleDown(1, PointerTool.FINGER, 100f, 200f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 200f, ms(10))
        h.handleMove(1, 110f, 200f, ms(30))
        h.handleMove(2, 310f, 200f, ms(30))
        h.handleMoveEnd(ms(30))
        assertTrue(abs(host.view.rotation) < RotationSnap.SNAP_OUT,
            "a pure pan must not inherit a stale rotation: ${host.view.rotation}")
    }

    @Test
    fun `the touch path allocates nothing in steady state`() {
        // `10-performance.md` §2.4 makes this a gate, not an extra: the step-2
        // risk table names it as the mitigation for touch-path GC jank.
        //
        // Measured, not inferred. §3.2 of 11-testing.md describes an indirect
        // proxy (asserting decision instance identity is stable) because it
        // assumed decisions are RETURNED; this handler delivers them through a
        // listener with primitive parameters, so there is no instance to
        // compare — and the honest check is then the real allocation counter.
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        assertTrue(
            bean is com.sun.management.ThreadMXBean && bean.isThreadAllocatedMemorySupported,
            "this JVM cannot measure per-thread allocation, so this gate would be vacuous",
        )
        val counter = bean as com.sun.management.ThreadMXBean
        counter.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id

        val host = Host()
        val h = handler(host)
        // Warm up: the first pass allocates the arbiter's state, the listener,
        // the class init and whatever the JIT wants. Steady state is what §2.4
        // is about — an app that allocated nothing at all could not start.
        repeat(WARMUP) { i ->
            val t = ms(i * 10L)
            h.handleDown(1, PointerTool.FINGER, 100f, 100f, t)
            h.handleDown(2, PointerTool.FINGER, 300f, 100f, t + ms(1))
            h.handleMove(1, 101f + i, 100f, t + ms(2))
            h.handleMove(2, 301f + i, 100f, t + ms(2))
            h.handleMoveEnd(t + ms(2))
            h.handleUp(1, t + ms(3))
            h.handleUp(2, t + ms(4))
        }
        host.events.clear()
        host.record = false

        // The measured window: one full two-finger drag, moves only.
        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 100f, ms(1))
        val before = counter.getThreadAllocatedBytes(thread)
        for (i in 0 until MOVES) {
            h.handleMove(1, 100f + i, 100f, ms(10L + i))
            h.handleMove(2, 300f + i, 100f, ms(10L + i))
            h.handleMoveEnd(ms(10L + i))
        }
        val allocated = counter.getThreadAllocatedBytes(thread) - before

        // The harness is muted above, so this measures the handler alone. The
        // old comment here claimed the recorder allocated "a String per view
        // change" and used that to justify a budget of 160; `"view"` is an
        // interned literal and allocates nothing, so the slack was real and
        // wide enough to admit the very regression the gate exists to catch.
        val budget = MOVES * BYTES_PER_MOVE_BUDGET
        assertTrue(
            allocated < budget,
            "the touch path allocated $allocated bytes over $MOVES moves " +
                "(budget $budget); §2.4 forbids per-sample allocation",
        )
    }

    @Test
    fun `lifting either pinch finger keeps the other one panning`() {
        // The arbiter stays in Navigate until the last pointer lifts, so the
        // survivor must keep driving the canvas whichever finger went up.
        for (lifted in intArrayOf(1, 2)) {
            val survivor = if (lifted == 1) 2 else 1
            val host = Host()
            val h = handler(host)
            h.handleDown(1, PointerTool.FINGER, 100f, 200f, ms(0))
            h.handleDown(2, PointerTool.FINGER, 300f, 200f, ms(10))
            h.handleMove(1, 110f, 200f, ms(30))
            h.handleMove(2, 310f, 200f, ms(30))
            h.handleMoveEnd(ms(30))

            h.handleUp(lifted, ms(40))
            val before = host.view.tx
            host.events.clear()
            h.handleMove(survivor, if (survivor == 1) 160f else 360f, 200f, ms(50))
            h.handleMoveEnd(ms(50))

            assertTrue(
                host.events.contains("view"),
                "lifting pointer $lifted must leave $survivor navigating, got ${host.events}",
            )
            assertTrue(
                host.view.tx > before,
                "the surviving finger must keep panning right: ${host.view.tx} vs $before",
            )
        }
    }

    @Test
    fun `a palm lifting does not end the pen's contact`() {
        // A resting palm is a real pointer that lifts like any other. Ending
        // stylus contact on any up started the hover grace mid-stroke, and
        // once it expired PalmRejection stopped rejecting the palm.
        val host = Host()
        val h = handler(host)
        h.handleDown(1, PointerTool.STYLUS, 100f, 100f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 400f, 400f, ms(5))
        h.handleUp(2, ms(20))

        assertTrue(h.stylus.isDown, "the pen is still on the glass after the palm lifts")
        assertTrue(
            PalmRejection.rejects(PointerTool.FINGER, h.stylus, ms(20) + 5_000_000_000L),
            "fingers must stay rejected while the pen is down, however long after",
        )

        h.handleUp(1, ms(30))
        assertTrue(!h.stylus.isDown, "the pen's own lift does end contact")
    }

    @Test
    fun `a cancel while the pen is down ends its contact, so touch is not dead`() {
        // The pen's own ACTION_UP never arrives after a cancel. With isDown
        // latched true, isNear stayed true forever and PalmRejection rejected
        // every finger from then on — the app looked dead to touch.
        val host = Host()
        val h = handler(host)
        h.handleDown(1, PointerTool.STYLUS, 100f, 100f, ms(0))
        assertTrue(h.stylus.isDown, "precondition: the pen is down")
        h.handleCancel(ms(50))
        assertTrue(!h.stylus.isDown, "a cancel must end pen contact")
        assertTrue(
            !PalmRejection.rejects(PointerTool.FINGER, h.stylus, ms(50) + 10_000_000_000L),
            "fingers must work again once the hover grace expires",
        )
    }

    @Test
    fun `right-angle snapping settles at the right angle, not at zero`() {
        // RotationSnap.nearestTarget returns 90-degree multiples when the pref
        // is on. applyNavigation hardcoded 0f, so the haptic fired near 90 and
        // the canvas was then thrown to straight.
        val host = Host()
        val h = handler(host)
        h.snapRightAngles = true
        val quarter = (PI / 2.0).toFloat()
        h.setView(ViewTransform(rotation = quarter - 0.2f))
        h.handleDown(1, PointerTool.FINGER, 100f, 200f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 200f, ms(10))
        // Rotate the pair by +0.2 rad, landing the raw angle on a right angle.
        val c = cos(0.2f); val sn = sin(0.2f)
        fun rot(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - 200f; val dy = y - 200f
            return Pair(200f + c * dx - sn * dy, 200f + sn * dx + c * dy)
        }
        val (x1, y1) = rot(100f, 200f)
        val (x2, y2) = rot(300f, 200f)
        h.handleMove(1, x1, y1, ms(30))
        h.handleMove(2, x2, y2, ms(30))
        h.handleMoveEnd(ms(30))
        assertEquals(
            quarter, host.view.rotation, 1e-5f,
            "with right angles on, the snap target is 90 degrees, not zero",
        )
        assertTrue("snap" in host.events, "entering the snap must still report for the haptic")
    }

    private companion object {
        const val WARMUP = 200
        const val MOVES = 2000

        /**
         * Tight, and set from a measurement rather than a guess: the floor is
         * 64.5 bytes per loop, which is the two `ViewTransform`s one navigation
         * step allocates — `applyTo` returns one and the snap's `copy` returns
         * another. That is per **event**, not per sample, and §2.4's target is
         * per-sample churn.
         *
         * 80 leaves ~15 bytes of headroom, so a single extra object per pointer
         * move — a per-sample `StrokeInput`, a boxed `Pair`, a lambda — fails
         * the gate: two per loop at the JVM's 16-byte minimum is already 32.
         *
         * Getting the floor to zero means an in-place `applyTo(view, out)` and
         * a mutable `ViewTransform`; `ViewTransform`'s immutability is relied on
         * well outside the touch path, so that is a follow-up, not this PR.
         */
        const val BYTES_PER_MOVE_BUDGET = 80
    }
}
