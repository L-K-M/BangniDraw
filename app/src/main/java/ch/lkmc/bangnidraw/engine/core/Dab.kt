package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.DAB_BATCH_CAPACITY
import ch.lkmc.bangnidraw.engine.core.PerfConstants.DAB_RING_SLOTS
import java.util.concurrent.atomic.AtomicLong

/**
 * One dab, as a value — for tests, fixtures and anything off the hot path.
 *
 * The stroke path does **not** allocate these: it writes the same eight
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
    /** Per-dab jitter and grain phase, derived from the stroke seed. */
    val seed: Float,
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

    var count = 0
        private set

    var strokeId = 0L

    /** Index of the first predicted dab, or -1 when the batch has none. */
    var predictedFrom = -1

    /** The union of the dabs' dirty rects, in canvas px. [IntRect.EMPTY] when empty. */
    var dirty: IntRect = IntRect.EMPTY
        private set

    val capacity: Int get() = x.size
    val isFull: Boolean get() = count == x.size

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
    ): Boolean {
        // [Dab]'s documented range, enforced where it is written rather than
        // only promised. An oversized radius is not a wrong-looking dab, it is
        // a [dirty] rect inflated for the whole batch — which turns partial
        // redraw into a full-canvas repaint with nothing pointing at the dab
        // that caused it. One comparison against work already being done here.
        require(radius >= Dab.MIN_RADIUS && radius <= Dab.MAX_RADIUS) {
            "dab radius $radius is outside ${Dab.MIN_RADIUS}..${Dab.MAX_RADIUS}"
        }
        if (isFull) return false
        val i = count
        this.x[i] = x
        this.y[i] = y
        this.radius[i] = radius
        this.flow[i] = flow
        this.hardness[i] = hardness
        this.angle[i] = angle
        this.aspect[i] = aspect
        this.seed[i] = seed
        count = i + 1
        dirty = dirty.union(IntRect.forDab(x, y, radius))
        return true
    }

    fun add(dab: Dab): Boolean = add(
        dab.x, dab.y, dab.radius, dab.flow, dab.hardness, dab.angle, dab.aspect, dab.seed,
    )

    /** Dab [index] as a value. Off the hot path — for tests and diagnostics. */
    operator fun get(index: Int): Dab {
        require(index in 0 until count) { "index $index is outside 0..${count - 1}" }
        return Dab(
            x[index], y[index], radius[index], flow[index],
            hardness[index], angle[index], aspect[index], seed[index],
        )
    }

    fun toList(): List<Dab> = List(count) { this[it] }

    /**
     * Empties the batch for reuse. Deliberately does not zero the arrays:
     * entries past [count] are unreadable through [get], and clearing 1024
     * floats × 8 on every batch is work that buys nothing.
     */
    fun clear() {
        count = 0
        predictedFrom = -1
        strokeId = 0L
        dirty = IntRect.EMPTY
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
 * [acquire] returning `null` is backpressure, not an error: the producer
 * falls back to an allocating batch and logs it in debug. If that ever fires
 * in practice the slot count is wrong, not the design. **That fallback batch
 * never comes back through [release]** — it belongs to no slot, and the GC
 * reclaims it. [release] throws on one rather than ignoring it: a batch this
 * ring never handed out arriving at [release] means the caller has lost track
 * of which batches are pooled, and that is worth a stack trace at the moment
 * it happens rather than a silent no-op and a later slot leak.
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

    val slots: Int get() = batches.size

    /** How many slots are available right now. */
    @get:Synchronized
    val freeSlots: Int get() = free.count { it }

    /**
     * The next free slot, cleared and ready, or `null` when every slot is
     * still held by the GL thread.
     */
    @Synchronized
    fun acquire(): DabBatch? {
        for (i in batches.indices) {
            if (free[i]) {
                free[i] = false
                batches[i].clear()
                return batches[i]
            }
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
