package ch.lkmc.bangnidraw.input

import android.os.Build
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import ch.lkmc.bangnidraw.engine.core.ButtonState
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.FitTransform
import ch.lkmc.bangnidraw.engine.core.GestureArbiter
import ch.lkmc.bangnidraw.engine.core.GestureListener
import ch.lkmc.bangnidraw.engine.core.LatencyTrace
import ch.lkmc.bangnidraw.engine.core.NavigationStep
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.PredictionGate
import ch.lkmc.bangnidraw.engine.core.PressureCurve
import ch.lkmc.bangnidraw.engine.core.RotationSnap
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.engine.core.StrokeInputBatch
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.StylusButtonPolicy
import ch.lkmc.bangnidraw.engine.core.ViewTransform

/**
 * What the canvas does with pointers — the callbacks a host implements.
 *
 * Strokes are declared here and consumed in roadmap 2.4b: 2.4a wires only the
 * navigation half, because `StrokeBuffer`, `DabPass` and `MergePass` do not
 * exist yet. Declaring them now rather than later keeps the handler's shape
 * honest — the arbiter already emits Draw decisions and the tests already
 * exercise them.
 */
interface CanvasInputHost {
    fun onViewChanged(view: ViewTransform)

    /** Fired once on entering the rotation snap, for the haptic tick (§7). */
    fun onRotationSnapped()

    fun onUndoRequested()
    fun onRedoRequested()

    /** Sample the colour under the finger (stylus-only long press). */
    fun onColorPick(x: Float, y: Float)

    /** Coalesced to one callback per frame while hover state changes. */
    fun onHoverChanged() {}

    /** Roadmap 2.4b. A stroke began with [source] at this pointer. */
    fun onStrokeBegin(pointerId: Int, source: StrokeSource) {}

    /** Roadmap 2.4b. One or more samples, already in canvas px. */
    fun onStrokeSample(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, timeNs: Long) {}

    fun onStrokeEnd(pointerId: Int) {}

    /** No history entry, no pixels: the stroke never happened (§4). */
    fun onStrokeCancel() {}

    /**
     * Roadmap 2.5b. One frame's predicted tail, in canvas px
     * (`07-input-and-stylus.md` §8, `03-canvas-engine.md` §9).
     *
     * Called at most once per frame while a stylus stroke is live, and each
     * call **replaces** the previous tail rather than extending it. The batch
     * is the handler's and is reused: read it inside the call, keep nothing.
     *
     * Nothing here ever reaches the stroke buffer or the layer — the host runs
     * it through a copy of the stroke's state and draws it in the front layer
     * only.
     *
     * **A frame with nothing to draw calls nothing**, so "replaces the previous
     * tail" cannot be the host's only way of losing one: the last tail of a
     * stroke is never replaced by a later call. The host drops it itself at
     * [onStrokeEnd] and [onStrokeCancel] — `CanvasRenderer.clearTail` on both
     * paths, under a `commit()`/`cancel()` that takes the front layer with it —
     * and any future clear or undo owes the same. A host that waited for the
     * next `onStrokePredicted` would leave a tip drawn out past the real
     * endpoint after every stylus lift.
     */
    fun onStrokePredicted(samples: StrokeInputBatch) {}
}

/**
 * The **only** code that touches `MotionEvent`
 * (`docs/plan/07-input-and-stylus.md` §2, `02-architecture.md` §2.6).
 *
 * It translates events into primitives and feeds [GestureArbiter],
 * [StylusState] and [PalmRejection], then turns navigation decisions into
 * [ViewTransform] steps. Everything it decides lives in `engine/core`; what is
 * here is the translation and the wiring.
 *
 * **Zero allocation in [onTouch]** (`10-performance.md` §2.4). The pointer
 * scratch arrays, the [NavigationStep] and the arbiter's listener are all
 * fields; nothing on this path returns an object, takes a lambda, or boxes.
 * The handler's logic is reachable from the JVM through the `handle*` methods
 * below, which take primitives — `MotionEvent` cannot be constructed in a unit
 * test, so a handler that only had `onTouch` would be untestable, and its
 * wiring is exactly the part worth testing.
 */
class CanvasTouchHandler(
    density: Float,
    private val host: CanvasInputHost,
) : View.OnTouchListener, View.OnHoverListener {

    val stylus = StylusState()
    val arbiter = GestureArbiter(density)
    private val snap = RotationSnap()
    private val step = NavigationStep()

    var view: ViewTransform = ViewTransform()
        private set

    private var fit: FitTransform? = null
    private var screen: ScreenTransform? = null

    val canvasToScreenScale: Float
        get() = screen?.effectiveScale ?: view.scale

    var stylusOnly: Boolean
        get() = arbiter.stylusOnly
        set(value) { arbiter.stylusOnly = value }

    /** Device pressure normalization selected in Settings. */
    var pressureCurve: PressureCurve = PressureCurve.of()

    /**
     * `Prefs.snapRightAngles` (§7). Off by default.
     *
     * Exposed because [snap] is private: without this the pref had no way to
     * reach the snap at all, so the feature was unreachable as well as broken.
     */
    var snapRightAngles: Boolean
        get() = snap.snapRightAngles
        set(value) { snap.snapRightAngles = value }

    /** Live navigation pointers, and where they were on the previous move. */
    private val navIds = IntArray(2) { NO_POINTER }
    private val prevX = FloatArray(2)
    private val prevY = FloatArray(2)

    /**
     * The last position **and axes** of every tracked pointer, so a move has a
     * previous and a decision has the right pointer's pressure.
     *
     * The axes live here rather than in three fields for the same reason the
     * position does: the arbiter can decide "draw" from the *clock* rather than
     * from an event — `beginFingerDraw` fires out of `tick`, which
     * [handleMoveEnd] calls after the whole event's pointers have been fed. A
     * single set of axis fields would by then hold the last-processed pointer's
     * values, which for a palm-plus-pen event is the palm's, and that opening
     * sample would carry them. Same defect class as the `actionIndex` bug the
     * axis parameters fixed, reached through the clock instead of the event.
     *
     * [track] writes all six together, so the axes can never belong to a
     * different pointer than the position beside them.
     */
    private val trackIds = IntArray(GestureArbiter.MAX_POINTERS) { NO_POINTER }
    private val trackX = FloatArray(GestureArbiter.MAX_POINTERS)
    private val trackY = FloatArray(GestureArbiter.MAX_POINTERS)
    private val trackPressure = FloatArray(GestureArbiter.MAX_POINTERS) { 1f }
    private val trackTilt = FloatArray(GestureArbiter.MAX_POINTERS)
    private val trackOrientation = FloatArray(GestureArbiter.MAX_POINTERS)

    /**
     * When each slot was last written, so an opening sample keeps the time it
     * actually happened at rather than the time it was noticed at.
     *
     * The two differ on both paths that open a stroke from something other
     * than a down. From a move past the slop, the slot holds the finger-*down*
     * point; from `tick` inside [handleMoveEnd], it holds whatever position
     * that pointer last reported, which may be several pointers and one whole
     * event ago. A single `lastEventNs` stamped both with the current event's
     * time — telling the generator the pen covered that distance in zero, or
     * near zero, elapsed time.
     */
    private val trackTimeNs = LongArray(GestureArbiter.MAX_POINTERS)

    private var navigating = false

    /** A move arrived and its event has not been closed by [handleMoveEnd] yet. */
    private var pendingMove = false

    /**
     * Whether the host has an unfinished stroke.
     *
     * The arbiter's `CancelStroke` means "discard the pending input", which it
     * emits whenever a chord interrupts a pending finger — including on an
     * ordinary two-finger tap, where no stroke ever reached the host. Forwarding
     * that unconditionally told the host to roll back a front buffer that holds
     * nothing, on every single tap.
     */
    private var strokeLive = false

    /** The un-snapped angle §7 keeps separately from the displayed rotation. */
    private var rawRotation = 0f

    /** Which pointer is the pen, so only its own lift ends pen contact. */
    private var stylusPointerId = NO_POINTER

    /** A field, not a lambda: an object allocated per event is what §2.4 forbids. */
    private val decisions = object : GestureListener {
        override fun onDraw(pointerId: Int, source: StrokeSource) {
            navigating = false
            strokeLive = true
            drawingId = pointerId
            drawingSource = source
            startPredicting(source)
            host.onStrokeBegin(pointerId, source)
            // The down that opened the stroke is a sample too. Without it a tap
            // that never moves leaves no mark at all, and a fast stroke starts
            // at its second sample.
            val i = trackIndexOf(pointerId)
            if (i >= 0) emitTracked(i)
        }

        override fun onNavigate() {
            navigating = true
            rawRotation = view.rotation
            snap.reset()
            captureNavPointers()
        }

        override fun onCancelStroke() {
            if (!strokeLive) return
            strokeLive = false
            drawingId = NO_POINTER
            drawingSource = null
            stopPredicting()
            host.onStrokeCancel()
        }
        override fun onTapUndo() = host.onUndoRequested()
        override fun onTapRedo() = host.onRedoRequested()
        override fun onLongPressPick(x: Float, y: Float) =
            host.onColorPick(canvasX(x, y), canvasY(x, y))
        override fun onIgnore(pointerId: Int) = Unit
        override fun onStrokeEnd(pointerId: Int) {
            strokeLive = false
            drawingId = NO_POINTER
            drawingSource = null
            stopPredicting()
            host.onStrokeEnd(pointerId)
        }
        override fun onNavigateEnd() {
            navigating = false
            navIds[0] = NO_POINTER
            navIds[1] = NO_POINTER
        }
    }

    fun setView(next: ViewTransform) {
        view = next
        updateScreen()
        rawRotation = next.rotation
        snap.reset()
    }

    fun setViewport(canvas: CanvasSize, width: Int, height: Int) {
        val next = if (width > 0 && height > 0) {
            FitTransform(
                viewWidth = width.toFloat(),
                viewHeight = height.toFloat(),
                imageWidth = canvas.width.toFloat(),
                imageHeight = canvas.height.toFloat(),
            )
        } else {
            null
        }
        val previous = fit
        if (previous != null && next != null && previous != next) {
            view = view.rebase(previous, next)
            host.onViewChanged(view)
        }
        fit = next
        updateScreen()
    }

    private fun updateScreen() {
        screen = fit?.let { ScreenTransform.of(it, view) }
    }

    // ------------------------------------------------------- primitive path

    internal fun handleDown(
        pointerId: Int,
        tool: PointerTool,
        x: Float,
        y: Float,
        timeNs: Long,
        pressure: Float = 1f,
        tilt: Float = 0f,
        orientation: Float = 0f,
    ) {
        arbiter.stylusNear = PalmRejection.rejects(PointerTool.FINGER, stylus, timeNs)
        if (tool == PointerTool.STYLUS || tool == PointerTool.ERASER) {
            stylusPointerId = pointerId
            stylus.onDown(x, y, tool)
        }
        track(pointerId, x, y, pressure, tilt, orientation, timeNs)
        arbiter.down(pointerId, tool, x, y, timeNs, decisions)
        if (navigating) captureNavPointers()
    }

    /**
     * One pointer's new position. Call [handleMoveEnd] once the whole event's
     * pointers have been fed.
     *
     * The split is not ceremony. A `MotionEvent` carries *every* pointer's
     * current position, and §7's formula reads both fingers' previous and
     * current positions in one step. Applying a step per pointer instead moves
     * the canvas twice per event, each time with one finger stale — the anchor
     * drifts, and a symmetric pinch visibly slides the point it should hold.
     */
    internal fun handleMove(
        pointerId: Int,
        x: Float,
        y: Float,
        timeNs: Long,
        pressure: Float = 1f,
        tilt: Float = 0f,
        orientation: Float = 0f,
    ) {
        // [track] stays AFTER the arbiter: a decision made from
        // this move opens the stroke at the pointer's previous position and
        // axes — on the first move, the point it went down at. That is the
        // sample the stroke would otherwise lose; the live sample below then
        // adds the current one, so the opening segment survives.
        arbiter.move(pointerId, x, y, timeNs, decisions)
        track(pointerId, x, y, pressure, tilt, orientation, timeNs)
        pendingMove = true
        if (strokeLive && pointerId == drawingId) {
            // Window px, deliberately: §8's threshold is about what the eye
            // sees, so a stroke at 8x zoom must not become eight times more
            // tolerant of a bad guess. [emitSample] converts to canvas px on
            // the way to the host; the gate is fed before that.
            if (prediction.actual(x, y, timeNs)) {
                latency.record(
                    prediction.scoredPredictedX,
                    prediction.scoredPredictedY,
                    prediction.scoredActualX,
                    prediction.scoredActualY,
                )
            }
            emitSample(x, y, pressure, tilt, orientation, timeNs)
        }
    }

    /**
     * Forwards one pen sample to the host in **canvas** px (§2, §6).
     *
     * The conversion happens here because this class owns the view transform
     * during a gesture and nothing downstream should have to know about
     * screens: `03-canvas-engine.md` §6's pipeline reads
     * "ScreenTransform.invert → StrokeInput samples", and a brush size is in
     * canvas px so that a pencil is the same width on the paper at any zoom.
     *
     * Through scalar inverse methods because the `Pair` returned by `invert`
     * would allocate per sample on the touch path (§2.4).
     */
    private fun emitSample(
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        timeNs: Long,
    ) {
        host.onStrokeSample(
            canvasX(x, y),
            canvasY(x, y),
            pressureFor(drawingSource, pressure),
            tilt,
            canvasOrientation(orientation),
            timeNs,
        )
    }

    private fun canvasX(x: Float, y: Float): Float =
        screen?.invertX(x, y) ?: view.invertX(x, y)

    private fun canvasY(x: Float, y: Float): Float =
        screen?.invertY(x, y) ?: view.invertY(x, y)

    /**
     * The pen's azimuth in **canvas** space: the screen-space azimuth minus the
     * view's rotation, wrapped to (−π, π] (§2's sample table).
     *
     * The digitizer reports azimuth relative to the *screen*. A chisel tip
     * takes its angle from that value, so on a rotated canvas the tip would
     * turn the wrong way — rotate the paper 90° and every chisel stroke lands
     * across the grain. Converted here beside the x/y conversion, because this
     * class owns the view transform during a gesture and nothing downstream
     * should have to know a screen exists.
     *
     * **Nothing shows this on the shipped preset**, and it is worth being exact
     * about why rather than leaving the next reader to guess. `DabGenerator`
     * takes a dab's angle from `sample.orientation` under *two* conditions:
     * when the tip elongates under tilt (`elongation > 1f`), or when
     * `preset.orientation` is `TipOrientation.Stylus`. `INK_PEN` — the only
     * preset in `BrushPresets.ALL` — has `tilt = TiltEffect.None`, whose
     * `elongate` is false, and `orientation = TipOrientation.Fixed`, so its
     * dabs are drawn at angle 0 and neither door opens. Fixed now because the
     * flat and bristle tips of `04-tools.md` walk straight through both, and a
     * wrong azimuth there is not a subtle defect.
     */
    private fun canvasOrientation(screenAzimuth: Float): Float =
        ViewTransform.normalizeAngle(screenAzimuth - view.rotation)

    /**
     * The sample a tracked pointer is currently standing on — position and axes
     * from the same slot, so they cannot come from different pointers.
     */
    private fun emitTracked(slot: Int) = emitSample(
        trackX[slot],
        trackY[slot],
        trackPressure[slot],
        trackTilt[slot],
        trackOrientation[slot],
        trackTimeNs[slot],
    )

    /** Which pointer the arbiter said is drawing, or [NO_POINTER]. */
    private var drawingId = NO_POINTER

    /** Source is fixed from pen-down to pen-up. */
    private var drawingSource: StrokeSource? = null

    private fun pressureFor(source: StrokeSource?, raw: Float): Float = when (source) {
        StrokeSource.STYLUS, StrokeSource.ERASER_END -> pressureCurve.apply(raw)
        StrokeSource.FINGER, StrokeSource.MOUSE, null -> 1f
    }

    /** Applies one navigation step from every pointer's position in this event. */
    internal fun handleMoveEnd(timeNs: Long) {
        arbiter.tick(timeNs, decisions)
        if (pendingMove && navigating) applyNavigation()
        pendingMove = false
    }

    internal fun handleUp(pointerId: Int, timeNs: Long) {
        arbiter.up(pointerId, timeNs, decisions)
        // Only the pen's own lift ends the pen's contact. Keyed on the pointer
        // id because a palm resting on the glass is a real pointer that lifts
        // like any other: ending stylus contact on *any* up started the hover
        // grace while the pen was still drawing, and 500 ms later
        // PalmRejection stopped rejecting the palm mid-stroke.
        if (pointerId == stylusPointerId) {
            stylusPointerId = NO_POINTER
            if (stylus.isDown) stylus.onUp(timeNs)
        }
        untrack(pointerId)
        // Compact rather than clear: the arbiter stays in Navigate until the
        // last pointer lifts, and applyNavigation reads slot 0 first. Clearing
        // slot 0 froze the canvas with a finger still down, while lifting the
        // other finger kept panning — the same gesture behaving two ways.
        if (navIds[0] == pointerId) {
            navIds[0] = navIds[1]
            prevX[0] = prevX[1]
            prevY[0] = prevY[1]
            navIds[1] = NO_POINTER
        } else if (navIds[1] == pointerId) {
            navIds[1] = NO_POINTER
        }
    }

    internal fun handleCancel(timeNs: Long) {
        arbiter.cancel(decisions)
        navigating = false
        pendingMove = false
        // Belt and braces: the arbiter's own `CancelStroke` already stops the
        // frame callback, but only when a stroke was live. A cancel that
        // arrives with none — the ordinary two-finger tap — must still leave no
        // callback posted, and a posted callback outlives every other piece of
        // per-stroke state here.
        stopPredicting()
        // The pen's own ACTION_UP never arrives after a cancel, so nothing else
        // would ever clear contact: isDown stayed true, isNear stayed true with
        // it, and every later finger was rejected as a palm. The app looked
        // dead to touch until the pen next happened to touch the glass.
        if (stylus.isDown) stylus.onUp(timeNs)
        stylusPointerId = NO_POINTER
        for (i in trackIds.indices) trackIds[i] = NO_POINTER
        navIds[0] = NO_POINTER
        navIds[1] = NO_POINTER
    }

    /** Drives the pending window and the long press when no event arrives. */
    internal fun handleTick(timeNs: Long) = arbiter.tick(timeNs, decisions)

    private fun applyNavigation() {
        val a = navIds[0]
        val b = navIds[1]
        if (a == NO_POINTER) return
        val ai = trackIndexOf(a)
        if (ai < 0) return
        if (b == NO_POINTER) {
            step.fromSinglePointer(prevX[0], prevY[0], trackX[ai], trackY[ai])
            prevX[0] = trackX[ai]; prevY[0] = trackY[ai]
        } else {
            val bi = trackIndexOf(b)
            if (bi < 0) return
            step.fromPointers(
                prevX[0], prevY[0], prevX[1], prevY[1],
                trackX[ai], trackY[ai], trackX[bi], trackY[bi],
            )
            prevX[0] = trackX[ai]; prevY[0] = trackY[ai]
            prevX[1] = trackX[bi]; prevY[1] = trackY[bi]
        }
        rawRotation += step.rotation
        // The angle to DISPLAY, which is the snap's target — zero, or the
        // nearest right angle when Prefs.snapRightAngles is on. Hardcoding 0f
        // here meant right-angle snapping fired its haptic near 90° and then
        // threw the canvas to straight, so the pref was silently broken.
        val displayed = snap.update(rawRotation)
        val stepped = step.applyTo(view)
        // The snap only touches rotation; pan and zoom are the gesture's.
        view = stepped.copy(rotation = displayed)
        updateScreen()
        if (snap.justEntered) host.onRotationSnapped()
        host.onViewChanged(view)
    }

    private fun captureNavPointers() {
        var n = 0
        for (i in trackIds.indices) {
            if (trackIds[i] == NO_POINTER) continue
            if (n >= 2) break
            navIds[n] = trackIds[i]
            prevX[n] = trackX[i]
            prevY[n] = trackY[i]
            n++
        }
        while (n < 2) {
            navIds[n] = NO_POINTER
            n++
        }
    }

    private fun track(
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        timeNs: Long,
    ) {
        for (i in trackIds.indices) {
            if (trackIds[i] == pointerId) {
                store(i, x, y, pressure, tilt, orientation, timeNs)
                return
            }
        }
        for (i in trackIds.indices) {
            if (trackIds[i] == NO_POINTER) {
                trackIds[i] = pointerId
                store(i, x, y, pressure, tilt, orientation, timeNs)
                return
            }
        }
    }

    private fun store(
        i: Int,
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        timeNs: Long,
    ) {
        trackX[i] = x; trackY[i] = y
        trackPressure[i] = pressure; trackTilt[i] = tilt; trackOrientation[i] = orientation
        trackTimeNs[i] = timeNs
    }

    private fun trackIndexOf(pointerId: Int): Int {
        for (i in trackIds.indices) if (trackIds[i] == pointerId) return i
        return -1
    }

    private fun untrack(pointerId: Int) {
        for (i in trackIds.indices) if (trackIds[i] == pointerId) trackIds[i] = NO_POINTER
    }

    // ------------------------------------------------ the predicted tail (§8)

    /**
     * §8's adaptive disable, in **window** px.
     *
     * Public so the debug overlay of 2.5d can show the running error next to
     * the real-vs-predicted points §8 asks it to draw — the number is the whole
     * reason that overlay exists.
     */
    val prediction = PredictionGate()

    /**
     * §8's "last N real vs predicted points", for the debug overlay of 2.5d.
     *
     * Fed from the one place that knows both halves of a pair — the scoring in
     * [PredictionGate.actual] — so what the overlay draws and the error it
     * prints beside them come from the same arithmetic.
     */
    val latency = LatencyTrace()

    /**
     * Built from the `View` that dispatches to us, on the first event, and
     * rebuilt if that view is ever replaced (§8: one per surface).
     *
     * **Null on the JVM, and that is what keeps this class testable.** Only
     * [onTouch] and [onHover] ever set it, and neither can run in a unit test —
     * `MotionEvent` cannot be constructed there. Everything below is therefore
     * guarded on it rather than on a flag, so the `handle*` path the tests
     * drive never reaches `Choreographer.getInstance()`, which needs a Looper.
     */
    private var predictor: Predictor? = null

    /**
     * The view [predictor] was last *attempted* for, whether or not it worked.
     *
     * Without it a device where `newInstance` throws re-entered the constructor
     * on **every** event — a `RuntimeException` built, caught and logged with a
     * full stack trace per touch sample, several hundred a second during a
     * drag, on exactly the devices the catch exists for. `predictor` alone
     * cannot carry that, because a failure leaves it null and null is
     * indistinguishable from "not tried yet".
     *
     * Keyed on the view rather than latched for the process: §8 ties a
     * predictor to a surface, so a new surface deserves a fresh attempt, and a
     * process-wide flag would make one failing surface disable prediction for
     * every canvas afterwards.
     */
    private var predictorView: View? = null

    /** The tail handed to the host, refilled every frame. */
    private val predictedSamples = StrokeInputBatch()

    /**
     * Each predicted sample's **window**-px position, parallel to
     * [predictedSamples], which holds canvas px.
     *
     * Both are needed and neither can be derived from the other cheaply: the
     * host draws in canvas px, and [prediction]'s threshold is a screen-space
     * one on purpose (§8). Kept alongside rather than re-read off the
     * `MotionEvent` afterwards, because the tail is truncated *after* it is
     * filled — so "the last sample kept" and "the last sample the event has"
     * are different indices, and mapping one onto the other by hand is a
     * subscript nobody would notice being wrong.
     */
    private val predictedWindowX = FloatArray(predictedSamples.capacity)
    private val predictedWindowY = FloatArray(predictedSamples.capacity)

    /** Whether a stylus stroke is live and the frame callback should keep running. */
    private var predicting = false

    /** Whether a frame callback is outstanding, so it is posted exactly once. */
    private var framePosted = false

    /** Hover is UI-only, but still coalesced so a fast digitizer cannot recompose per sample. */
    private var hoverFramePosted = false

    private val hoverFrameCallback = Choreographer.FrameCallback {
        hoverFramePosted = false
        host.onHoverChanged()
    }

    /**
     * The source the stroke opened with, carried onto every predicted sample.
     *
     * Read from the arbiter's decision rather than from [stylus] at fill time:
     * the pen can be lifted and its eraser end put down while the tail's last
     * frame is still in flight, and a tail that changed tool mid-stroke would
     * describe a stroke that does not exist. The same reason `CanvasScreen`
     * pins `strokeState.source` at pen-down for the real samples.
     */
    private var predictedSource = StrokeSource.STYLUS

    /**
     * §8's cadence: `predict()` once per frame, not per event.
     *
     * A field rather than a lambda at the post site — one object for the life
     * of the handler instead of one per frame (`10-performance.md` §2.4).
     */
    private val frameCallback = Choreographer.FrameCallback {
        framePosted = false
        // The surface check is the loop's own kill switch, and it is load
        // bearing rather than defensive: this callback reposts itself for as
        // long as a stroke is live, and a surface torn down mid-stroke does not
        // reliably deliver an `ACTION_CANCEL` to end that stroke. Without it a
        // back navigation during a stroke leaves a frame callback running for
        // the life of the process, holding this handler and its host.
        if (predicting && predictor?.isUsable == true) {
            // Reposted before the work, not after: `predictFrame` returns early
            // on a dozen paths (no predictor, prediction disabled, nothing to
            // predict) and every one of them must still leave the next frame
            // scheduled, or the tail stops for the rest of the stroke the first
            // time the predictor declines a frame.
            postFrame()
            predictFrame()
        } else {
            predicting = false
        }
    }

    /**
     * Starts the per-frame tail for a stylus stroke.
     *
     * Fingers are not predicted in v1 (§8: "finger latency is not the
     * product"), and an eraser end is a stylus for this purpose — it is the
     * same digitizer with the same lag.
     */
    private fun startPredicting(source: StrokeSource) {
        if (predictor == null) return
        if (source != StrokeSource.STYLUS && source != StrokeSource.ERASER_END) return
        predictedSource = source
        // §8's "re-enabled at the next ACTION_DOWN". Carrying the previous
        // stroke's error would let one bad flick disable prediction for a
        // session.
        prediction.reset()
        // The window too: last stroke's misses say nothing about this one, and
        // an overlay showing both would read as one long erratic stroke.
        latency.clear()
        predicting = true
        postFrame()
    }

    private fun stopPredicting() {
        predicting = false
        if (!framePosted) return
        framePosted = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun postFrame() {
        if (framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun postHoverFrame() {
        if (hoverFramePosted) return
        hoverFramePosted = true
        Choreographer.getInstance().postFrameCallback(hoverFrameCallback)
    }

    /**
     * One frame's tail: predict, convert, truncate, hand over.
     *
     * Returns without calling the host on every path that has nothing to draw,
     * and calling with an empty batch would be the same thing said louder — the
     * host would clear a tail the *next real batch* is about to clear anyway.
     */
    private fun predictFrame() {
        val p = predictor ?: return
        if (!prediction.enabled) return
        val id = drawingId
        if (id == NO_POINTER) return
        val slot = trackIndexOf(id)
        if (slot < 0) return
        val e = p.predict() ?: return
        val pointer = e.findPointerIndex(id)
        if (pointer < 0) return

        predictedSamples.clear()
        for (h in 0 until e.historySize) fill(e, pointer, h)
        fill(e, pointer, CURRENT)
        if (predictedSamples.size == 0) return

        // §8's PREDICT_MAX_NS, measured from the last REAL sample rather than
        // from the frame clock: "16 ms of lookahead" is 16 ms past where the
        // pen actually is, and a frame callback that ran late would otherwise
        // truncate a tail that was the right length.
        val base = trackTimeNs[slot]
        predictedSamples.size = prediction.keepCount(predictedSamples.size) {
            predictedSamples[it].timeNs - base
        }
        if (predictedSamples.size == 0) return

        // The furthest-ahead point is the one scored: it is the tip of the
        // tail, the part the eye actually judges, and the hardest guess in the
        // batch.
        val last = predictedSamples.size - 1
        prediction.predicted(predictedWindowX[last], predictedWindowY[last], predictedSamples[last].timeNs)

        host.onStrokePredicted(predictedSamples)
    }

    /**
     * Appends one predicted sample — canvas px into [predictedSamples], window
     * px into the parallel arrays — or does nothing if the batch is full.
     *
     * [history] is the historical index, or [CURRENT] for the event's own
     * sample. A predicted event lays its lookahead out in the same
     * chronological order a real event lays out its backlog — **historical
     * samples are the nearest predictions and the event's own is the furthest
     * ahead** — so both have to be read, by §2's rule for real events verbatim
     * rather than mirrored.
     *
     * Read off the 1.0.0 bytecode rather than assumed, because the order is
     * what the two steps after this one depend on: `MultiPointerPredictor.predict`
     * builds the event with `MotionEvent.obtain` for the first predicted instant
     * and then `addBatch` for each later one, and `addBatch` pushes the current
     * sample into history and makes the new one current. So the last-added —
     * furthest-ahead — sample is the event's own. An earlier draft of this
     * comment had it backwards; the *code* was right, which is exactly why the
     * comment was worth checking. Inverting the fill order to match the wrong
     * comment would have made `keepCount`'s prefix truncation drop the nearest
     * samples and keep the furthest, and fed [prediction] the least demanding
     * point instead of the tip.
     */
    private fun fill(e: MotionEvent, pointer: Int, history: Int) {
        val slot = predictedSamples.size
        val sample = predictedSamples.next() ?: return
        val current = history == CURRENT
        val windowX = if (current) e.getX(pointer) else e.getHistoricalX(pointer, history)
        val windowY = if (current) e.getY(pointer) else e.getHistoricalY(pointer, history)
        predictedWindowX[slot] = windowX
        predictedWindowY[slot] = windowY
        sample.set(
            x = canvasX(windowX, windowY),
            y = canvasY(windowX, windowY),
            pressure = pressureFor(
                predictedSource,
                if (current) e.getPressure(pointer) else e.getHistoricalPressure(pointer, history),
            ),
            tilt = if (current) {
                e.getAxisValue(MotionEvent.AXIS_TILT, pointer)
            } else {
                e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointer, history)
            },
            // Canvas-relative, exactly as the real path converts it in
            // [emitSample]. A tail whose azimuth were left in screen space
            // would draw a chisel tip at a different angle from the real dabs
            // it is predicting — §7.5's "the preview does not lie", broken by
            // the one path that never goes through `onStrokeSample`.
            orientation = canvasOrientation(
                if (current) {
                    e.getOrientation(pointer)
                } else {
                    e.getHistoricalOrientation(pointer, history)
                },
            ),
            timeNs = if (current) {
                e.eventTime * 1_000_000L
            } else {
                e.getHistoricalEventTime(history) * 1_000_000L
            },
            source = predictedSource,
            predicted = true,
        )
    }

    // -------------------------------------------------------- MotionEvent

    /**
     * The translation layer, and the only place `MotionEvent` appears.
     *
     * Historical samples are consumed before the current one (§2): a 240 Hz
     * digitizer batches several samples into one 60 Hz event, and dropping them
     * turns a smooth curve into four straight segments.
     */
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        val e = event ?: return false
        val index = e.actionIndex
        val id = e.getPointerId(index)
        val timeNs = e.eventTime * 1_000_000L
        // §8: one predictor per surface, recreated with it. `v` is the
        // SurfaceView the session draws into, so building it from here means
        // nothing has to be plumbed through the composable that owns both.
        attachPredictor(v)
        recordForPrediction(e)
        syncStylusButton(e)
        // Before the `when`, not inside its DOWN arm. That arm matches
        // ACTION_POINTER_DOWN too, so the call needed a nested re-check of the
        // value the `when` had already switched on — invisible to anyone
        // scanning the arms, and silently inherited by whatever action is added
        // to that arm next.
        if (e.actionMasked == MotionEvent.ACTION_DOWN) requestUnbuffered(v, e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                handleDown(
                    id, toolOf(e.getToolType(index)), e.getX(index), e.getY(index), timeNs,
                    e.getPressure(index),
                    e.getAxisValue(MotionEvent.AXIS_TILT, index),
                    e.getOrientation(index),
                )
            }

            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    val hNs = e.getHistoricalEventTime(h) * 1_000_000L
                    for (p in 0 until e.pointerCount) {
                        // Axes read at index `p`, the same pointer handleMove
                        // is given. For ACTION_MOVE the action's pointer-index
                        // bits are always zero, so reading them at
                        // `actionIndex` gave every pointer the FIRST pointer's
                        // pressure and tilt — wrong exactly when it matters
                        // most, with a palm down as pointer 0 and the pen
                        // drawing as pointer 1.
                        handleMove(
                            e.getPointerId(p),
                            e.getHistoricalX(p, h),
                            e.getHistoricalY(p, h),
                            hNs,
                            e.getHistoricalPressure(p, h),
                            e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, p, h),
                            e.getHistoricalOrientation(p, h),
                        )
                    }
                    // Each historical sample is a complete event's worth of
                    // pointers, so it gets its own step.
                    handleMoveEnd(hNs)
                }
                for (p in 0 until e.pointerCount) {
                    handleMove(
                        e.getPointerId(p), e.getX(p), e.getY(p), timeNs,
                        e.getPressure(p),
                        e.getAxisValue(MotionEvent.AXIS_TILT, p),
                        e.getOrientation(p),
                    )
                }
                handleMoveEnd(timeNs)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> handleUp(id, timeNs)
            // §6's barrel button. Without these StylusState.buttonPressed could
            // never leave false, and its KDoc promising these two actions was a
            // description of code that did not exist.
            //
            // The press is scoped to the pen: a mouse's secondary button also
            // arrives as ACTION_BUTTON_PRESS, and letting it through would latch
            // barrel state on the stylus model for a device §6 never described.
            // The release is deliberately NOT scoped — clearing state is
            // fail-safe, and a guarded press with an unguarded release can only
            // ever under-latch, while the reverse would leave it stuck on.
            MotionEvent.ACTION_BUTTON_PRESS -> {
                val tool = toolOf(e.getToolType(index))
                if (tool == PointerTool.STYLUS || tool == PointerTool.ERASER) {
                    syncStylusButton(e)
                }
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> stylus.onButton(ButtonState.Released)
            MotionEvent.ACTION_CANCEL -> handleCancel(timeNs)
            else -> return false
        }
        val downTool = toolOf(e.getToolType(index))
        val isDown = e.actionMasked == MotionEvent.ACTION_DOWN ||
            e.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        if (isDown && (downTool == PointerTool.STYLUS || downTool == PointerTool.ERASER)) {
            postHoverFrame()
        }
        return true
    }

    override fun onHover(v: View?, event: MotionEvent?): Boolean {
        val e = event ?: return false
        val timeNs = e.eventTime * 1_000_000L
        attachPredictor(v)
        // §8 records hover too: hover history improves the first predicted
        // samples after contact, which is the moment the tail is least accurate
        // and the pen is moving fastest.
        recordForPrediction(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                requestUnbufferedHover(v)
                stylus.onHoverEnter(e.x, e.y, e.getAxisValue(MotionEvent.AXIS_DISTANCE), toolOf(e.getToolType(0)))
            }
            MotionEvent.ACTION_HOVER_MOVE ->
                stylus.onHoverMove(e.x, e.y, e.getAxisValue(MotionEvent.AXIS_DISTANCE))
            MotionEvent.ACTION_HOVER_EXIT -> stylus.onHoverExit(timeNs)
            else -> return false
        }
        postHoverFrame()
        return true
    }

    /**
     * Builds the predictor for [v] the first time this view dispatches to us,
     * and **once only** — a view whose attempt failed is not retried.
     *
     * See [predictorView] for why the failure has to be remembered separately
     * from the predictor itself.
     */
    private fun attachPredictor(v: View?) {
        if (v == null || v === predictorView) return
        predictorView = v
        predictor = Predictor.forView(v, predictor)
    }

    /**
     * Feeds one real event to the predictor — §8's "every `DOWN`/`MOVE`/`UP`
     * for a stylus pointer, including `ACTION_HOVER_MOVE`".
     *
     * Scoped to the pen: a finger or a mouse dragging across the glass is
     * history the predictor would fit a curve to and then answer the pen's next
     * `predict()` with. §8 does not predict fingers in v1 at all.
     *
     * **Every pointer is checked, not index 0.** A `MotionEvent` carries one
     * tool type per pointer, so a palm that landed first is pointer 0 and the
     * pen is pointer 1 — and reading the tool at 0 classified the whole event
     * as finger input and recorded no pen history at all. That fails exactly on
     * palm-heavy usage, which is the hardware a predicted tail is for, and it
     * fails silently: the tail either never appears or keeps extrapolating from
     * pre-palm samples. Same defect class as the `actionIndex` bug the axis
     * parameters fixed, and as the one `trackTimeNs` fixed — a per-pointer fact
     * read at a single fixed index.
     *
     * Nothing predicted is ever recorded back (§8), which holds here by
     * construction: this is only ever reached from [onTouch] and [onHover], and
     * a predicted event never arrives through either.
     */
    private fun recordForPrediction(e: MotionEvent) {
        val p = predictor ?: return
        for (i in 0 until e.pointerCount) {
            val tool = toolOf(e.getToolType(i))
            if (tool == PointerTool.STYLUS || tool == PointerTool.ERASER) {
                // The whole event, once: `record` takes the event, not a
                // pointer, and the library splits it per pointer itself
                // (`MultiPointerPredictor.onTouchEvent`).
                p.record(e)
                return
            }
        }
    }

    /**
     * §2.1's unbuffered dispatch: events at the digitizer's rate instead of
     * batched to vsync.
     *
     * On `ACTION_DOWN` only. The `MotionEvent` overload returns early unless
     * the event is a touch event with action DOWN or MOVE, so calling it on
     * `ACTION_POINTER_DOWN` would do nothing — and the request already lasts
     * until the whole gesture ends, so a second pointer has nothing to add.
     * Re-issued per stroke for the same reason: it does *not* survive the
     * gesture that requested it.
     *
     * For **all** tool types, as §2.1 says: the finger path benefits equally on
     * a phone. The cost is more main-thread wakeups while a pointer is down,
     * which is the entire point — the front-buffered path of §8.1 turns each
     * extra sample into ink under the pen sooner, and this thread does nothing
     * else while a stroke is live.
     */
    private fun requestUnbuffered(v: View?, e: MotionEvent) {
        v?.requestUnbufferedDispatch(e)
    }

    /**
     * The hover half, which needs the **other** overload.
     *
     * `requestUnbufferedDispatch(MotionEvent)` is documented to act only on
     * touch streams, so it does nothing for hover; the source-taking overload
     * is what covers a hovering pen, and it is API 30 while this app's minSdk
     * is 29 (both levels read out of the SDK's own `api-versions.xml`, not
     * assumed). On 29 hover stays vsync-batched, which costs a slightly
     * coarser hover cursor and nothing else — no stroke has begun yet.
     *
     * A *class* is broader than the pen: `SOURCE_MOUSE` is `0x2002` and
     * `SOURCE_TOUCHSCREEN` is `0x1002`, so both carry `SOURCE_CLASS_POINTER`'s
     * `0x2` and both get unbuffered hover out of this call as well. Harmless —
     * a smoother cursor, and more main-thread wakeups while pointer-class
     * events target this view — but it is what the code does, and "covers a
     * hovering pen" implied a narrower effect than that.
     *
     * **How long the request stands is deliberately not stated here.** An
     * earlier version of this comment priced it as "one wakeup per mouse move
     * while the pointer is over the canvas", which assumes the request dies
     * with the hover stream. A hover stream ends in `ACTION_HOVER_EXIT`, not
     * `ACTION_UP` or `ACTION_CANCEL`, so it may well stand until some later
     * gesture ends — but the platform javadoc is not in the SDK's stub sources
     * and could not be reached to settle it. Rather than swap one unverified
     * bound for another, the cost is left unquantified: whoever trims wakeups
     * here should read `ViewRootImpl`'s input path first. The `ACTION_DOWN`
     * path asks for pointer-class unbuffered dispatch anyway, so nothing here
     * depends on the answer.
     *
     * **`SOURCE_CLASS_POINTER`, not `SOURCE_STYLUS`**, which is what
     * `07-input-and-stylus.md` §2.1 writes — it flagged the call "(to verify)",
     * and this is the verification. The parameter is annotated
     * `@InputSourceClass`, so it takes a source *class* rather than a source:
     * `SOURCE_STYLUS` is `0x4002`, whose low bit happens to be
     * `SOURCE_CLASS_POINTER`, so it would most likely have worked by accident
     * while being the wrong constant. Android Lint rejects it outright
     * (`WrongConstant`), which is how this was caught rather than shipped.
     */
    private fun requestUnbufferedHover(v: View?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        v?.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
    }

    private fun toolOf(toolType: Int): PointerTool = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerTool.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerTool.ERASER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerTool.MOUSE
        else -> PointerTool.FINGER
    }

    private fun syncStylusButton(event: MotionEvent) {
        var hasStylus = false
        for (index in 0 until event.pointerCount) {
            val tool = toolOf(event.getToolType(index))
            if (tool == PointerTool.STYLUS || tool == PointerTool.ERASER) {
                hasStylus = true
                break
            }
        }
        if (!hasStylus) return

        val state = StylusButtonPolicy.resolve(
            event.buttonState,
            MotionEvent.BUTTON_STYLUS_PRIMARY,
            MotionEvent.BUTTON_SECONDARY,
        )
        stylus.onButton(state)
    }

    private companion object {
        const val NO_POINTER = -1

        /** [fill]'s "not a historical sample, the event's own". */
        const val CURRENT = -1
    }
}
