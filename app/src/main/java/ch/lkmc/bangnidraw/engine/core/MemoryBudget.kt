package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.GPU_TILE_FRACTION
import ch.lkmc.bangnidraw.engine.core.PerfConstants.GPU_TILE_MAX_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.GPU_TILE_MIN_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.HISTORY_BYTES_LARGE
import ch.lkmc.bangnidraw.engine.core.PerfConstants.HISTORY_BYTES_SMALL
import ch.lkmc.bangnidraw.engine.core.PerfConstants.HISTORY_STEPS_LARGE
import ch.lkmc.bangnidraw.engine.core.PerfConstants.HISTORY_STEPS_SMALL
import ch.lkmc.bangnidraw.engine.core.PerfConstants.LARGE_DEVICE_TOTAL_MEM
import ch.lkmc.bangnidraw.engine.core.PerfConstants.LOW_RAM_GPU_TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.MAX_CANVAS_EDGE_V1
import ch.lkmc.bangnidraw.engine.core.PerfConstants.MAX_LAYERS
import ch.lkmc.bangnidraw.engine.core.PerfConstants.MIN_LAYERS
import ch.lkmc.bangnidraw.engine.core.PerfConstants.MIN_USEFUL_LAYERS
import ch.lkmc.bangnidraw.engine.core.PerfConstants.STROKE_BUFFER_RESERVE_LAYERS
import ch.lkmc.bangnidraw.engine.core.PerfConstants.THUMB_MIB_LARGE
import ch.lkmc.bangnidraw.engine.core.PerfConstants.THUMB_MIB_LOW_RAM
import ch.lkmc.bangnidraw.engine.core.PerfConstants.THUMB_MIB_SMALL
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE

/**
 * Everything the budget needs about the device, read once (from
 * `ActivityManager` and the first GL context) and never re-queried per frame.
 */
data class DeviceMemory(
    val totalMemBytes: Long,
    val isLowRamDevice: Boolean,
    /**
     * Not consulted by [MemoryBudget], deliberately: the tile budget is GPU
     * texture memory, which does not come out of the Java heap the memory
     * class describes. Captured because it is the number the device-class
     * triage in `docs/plan/10-performance.md` §1 is written against.
     */
    val largeMemoryClassMb: Int,
    /** `GL_MAX_ARRAY_TEXTURE_LAYERS`; 0 means "no context yet". */
    val glMaxArrayLayers: Int,
    /**
     * Not consulted when sizing a canvas — a canvas is a grid of 256 px
     * slices, never one texture (AGENTS.md, "Deviations"). It bounds the
     * viewport-sized `Accum`/`Scratch` targets of
     * `docs/plan/03-canvas-engine.md` §3.2 instead, which arrive with PR 2.3.
     */
    val glMaxTextureSize: Int,
) {
    init {
        // totalMemBytes is validated once here and then treated as a fact;
        // neither MemoryBudget nor TilePool re-checks it. The GL fields are
        // deliberately unchecked — glMaxArrayLayers takes 0 as "no context
        // yet" — and callers handle them where they are read.
        require(totalMemBytes > 0) { "totalMemBytes must be positive, was $totalMemBytes" }
    }
}

/**
 * A canvas size in pixels, with the tile arithmetic that follows from it.
 *
 * Unlike [TileGrid] this type does **not** refuse a size: it is what
 * `CanvasPresets.custom` measures a user-typed size with, so it has to be able
 * to describe a size in order to reject it. That makes its arithmetic the one
 * place that must survive absurd input, hence the ceilings below are written
 * as quotient-plus-remainder rather than the usual `(n + 255) / 256`, which
 * overflows to a *negative* tile count near `Int.MAX_VALUE`, and the products
 * are taken in `Long`.
 */
data class CanvasSize(val width: Int, val height: Int) {
    val tilesX: Int get() = tilesFor(width)
    val tilesY: Int get() = tilesFor(height)
    val tilesPerLayer: Long get() = tilesX.toLong() * tilesY

    /**
     * Saturates rather than wraps. `tilesPerLayer` fits a `Long` for every
     * `Int` side, but multiplying it by [TILE_BYTES] does not — and a budget
     * computed from a wrapped negative byte count would report a *generous*
     * layer cap for the largest canvas imaginable, which is the one answer
     * that must never come out of this class.
     */
    val layerBytesWorstCase: Long
        get() {
            val tiles = tilesPerLayer
            return if (tiles > Long.MAX_VALUE / TILE_BYTES) Long.MAX_VALUE else tiles * TILE_BYTES
        }

    private companion object {
        /**
         * `ceil(px / 256)` for a positive side, and **zero** for a side that
         * is not positive — a canvas with no area has no tiles. Kotlin's `/`
         * truncates toward zero rather than flooring, so the plain expression
         * would answer 1 for a side of -1 and quietly budget a nonsense canvas
         * as if it were 256 px wide.
         */
        fun tilesFor(px: Int): Int =
            if (px <= 0) 0 else px / TILE_SIZE + if (px % TILE_SIZE != 0) 1 else 0
    }
}

/**
 * Turns "how much memory does this device have" and "how big is this canvas"
 * into the caps the app is honest about: how many layers fit, how large a
 * canvas may be, how deep undo goes (decision 4).
 *
 * Pure arithmetic over numbers the caller read from the platform — no
 * `android.*`, no silent downgrade, and `MemoryBudgetTest` pins the worked
 * table of `docs/plan/10-performance.md` §4 so a constant change is a visible
 * diff.
 */
object MemoryBudget {
    data class Result(
        /** The raw tile budget. What the pool can *allocate* is [poolCapacityBytes]. */
        val gpuTileBudgetBytes: Long,
        /**
         * `poolArrayCount × poolArraySlices × TILE_BYTES` — whole arrays only,
         * so up to one array below [gpuTileBudgetBytes]. Every cap here is
         * derived from this, not from the raw budget; a caller asking "do N
         * bytes of tiles fit?" must ask this one or it can over-commit.
         */
        val poolCapacityBytes: Long,
        /** For THIS canvas size, `MIN_LAYERS..MAX_LAYERS`. */
        val maxLayers: Int,
        /** The largest edge any preset may offer on this device. */
        val maxCanvasEdge: Int,
        val historyMaxSteps: Int,
        val historyMaxBytes: Long,
        val thumbnailCacheBytes: Long,
        /** Slices per texture array `TilePool` creates; never above `glMaxArrayLayers`. */
        val poolArraySlices: Int,
        /** How many texture arrays fit the budget. */
        val poolArrayCount: Int,
    )

    /** The spec minimum for `GL_MAX_ARRAY_TEXTURE_LAYERS`, and our page size. */
    const val SLICES_PER_PAGE = 256

    /**
     * How many layers of [canvas] fit a pool capacity of [poolCapacityBytes],
     * clamped to `MIN_LAYERS..MAX_LAYERS`. `CanvasPresets` annotates every row
     * with this, so the New Canvas dialog and the layer panel can never
     * disagree.
     *
     * The clamp raises as well as lowers: a canvas so large that not even one
     * layer fits still answers [MIN_LAYERS], never 0. A document always has a
     * layer, so 0 would not mean "paint with fewer" — it would mean "this
     * canvas cannot be opened", which is a different question, answered
     * separately by `CanvasPresets.fits`. **So this number is only meaningful
     * for a size that clears [Result.maxCanvasEdge]**; for anything larger it
     * is the floor, not a promise, and a caller that shows it as one advertises
     * layers the pool cannot back. `CanvasPreset` pairs it with `enabled` for
     * exactly that reason.
     *
     * The parameter is named for the *capacity*, not the raw budget: passing
     * `Result.gpuTileBudgetBytes` here compiles and reads naturally, and is
     * exactly the over-commit [Result.poolCapacityBytes] exists to prevent.
     */
    fun maxLayersFor(poolCapacityBytes: Long, canvas: CanvasSize): Int {
        // A canvas with no area divides by zero below. It is not this
        // function's job to reject one — `CanvasPresets.custom` does that, and
        // `CanvasSize` exists precisely to describe a size in order to refuse
        // it — but it must not throw on the way past.
        // Every other invalid input in this package fails loudly; this one used
        // to clamp up to MIN_LAYERS and report "1 layer fits" for a pool that
        // holds none — the over-commit this function exists to prevent, arriving
        // silently. compute() cannot produce a non-positive capacity, but this
        // is public and its KDoc already warns it is easy to feed the wrong field.
        require(poolCapacityBytes > 0) { "poolCapacityBytes must be positive, was $poolCapacityBytes" }
        if (canvas.tilesPerLayer <= 0L) return MIN_LAYERS
        // coerceAtMost before toInt(): a Long quotient past Int.MAX_VALUE
        // narrows by truncation, and Long.MAX_VALUE / a small canvas lands on
        // -1, which would answer MIN_LAYERS where the honest answer is the cap.
        val layersThatFit =
            (poolCapacityBytes / canvas.layerBytesWorstCase)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt() - STROKE_BUFFER_RESERVE_LAYERS
        // The upward half of this clamp is a documented contract, not an
        // accident — see the KDoc. For a canvas that clears maxCanvasEdge, the
        // honest cap is enforced where it can be: the pool allocates pages
        // lazily and reports "layer limit reached early" (`10-performance.md` §4).
        return layersThatFit.coerceIn(MIN_LAYERS, MAX_LAYERS)
    }

    fun compute(device: DeviceMemory, canvas: CanvasSize): Result {
        val gpu = when {
            device.isLowRamDevice -> LOW_RAM_GPU_TILE_BYTES
            else -> (device.totalMemBytes * GPU_TILE_FRACTION).toLong()
                .coerceIn(GPU_TILE_MIN_BYTES, GPU_TILE_MAX_BYTES)
        }
        // A driver reporting fewer slices than the ES 3.0 minimum of 256 is
        // trusted as-is rather than refused: the capacity arithmetic stays
        // self-consistent (smaller arrays, more of them), so no cap comes out
        // wrong — the pool just degenerates toward many near-empty arrays.
        // Zero or negative means "no GL context yet", which takes the page size.
        val slices =
            if (device.glMaxArrayLayers > 0) minOf(device.glMaxArrayLayers, SLICES_PER_PAGE)
            else SLICES_PER_PAGE
        // maxOf(1, ...) would let poolCapacityBytes exceed the budget itself.
        // It never can today, but only because GPU_TILE_MIN_BYTES (256 MiB)
        // happens to be >= SLICES_PER_PAGE * TILE_BYTES (64 MiB) — a coupling
        // between three constants that nothing enforced. Now it does.
        val bytesPerArray = slices.toLong() * TILE_BYTES
        check(gpu >= bytesPerArray) {
            "tile budget of $gpu B cannot hold one $slices-slice array ($bytesPerArray B)"
        }
        // coerceAtMost before toInt(), as maxLayersFor does: toInt() truncates
        // to the low 32 bits and can wrap negative. The quotient is provably
        // small today, but that is one more unenforced coupling between
        // constants, and this file's habit is to not rely on those.
        val arrays = (gpu / bytesPerArray).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // The pool hands out whole texture arrays, so what it can actually
        // allocate is arrays x slices x TILE_BYTES — up to one array (64 MiB at
        // 256 slices) less than the raw budget. Sizing the layer cap from the
        // raw budget instead lets the two disagree: a device reporting 2800 MiB
        // gets a 350 MiB budget, which is 16 layers of a 2304 canvas (1296
        // tiles) but only 5 arrays (1280 slices). The dialog would advertise a
        // layer the pool cannot hold, and the KDoc promises those two never
        // disagree — so both the cap and the size ceiling come from capacity.
        val poolCapacityBytes = arrays.toLong() * slices * TILE_BYTES
        val maxLayers = maxLayersFor(poolCapacityBytes, canvas)
        // maxCanvasEdge is bounded by memory and by the v1 ceiling, never by
        // glMaxTextureSize: tiles are 256 px, so a big canvas never needs a
        // big texture. The largest multiple of TILE_SIZE whose square, fully
        // painted, still holds MIN_USEFUL_LAYERS plus the stroke-buffer
        // reserve — so a size the dialog offers can always be painted on.
        val perLayerLimit = poolCapacityBytes / (MIN_USEFUL_LAYERS + STROKE_BUFFER_RESERVE_LAYERS)
        // The loop tests TILE_SIZE + TILE_SIZE and up; the starting value is
        // returned untested, so the "always paintable" promise holds at the
        // floor only through a coupling between the minimum tile budget,
        // MIN_USEFUL_LAYERS and TILE_BYTES that nothing enforced. Enforce it,
        // the same way the array-size check above enforces its own three
        // constants: it cannot fire today (five tiles is 1.25 MiB against a
        // floor budget in the hundreds), and that is the point — if someone
        // lowers the budget or raises MIN_USEFUL_LAYERS it fails here rather
        // than offering a 256 px canvas that cannot hold the minimum stack.
        check(TILE_BYTES.toLong() * (MIN_USEFUL_LAYERS + STROKE_BUFFER_RESERVE_LAYERS) <= poolCapacityBytes) {
            "a pool capacity of $poolCapacityBytes B cannot hold " +
                "${MIN_USEFUL_LAYERS + STROKE_BUFFER_RESERVE_LAYERS} layers of a ${TILE_SIZE}px canvas"
        }
        var maxCanvasEdge = TILE_SIZE
        while (maxCanvasEdge + TILE_SIZE <= MAX_CANVAS_EDGE_V1 &&
            CanvasSize(maxCanvasEdge + TILE_SIZE, maxCanvasEdge + TILE_SIZE)
                .layerBytesWorstCase <= perLayerLimit
        ) {
            maxCanvasEdge += TILE_SIZE
        }
        val large = device.totalMemBytes >= LARGE_DEVICE_TOTAL_MEM && !device.isLowRamDevice
        val historySteps = if (large) HISTORY_STEPS_LARGE else HISTORY_STEPS_SMALL
        val historyBytes = if (large) HISTORY_BYTES_LARGE else HISTORY_BYTES_SMALL
        val thumbBytes = (
            when {
                device.isLowRamDevice -> THUMB_MIB_LOW_RAM
                large -> THUMB_MIB_LARGE
                else -> THUMB_MIB_SMALL
            }
            ).toLong() shl 20
        return Result(
            gpuTileBudgetBytes = gpu,
            poolCapacityBytes = poolCapacityBytes,
            maxLayers = maxLayers,
            maxCanvasEdge = maxCanvasEdge,
            historyMaxSteps = historySteps,
            historyMaxBytes = historyBytes,
            thumbnailCacheBytes = thumbBytes,
            poolArraySlices = slices,
            poolArrayCount = arrays,
        )
    }
}
