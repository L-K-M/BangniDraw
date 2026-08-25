package ch.lkmc.bangnidraw.engine.core

/**
 * What produced a stroke sample (`docs/plan/07-input-and-stylus.md` §2).
 *
 * Carried per sample rather than per stroke even though a stroke never
 * changes source: every stage downstream then has it without threading a
 * second parameter, and a tool-type change is a stroke boundary (§6), so the
 * two readings can never disagree.
 */
enum class StrokeSource {
    STYLUS,
    ERASER_END,
    FINGER,
    MOUSE,
}

/**
 * One input sample, in **canvas** pixels
 * (`docs/plan/07-input-and-stylus.md` §2).
 *
 * Mutable and pooled on purpose: the touch handler fills these inside the
 * motion callback, where an allocation per sample at 120 Hz is exactly the
 * GC jank `10-performance.md` §2.4 budgets against. A sample never escapes
 * the batch it lives in — copy it with [set] if you need to keep one.
 */
class StrokeInput {
    /** Canvas px, sub-pixel, via `ViewTransform.invert`. */
    var x = 0f

    /** Canvas px, sub-pixel. */
    var y = 0f

    /** `0..1`, already through `PressureCurve`. Constant 1 for fingers and mice. */
    var pressure = 1f

    /** Radians: 0 is perpendicular to the glass, π/2 is flat. */
    var tilt = 0f

    /** Radians, canvas-relative: the stylus azimuth minus the view rotation. */
    var orientation = 0f

    /**
     * Event time in nanoseconds. Non-decreasing within a stroke, and not
     * strictly increasing: a device may stamp a whole historical run with the
     * batch's event time, and `Stabilizer.finish` synthesizes its catch-up
     * samples at the single instant the pen lifted. Anything deriving speed
     * from it owes a zero-delta branch.
     */
    var timeNs = 0L

    var source = StrokeSource.STYLUS

    /** True only in a predicted batch — the removable tail, front layer only. */
    var predicted = false

    fun set(other: StrokeInput) {
        x = other.x
        y = other.y
        pressure = other.pressure
        tilt = other.tilt
        orientation = other.orientation
        timeNs = other.timeNs
        source = other.source
        predicted = other.predicted
    }

    /** For tests and fixtures; the input path uses [set] into a pooled sample. */
    fun set(
        x: Float,
        y: Float,
        pressure: Float = 1f,
        tilt: Float = 0f,
        orientation: Float = 0f,
        timeNs: Long = 0L,
        source: StrokeSource = StrokeSource.STYLUS,
        predicted: Boolean = false,
    ) {
        this.x = x
        this.y = y
        this.pressure = pressure
        this.tilt = tilt
        this.orientation = orientation
        this.timeNs = timeNs
        this.source = source
        this.predicted = predicted
    }

    override fun toString(): String =
        "StrokeInput(x=$x, y=$y, p=$pressure, tilt=$tilt, t=$timeNs, $source" +
            (if (predicted) ", predicted)" else ")")
}

/**
 * A reused run of samples (`docs/plan/07-input-and-stylus.md` §2): one
 * batched `MotionEvent`'s historical samples plus the current one.
 *
 * The capacity never grows — an overflow flushes early instead — because a
 * growing array on the input path is an allocation on the input path.
 */
class StrokeInputBatch(capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "batch capacity must be positive, was $capacity" }
    }

    val items: Array<StrokeInput> = Array(capacity) { StrokeInput() }

    var size = 0
        set(value) {
            require(value in 0..items.size) { "size $value is outside 0..${items.size}" }
            field = value
        }

    val capacity: Int get() = items.size
    val isFull: Boolean get() = size == items.size

    /**
     * The next free sample to fill, or `null` when the batch is full — the
     * caller flushes and starts a new one. A `null` rather than a throw
     * because overflow is an expected rhythm of a busy digitizer, not a bug.
     */
    fun next(): StrokeInput? = if (isFull) null else items[size++]

    fun clear() {
        size = 0
    }

    operator fun get(index: Int): StrokeInput {
        require(index in 0 until size) { "index $index is outside 0..${size - 1}" }
        return items[index]
    }

    companion object {
        /**
         * Above the largest historical run seen on an S Pen at 120 Hz vsync.
         * To verify on device (`07-input-and-stylus.md` §2).
         */
        const val DEFAULT_CAPACITY = 64
    }
}
