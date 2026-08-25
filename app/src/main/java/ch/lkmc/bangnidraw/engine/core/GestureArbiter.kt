package ch.lkmc.bangnidraw.engine.core

import kotlin.math.abs
import kotlin.math.max

/** What a pointer is, as far as the arbiter cares (`docs/plan/07-input-and-stylus.md` §3). */
enum class PointerTool { STYLUS, ERASER, FINGER, MOUSE }

/**
 * What the arbiter decided. Delivered through [GestureListener] rather than
 * returned, because a single event can produce two decisions — a second finger
 * arriving cancels a pending stroke *and* starts navigation — and because
 * returning them would allocate on the touch path.
 */
interface GestureListener {
    /** This pointer is a stroke from its down position; buffered samples are replayed. */
    fun onDraw(pointerId: Int, source: StrokeSource)

    /** The pointers form a navigation gesture. Any live stroke was cancelled first. */
    fun onNavigate()

    /** Roll back the live stroke; no history entry (§4). */
    fun onCancelStroke()

    fun onTapUndo()
    fun onTapRedo()

    /** One finger held still; sample the colour under it. */
    fun onLongPressPick(x: Float, y: Float)

    /** Palm, or touch while the stylus is near. Nothing happens. */
    fun onIgnore(pointerId: Int)

    /** The drawing pointer lifted normally. */
    fun onStrokeEnd(pointerId: Int)

    /** Navigation ended; the view keeps whatever it reached (no fling, §7). */
    fun onNavigateEnd()
}

/**
 * The pointer state machine of `docs/plan/07-input-and-stylus.md` §3.
 *
 * Pure `engine/core`: it is fed a timeline of `down` / `move` / `up` / `cancel`
 * and has **no reference to `MotionEvent`**, so its tests build timelines by
 * hand and every rule below is checkable without a device. That is the whole
 * reason it is not in `input/` — PLAN.md §3 puts it beside [ViewTransform].
 *
 * Positions arrive in **view px**; the dp thresholds are converted once at
 * construction from [density], so a slop in dp compares correctly against them.
 *
 * The rules it implements, and why each number is what it is, are §3's table.
 * The two that shape everything else:
 *
 * - **A stylus draws immediately.** Pen latency is the product and a stylus
 *   never navigates, so there is nothing to disambiguate.
 * - **A finger is *pending* for [PENDING_MS].** Long enough to see the second
 *   finger of a chord (typically 20–80 ms behind the first); short enough not
 *   to feel laggy. Nothing is lost — the caller buffers the pending samples and
 *   replays them on [GestureListener.onDraw] — only the first pixels appear up
 *   to 120 ms late. A finger that moves past the slop first resolves early: a
 *   deliberate line is not a chord.
 *
 * Not thread-safe, and does not need to be: every caller is the main thread.
 */
class GestureArbiter(
    density: Float,
    /** Stylus-only mode: one finger pans instead of drawing (§3, the S Pen request). */
    var stylusOnly: Boolean = false,
) {

    init {
        require(density > 0f && density.isFinite()) { "density must be positive, was $density" }
    }

    /** [TAP_SLOP_DP] in view px. */
    private val slopPx: Float = TAP_SLOP_DP * density

    /**
     * Set by the caller from `StylusState` before feeding an event: true while
     * a stylus is down or hovering, and for the hover grace after it leaves
     * (§5). While true every finger pointer is ignored.
     *
     * A property rather than a parameter because it is a property of the
     * *device*, not of the event, and threading it through four methods would
     * invite a call site that forgets it.
     */
    var stylusNear: Boolean = false

    private enum class State { IDLE, STYLUS_DRAW, FINGER_PENDING, FINGER_DRAW, NAVIGATE, TAP_WAIT }

    private var state = State.IDLE

    /** Per active pointer, parallel arrays — no per-event allocation. */
    private val ids = IntArray(MAX_POINTERS) { NO_POINTER }
    private val downX = FloatArray(MAX_POINTERS)
    private val downY = FloatArray(MAX_POINTERS)
    private val curX = FloatArray(MAX_POINTERS)
    private val curY = FloatArray(MAX_POINTERS)
    private val downNs = LongArray(MAX_POINTERS)
    private val movedPast = BooleanArray(MAX_POINTERS)
    private val ignored = BooleanArray(MAX_POINTERS)
    private var count = 0

    /** The pointer currently drawing, or [NO_POINTER]. */
    private var drawingId = NO_POINTER

    /**
     * The most pointers down at once during this gesture — what a tap counts.
     *
     * The *maximum*, not the current count, so two fingers landing a few ms
     * apart still read as a two-finger tap.
     */
    private var maxDown = 0

    /** True once this gesture became a navigation, so a third finger is ignored. */
    private var navigating = false

    /**
     * Whether this gesture could still be a tap.
     *
     * Gesture-level, not per pointer, because [isTapCandidate] used to walk the
     * live pointers — and by the last `up` the earlier ones are already gone,
     * so their movement and timing had been forgotten and no multi-finger tap
     * could ever qualify. Cleared when any pointer passes the slop, when any
     * pointer's own lift is past [TAP_MS], or when a stroke begins.
     *
     * Note what does *not* clear it: entering navigation. §3 is explicit that a
     * two-finger tap is an undo and that it becomes a pan "from the moment slop
     * is exceeded" — so movement disqualifies a tap, and the decision to start
     * tracking a navigation does not.
     */
    private var tapPossible = true

    /** True once a long press fired, so it cannot fire twice for one hold. */
    private var longPressFired = false

    // ------------------------------------------------------------- events

    fun down(pointerId: Int, tool: PointerTool, x: Float, y: Float, timeNs: Long, out: GestureListener) {
        val stylus = tool == PointerTool.STYLUS || tool == PointerTool.ERASER
        // A stylus landing takes over from anything a finger was doing: at that
        // point the pen is the intent (§5). Navigation ends without a step, a
        // finger stroke is rolled back.
        if (stylus) {
            if (state == State.NAVIGATE) {
                navigating = false
                out.onNavigateEnd()
            } else if (drawingId != NO_POINTER || state == State.FINGER_PENDING) {
                out.onCancelStroke()
            }
            clearPointers()
            add(pointerId, x, y, timeNs)
            state = State.STYLUS_DRAW
            drawingId = pointerId
            maxDown = 1
            out.onDraw(pointerId, if (tool == PointerTool.ERASER) StrokeSource.ERASER_END else StrokeSource.STYLUS)
            return
        }

        // A finger while the pen is down or near is a palm, always (§5).
        if (state == State.STYLUS_DRAW || stylusNear) {
            val slot = add(pointerId, x, y, timeNs)
            if (slot >= 0) ignored[slot] = true
            out.onIgnore(pointerId)
            return
        }

        val slot = add(pointerId, x, y, timeNs)
        if (slot < 0) {
            // More pointers than the arbiter tracks. Ignoring is the safe
            // answer: §7's table ends at three fingers and says "nothing else".
            out.onIgnore(pointerId)
            return
        }
        maxDown = max(maxDown, count)

        when (state) {
            State.IDLE, State.TAP_WAIT -> {
                state = State.FINGER_PENDING
                longPressFired = false
            }
            State.FINGER_PENDING -> {
                // The second finger of a chord, inside the pending window: the
                // stroke has not committed, so nothing is rolled back — but the
                // caller may already have buffered samples, and §3 says a
                // pending stroke is cancelled.
                out.onCancelStroke()
                beginNavigation(out)
            }
            State.FINGER_DRAW -> {
                // §3: a second finger AFTER the pending window is ignored. The
                // user is drawing with one finger and rested another; changing
                // modes mid-stroke would be a surprise.
                ignored[slot] = true
                out.onIgnore(pointerId)
            }
            State.NAVIGATE -> {
                // Two pointers navigate; a third is ignored (§7's table stops
                // at three fingers, and only for taps).
                ignored[slot] = true
                out.onIgnore(pointerId)
            }
            State.STYLUS_DRAW -> Unit // handled above
        }
    }

    fun move(pointerId: Int, x: Float, y: Float, timeNs: Long, out: GestureListener) {
        val slot = indexOf(pointerId)
        if (slot < 0) return
        curX[slot] = x
        curY[slot] = y
        if (abs(x - downX[slot]) > slopPx || abs(y - downY[slot]) > slopPx) {
            movedPast[slot] = true
            if (!ignored[slot]) tapPossible = false
        }
        if (ignored[slot]) return

        when (state) {
            State.FINGER_PENDING -> {
                if (movedPast[slot]) {
                    // A deliberate line is not a chord: resolve early rather
                    // than making the user wait out the pending window.
                    if (stylusOnly) beginNavigation(out) else beginFingerDraw(pointerId, out)
                }
            }
            else -> Unit
        }
    }

    /**
     * Advances time without a pointer event.
     *
     * The pending window and the long press both expire on a clock, not on a
     * move — a finger held perfectly still produces no `ACTION_MOVE` at all, so
     * without this a motionless finger would stay pending forever and the long
     * press could never fire.
     */
    fun tick(timeNs: Long, out: GestureListener) {
        if (state != State.FINGER_PENDING) return
        val slot = firstActive()
        if (slot < 0) return
        val heldMs = (timeNs - downNs[slot]) / 1_000_000L
        if (!longPressFired && !movedPast[slot] && heldMs >= LONG_PRESS_MS) {
            longPressFired = true
            out.onLongPressPick(curX[slot], curY[slot])
            return
        }
        // Only when touch drawing is on. In stylus-only mode the pending window
        // does not resolve on the clock at all: §3's diagram says one finger
        // goes to navigation "after slop", and [move] is what takes it there.
        // Resolving here too would fire Navigate at 120 ms and the long press
        // at 500 ms could never be reached — which is the one mode §3 says
        // long-press-pick exists in.
        if (!stylusOnly && heldMs >= PENDING_MS) beginFingerDraw(ids[slot], out)
    }

    fun up(pointerId: Int, timeNs: Long, out: GestureListener) {
        val slot = indexOf(pointerId)
        if (slot < 0) return
        val wasIgnored = ignored[slot]
        if (!wasIgnored) noteLift(slot, timeNs)
        remove(slot)

        // What THIS pointer's lift means, if it was participating.
        if (!wasIgnored) {
            when (state) {
                State.STYLUS_DRAW -> out.onStrokeEnd(pointerId)
                State.FINGER_DRAW -> if (drawingId == pointerId) {
                    out.onStrokeEnd(pointerId)
                    drawingId = NO_POINTER
                }
                // §3: navigation continues with the remaining pointer as
                // pan-only until it lifts — no zoom from one finger.
                State.NAVIGATE -> Unit
                State.FINGER_PENDING, State.TAP_WAIT -> if (count > 0) state = State.TAP_WAIT
                State.IDLE -> Unit
            }
        }

        // And what the GESTURE's end means — in one place, reached whoever
        // lifted last. Finalising inside the branch above meant an ignored
        // pointer lifting last returned early, so a three-finger tap (whose
        // third finger is ignored for navigation) ended without its navEnd and
        // without firing redo.
        if (count == 0) {
            if (state == State.NAVIGATE) out.onNavigateEnd()
            // Motionless fingers reach here having entered navigation on the
            // second down: that is exactly what a multi-finger tap looks like.
            if (state != State.STYLUS_DRAW && state != State.FINGER_DRAW) emitTap(out)
            resetGesture()
        }
    }

    /**
     * The platform took the gesture away, or `FLAG_CANCELED` retroactively
     * rejected the pointer (§4). A cancelled stroke leaves **no trace**.
     */
    fun cancel(out: GestureListener) {
        if (state == State.NAVIGATE) {
            out.onNavigateEnd()
        } else if (drawingId != NO_POINTER || state == State.FINGER_PENDING) {
            out.onCancelStroke()
        }
        resetGesture()
    }

    /** Drops all state without emitting anything — a new surface, a new session. */
    fun reset() = resetGesture()

    // ------------------------------------------------------------ internals

    private fun beginFingerDraw(pointerId: Int, out: GestureListener) {
        state = State.FINGER_DRAW
        drawingId = pointerId
        tapPossible = false
        out.onDraw(pointerId, StrokeSource.FINGER)
    }

    private fun beginNavigation(out: GestureListener) {
        state = State.NAVIGATE
        navigating = true
        drawingId = NO_POINTER
        out.onNavigate()
    }

    /**
     * Records one pointer's lift against the tap rules: up within [TAP_MS] of
     * **its own** down, and never past the slop.
     *
     * Its own down, not the gesture's first, so two fingers landing a few ms
     * apart both get the full window.
     */
    private fun noteLift(slot: Int, timeNs: Long) {
        if ((timeNs - downNs[slot]) / 1_000_000L > TAP_MS) tapPossible = false
        if (movedPast[slot]) tapPossible = false
    }

    private fun emitTap(out: GestureListener) {
        if (!tapPossible) return
        when (maxDown) {
            2 -> out.onTapUndo()
            3 -> out.onTapRedo()
            else -> Unit
        }
    }

    private fun add(pointerId: Int, x: Float, y: Float, timeNs: Long): Int {
        if (indexOf(pointerId) >= 0) return NO_POINTER
        for (i in 0 until MAX_POINTERS) {
            if (ids[i] == NO_POINTER) {
                ids[i] = pointerId
                downX[i] = x; downY[i] = y
                curX[i] = x; curY[i] = y
                downNs[i] = timeNs
                movedPast[i] = false
                ignored[i] = false
                count++
                return i
            }
        }
        return NO_POINTER
    }

    private fun remove(slot: Int) {
        if (ids[slot] == NO_POINTER) return
        ids[slot] = NO_POINTER
        count--
    }

    private fun indexOf(pointerId: Int): Int {
        for (i in 0 until MAX_POINTERS) if (ids[i] == pointerId) return i
        return -1
    }

    private fun firstActive(): Int {
        for (i in 0 until MAX_POINTERS) if (ids[i] != NO_POINTER && !ignored[i]) return i
        return -1
    }

    private fun clearPointers() {
        for (i in 0 until MAX_POINTERS) ids[i] = NO_POINTER
        count = 0
    }

    private fun resetGesture() {
        clearPointers()
        state = State.IDLE
        drawingId = NO_POINTER
        maxDown = 0
        navigating = false
        longPressFired = false
        tapPossible = true
    }

    /** Test and debug window onto the machine; never branched on by production code. */
    internal val stateName: String get() = state.name

    companion object {
        /**
         * How long a finger stays pending before it becomes a stroke.
         *
         * The tests reference these by name, so tuning on a device is a
         * one-line change with the tests still meaningful (§3).
         */
        const val PENDING_MS = 120L

        /** Up within this of its own down, and under the slop, is a tap. */
        const val TAP_MS = 200L

        /** Android's standard touch-slop order of magnitude. */
        const val TAP_SLOP_DP = 8f

        /** Matches the platform long press. */
        const val LONG_PRESS_MS = 500L

        /**
         * Three fingers is the most any gesture in §7's table uses, and it ends
         * with "Nothing else": four-finger gestures have been a palm-triggered
         * accident in every app that shipped them. A fourth pointer is tracked
         * only so it can be ignored by id.
         */
        const val MAX_POINTERS = 4

        private const val NO_POINTER = -1
    }
}
