package ch.lkmc.bangnidraw.input

import android.os.Build
import android.view.MotionEvent
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.FitTransform
import ch.lkmc.bangnidraw.engine.core.GestureArbiter
import ch.lkmc.bangnidraw.engine.core.NavigationTarget
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.PressureCalibration
import ch.lkmc.bangnidraw.engine.core.PressureCurve
import ch.lkmc.bangnidraw.engine.core.PressurePreference
import ch.lkmc.bangnidraw.engine.core.RotationSnap
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §3.2's handler row, driven through the primitive
 * `handle*` path.
 *
 * **What this cannot cover:** `MotionEvent` cannot be constructed in a JVM unit
 * test, so `onTouch`'s translation — action masking, historical samples, tool
 * types, axis lookups — is device-only and is stated as such rather than
 * mocked. Two things added in roadmap 2.5b/2.5c sit squarely in that gap and
 * are named rather than left to look covered: **unbuffered dispatch**, which is
 * two `View.requestUnbufferedDispatch` calls whose only observable effect is
 * the *rate* events arrive at, and the **predicted tail's** per-frame path,
 * which needs a real predictor and a `Choreographer`. The orientation
 * conversion those paths share IS reachable here, through `handle*`, and is
 * tested below. Everything the handler *decides* is reachable here, which is
 * why the logic lives on methods that take primitives.
 */
class CanvasTouchHandlerTest {

    private class Host : CanvasInputHost {
        var view = ViewTransform()
        val events = mutableListOf<String>()
        /** Every stroke sample, in the canvas px the host is promised. */
        val samples = mutableListOf<Pair<Float, Float>>()
        var lastPressure = -1f
        var lastTilt = -1f
        var lastOrientation = -1f
        var samplesAtEnd = -1
        /** Every sample's timestamp, so the opening pair's dt is checkable. */
        val times = mutableListOf<Long>()
        val referencePans = mutableListOf<Pair<Float, Float>>()
        /** Muted during the allocation gate's measured window, so the harness costs nothing. */
        var record = true
        override fun onViewChanged(view: ViewTransform) { this.view = view; if (record) events += "view" }
        override fun onViewportResized(view: ViewTransform) {
            this.view = view
            if (record) events += "resize"
        }
        override fun onRotationSnapped() { events += "snap" }
        override fun onUndoRequested() { events += "undo" }
        override fun onRedoRequested() { events += "redo" }
        override fun onColorPick(x: Float, y: Float) { events += "pick" }
        override fun onStrokeBegin(pointerId: Int, source: StrokeSource) { events += "begin($source)" }
        override fun onStrokeSample(
            x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, timeNs: Long,
        ) {
            // Muted with the rest of the recorder during the allocation gate:
            // `x to y` is a Pair, and the gate must measure the handler rather
            // than the harness that watches it.
            if (record) { samples += x to y; times += timeNs }
            lastPressure = pressure
            lastTilt = tilt
            lastOrientation = orientation
        }
        override fun onStrokeEnd(pointerId: Int) {
            samplesAtEnd = samples.size
            events += "end"
        }
        override fun onStrokeCancel() { events += "cancel" }
        override fun onNavigateActive(active: Boolean) { events += if (active) "nav+" else "nav-" }
        override fun onReferenceGesture(
            pivotX: Float,
            pivotY: Float,
            panX: Float,
            panY: Float,
            zoom: Float,
            rotationDelta: Float,
        ) {
            referencePans += panX to panY
        }
    }

    private fun ms(v: Long) = v * 1_000_000L
    private fun handler(host: Host) = CanvasTouchHandler(density = 2f, host = host)

    @Test
    fun `viewport changes preserve the source point at center`() {
        val host = Host()
        val h = handler(host)
        val canvas = CanvasSize(1000, 500)
        val oldFit = FitTransform(1000f, 1000f, 1000f, 500f)
        h.setViewport(canvas, width = 1000, height = 1000)
        h.setView(ViewTransform(scale = 2f, rotation = 0.2f, tx = 80f, ty = -40f))
        val oldCanvas = h.view.invert(oldFit.viewWidth / 2f, oldFit.viewHeight / 2f)
        val oldUv = oldFit.viewToUv(oldCanvas.first, oldCanvas.second)

        val newFit = FitTransform(600f, 1000f, 1000f, 500f)
        h.setViewport(canvas, width = 600, height = 1000)
        val newCanvas = h.view.invert(newFit.viewWidth / 2f, newFit.viewHeight / 2f)
        val newUv = newFit.viewToUv(newCanvas.first, newCanvas.second)

        assertEquals(oldUv.first, newUv.first, 1e-5f)
        assertEquals(oldUv.second, newUv.second, 1e-5f)
        assertTrue(host.events.contains("resize"), "resize must publish the rebased transform")
        assertTrue("view" !in host.events, "resize must not use the navigation callback")
    }

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
    fun `reference edit routes navigation without moving the canvas`() {
        val host = Host()
        val h = handler(host)
        h.setViewport(CanvasSize(1_000, 1_000), width = 1_000, height = 1_000)
        h.navigationTarget = NavigationTarget.TRACING_REFERENCE
        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 100f, ms(10))
        h.handleMove(1, 150f, 100f, ms(30))
        h.handleMove(2, 350f, 100f, ms(30))
        h.handleMoveEnd(ms(30))

        assertTrue(h.view.isIdentity)
        assertEquals(50f, host.referencePans.single().first, 0.001f)
        assertEquals(0f, host.referencePans.single().second, 0.001f)
        assertTrue(
            host.events.none { it.startsWith("begin") || it == "cancel" },
            "reference navigation has no stroke to cancel: ${host.events}",
        )
    }

    @Test
    fun `one finger in reference edit emits no stroke lifecycle`() {
        val host = Host()
        val h = handler(host)
        h.navigationTarget = NavigationTarget.TRACING_REFERENCE

        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(0))
        h.handleTick(ms(GestureArbiter.PENDING_MS + 1L))
        h.handleUp(1, ms(GestureArbiter.PENDING_MS + 2L))

        assertTrue(host.events.none { it.startsWith("begin") || it == "end" || it == "cancel" })
    }

    @Test
    fun `navigation activity reaches the host`() {
        val host = Host()
        val h = handler(host)
        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 100f, ms(10))
        h.handleMove(1, 150f, 100f, ms(30))
        h.handleMove(2, 350f, 100f, ms(30))
        h.handleMoveEnd(ms(30))
        assertTrue(host.events.contains("nav+"), "entering navigation must tell the host: ${host.events}")

        h.handleUp(1, ms(40))
        h.handleUp(2, ms(41))
        assertTrue(host.events.contains("nav-"), "leaving navigation must tell the host: ${host.events}")
    }

    @Test
    fun `a cancelled navigation still reports its end`() {
        val host = Host()
        val h = handler(host)
        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 100f, ms(10))
        h.handleMove(1, 150f, 100f, ms(30))
        h.handleMove(2, 350f, 100f, ms(30))
        h.handleMoveEnd(ms(30))
        host.events.clear()

        h.handleCancel(ms(50))
        assertTrue(host.events.contains("nav-"), "cancel must end navigation for the host: ${host.events}")
    }

    @Test
    fun `a platform-cancelled stroke lift cancels instead of ending`() {
        val host = Host()
        val h = handler(host)
        h.handleDown(7, PointerTool.STYLUS, 100f, 100f, ms(0))
        host.events.clear()

        assertTrue(
            h.handlePlatformCancellation(
                pointerId = 7,
                action = MotionEvent.ACTION_UP,
                flags = MotionEvent.FLAG_CANCELED,
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                timeNs = ms(1),
            ),
        )
        h.handleUp(7, ms(2))

        assertEquals(listOf("cancel"), host.events)
    }

    @Test
    fun `a platform-cancelled palm lift preserves the stylus stroke`() {
        val host = Host()
        val h = handler(host)
        h.handleDown(7, PointerTool.STYLUS, 100f, 100f, ms(0))
        h.handleDown(9, PointerTool.FINGER, 300f, 300f, ms(1))
        host.events.clear()

        assertFalse(
            h.handlePlatformCancellation(
                pointerId = 9,
                action = MotionEvent.ACTION_POINTER_UP,
                flags = MotionEvent.FLAG_CANCELED,
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                timeNs = ms(2),
            ),
        )
        h.handleUp(9, ms(2))
        h.handleUp(7, ms(3))

        assertEquals(listOf("end"), host.events)
    }

    @Test
    fun `a cancellation flag on a move does not cancel the stroke`() {
        val host = Host()
        val h = handler(host)
        h.handleDown(7, PointerTool.STYLUS, 100f, 100f, ms(0))
        host.events.clear()

        assertFalse(
            h.handlePlatformCancellation(
                pointerId = 7,
                action = MotionEvent.ACTION_MOVE,
                flags = MotionEvent.FLAG_CANCELED,
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                timeNs = ms(1),
            ),
        )
        h.handleUp(7, ms(2))

        assertEquals(listOf("end"), host.events)
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
        // A second move to -0.23 puts raw at 0.07, which is strictly BETWEEN
        // SNAP_IN (0.0524) and SNAP_OUT (0.0873) — so only the wider exit band
        // keeps it snapped, and the hysteresis gap is what this move tests.
        // -0.26 would have put raw at 0.04, still inside SNAP_IN, where the
        // test would pass even with SNAP_OUT == SNAP_IN.
        //
        // The fixture before that rotated the pair back to horizontal, putting
        // raw at 0.3 and LEAVING the snap — which is why every assertion below
        // used to sit behind an `if` that never ran.
        h.handleMove(2, 294.7333f, 154.4045f, ms(50))
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
    fun `stylus pressure uses the selected preference but finger pressure stays full`() {
        val host = Host()
        val h = handler(host)
        val curve = PressureCurve.of(
            PressureCalibration.NONE,
            PressurePreference.SOFTER,
        )
        h.pressureCurve = curve

        h.handleDown(9, PointerTool.STYLUS, 50f, 50f, ms(0), pressure = 0.25f)
        assertEquals(curve.apply(0.25f), host.lastPressure, 1e-6f)
        h.handleUp(9, ms(20))

        val fingerDownMs = StylusState.HOVER_GRACE_MS + 40L
        h.handleDown(1, PointerTool.FINGER, 50f, 50f, ms(fingerDownMs), pressure = 0.25f)
        h.handleTick(ms(fingerDownMs + GestureArbiter.PENDING_MS))
        assertEquals(1f, host.lastPressure, "finger drawing ignores capacitive pressure")
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
        // The tap legitimately passes through Navigate (the second finger
        // enters it, the lifts end it), so the host's navigation readout sees
        // one full transition before the tap resolves to undo — the interface
        // promises exactly once per transition, and the UI hides the readout
        // for blips this short.
        assertEquals(
            listOf("nav+", "nav-", "undo"),
            host.events,
            "a tap must not tell the host to roll back a stroke that never began",
        )
        assertTrue(host.view.isIdentity, "a tap must not nudge the view")
    }

    @Test
    fun `cancel rolls the stroke back and leaves the view alone`() {
        val host = Host()
        val h = handler(host)
        // A NON-identity view, which is what makes the second assertion a test
        // (REVIEW.md R-052). It used to assert `isIdentity` from an identity
        // fixture, so it pinned the value the handler started with rather than
        // the invariance its own name claims: a cancel that reset the view to
        // identity — the exact bug worth catching — would have passed.
        val before = ViewTransform(scale = 2.5f, rotation = 0.4f, tx = 30f, ty = -12f)
        h.setView(before)
        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(0))
        h.handleTick(ms(GestureArbiter.PENDING_MS))
        host.events.clear()
        h.handleCancel(ms(200))
        assertEquals(listOf("cancel"), host.events)
        // The second half of this test's own name, which it did not check.
        //
        // On the HANDLER's view, not the host's. host.view is only ever written
        // by onViewChanged, and the assertion above already proves no "view"
        // event fired — so host.view.isIdentity is implied by it and cannot
        // fail independently. Asserting there would read as coverage while
        // adding none, and would be blind to exactly the case worth pinning:
        // a cancel that mutates the transform WITHOUT emitting. Mutation-checked
        // both ways (REVIEW.md R-051).
        assertEquals(before, h.view, "a cancel must not nudge the view")
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
        val samplesBeforePalmUp = host.samples.size
        h.handleUp(
            2, 400f, 400f, ms(20),
            pressure = 1f, tilt = 0f, orientation = 0f,
        )

        assertTrue(h.stylus.isDown, "the pen is still on the glass after the palm lifts")
        assertEquals(
            samplesBeforePalmUp,
            host.samples.size,
            "a non-drawing pointer's final sample must not enter the pen stroke",
        )
        assertTrue(
            PalmRejection.rejects(PointerTool.FINGER, h.stylus, ms(20) + 5_000_000_000L),
            "fingers must stay rejected while the pen is down, however long after",
        )

        h.handleUp(1, ms(30))
        assertTrue(!h.stylus.isDown, "the pen's own lift does end contact")
    }

    @Test
    fun `a drawing pointer's up sample reaches the stroke before it ends`() {
        val host = Host()
        val h = handler(host)
        val halfPi = (PI / 2.0).toFloat()
        h.handleDown(
            1, PointerTool.STYLUS, 100f, 100f, ms(0),
            pressure = 0.1f, tilt = 0.1f, orientation = halfPi,
        )

        h.handleUp(
            1, 124f, 108f, ms(10),
            pressure = 0.8f, tilt = 0.4f, orientation = halfPi,
        )

        assertEquals(2, host.samplesAtEnd, "the final sample must precede onStrokeEnd")
        assertEquals(124f to 108f, host.samples.last())
        assertEquals(PressureCurve.of().apply(0.8f), host.lastPressure, 1e-6f)
        assertEquals(0.4f, host.lastTilt, 1e-6f)
        assertEquals(0f, host.lastOrientation, 1e-6f, "screen-right is canvas +x")
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

    @Test
    fun `stroke samples reach the host in canvas pixels, not view pixels`() {
        // §6's pipeline inverts the view before the samples leave this class,
        // and it has to: a brush size is in canvas px so a pencil is the same
        // width on the paper at any zoom. Forwarding view px would make every
        // brush scale with the zoom.
        val host = Host()
        val h = handler(host)
        h.stylusOnly = false
        h.setView(ViewTransform(scale = 2f, tx = 100f, ty = 50f))
        h.handleDown(1, PointerTool.FINGER, 300f, 250f, ms(0))
        h.handleTick(ms(GestureArbiter.PENDING_MS))   // resolve the pending window into a draw
        h.handleMove(1, 500f, 450f, ms(30))
        h.handleMoveEnd(ms(30))

        assertTrue(host.samples.size >= 2, "a draw must emit samples, got ${host.samples}")
        // (500,450) in view px, with scale 2 and translation (100,50), is
        // (200,200) on the canvas.
        val (x, y) = host.samples.last()
        assertEquals(200f, x, 1e-3f, "sample x must be canvas px")
        assertEquals(200f, y, 1e-3f, "sample y must be canvas px")
        // The opening down under the same transform: (300,250) view px at
        // scale 2 with translation (100,50) is (100,100) on the canvas. Without
        // this, a regression that inverted moves but emitted the down in view
        // px would pass both this test and the identity-view one.
        val (downX, downY) = host.samples.first()
        assertEquals(100f, downX, 1e-3f, "the opening down must be canvas px too")
        assertEquals(100f, downY, 1e-3f, "the opening down must be canvas px too")
    }

    @Test
    fun `stroke samples invert the fitted canvas letterbox`() {
        val host = Host()
        val h = handler(host)
        h.stylusOnly = false
        h.setViewport(CanvasSize(1000, 500), width = 1000, height = 1000)

        h.handleDown(1, PointerTool.FINGER, 100f, 350f, ms(0))
        h.handleTick(ms(GestureArbiter.PENDING_MS))

        assertEquals(100f, host.samples.single().first, 1e-3f)
        assertEquals(100f, host.samples.single().second, 1e-3f)
    }

    @Test
    fun `the down that opens a stroke is itself a sample`() {
        // Without it a slow tap-and-hold leaves no mark at all, and a fast
        // stroke visibly starts at its second sample.
        val host = Host()
        val h = handler(host)
        h.handleDown(1, PointerTool.FINGER, 10f, 20f, ms(0))
        h.handleTick(ms(GestureArbiter.PENDING_MS))
        assertEquals(1, host.samples.size, "the opening down must emit one sample")
        assertEquals(10f to 20f, host.samples.first())
    }

    @Test
    fun `a navigating gesture emits no stroke samples`() {
        val host = Host()
        val h = handler(host)
        // Pinned, or the test passes for the wrong reason if the default ever
        // flips: in stylus-only mode a finger is refused before the arbiter or
        // palm rejection runs at all, and their coverage vanishes silently.
        h.stylusOnly = false
        h.handleDown(1, PointerTool.FINGER, 100f, 200f, ms(0))
        h.handleDown(2, PointerTool.FINGER, 300f, 200f, ms(10))
        h.handleMove(1, 150f, 200f, ms(30))
        h.handleMove(2, 350f, 200f, ms(30))
        h.handleMoveEnd(ms(30))
        assertTrue(host.samples.isEmpty(), "a two-finger pan must not draw: ${host.samples}")
    }

    @Test
    fun `a rejected palm emits no stroke samples`() {
        val host = Host()
        val h = handler(host)
        h.stylusOnly = false
        h.handleDown(1, PointerTool.STYLUS, 50f, 50f, ms(0))   // the pen owns the gesture
        host.samples.clear()
        h.handleDown(2, PointerTool.FINGER, 400f, 400f, ms(5)) // the palm
        h.handleMove(2, 410f, 400f, ms(20))
        h.handleMoveEnd(ms(20))
        assertTrue(host.samples.isEmpty(), "a palm must not draw: ${host.samples}")
    }

    @Test
    fun `the drawing pointer's own axes reach the host, not another pointer's`() {
        // The regression this pins: for ACTION_MOVE the action's pointer-index
        // bits are always zero, so axes read at `actionIndex` gave EVERY
        // pointer the first pointer's values — wrong exactly in the setup this
        // class exists for, a palm resting as pointer 0 and the pen drawing as
        // pointer 1.
        //
        // Two pointers with different axes, and the drawing one is not the
        // first: with one pointer down the two readings coincide and the test
        // could not tell a correct selection from the buggy one. The axes are
        // parameters of handleMove precisely so this is decidable here rather
        // than only on a device.
        val host = Host()
        val h = handler(host)
        h.stylusOnly = false
        // The palm lands first and owns index 0.
        h.handleDown(1, PointerTool.FINGER, 400f, 400f, ms(0), pressure = 0.1f, tilt = 0f, orientation = 0.1f)
        h.handleTick(ms(GestureArbiter.PENDING_MS))
        host.samples.clear()

        // Then the pen, which takes the gesture over (§5) and does the drawing.
        h.handleDown(2, PointerTool.STYLUS, 100f, 100f, ms(200), pressure = 0.9f, tilt = 0.3f, orientation = 0.7f)
        h.handleMove(1, 401f, 400f, ms(230), pressure = 0.1f, tilt = 0f, orientation = 0.1f) // the palm
        h.handleMove(2, 140f, 100f, ms(230), pressure = 0.75f, tilt = 0.4f, orientation = 0.6f)
        h.handleMoveEnd(ms(230))

        // "The palm is ignored" was a comment, not an assertion, and the
        // assertions below could not have caught a leak: the pen's move is
        // processed last, so it overwrites lastPressure/lastTilt whether or not
        // the palm emitted anything first. The palm sits at x ~ 400 and the pen
        // at x <= 140, and the view is identity here, so the split is clean.
        // This is the SUPERSEDED case — a finger the pen took over from —
        // which `a rejected palm emits no stroke samples` does not cover.
        assertTrue(
            host.samples.none { it.first > 300f },
            "the superseded palm must not emit samples: ${host.samples}",
        )
        assertEquals(
            PressureCurve.of().apply(0.75f), host.lastPressure, 1e-6f,
            "the sample must carry the normalized PEN pressure, not the palm's",
        )
        assertEquals(0.4f, host.lastTilt, 1e-6f, "and the pen's tilt")
        assertEquals(
            0.6f - (PI / 2.0).toFloat(),
            host.lastOrientation,
            1e-6f,
            "and the pen's converted orientation",
        )
    }

    @Test
    fun `Android stylus azimuth becomes an x-axis canvas angle`() {
        // Android measures from screen-up; the engine measures from canvas +x.
        val host = Host()
        val h = handler(host)
        h.setView(ViewTransform(rotation = 0.5f))
        h.handleDown(1, PointerTool.STYLUS, 100f, 100f, ms(0), orientation = 1.2f)
        h.handleMove(1, 120f, 100f, ms(10), orientation = 1.2f)
        h.handleMoveEnd(ms(10))

        val expected = 1.2f - (PI / 2.0).toFloat() - 0.5f
        assertEquals(
            expected, host.lastOrientation, 1e-6f,
            "screen-up needs a -pi/2 basis conversion before view rotation",
        )
    }

    @Test
    fun `a canvas-relative azimuth is wrapped, not left to run past pi`() {
        // Basis conversion and view rotation can still leave an angle past pi.
        val host = Host()
        val h = handler(host)
        h.setView(ViewTransform(rotation = -2.9f))
        h.handleDown(1, PointerTool.STYLUS, 100f, 100f, ms(0), orientation = 2.9f)
        h.handleMove(1, 120f, 100f, ms(10), orientation = 2.9f)
        h.handleMoveEnd(ms(10))

        assertTrue(
            host.lastOrientation > -PI.toFloat() && host.lastOrientation <= PI.toFloat(),
            "orientation must land in (-pi, pi], was ${host.lastOrientation}",
        )
        assertEquals(
            (5.8f - (PI / 2.0).toFloat() - 2f * PI.toFloat()),
            host.lastOrientation,
            1e-5f,
            "the converted canvas angle must wrap back into range",
        )
    }

    @Test
    fun `a finger stroke that resolves on the clock carries its own axes`() {
        // The same defect as the test above, reached through the CLOCK rather
        // than through an event — and this is the path that made per-pointer
        // storage necessary rather than merely tidy.
        //
        // `GestureArbiter.tick` resolves the pending window by calling
        // `beginFingerDraw`, whose `onDraw` emits the stroke's opening sample.
        // `handleMoveEnd` calls `tick` AFTER the whole event's pointers have
        // been fed, so a single set of axis fields would by then hold the
        // last-processed pointer's values. With a palm moving last, the finger
        // stroke opened at the palm's pressure — every time.
        //
        // The setup is the one §5 describes: a palm lands while the pen is
        // hovering (so the arbiter ignores it), the pen goes away, its grace
        // expires, and the user then draws with a finger while the palm still
        // rests on the glass.
        val host = Host()
        val h = handler(host)
        h.stylusOnly = false

        h.stylus.onHoverEnter(300f, 300f, 5f, PointerTool.STYLUS)
        // 0.1 pressure, and ignored: this is the pointer whose axes must NOT
        // reach the host.
        h.handleDown(1, PointerTool.FINGER, 400f, 400f, ms(0), pressure = 0.1f, tilt = 0f, orientation = 0.1f)
        h.stylus.onHoverExit(ms(10))

        // Past HOVER_GRACE_MS, so the next finger is no longer a palm.
        h.handleDown(2, PointerTool.FINGER, 100f, 100f, ms(1000), pressure = 0.9f, tilt = 0.3f, orientation = 0.7f)
        // Both move, the drawing finger under the tap slop so the window is
        // still open — and the palm last, which is what poisons a shared field.
        h.handleMove(2, 102f, 100f, ms(1010), pressure = 0.8f, tilt = 0.25f, orientation = 0.55f)
        h.handleMove(1, 401f, 400f, ms(1010), pressure = 0.1f, tilt = 0f, orientation = 0.1f)
        host.samples.clear()

        // 130 ms held: past PENDING_MS, so the tick inside handleMoveEnd
        // resolves the window and opens the stroke.
        h.handleMoveEnd(ms(1130))

        assertTrue("begin(FINGER)" in host.events, "the pending window must resolve into a stroke: ${host.events}")
        assertEquals(1, host.samples.size, "opening the stroke must emit exactly one sample")
        assertEquals(
            1f, host.lastPressure, 1e-6f,
            "finger drawing must ignore capacitive pressure",
        )
        assertEquals(0.25f, host.lastTilt, 1e-6f, "and the drawing finger's tilt")
        assertEquals(
            0.55f - (PI / 2.0).toFloat(),
            host.lastOrientation,
            1e-6f,
            "and the drawing finger's converted orientation",
        )
    }

    @Test
    fun `a stroke opened from a move keeps the down point's own timestamp`() {
        // The opening sample is the position the pointer went DOWN at — the
        // arbiter only resolves "draw" once the finger has crossed the slop,
        // several ms later. Stamping it with the resolving move's time claimed
        // the finger covered that distance in zero elapsed time.
        //
        // DabGenerator.updateVelocity does survive that: it has an explicit
        // `elapsedNs <= 0` branch that defers the distance instead of dividing
        // by it, and StrokeInput.timeNs documents the debt. So this is an
        // accuracy fix, not a crash fix — the opening segment's speed goes
        // from "unmeasurable, carried forward" to simply measured.
        val host = Host()
        val h = handler(host)
        h.stylusOnly = false
        h.handleDown(1, PointerTool.FINGER, 100f, 100f, ms(0))
        // Past TAP_SLOP_DP * density = 16 px, so the arbiter opens the stroke
        // from inside this very call.
        h.handleMove(1, 140f, 100f, ms(30))
        h.handleMoveEnd(ms(30))

        assertTrue("begin(FINGER)" in host.events, "crossing the slop must open a stroke: ${host.events}")
        assertEquals(2, host.samples.size, "the opening pair is the down point and the current one")
        assertEquals(100f to 100f, host.samples[0], "the first sample is the finger-down point")
        assertEquals(
            ms(0), host.times[0],
            "and carries the time it happened at, not the time it was noticed at",
        )
        assertEquals(ms(30), host.times[1], "the live sample carries its own move's time")
        assertTrue(
            host.times[1] > host.times[0],
            "the opening segment must have a positive duration: ${host.times}",
        )
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
