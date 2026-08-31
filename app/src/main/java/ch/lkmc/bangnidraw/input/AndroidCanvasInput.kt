package ch.lkmc.bangnidraw.input

import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import ch.lkmc.bangnidraw.engine.core.ButtonState
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.StylusButtonPolicy

/**
 * The Android half of the input seam (DESKTOP.md seam 3): the **only** code
 * that touches `MotionEvent`. It flattens events into one reused
 * [PointerSample] and drives the shared [CanvasTouchHandler]'s record
 * entries, and it owns every platform service the handler stays ignorant
 * of — unbuffered dispatch, `Choreographer`, the `MotionEventPredictor`.
 *
 * Attached per surface in `CanvasSurface`, like the listener wiring it
 * replaced. Behavior is the translation the handler's `onTouch` used to
 * carry inline, unchanged; the class exists so the same decisions can be
 * driven by any platform's pointer events.
 */
class AndroidCanvasInput(
    private val handler: CanvasTouchHandler,
) : View.OnTouchListener, View.OnHoverListener, View.OnGenericMotionListener {

    init {
        // Before any event can run: the frame-driven paths (predicted tail,
        // hover coalescing) post through this, not through Choreographer
        // directly. An instance, not a singleton — the callback cache below
        // lives and dies with this adapter, so a replaced handler cannot
        // pin retired Runnables.
        val choreographer = object : FrameScheduler {
            override fun post(callback: Runnable) {
                Choreographer.getInstance().postFrameCallback(frameCallbackFor(callback))
            }

            override fun cancel(callback: Runnable) {
                // Evict while cancelling: the cache must not outlive the
                // callback it wrapped, and removing a never-posted callback
                // must not create an entry for it.
                val wrapped = wraps.remove(callback) ?: return
                Choreographer.getInstance().removeFrameCallback(wrapped)
            }

            private fun frameCallbackFor(callback: Runnable): Choreographer.FrameCallback =
                wraps.computeIfAbsent(callback) { runnable ->
                    Choreographer.FrameCallback { runnable.run() }
                }

            private val wraps = java.util.concurrent.ConcurrentHashMap<Runnable, Choreographer.FrameCallback>()
        }
        handler.frameScheduler = choreographer
    }

    /** The one sample every entry reuses — the touch path stays zero-alloc. */
    private val sample = PointerSample()

    private var deadlineView: View? = null

    /**
     * The view the predictor was last attempted for, successful or not —
     * the "do not retry a failing `newInstance` at 240 Hz" memory, which
     * lived on the handler before the seam and cannot: a null predictor is
     * indistinguishable from "not tried yet" without it.
     */
    private var predictorView: View? = null
    private var predictor: Predictor? = null

    /**
     * The translation layer. Historical samples are consumed before the
     * current one (§2): a 240 Hz digitizer batches several samples into one
     * 60 Hz event, and dropping them turns a smooth curve into four straight
     * segments.
     */
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        val e = event ?: return false
        val action = e.actionMasked
        val index = e.actionIndex
        val id = e.getPointerId(index)
        val timeNs = e.eventTime * NANOS_PER_MILLISECOND
        if (consumesPlatformCancellation(e, action, index, timeNs)) return true
        attachDeadlineScheduler(v)
        attachPredictor(v)
        recordForPrediction(e)
        syncStylusButton(e)
        // Before the `when`, not inside its DOWN arm. That arm matches
        // ACTION_POINTER_DOWN too, so the call needed a nested re-check of
        // the value the `when` had already switched on — invisible to anyone
        // scanning the arms, and silently inherited by whatever action is
        // added to that arm next.
        if (action == MotionEvent.ACTION_DOWN) requestUnbuffered(v, e)
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> handler.onPointerDown(
                sample.set(
                    pointerId = id,
                    tool = toolOf(e.getToolType(index)),
                    x = e.getX(index),
                    y = e.getY(index),
                    pressure = e.getPressure(index),
                    tilt = e.getAxisValue(MotionEvent.AXIS_TILT, index),
                    orientation = e.getOrientation(index),
                    timeNs = timeNs,
                ),
            )

            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    val hNs = e.getHistoricalEventTime(h) * NANOS_PER_MILLISECOND
                    for (p in 0 until e.pointerCount) {
                        // Axes read at index `p`, the same pointer the sample
                        // is filled for. For ACTION_MOVE the action's
                        // pointer-index bits are always zero, so reading them
                        // at `actionIndex` gave every pointer the FIRST
                        // pointer's pressure and tilt — wrong exactly when it
                        // matters most, with a palm down as pointer 0 and the
                        // pen drawing as pointer 1.
                        handler.onPointerMove(
                            sample.set(
                                pointerId = e.getPointerId(p),
                                tool = toolOf(e.getToolType(p)),
                                x = e.getHistoricalX(p, h),
                                y = e.getHistoricalY(p, h),
                                pressure = e.getHistoricalPressure(p, h),
                                tilt = e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, p, h),
                                orientation = e.getHistoricalOrientation(p, h),
                                timeNs = hNs,
                            ),
                        )
                    }
                    // Each historical sample is a complete event's worth of
                    // pointers, so it gets its own step.
                    handler.onPointerMoveEnd(hNs)
                }
                for (p in 0 until e.pointerCount) {
                    handler.onPointerMove(
                        sample.set(
                            pointerId = e.getPointerId(p),
                            tool = toolOf(e.getToolType(p)),
                            x = e.getX(p),
                            y = e.getY(p),
                            pressure = e.getPressure(p),
                            tilt = e.getAxisValue(MotionEvent.AXIS_TILT, p),
                            orientation = e.getOrientation(p),
                            timeNs = timeNs,
                        ),
                    )
                }
                handler.onPointerMoveEnd(timeNs)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> handler.onPointerUp(
                sample.set(
                    pointerId = id,
                    tool = toolOf(e.getToolType(index)),
                    x = e.getX(index),
                    y = e.getY(index),
                    pressure = e.getPressure(index),
                    tilt = e.getAxisValue(MotionEvent.AXIS_TILT, index),
                    orientation = e.getOrientation(index),
                    timeNs = timeNs,
                ),
            )
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
                // The pre-`when` syncStylusButton(e) already ran for this
                // event with the stronger all-pointers pen scan; this arm
                // only keeps the stream consumed. The historical guarded
                // re-call duplicated an idempotent state set.
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> handler.onStylusButton(ButtonState.Released)
            MotionEvent.ACTION_CANCEL -> handler.onPointerCancel(timeNs)
            else -> return false
        }
        return true
    }

    override fun onHover(v: View?, event: MotionEvent?): Boolean {
        val e = event ?: return false
        val timeNs = e.eventTime * NANOS_PER_MILLISECOND
        attachPredictor(v)
        // §8 records hover too: hover history improves the first predicted
        // samples after contact, which is the moment the tail is least accurate
        // and the pen is moving fastest.
        recordForPrediction(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                requestUnbufferedHover(v)
                handler.onHoverEnter(
                    sample.set(
                        pointerId = e.getPointerId(0),
                        tool = toolOf(e.getToolType(0)),
                        x = e.x,
                        y = e.y,
                        distance = e.getAxisValue(MotionEvent.AXIS_DISTANCE),
                        timeNs = timeNs,
                    ),
                )
            }
            MotionEvent.ACTION_HOVER_MOVE -> handler.onHoverMove(
                sample.set(
                    pointerId = e.getPointerId(0),
                    tool = toolOf(e.getToolType(0)),
                    x = e.x,
                    y = e.y,
                    distance = e.getAxisValue(MotionEvent.AXIS_DISTANCE),
                    timeNs = timeNs,
                ),
            )
            MotionEvent.ACTION_HOVER_EXIT -> handler.onHoverExit(timeNs)
            else -> return false
        }
        return true
    }

    /**
     * Wheel and trackpad scroll — the one generic-motion event the canvas
     * consumes. Pointer-class sources zoom about the cursor. `SOURCE_TOUCHPAD`
     * is position-class, not pointer-class, so it is accepted by name: most
     * touchpads scroll through a synthesized mouse pointer, but one that
     * reports directly carries pad-relative coordinates — the viewport centre
     * is the only honest pivot there, and before layout has provided one
     * the event is dropped rather than zoomed about pad coordinates. A
     * rotary encoder or joystick also delivers `ACTION_SCROLL` and stays
     * refused: no cursor, no pad, nothing to zoom about.
     *
     * Historical samples are summed with the current one — a batching device
     * folds several movements into one event, and dropping them under-zooms a
     * fling. The per-event tick bound applies to the sum.
     *
     * `AXIS_VSCROLL` is positive with the wheel rolled away from the user,
     * which zooms in — the shared convention of maps and every desktop
     * canvas app. Horizontal scroll is deliberately left unconsumed until it
     * has a defined meaning.
     */
    override fun onGenericMotion(v: View?, event: MotionEvent?): Boolean {
        val e = event ?: return false
        if (e.actionMasked != MotionEvent.ACTION_SCROLL) return false
        val pointerClass = e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        if (!pointerClass && !e.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return false

        var ticks = 0f
        for (h in 0 until e.historySize) {
            ticks += e.getHistoricalAxisValue(MotionEvent.AXIS_VSCROLL, h)
        }
        ticks += e.getAxisValue(MotionEvent.AXIS_VSCROLL)

        return handler.onScroll(
            eventX = e.x,
            eventY = e.y,
            ticks = ticks,
            pointerClass = pointerClass,
        )
    }

    // ------------------------------------------------------------- plumbing

    /**
     * API 33+ retroactive cancellation (`FLAG_CANCELED` on an `UP`): the
     * pointer-identity half of the gate lives in the handler; everything the
     * handler cannot know — the raw action code, the flag, the platform
     * level — is neutralized here first.
     */
    private fun consumesPlatformCancellation(
        e: MotionEvent,
        action: Int,
        index: Int,
        timeNs: Long,
    ): Boolean {
        val kind = when (action) {
            MotionEvent.ACTION_UP -> PointerUpKind.UP
            MotionEvent.ACTION_POINTER_UP -> PointerUpKind.POINTER_UP
            else -> PointerUpKind.OTHER
        }

        return handler.onPlatformCanceledUp(
            kind = kind,
            flagged = e.flags and MotionEvent.FLAG_CANCELED != 0,
            apiLevel = Build.VERSION.SDK_INT,
            pointerId = e.getPointerId(index),
            timeNs = timeNs,
        )
    }

    private fun attachDeadlineScheduler(view: View?) {
        if (view == null || view === deadlineView) return
        deadlineView = view
        handler.attachDeadlineScheduler(ViewGestureDeadlineScheduler(view))
    }

    /**
     * Builds the predictor for [v] the first time this view dispatches to us,
     * and **once only** — a view whose attempt failed is not retried.
     *
     * The handler's predictor field is re-written every event rather than
     * only on change: `reset`/`dispose` null it as part of rolling handler
     * state back, and a stale null would otherwise silently disable the tail
     * for the rest of the surface's life — the exact regression the
     * predictor-view memory exists to avoid, reached from the other side.
     */
    private fun attachPredictor(v: View?) {
        if (v == null) return
        if (v !== predictorView) {
            predictorView = v
            predictor = Predictor.forView(v, predictor)
        }
        handler.predictor = predictor
    }

    /**
     * Feeds one real event to the predictor — §8's "every `DOWN`/`MOVE`/`UP`
     * for a stylus pointer, including `ACTION_HOVER_MOVE`".
     *
     * Scoped to the pen: a finger or a mouse dragging across the glass is
     * history the predictor would fit a curve to and then answer the pen's
     * next `predict()` with. §8 does not predict fingers in v1 at all.
     *
     * **Every pointer is checked, not index 0.** A `MotionEvent` carries one
     * tool type per pointer, so a palm that landed first is pointer 0 and the
     * pen is pointer 1 — and reading the tool at 0 classified the whole event
     * as finger input and recorded no pen history at all. That fails exactly
     * on palm-heavy usage, which is the hardware a predicted tail is for, and
     * it fails silently: the tail either never appears or keeps extrapolating
     * from pre-palm samples. Same defect class as the `actionIndex` bug the
     * per-pointer sample fill fixed — a per-pointer fact read at a single
     * fixed index.
     *
     * Nothing predicted is ever recorded back (§8), which holds here by
     * construction: this is only ever reached from [onTouch] and [onHover],
     * and a predicted event never arrives through either.
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
     * **`SOURCE_CLASS_POINTER`, not `SOURCE_STYLUS`**, which is what
     * `07-input-and-stylus.md` §2.1 writes — it flagged the call "(to
     * verify)", and this is the verification. The parameter is annotated
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
        handler.onStylusButton(state)
    }

    private fun toolOf(toolType: Int): PointerTool = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerTool.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerTool.ERASER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerTool.MOUSE
        else -> PointerTool.FINGER
    }

}

/** Converts uptime-based milliseconds (`MotionEvent.getEventTime`, `SystemClock.uptimeMillis`) to nanoseconds. */
private const val NANOS_PER_MILLISECOND = 1_000_000L

/** The handler's [GestureDeadlineScheduler] on a dispatching `View`. */
private class ViewGestureDeadlineScheduler(
    private val view: View,
) : GestureDeadlineScheduler {

    override fun scheduleAt(deadlineNs: Long, callback: Runnable) {
        val nowNs = SystemClock.uptimeMillis() * NANOS_PER_MILLISECOND
        val remainingNs = deadlineNs - nowNs
        val delayMs = if (remainingNs <= 0L) {
            0L
        } else {
            (remainingNs + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
        }

        view.postDelayed(callback, delayMs)
    }

    override fun cancel(callback: Runnable) {
        view.removeCallbacks(callback)
    }
}

