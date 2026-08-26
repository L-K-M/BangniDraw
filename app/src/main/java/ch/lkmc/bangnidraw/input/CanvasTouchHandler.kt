package ch.lkmc.bangnidraw.input

import android.view.MotionEvent
import android.view.View
import ch.lkmc.bangnidraw.engine.core.GestureArbiter
import ch.lkmc.bangnidraw.engine.core.GestureListener
import ch.lkmc.bangnidraw.engine.core.NavigationStep
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.RotationSnap
import ch.lkmc.bangnidraw.engine.core.StrokeSource
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

    /** Roadmap 2.4b. A stroke began with [source] at this pointer. */
    fun onStrokeBegin(pointerId: Int, source: StrokeSource) {}

    /** Roadmap 2.4b. One or more samples, already in canvas px. */
    fun onStrokeSample(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, timeNs: Long) {}

    fun onStrokeEnd(pointerId: Int) {}

    /** No history entry, no pixels: the stroke never happened (§4). */
    fun onStrokeCancel() {}
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

    var stylusOnly: Boolean
        get() = arbiter.stylusOnly
        set(value) { arbiter.stylusOnly = value }

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

    /** The last position of every tracked pointer, so a move has a previous. */
    private val trackIds = IntArray(GestureArbiter.MAX_POINTERS) { NO_POINTER }
    private val trackX = FloatArray(GestureArbiter.MAX_POINTERS)
    private val trackY = FloatArray(GestureArbiter.MAX_POINTERS)

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

    /** The timestamp of the event being processed, for the down sample. */
    private var lastEventNs = 0L

    /** A field, not a lambda: an object allocated per event is what §2.4 forbids. */
    private val decisions = object : GestureListener {
        override fun onDraw(pointerId: Int, source: StrokeSource) {
            navigating = false
            strokeLive = true
            drawingId = pointerId
            host.onStrokeBegin(pointerId, source)
            // The down that opened the stroke is a sample too. Without it a tap
            // that never moves leaves no mark at all, and a fast stroke starts
            // at its second sample.
            val i = trackIndexOf(pointerId)
            if (i >= 0) emitSample(trackX[i], trackY[i], lastEventNs)
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
            host.onStrokeCancel()
        }
        override fun onTapUndo() = host.onUndoRequested()
        override fun onTapRedo() = host.onRedoRequested()
        override fun onLongPressPick(x: Float, y: Float) = host.onColorPick(x, y)
        override fun onIgnore(pointerId: Int) = Unit
        override fun onStrokeEnd(pointerId: Int) {
            strokeLive = false
            drawingId = NO_POINTER
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
        rawRotation = next.rotation
        snap.reset()
    }

    // ------------------------------------------------------- primitive path

    internal fun handleDown(pointerId: Int, tool: PointerTool, x: Float, y: Float, timeNs: Long) {
        lastEventNs = timeNs
        arbiter.stylusNear = PalmRejection.rejects(PointerTool.FINGER, stylus, timeNs)
        if (tool == PointerTool.STYLUS || tool == PointerTool.ERASER) {
            stylusPointerId = pointerId
            stylus.onDown(x, y, tool)
        }
        track(pointerId, x, y)
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
    internal fun handleMove(pointerId: Int, x: Float, y: Float, timeNs: Long) {
        // Before the arbiter, because the arbiter can decide "draw" from a
        // move — a finger past the slop — and the opening sample that decision
        // emits would otherwise be stamped with the last DOWN's timestamp,
        // hundreds of ms stale, skewing the velocity curve at stroke start.
        lastEventNs = timeNs
        arbiter.move(pointerId, x, y, timeNs, decisions)
        track(pointerId, x, y)
        pendingMove = true
        if (strokeLive && pointerId == drawingId) emitSample(x, y, timeNs)
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
     * Through [ViewTransform.invertX]/[invertY] rather than `invert`, because
     * this runs per sample and the `Pair` would be an allocation on the touch
     * path (§2.4).
     */
    private fun emitSample(x: Float, y: Float, timeNs: Long) {
        host.onStrokeSample(
            view.invertX(x, y),
            view.invertY(x, y),
            pressure,
            tilt,
            orientation,
            timeNs,
        )
    }

    /**
     * The axes of the pointer currently drawing, refreshed from every event.
     *
     * Fields rather than parameters threaded through the arbiter: the arbiter
     * is pure and knows nothing about pressure, and `handleMove`'s primitive
     * signature is what makes the handler drivable from a JVM test.
     */
    private var pressure = 1f
    private var tilt = 0f
    private var orientation = 0f

    /** Which pointer the arbiter said is drawing, or [NO_POINTER]. */
    private var drawingId = NO_POINTER

    /** Sets the axes for the next sample; `onTouch` reads them off the event. */
    internal fun setAxes(pressure: Float, tilt: Float, orientation: Float) {
        this.pressure = pressure
        this.tilt = tilt
        this.orientation = orientation
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

    private fun track(pointerId: Int, x: Float, y: Float) {
        for (i in trackIds.indices) {
            if (trackIds[i] == pointerId) {
                trackX[i] = x; trackY[i] = y
                return
            }
        }
        for (i in trackIds.indices) {
            if (trackIds[i] == NO_POINTER) {
                trackIds[i] = pointerId
                trackX[i] = x; trackY[i] = y
                return
            }
        }
    }

    private fun trackIndexOf(pointerId: Int): Int {
        for (i in trackIds.indices) if (trackIds[i] == pointerId) return i
        return -1
    }

    private fun untrack(pointerId: Int) {
        for (i in trackIds.indices) if (trackIds[i] == pointerId) trackIds[i] = NO_POINTER
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
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                setAxes(
                    e.getPressure(index),
                    e.getAxisValue(MotionEvent.AXIS_TILT, index),
                    e.getOrientation(index),
                )
                handleDown(id, toolOf(e.getToolType(index)), e.getX(index), e.getY(index), timeNs)
            }

            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    val hNs = e.getHistoricalEventTime(h) * 1_000_000L
                    for (p in 0 until e.pointerCount) {
                        // Per pointer, inside the loop. For ACTION_MOVE the
                        // action's pointer-index bits are always zero, so
                        // `index` is pointer 0 — and reading the axes there
                        // gave every pointer the FIRST pointer's pressure and
                        // tilt. That is wrong exactly when it matters most:
                        // with a palm down as pointer 0 and the pen drawing as
                        // pointer 1, every pen sample carried the palm's
                        // pressure and a tilt of zero.
                        setAxes(
                            e.getHistoricalPressure(p, h),
                            e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, p, h),
                            e.getHistoricalOrientation(p, h),
                        )
                        handleMove(
                            e.getPointerId(p),
                            e.getHistoricalX(p, h),
                            e.getHistoricalY(p, h),
                            hNs,
                        )
                    }
                    // Each historical sample is a complete event's worth of
                    // pointers, so it gets its own step.
                    handleMoveEnd(hNs)
                }
                for (p in 0 until e.pointerCount) {
                    setAxes(
                        e.getPressure(p),
                        e.getAxisValue(MotionEvent.AXIS_TILT, p),
                        e.getOrientation(p),
                    )
                    handleMove(e.getPointerId(p), e.getX(p), e.getY(p), timeNs)
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
                    stylus.onButton(true)
                }
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> stylus.onButton(false)
            MotionEvent.ACTION_CANCEL -> handleCancel(timeNs)
            else -> return false
        }
        return true
    }

    override fun onHover(v: View?, event: MotionEvent?): Boolean {
        val e = event ?: return false
        val timeNs = e.eventTime * 1_000_000L
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER ->
                stylus.onHoverEnter(e.x, e.y, e.getAxisValue(MotionEvent.AXIS_DISTANCE), toolOf(e.getToolType(0)))
            MotionEvent.ACTION_HOVER_MOVE ->
                stylus.onHoverMove(e.x, e.y, e.getAxisValue(MotionEvent.AXIS_DISTANCE))
            MotionEvent.ACTION_HOVER_EXIT -> stylus.onHoverExit(timeNs)
            else -> return false
        }
        return true
    }

    private fun toolOf(toolType: Int): PointerTool = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerTool.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerTool.ERASER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerTool.MOUSE
        else -> PointerTool.FINGER
    }

    private companion object {
        const val NO_POINTER = -1
    }
}
