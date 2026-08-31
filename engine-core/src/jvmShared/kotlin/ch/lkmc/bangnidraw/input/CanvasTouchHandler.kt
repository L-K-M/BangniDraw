package ch.lkmc.bangnidraw.input

import ch.lkmc.bangnidraw.engine.core.ActualSizePolicy
import ch.lkmc.bangnidraw.engine.core.ButtonState
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.FitTransform
import ch.lkmc.bangnidraw.engine.core.GestureArbiter
import ch.lkmc.bangnidraw.engine.core.GestureListener
import ch.lkmc.bangnidraw.engine.core.LatencyTrace
import ch.lkmc.bangnidraw.engine.core.NavigationStep
import ch.lkmc.bangnidraw.engine.core.NavigationTarget
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.PredictionGate
import ch.lkmc.bangnidraw.engine.core.PressureCurve
import ch.lkmc.bangnidraw.engine.core.RotationSnap
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.engine.core.ScrollZoom
import ch.lkmc.bangnidraw.engine.core.StrokeInputBatch
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.StylusButtonPolicy
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.engine.core.ViewportResizeOwner
import ch.lkmc.bangnidraw.engine.core.ViewportResizePolicy
import ch.lkmc.bangnidraw.engine.core.ViewportResizeState
import kotlin.math.PI

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

    /** A resize rebase must reach rendering before its replacement surface frame. */
    fun onViewportResized(view: ViewTransform) = onViewChanged(view)

    /** Fired once on entering the rotation snap, for the haptic tick (§7). */
    fun onRotationSnapped()

    fun onUndoRequested()
    fun onRedoRequested()

    /** Sample the colour under the finger (stylus-only long press). */
    fun onColorPick(x: Float, y: Float)

    /** Coalesced to one callback per frame while hover state changes. */
    fun onHoverChanged() {}

    /**
     * A navigation gesture became live, or just ended — exactly once per
     * transition, so a chrome readout can appear while the fingers move and
     * disappear when they lift.
     */
    fun onNavigateActive(active: Boolean) {}

    /** A tracing-reference gesture, already converted from window to canvas px. */
    fun onReferenceGesture(
        pivotX: Float,
        pivotY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        rotationDelta: Float,
    ) {}

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

/** Main-thread deadline driver, abstracted so stationary gestures stay JVM-testable. */
interface GestureDeadlineScheduler {
    fun scheduleAt(deadlineNs: Long, callback: Runnable)
    fun cancel(callback: Runnable)
}

/**
 * The pointer-gesture brain: it consumes [PointerSample]s and feeds
 * [GestureArbiter], [StylusState] and [PalmRejection], then turns
 * navigation decisions into [ViewTransform] steps. Everything it decides
 * lives in `engine/core`; what is here is the translation and the wiring.
 *
 * Platform events never reach this class. The Android `MotionEvent`
 * adapter (app module) flattens events into reused samples; the desktop
 * host fills the same record from its pointer events. The platform-touching
 * halves — unbuffered dispatch, `Choreographer`, the predictor — are the
 * glue's, injected here as a [FrameScheduler] and a [StrokePredictor].
 *
 * **Zero allocation on the sample path** (`10-performance.md` §2.4). The
 * pointer scratch arrays, the [NavigationStep] and the arbiter's listener
 * are all fields; nothing on this path returns an object, takes a lambda,
 * or boxes. The handler's logic is reachable from the JVM through the
 * `handle*` methods below, which take primitives — the record-consuming
 * `onPointer*` entries are thin wrappers over them, so a host can drive
 * either surface and the tests pin the primitive one.
 */
class CanvasTouchHandler(
    density: Float,
    private val host: CanvasInputHost,
) {

    val stylus = StylusState()
    val arbiter = GestureArbiter(density)
    private val snap = RotationSnap()
    private val step = NavigationStep()

    /** One retained callback; touch events only reschedule its absolute deadline. */
    private var deadlineScheduler: GestureDeadlineScheduler? = null
    private var deadlineSchedulerInjected = false
    private var scheduledDeadlineNs = GestureArbiter.NO_DEADLINE_NS
    private val gestureDeadlineCallback = Runnable {
        val deadlineNs = scheduledDeadlineNs
        if (deadlineNs == GestureArbiter.NO_DEADLINE_NS) return@Runnable

        scheduledDeadlineNs = GestureArbiter.NO_DEADLINE_NS
        arbiter.tick(deadlineNs, decisions)
        syncGestureDeadline()
    }

    internal constructor(
        density: Float,
        host: CanvasInputHost,
        deadlineScheduler: GestureDeadlineScheduler,
    ) : this(density, host) {
        this.deadlineScheduler = deadlineScheduler
        deadlineSchedulerInjected = true
    }

    var view: ViewTransform = ViewTransform()
        private set

    var navigationTarget: NavigationTarget = NavigationTarget.CANVAS

    private var fit: FitTransform? = null
    private var screen: ScreenTransform? = null

    val canvasToScreenScale: Float
        get() = screen?.effectiveScale ?: view.scale

    /** The current canvas→window mapping, for chrome overlays drawn in window px. */
    val screenTransform: ScreenTransform?
        get() = screen

    var stylusOnly: Boolean
        get() = arbiter.stylusOnly
        set(value) {
            arbiter.stylusOnly = value
            syncGestureDeadline()
        }

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
            if (navigationTarget == NavigationTarget.TRACING_REFERENCE) return

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
            host.onNavigateActive(true)
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
        override fun onTapUndo() {
            if (navigationTarget == NavigationTarget.CANVAS) host.onUndoRequested()
        }
        override fun onTapRedo() {
            if (navigationTarget == NavigationTarget.CANVAS) host.onRedoRequested()
        }
        override fun onLongPressPick(x: Float, y: Float) =
            if (navigationTarget == NavigationTarget.CANVAS) {
                host.onColorPick(canvasX(x, y), canvasY(x, y))
            } else {
                Unit
        }
        override fun onIgnore(pointerId: Int) = Unit
        override fun onStrokeEnd(pointerId: Int) {
            if (!strokeLive) return

            strokeLive = false
            drawingId = NO_POINTER
            drawingSource = null
            stopPredicting()
            host.onStrokeEnd(pointerId)
        }
        override fun onNavigateEnd() {
            navigating = false
            host.onNavigateActive(false)
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

    /**
     * The 100 %-zoom view anchored at the viewport centre — the reset pill's
     * long-press — or null before the first layout. The handler owns [fit],
     * so the policy's 1/fit.scale is computed here, not in the composable.
     */
    fun actualSizeView(): ViewTransform? =
        fit?.let { ActualSizePolicy.transform(it, view) }

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
            val resized = ViewportResizePolicy.resize(
                ViewportResizeState(view, previous),
                next,
                ViewportResizeOwner.INPUT,
            )
            view = resized.view
            host.onViewportResized(view)
        }
        fit = next
        updateScreen()
    }

    private fun updateScreen() {
        screen = fit?.let { ScreenTransform.of(it, view) }
    }

    private fun attachGestureDeadlineScheduler(scheduler: GestureDeadlineScheduler?) {
        if (deadlineSchedulerInjected || scheduler == null || scheduler === deadlineScheduler) return

        cancelGestureDeadline()
        deadlineScheduler = scheduler
        syncGestureDeadline()
    }

    /**
     * The platform glue installs its main-thread deadline driver here — the
     * Android one wraps the dispatching `View`. Swapping drivers resyncs any
     * pending deadline, so a replaced surface keeps its stationary-gesture
     * clock. Once injected, [reset] and [dispose] leave it in place: the
     * caller owns its lifecycle.
     */
    fun attachDeadlineScheduler(scheduler: GestureDeadlineScheduler) {
        attachGestureDeadlineScheduler(scheduler)
    }

    private fun syncGestureDeadline() {
        val nextDeadlineNs = arbiter.nextDeadlineNs()
        if (nextDeadlineNs == scheduledDeadlineNs) return

        cancelGestureDeadline()
        if (nextDeadlineNs == GestureArbiter.NO_DEADLINE_NS) return
        val scheduler = deadlineScheduler ?: return

        scheduledDeadlineNs = nextDeadlineNs
        scheduler.scheduleAt(nextDeadlineNs, gestureDeadlineCallback)
    }

    private fun cancelGestureDeadline() {
        if (scheduledDeadlineNs == GestureArbiter.NO_DEADLINE_NS) return

        deadlineScheduler?.cancel(gestureDeadlineCallback)
        scheduledDeadlineNs = GestureArbiter.NO_DEADLINE_NS
    }

    /** Replacing a handler rolls any live gesture back through its host. */
    fun reset() {
        arbiter.cancel(decisions)
        clearHandlerState()
    }

    /** Surface teardown is silent because session detachment owns the rollback. */
    fun dispose() {
        arbiter.reset()
        clearHandlerState()
    }

    private fun clearHandlerState() {
        cancelGestureDeadline()
        // A scheduler the glue attached goes with the surface; one the tests
        // injected stays for the next handler on the same clock.
        if (!deadlineSchedulerInjected) {
            deadlineScheduler = null
        }

        navigating = false
        pendingMove = false
        strokeLive = false
        drawingId = NO_POINTER
        drawingSource = null
        stylusPointerId = NO_POINTER
        stylus.reset()
        stopPredicting()

        if (hoverFramePosted) {
            hoverFramePosted = false
            frameScheduler?.cancel(hoverFrameCallback)
        }

        for (i in trackIds.indices) trackIds[i] = NO_POINTER
        navIds[0] = NO_POINTER
        navIds[1] = NO_POINTER
        predictor = null
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
        syncGestureDeadline()
        if (navigating) captureNavPointers()
    }

    /**
     * One pointer's new position. Call [handleMoveEnd] once the whole event's
     * pointers have been fed.
     *
     * The split is not ceremony. A pointer event carries *every* pointer's
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
        syncGestureDeadline()
        track(pointerId, x, y, pressure, tilt, orientation, timeNs)
        pendingMove = true
        if (strokeLive && pointerId == drawingId) {
            emitDrawingSample(x, y, pressure, tilt, orientation, timeNs)
        }
    }

    private fun emitDrawingSample(
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        timeNs: Long,
    ) {
        // Window px: prediction error is visual. Canvas conversion happens only
        // when the accepted sample reaches the host.
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
     * The pen's azimuth in **canvas** space, wrapped to (−π, π].
     *
     * Android's `AXIS_ORIENTATION` is zero at screen-up; brush geometry is zero
     * on +x. Subtract that quarter-turn before the view rotation. Converted
     * here beside x/y because this class owns the view transform.
     */
    private fun canvasOrientation(screenAzimuth: Float): Float =
        ViewTransform.normalizeAngle(
            screenAzimuth - ANDROID_ORIENTATION_BASIS_RAD - view.rotation,
        )

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
        syncGestureDeadline()
        if (pendingMove && navigating) applyNavigation()
        pendingMove = false
    }

    internal fun handleUp(pointerId: Int, timeNs: Long) {
        arbiter.up(pointerId, timeNs, decisions)
        syncGestureDeadline()
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

    /** Forwards the lifting pointer's final coordinates and axes before pen-up. */
    internal fun handleUp(
        pointerId: Int,
        x: Float,
        y: Float,
        timeNs: Long,
        pressure: Float,
        tilt: Float,
        orientation: Float,
    ) {
        if (strokeLive && pointerId == drawingId) {
            emitDrawingSample(x, y, pressure, tilt, orientation, timeNs)
        }
        handleUp(pointerId, timeNs)
    }

    internal fun handleCancel(timeNs: Long) {
        arbiter.cancel(decisions)
        syncGestureDeadline()
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
    internal fun handleTick(timeNs: Long) {
        arbiter.tick(timeNs, decisions)
        syncGestureDeadline()
    }

    /**
     * One wheel or trackpad scroll: zoom about the cursor at ([x], [y]) in
     * window px, by [ScrollZoom]'s factor for [ticks] notches.
     *
     * Returns whether the event was consumed. Deliberately inert while a
     * stroke is live — re-mapping the canvas under in-flight pen samples would
     * bend the line being drawn — and in reference-edit mode, where the wheel
     * has no defined meaning yet. [ViewTransform.gesture] owns the pivot
     * arithmetic and the scale clamp, so the point under the cursor stays
     * under the cursor, exactly as it does under a pinch.
     */
    internal fun handleScroll(x: Float, y: Float, ticks: Float): Boolean {
        if (navigationTarget != NavigationTarget.CANVAS) return false
        if (strokeLive) return false
        val factor = ScrollZoom.factor(ticks)
        if (factor == 1f) return false

        view = view.gesture(
            pivotX = x,
            pivotY = y,
            panX = 0f,
            panY = 0f,
            zoom = factor,
            rotationDelta = 0f,
        )
        updateScreen()
        host.onViewChanged(view)
        return true
    }

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
        if (navigationTarget == NavigationTarget.TRACING_REFERENCE) {
            applyReferenceNavigation()
            return
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

    private fun applyReferenceNavigation() {
        val current = screen ?: return
        val pivotX = current.invertX(step.anchorX, step.anchorY)
        val pivotY = current.invertY(step.anchorX, step.anchorY)
        val movedX = current.invertX(step.anchorX + step.panX, step.anchorY + step.panY)
        val movedY = current.invertY(step.anchorX + step.panX, step.anchorY + step.panY)
        host.onReferenceGesture(
            pivotX = pivotX,
            pivotY = pivotY,
            panX = movedX - pivotX,
            panY = movedY - pivotY,
            zoom = step.zoom,
            rotationDelta = step.rotation,
        )
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
     * The predicted-tail source, injected by the platform glue — the Android
     * adapter's `MotionEventPredictor` wrapper, or null where none exists.
     *
     * **Null on the JVM, and that is what keeps this class testable.** Only
     * the glue ever sets it, and the glue cannot run in a unit test. The
     * desktop host leaves it null: prediction is platform-tailored, and
     * desktop input rates are modest (DESKTOP.md seam 3).
     */
    var predictor: StrokePredictor? = null

    /**
     * The next-frame poster, injected by the platform glue — the Android
     * `Choreographer`, or null where none exists. Null leaves the
     * frame-driven paths (predicted tail, hover coalescing) inert, which is
     * exactly the JVM-test posture this class has always had.
     */
    var frameScheduler: FrameScheduler? = null

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

    private val hoverFrameCallback = Runnable {
        hoverFramePosted = false
        host.onHoverChanged()
    }

    /**
     * The source the stroke opened with, carried onto every predicted sample.
     *
     * Read from the arbiter's decision rather than from [stylus] at predict time:
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
    private val frameCallback = Runnable {
        framePosted = false
        // The scheduler check is the loop's own kill switch, and it is load
        // bearing rather than defensive: this callback reposts itself for as
        // long as a stroke is live, and a surface torn down mid-stroke does not
        // reliably deliver a cancellation to end that stroke. Without it a
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
        frameScheduler?.cancel(frameCallback)
    }

    private fun postFrame() {
        // Without a scheduler there is no loop to join: latching `framePosted`
        // would strand every later frame behind a flag nothing clears — the
        // JVM posture, identical to when Choreographer was unreachable.
        if (frameScheduler == null) return
        if (framePosted) return
        framePosted = true
        frameScheduler?.post(frameCallback)
    }

    private fun postHoverFrame() {
        if (frameScheduler == null) return
        if (hoverFramePosted) return
        hoverFramePosted = true
        frameScheduler?.post(hoverFrameCallback)
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
        val count = p.predict(id)
        if (count == 0) return

        predictedSamples.clear()
        for (h in 0 until count) appendPredicted(p.predictedAt(h))
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
     * Samples arrive from the predictor nearest-first, laid out in the same
     * chronological order a real event lays out its backlog — **the nearest
     * predictions come first and the furthest-ahead sample is last** — by
     * §2's rule for real events verbatim rather than mirrored. Read off the
     * 1.0.0 bytecode rather than assumed, because the order is what the two
     * steps after this one depend on: `MultiPointerPredictor.predict` builds
     * its event for the first predicted instant and then `addBatch`es each
     * later one, so the last-added — furthest-ahead — sample is the event's
     * own. Inverting the order would make `keepCount`'s prefix truncation
     * drop the nearest samples and keep the furthest, and fed [prediction]
     * the least demanding point instead of the tip.
     */
    private fun appendPredicted(predicted: PointerSample) {
        val slot = predictedSamples.size
        val sample = predictedSamples.next() ?: return
        val windowX = predicted.x
        val windowY = predicted.y
        predictedWindowX[slot] = windowX
        predictedWindowY[slot] = windowY
        sample.set(
            x = canvasX(windowX, windowY),
            y = canvasY(windowX, windowY),
            pressure = pressureFor(predictedSource, predicted.pressure),
            tilt = predicted.tilt,
            // Canvas-relative, exactly as the real path converts it in
            // [emitSample]. A tail whose azimuth were left in screen space
            // would draw a chisel tip at a different angle from the real dabs
            // it is predicting — §7.5's "the preview does not lie", broken by
            // the one path that never goes through `onStrokeSample`.
            orientation = canvasOrientation(predicted.orientation),
            timeNs = predicted.timeNs,
            source = predictedSource,
            predicted = true,
        )
    }

    // ------------------------------------------------------ pointer records

    /**
     * The record-consuming surface: the platform glue flattens its events
     * into reused [PointerSample]s and drives these entries, each of which
     * is a thin wrapper over the primitive `handle*` path above.
     *
     * Historical samples are the adapter's job (§2): it feeds one
     * [onPointerMove] per historical sample and closes each event's worth
     * with [onPointerMoveEnd]. A 240 Hz digitizer batches several samples
     * into one event, and dropping them turns a smooth curve into four
     * straight segments.
     */
    fun onPointerDown(sample: PointerSample) {
        handleDown(
            sample.pointerId, sample.tool, sample.x, sample.y, sample.timeNs,
            sample.pressure, sample.tilt, sample.orientation,
        )
        if (sample.tool == PointerTool.STYLUS || sample.tool == PointerTool.ERASER) {
            postHoverFrame()
        }
    }

    fun onPointerMove(sample: PointerSample) {
        handleMove(
            sample.pointerId, sample.x, sample.y, sample.timeNs,
            sample.pressure, sample.tilt, sample.orientation,
        )
    }

    /** Closes one event's worth of moves; see [handleMoveEnd]. */
    fun onPointerMoveEnd(timeNs: Long) = handleMoveEnd(timeNs)

    fun onPointerUp(sample: PointerSample) {
        handleUp(
            sample.pointerId, sample.x, sample.y, sample.timeNs,
            sample.pressure, sample.tilt, sample.orientation,
        )
    }

    fun onPointerCancel(timeNs: Long) = handleCancel(timeNs)

    /**
     * A lift the platform flagged as canceled — Android API 33+'s retroactive
     * `FLAG_CANCELED` on an `UP`/`POINTER_UP`, with the platform facts already
     * neutralized by the glue ([kind], [flagged], [apiLevel]).
     *
     * Rolls the whole gesture back and returns true — the event is consumed —
     * or returns false when the gate declines: a flag below the platform that
     * delivers it, a non-lift action, or a non-primary flagged lift belonging
     * to a pointer that was not drawing (a rejected palm beside a live pen
     * follows normal per-pointer cleanup instead, exactly as `handleUp`
     * would give it).
     */
    fun onPlatformCanceledUp(
        kind: PointerUpKind,
        flagged: Boolean,
        apiLevel: Int,
        pointerId: Int,
        timeNs: Long,
    ): Boolean {
        // The flag is delivered only on T+ to apps targeting T+; the runtime
        // check is a safe superset of that platform contract.
        if (apiLevel < PLATFORM_CANCEL_MIN_API) return false
        if (kind == PointerUpKind.OTHER) return false
        if (!flagged) return false
        if (kind == PointerUpKind.POINTER_UP && pointerId != drawingId) return false

        handleCancel(timeNs)
        return true
    }

    /** §6's barrel button state, already resolved by the glue. */
    fun onStylusButton(state: ButtonState) = stylus.onButton(state)

    /** Hover arrival; the sample's [PointerSample.distance] is the hover axis. */
    fun onHoverEnter(sample: PointerSample) {
        stylus.onHoverEnter(sample.x, sample.y, sample.distance, sample.tool)
        postHoverFrame()
    }

    fun onHoverMove(sample: PointerSample) {
        stylus.onHoverMove(sample.x, sample.y, sample.distance)
        postHoverFrame()
    }

    fun onHoverExit(timeNs: Long) {
        stylus.onHoverExit(timeNs)
        postHoverFrame()
    }

    /**
     * Wheel and trackpad scroll, with the pivot policy applied here because
     * the handler owns the viewport ([handleScroll]'s semantics; the
     * platform half — `AXIS_VSCROLL` reads and tick sums — stays with the
     * glue). [pointerClass] is true for pointer-class sources, false for a
     * touchpad reporting directly (pad-relative coordinates pivot at the
     * viewport centre instead).
     */
    fun onScroll(eventX: Float, eventY: Float, ticks: Float, pointerClass: Boolean): Boolean {
        val f = fit ?: return false
        val pivot = ScrollZoom.pivot(
            pointerClass = pointerClass,
            eventX = eventX,
            eventY = eventY,
            viewWidth = f.viewWidth,
            viewHeight = f.viewHeight,
        ) ?: return false
        return handleScroll(pivot.first, pivot.second, ticks)
    }

    private companion object {
        const val NO_POINTER = -1

        /** The platform release whose retroactive-cancellation flag is honored. */
        const val PLATFORM_CANCEL_MIN_API = 33

        /** Android orientation zero is screen-up; engine angle zero is +x. */
        val ANDROID_ORIENTATION_BASIS_RAD = (PI / 2.0).toFloat()
    }
}

/** The lift flavor a cancellation flag can ride on, neutralized from the platform's action codes. */
enum class PointerUpKind { UP, POINTER_UP, OTHER }
