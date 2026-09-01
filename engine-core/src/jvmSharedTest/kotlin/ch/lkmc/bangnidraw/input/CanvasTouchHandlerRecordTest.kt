package ch.lkmc.bangnidraw.input

import ch.lkmc.bangnidraw.engine.core.ButtonState
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.PressureCurve
import ch.lkmc.bangnidraw.engine.core.StrokeInputBatch
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The record surface (`PointerSample` → `onPointer*`) that the Android
 * `MotionEvent` adapter and a desktop host both drive — the mapping half of
 * DESKTOP.md seam 3 that a JVM can reach. Each entry is a thin wrapper over
 * the primitive `handle*` path, so these pin the wrapper, not the (already
 * pinned) decisions: the sample's axes reach the host unchanged, the
 * opening and lifting samples survive, hover updates the shared stylus, and
 * the predicted tail flows through the [StrokePredictor] seam in the order
 * the truncation depends on.
 *
 * What this still cannot cover is the reading of a platform event into the
 * sample — `MotionEvent` itself remains device-gated, exactly as
 * `11-testing.md` §3.2 records for the handler.
 */
class CanvasTouchHandlerRecordTest {

    private class Host : CanvasInputHost {
        val events = mutableListOf<String>()
        val samples = mutableListOf<FloatArray>()
        val times = mutableListOf<Long>()
        var predicted: StrokeInputBatch? = null
        var hoverChanged = 0

        override fun onViewChanged(view: ViewTransform) { events += "view" }
        override fun onRotationSnapped() { events += "snap" }
        override fun onUndoRequested() { events += "undo" }
        override fun onRedoRequested() { events += "redo" }
        override fun onColorPick(x: Float, y: Float) { events += "pick" }
        override fun onStrokeBegin(pointerId: Int, source: StrokeSource) { events += "begin($source)" }
        override fun onStrokeSample(
            x: Float,
            y: Float,
            pressure: Float,
            tilt: Float,
            orientation: Float,
            timeNs: Long,
        ) {
            samples += floatArrayOf(x, y, pressure, tilt, orientation)
            times += timeNs
        }

        override fun onStrokeEnd(pointerId: Int) { events += "end" }
        override fun onStrokeCancel() { events += "cancel" }
        override fun onStrokePredicted(batch: StrokeInputBatch) {
            predicted = batch
            events += "predicted(${batch.size})"
        }

        override fun onHoverChanged() { hoverChanged++ }
    }

    /** A queued next-frame poster: callbacks run on `pump`, one pass each. */
    private class QueuedFrameScheduler : FrameScheduler {
        val queued = ArrayDeque<Runnable>()

        override fun post(callback: Runnable) { queued += callback }
        override fun cancel(callback: Runnable) { queued.remove(callback) }

        fun pump() {
            // One frame's worth: drain what running this frame posts for the
            // next, without following the repost chain inline.
            val thisFrame = ArrayDeque(queued)
            queued.clear()
            while (thisFrame.isNotEmpty()) thisFrame.removeFirst().run()
        }
    }

    /** Serves a fixed nearest-first tail for the drawing pointer. */
    private class FakePredictor(vararg val timesNs: Long) : StrokePredictor {
        override val isUsable = true
        private val samples = Array(timesNs.size) { PointerSample() }
        private var count = 0
        var askedForPointer: Int = -1

        override fun predict(pointerId: Int): Int {
            askedForPointer = pointerId
            count = 0
            samples.forEachIndexed { i, s ->
                s.set(
                    pointerId = pointerId,
                    tool = PointerTool.STYLUS,
                    x = 50f + i,
                    y = 60f,
                    pressure = 0.5f,
                    tilt = 0.25f,
                    orientation = 0f,
                    timeNs = timesNs[i],
                )
            }
            count = timesNs.size
            return count
        }

        override fun predictedAt(index: Int): PointerSample {
            // Mirrors the real Predictor's lifetime tripwire: reading past
            // the producing predict fails here in CI, not on a device.
            require(index in 0 until count) {
                "predictedAt($index) outside the last predict() range (0 until $count)"
            }
            return samples[index]
        }
    }

    private fun handler(host: Host): CanvasTouchHandler {
        val h = CanvasTouchHandler(density = 1f, host = host)
        // Identity, so the record tests assert the mapping rather than the
        // user-selected curve's arithmetic (the curve itself is pinned by
        // PressureCurve's own tests).
        h.pressureCurve = PressureCurve.IDENTITY
        h.setViewport(CanvasSize(100, 100), 100, 100)
        return h
    }

    private val sample = PointerSample()

    private fun stylusDown(h: CanvasTouchHandler, timeNs: Long = 0L) {
        h.onPointerDown(
            sample.set(7, PointerTool.STYLUS, 10f, 20f, pressure = 0.5f, tilt = 0.25f, orientation = 0.5f, timeNs = timeNs),
        )
    }

    @Test
    fun `a mouse record opens immediately without stylus proximity`() {
        val host = Host()
        val h = handler(host)

        h.onPointerDown(
            sample.set(9, PointerTool.MOUSE, 10f, 20f, 1f, 0f, 0f, 0L),
        )
        h.onPointerUp(
            sample.set(9, PointerTool.MOUSE, 10f, 20f, 1f, 0f, 0f, 1_000_000L),
        )

        assertEquals(listOf("begin(MOUSE)", "end"), host.events)
        assertFalse(h.stylus.isNear(1_000_000L))
    }

    @Test
    fun `a record down opens the stroke at the tracked sample`() {
        val host = Host()
        val h = handler(host)

        stylusDown(h)

        assertEquals(listOf("begin(STYLUS)"), host.events)
        assertEquals(1, host.samples.size, "the down itself is a sample — a tap must leave a mark")
        assertEquals(10f, host.samples[0][0])
        assertEquals(20f, host.samples[0][1])
        assertEquals(0.5f, host.samples[0][2], "pressure rides the record unchanged")
        assertEquals(0.25f, host.samples[0][3], "tilt rides the record unchanged")
    }

    @Test
    fun `record moves carry their axes and move ends close the event`() {
        val host = Host()
        val h = handler(host)
        stylusDown(h)

        h.onPointerMove(sample.set(7, PointerTool.STYLUS, 30f, 40f, 0.75f, 0.5f, 0.25f, 1_000_000L))
        h.onPointerMoveEnd(1_000_000L)

        assertEquals(2, host.samples.size)
        assertEquals(listOf(0L, 1_000_000L), host.times, "timestamps ride the record unchanged")
        assertEquals(0.75f, host.samples[1][2])
        assertEquals(0.5f, host.samples[1][3])
        // Canvas azimuth: screen orientation minus the quarter-turn basis
        // and the (identity) view rotation, wrapped.
        assertEquals(0.25f - (PI / 2.0).toFloat(), host.samples[1][4], "orientation converts through the same path as handleMove")
    }

    @Test
    fun `a record up emits the lifting sample before ending`() {
        val host = Host()
        val h = handler(host)
        stylusDown(h)

        h.onPointerUp(sample.set(7, PointerTool.STYLUS, 80f, 90f, 0.25f, 0.1f, 0f, 2_000_000L))

        assertEquals(listOf("begin(STYLUS)", "end"), host.events)
        assertEquals(2, host.samples.size)
        assertEquals(80f, host.samples[1][0])
        assertEquals(90f, host.samples[1][1])
    }

    @Test
    fun `a record cancel discards the stroke without an end`() {
        val host = Host()
        val h = handler(host)
        stylusDown(h)

        h.onPointerCancel(1_000_000L)

        assertEquals(listOf("begin(STYLUS)", "cancel"), host.events)
    }

    @Test
    fun `hover records update the shared stylus`() {
        val host = Host()
        val h = handler(host)
        val frames = QueuedFrameScheduler()
        h.frameScheduler = frames

        h.onHoverEnter(sample.setHover(0, PointerTool.ERASER, 10f, 20f, distance = 3f, timeNs = 0L))
        h.onHoverMove(sample.setHover(0, PointerTool.ERASER, 30f, 40f, distance = 2f, timeNs = 1_000_000L))
        h.onHoverExit(2_000_000L)
        frames.pump()

        assertEquals(30f, h.stylus.hoverX)
        assertEquals(40f, h.stylus.hoverY)
        assertEquals(2f, h.stylus.hoverDistance)
        assertEquals(PointerTool.ERASER, h.stylus.tool)
        assertFalse(h.stylus.isHovering)
        assertTrue(h.stylus.isNear(2_000_000L), "the grace window follows a record hover exit")
    }

    @Test
    fun `hover records coalesce to one host notification per frame`() {
        val host = Host()
        val h = handler(host)
        val frames = QueuedFrameScheduler()
        h.frameScheduler = frames

        h.onHoverEnter(sample.setHover(0, PointerTool.STYLUS, 10f, 20f, distance = 3f, timeNs = 0L))
        h.onHoverMove(sample.setHover(0, PointerTool.STYLUS, 30f, 40f, distance = 2f, timeNs = 1_000_000L))
        h.onHoverMove(sample.setHover(0, PointerTool.STYLUS, 50f, 60f, distance = 1f, timeNs = 2_000_000L))
        h.onHoverExit(3_000_000L)

        assertEquals(0, host.hoverChanged, "nothing notifies before the frame runs")
        frames.pump()
        assertEquals(1, host.hoverChanged, "a fast digitizer cannot recompose per sample")
    }

    @Test
    fun `the barrel button record drives the stylus model`() {
        val host = Host()
        val h = handler(host)

        h.onStylusButton(ButtonState.Pressed)
        assertTrue(h.stylus.buttonPressed)
        h.onStylusButton(ButtonState.Released)
        assertFalse(h.stylus.buttonPressed)
    }

    @Test
    fun `the predicted tail flows through the seam nearest-first`() {
        val host = Host()
        val h = handler(host)
        val frames = QueuedFrameScheduler()
        h.frameScheduler = frames
        val base = 10_000_000L
        h.predictor = FakePredictor(base + 4_000_000L, base + 8_000_000L, base + 40_000_000L)

        stylusDown(h, timeNs = base)
        h.onPointerMove(sample.set(7, PointerTool.STYLUS, 30f, 40f, 1f, 0f, 0f, base))
        h.onPointerMoveEnd(base)
        frames.pump()

        assertEquals(listOf("predicted(2)"), host.events.filter { it.startsWith("predicted") })
        val tail = host.predicted!!
        // Nearest-first, and the 40 ms outlier (past §8's lookahead window)
        // is truncated by age measured from the last real sample.
        assertEquals(2, tail.size)
        assertEquals(50f, tail.items[0].x, "nearest sample first")
        assertEquals(51f, tail.items[1].x, "furthest sample last")
        // `take(size)`: the batch's items array is capacity-sized and its
        // untouched slots still hold defaults.
        assertTrue(tail.items.take(tail.size).all { it.predicted && it.source == StrokeSource.STYLUS })
    }

    @Test
    fun `a finger stroke asks the predictor for nothing`() {
        val host = Host()
        val h = handler(host)
        h.frameScheduler = QueuedFrameScheduler()
        val predictor = FakePredictor(1_000_000L)
        h.predictor = predictor

        h.onPointerDown(sample.set(3, PointerTool.FINGER, 10f, 20f, pressure = 1f, tilt = 0f, orientation = 0f, timeNs = 0L))
        h.onPointerUp(sample.set(3, PointerTool.FINGER, 10f, 20f, pressure = 1f, tilt = 0f, orientation = 0f, timeNs = 0L))

        assertEquals(-1, predictor.askedForPointer, "fingers are not predicted in v1 (§8)")
    }
}
