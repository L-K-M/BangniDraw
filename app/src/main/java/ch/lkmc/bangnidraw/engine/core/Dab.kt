package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.DAB_BATCH_CAPACITY
import ch.lkmc.bangnidraw.engine.core.PerfConstants.DAB_RING_SLOTS
import java.util.concurrent.atomic.AtomicLong

/**
 * One dab, as a value — for tests, fixtures and anything off the hot path.
 *
 * The stroke path does **not** allocate these: it writes the same eleven
 * fields into the parallel arrays of a [DabBatch] (`02-architecture.md`
 * §3.2), which is what `DAB_STRIDE` counts. Colour and opacity are not here
 * because they are per *stroke*, not per dab.
 */
data class Dab(
    /** Canvas px, sub-pixel. There is no pixel snapping anywhere. */
    val x: Float,
    val y: Float,
    /** Canvas px, at least [MIN_RADIUS]. */
    val radius: Float,
    /** Dab alpha after the curves. */
    val flow: Float,
    val hardness: Float,
    /** Radians. */
    val angle: Float,
    /** Minor/major axis; 1 is round. */
    val aspect: Float,
    /** Standard per-dab phase; fixed for one Chinese ink stroke. */
    val seed: Float,
    /** Ink remaining in the contacted tuft; 1 for ordinary dabs. */
    val wetness: Float = 1f,
    /** Transported bristle-field coordinates at the dab centre. */
    val bristleAlong: Float = 0f,
    val bristleAcross: Float = 0f,
) {
    companion object {
        /**
         * `04-tools.md` §3.5: a 1 px pen at light pressure still draws a faint
         * anti-aliased line rather than vanishing between sub-pixel gaps.
         * Below 1 px `DabPass` draws at r = 1 and weights alpha by the area,
         * so a thinning stroke fades out instead of snapping off.
         */
        const val MIN_RADIUS = 0.5f

        /** So one dab quad never exceeds a reasonable dirty rect (§3.5). */
        const val MAX_RADIUS = 1024f
    }
}

/**
 * A run of dabs as a struct of arrays (`02-architecture.md` §3.2),
 * preallocated and reused.
 *
 * Struct-of-arrays rather than an array of [Dab]: the GL thread uploads each
 * field as a contiguous vertex attribute, and an array of objects would mean
 * a gather per dab plus `capacity` allocations the input path cannot afford.
 */
class DabBatch(capacity: Int = DAB_BATCH_CAPACITY) {
    init {
        require(capacity > 0) { "dab batch capacity must be positive, was $capacity" }
    }

    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val radius = FloatArray(capacity)
    val flow = FloatArray(capacity)
    val hardness = FloatArray(capacity)
    val angle = FloatArray(capacity)
    val aspect = FloatArray(capacity)
    val seed = FloatArray(capacity)
    val wetness = FloatArray(capacity)
    val bristleAlong = FloatArray(capacity)
    val bristleAcross = FloatArray(capacity)

    var count = 0
        private set

    /** Distinguishes the same pooled object across separate acquisitions. */
    internal var reuseGeneration = 0L
        private set

    var strokeId = 0L

    /**
     * Index of the first predicted dab, or -1 when the batch has none.
     *
     * `private set` because `CanvasRenderer.stampDabs` splits a batch on
     * [committedCount] and hands the halves to `DabPass.stamp`, whose range
     * `require` turns an out-of-range header into an `IllegalArgumentException`
     * on the GL thread. [markPredictedFromHere] and [clear] are the only
     * writers and both keep it in `-1..count`, so the invariant the renderer
     * relies on holds by construction rather than by convention — which is what
     * makes clamping at the read site the wrong fix: a clamp would draw a
     * plausible frame from a header that was already wrong.
     */
    var predictedFrom = -1
        private set

    private var dirtyLeft = 0
    private var dirtyTop = 0
    private var dirtyRight = 0
    private var dirtyBottom = 0
    private var dirtyEmpty = true

    /** One value allocation when consumed; adding dabs mutates primitive edges. */
    val dirty: IntRect
        get() = if (dirtyEmpty) {
            IntRect.EMPTY
        } else {
            IntRect(dirtyLeft, dirtyTop, dirtyRight, dirtyBottom)
        }

    val capacity: Int get() = x.size
    val isFull: Boolean get() = count == x.size

    /** Exact canvas bounds for a populated subrange of this batch. */
    fun bounds(from: Int = 0, until: Int = count): IntRect {
        require(from in 0..until && until <= count) {
            "dab range [$from, $until) is not valid for $count dabs"
        }
        if (from == until) return IntRect.EMPTY
        if (from == 0 && until == count) return dirty

        DabBounds.requireValid(x[from], y[from], radius[from])
        var left = DabBounds.left(x[from], radius[from])
        var top = DabBounds.top(y[from], radius[from])
        var right = DabBounds.right(x[from], radius[from])
        var bottom = DabBounds.bottom(y[from], radius[from])
        for (index in from + 1 until until) {
            DabBounds.requireValid(x[index], y[index], radius[index])
            left = minOf(left, DabBounds.left(x[index], radius[index]))
            top = minOf(top, DabBounds.top(y[index], radius[index]))
            right = maxOf(right, DabBounds.right(x[index], radius[index]))
            bottom = maxOf(bottom, DabBounds.bottom(y[index], radius[index]))
        }
        return IntRect(left, top, right, bottom)
    }

    /**
     * Appends one dab and returns true, or returns false when the batch is
     * full — the caller publishes it and takes the next ring slot. A `false`
     * rather than a throw: a full batch is the normal rhythm of a long
     * stroke, not a bug.
     */
    fun add(
        x: Float,
        y: Float,
        radius: Float,
        flow: Float,
        hardness: Float,
        angle: Float,
        aspect: Float,
        seed: Float,
        wetness: Float = 1f,
        bristleAlong: Float = 0f,
        bristleAcross: Float = 0f,
    ): Boolean {
        // [Dab]'s documented range, enforced where it is written rather than
        // only promised. An oversized radius is not a wrong-looking dab, it is
        // a [dirty] rect inflated for the whole batch — which turns partial
        // redraw into a full-canvas repaint with nothing pointing at the dab
        // that caused it. One comparison against work already being done here.
        require(radius >= Dab.MIN_RADIUS && radius <= Dab.MAX_RADIUS) {
            "dab radius $radius is outside ${Dab.MIN_RADIUS}..${Dab.MAX_RADIUS}"
        }
        requireExtendedState(seed, wetness, bristleAlong, bristleAcross)
        DabBounds.requireValid(x, y, radius)
        if (isFull) return false
        val i = count
        write(
            i, x, y, radius, flow, hardness, angle, aspect, seed, wetness,
            bristleAlong, bristleAcross,
        )
        count = i + 1
        includeDirtyDab(x, y, radius)
        return true
    }

    /** Rewrites an unsubmitted dab, expanding its dirty bounds if needed. */
    internal fun replace(
        index: Int,
        x: Float,
        y: Float,
        radius: Float,
        flow: Float,
        hardness: Float,
        angle: Float,
        aspect: Float,
        seed: Float,
        wetness: Float = 1f,
        bristleAlong: Float = 0f,
        bristleAcross: Float = 0f,
    ) {
        require(index in 0 until count) { "index $index is outside 0..${count - 1}" }
        require(radius in Dab.MIN_RADIUS..Dab.MAX_RADIUS) {
            "dab radius $radius is outside ${Dab.MIN_RADIUS}..${Dab.MAX_RADIUS}"
        }
        requireExtendedState(seed, wetness, bristleAlong, bristleAcross)
        DabBounds.requireValid(x, y, radius)

        write(
            index, x, y, radius, flow, hardness, angle, aspect, seed, wetness,
            bristleAlong, bristleAcross,
        )
        includeDirtyDab(x, y, radius)
    }

    private fun requireExtendedState(
        seed: Float,
        wetness: Float,
        bristleAlong: Float,
        bristleAcross: Float,
    ) {
        require(seed.isFinite() && seed in 0f..1f) {
            "dab seed must be 0..1, was $seed"
        }
        require(wetness.isFinite() && wetness in 0f..1f) {
            "dab wetness must be 0..1, was $wetness"
        }
        require(bristleAlong.isFinite() && bristleAcross.isFinite()) {
            "dab bristle coordinates must be finite, were $bristleAlong, $bristleAcross"
        }
    }

    private fun includeDirtyDab(x: Float, y: Float, radius: Float) {
        val left = DabBounds.left(x, radius)
        val top = DabBounds.top(y, radius)
        val right = DabBounds.right(x, radius)
        val bottom = DabBounds.bottom(y, radius)
        if (dirtyEmpty) {
            dirtyLeft = left
            dirtyTop = top
            dirtyRight = right
            dirtyBottom = bottom
            dirtyEmpty = false
            return
        }

        dirtyLeft = minOf(dirtyLeft, left)
        dirtyTop = minOf(dirtyTop, top)
        dirtyRight = maxOf(dirtyRight, right)
        dirtyBottom = maxOf(dirtyBottom, bottom)
    }

    private fun write(
        index: Int,
        x: Float,
        y: Float,
        radius: Float,
        flow: Float,
        hardness: Float,
        angle: Float,
        aspect: Float,
        seed: Float,
        wetness: Float,
        bristleAlong: Float,
        bristleAcross: Float,
    ) {
        val i = index
        this.x[i] = x
        this.y[i] = y
        this.radius[i] = radius
        this.flow[i] = flow
        this.hardness[i] = hardness
        this.angle[i] = angle
        this.aspect[i] = aspect
        this.seed[i] = seed
        this.wetness[i] = wetness
        this.bristleAlong[i] = bristleAlong
        this.bristleAcross[i] = bristleAcross
    }

    fun add(dab: Dab): Boolean = add(
        dab.x, dab.y, dab.radius, dab.flow, dab.hardness, dab.angle, dab.aspect, dab.seed,
        dab.wetness, dab.bristleAlong, dab.bristleAcross,
    )

    /** Dab [index] as a value. Off the hot path — for tests and diagnostics. */
    operator fun get(index: Int): Dab {
        require(index in 0 until count) { "index $index is outside 0..${count - 1}" }
        return Dab(
            x[index], y[index], radius[index], flow[index],
            hardness[index], angle[index], aspect[index], seed[index], wetness[index],
            bristleAlong[index], bristleAcross[index],
        )
    }

    fun toList(): List<Dab> = List(count) { this[it] }

    /**
     * Empties the batch for reuse. Deliberately does not zero the arrays:
     * entries past [count] are unreadable through [get], and clearing 1024
     * floats × 11 on every batch is work that buys nothing.
     */
    fun clear() {
        reuseGeneration++
        count = 0
        predictedFrom = -1
        strokeId = 0L
        dirtyEmpty = true
    }

    /** Marks the dabs from here on as predicted — the removable tail. */
    fun markPredictedFromHere() {
        if (predictedFrom < 0) predictedFrom = count
    }

    /** How many dabs the multi-buffered pass will commit, i.e. excluding the tail. */
    val committedCount: Int get() = if (predictedFrom < 0) count else predictedFrom

}

/**
 * The main → GL handoff (`02-architecture.md` §3.2): a fixed set of
 * [DabBatch] slots, one producer (the thread reading the digitizer) and one
 * consumer (the GL thread).
 *
 * A slot is released on the **GL thread**, after the pass has consumed it —
 * never from the producer after publishing. graphics-core replays the
 * published batches into the multi-buffered pass asynchronously and holds
 * the references until that replay has actually run, so identity matters and
 * reusing a slot early would repaint a stroke with the next one's dabs.
 *
 * [acquire] returning `null` is bounded backpressure. Prediction may skip a
 * frame; real input resumes from its last accepted sample when a slot returns.
 * Allocating on this path would let a stalled GL thread grow memory without a
 * bound.
 */
class DabRing(
    slots: Int = DAB_RING_SLOTS,
    capacity: Int = DAB_BATCH_CAPACITY,
) {
    init {
        require(slots > 0) { "ring must have at least one slot, was $slots" }
        require(capacity > 0) { "dab batch capacity must be positive, was $capacity" }
    }

    private val batches = Array(slots) { DabBatch(capacity) }
    private val free = BooleanArray(slots) { true }
    private val nextStrokeId = AtomicLong(1L)

    private enum class BatchPurpose {
        REAL_INPUT,
        PREDICTION,
    }

    val slots: Int get() = batches.size

    /** How many slots are available right now. */
    @get:Synchronized
    val freeSlots: Int get() = free.count { it }

    /**
     * The next free slot, cleared and ready, or `null` when every slot is
     * still held by the GL thread.
     */
    fun acquire(): DabBatch? = acquire(BatchPurpose.REAL_INPUT)

    /**
     * A prediction slot, or `null` when borrowing it would consume the slot
     * reserved for real digitizer input. Prediction can be regenerated next
     * frame; a real sample cannot.
     */
    internal fun acquirePrediction(): DabBatch? = acquire(BatchPurpose.PREDICTION)

    @Synchronized
    private fun acquire(purpose: BatchPurpose): DabBatch? {
        if (
            purpose == BatchPurpose.PREDICTION &&
            free.count { it } <= REAL_INPUT_RESERVED_SLOTS
        ) {
            return null
        }

        for (i in batches.indices) {
            if (!free[i]) continue

            free[i] = false
            batches[i].clear()
            return batches[i]
        }
        return null
    }

    /**
     * Returns [batch] to the pool. Called on the GL thread at the end of the
     * pass that consumed it.
     */
    @Synchronized
    fun release(batch: DabBatch) {
        val i = batches.indexOfFirst { it === batch }
        require(i >= 0) { "released a batch this ring never handed out" }
        // Not idempotent on purpose: a double release would hand one slot to
        // two producers, and the resulting stroke would be interleaved with
        // another's dabs — a corruption far more expensive to find than this.
        require(!free[i]) { "batch at slot $i was released twice" }
        free[i] = true
    }

    /**
     * Releases every slot of [strokeId]; a cancelled stroke is never replayed.
     *
     * **Call this on the GL thread**, as `02-architecture.md` §3.2 specifies:
     * `cancelStroke()` posts it through `execute {}` right after
     * `renderer.cancel()`. That placement is what makes it safe, and the
     * safety does not survive being moved. Called from the producer instead,
     * it frees slots the GL thread may still be replaying: a later
     * `release(batch)` would either hit [release]'s double-release guard and
     * throw on the render thread, or — worse, if a new stroke has already
     * acquired the slot — free a batch that stroke is filling, and two
     * producers would write one batch.
     */
    @Synchronized
    fun releaseStroke(strokeId: Long) {
        for (i in batches.indices) {
            if (!free[i] && batches[i].strokeId == strokeId) free[i] = true
        }
    }

    /** A fresh stroke id. Monotonic, so a late batch can always be recognised. */
    fun newStrokeId(): Long = nextStrokeId.getAndIncrement()
}

private const val REAL_INPUT_RESERVED_SLOTS = 1
