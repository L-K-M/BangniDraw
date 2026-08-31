package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SHIFT
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE

/**
 * A tile address in canvas space, packed into one `Int`
 * (`docs/plan/03-canvas-engine.md` §1).
 *
 * Packing a key into one `Int` keeps it a single field, but it does **not**
 * make key storage allocation-free: a value class boxes whenever it appears as
 * a generic argument, so every `MutableList<TileKey>` element is boxed. The
 * per-frame paths must carry packed `Int`s (an `IntArray`) and wrap only at
 * the boundary.
 *
 * Coordinates are unsigned 16-bit, so the packing alone would address 65 536
 * tiles — 16 777 216 px — per side. The format's real ceiling of 8192 px is
 * [TileGrid.MAX_EDGE], and it comes from `CanvasPresets.MAX_TILES` (1024
 * tiles per layer, i.e. 32 per side on a square canvas) together with the
 * readback chunking and sandwich rebuild that are sized for it. Coordinates
 * outside `0..65 535` **wrap** rather than throw, which is why [TileGrid]
 * validates its sides at construction.
 */
@JvmInline
value class TileKey(val packed: Int) {
    constructor(tx: Int, ty: Int) : this((ty shl 16) or (tx and 0xFFFF))

    val tx: Int get() = packed and 0xFFFF
    val ty: Int get() = packed ushr 16

    override fun toString(): String = "TileKey($tx, $ty)"
}

/** Integer point in canvas pixels. */
data class IntPoint(val x: Int, val y: Int)

/** Shared primitive dab bounds for allocation-free hot paths. */
object DabBounds {
    private const val ANTIALIAS_MARGIN_PX = 1f

    fun requireValid(x: Float, y: Float, radius: Float) {
        require(
            x.isFinite() && y.isFinite() && radius.isFinite() &&
                radius in 0f..IntRect.MAX_RADIUS
        ) {
            "dab must be finite with radius in 0..${IntRect.MAX_RADIUS}, " +
                "was x=$x, y=$y, radius=$radius"
        }
    }

    fun left(x: Float, radius: Float): Int =
        kotlin.math.floor(x - radius - ANTIALIAS_MARGIN_PX).toInt()

    fun top(y: Float, radius: Float): Int =
        kotlin.math.floor(y - radius - ANTIALIAS_MARGIN_PX).toInt()

    fun right(x: Float, radius: Float): Int =
        kotlin.math.ceil(x + radius + ANTIALIAS_MARGIN_PX).toInt()

    fun bottom(y: Float, radius: Float): Int =
        kotlin.math.ceil(y + radius + ANTIALIAS_MARGIN_PX).toInt()
}

/**
 * Half-open integer rect in canvas pixels: `right` and `bottom` are exclusive.
 *
 * The four edges are stored unvalidated. [forDab] is the value-producing API
 * for cold paths; live dab loops retain [DabBounds]' primitive edges. [width]
 * and [height] are plain subtractions with no overflow guard, and a rect wider
 * than `Int.MAX_VALUE` reports a *negative* size.
 *
 * Nothing in this engine builds one. [forDab]'s `require` is what keeps it that
 * way, and note what it has to reject to do so: finiteness alone bounds a radius
 * only by `Float.MAX_VALUE`, and `Float.toInt()` saturates at roughly 1.07e9 px,
 * so the bound on *magnitude* is doing that work, not the `isFinite` check.
 * Canvas rects are capped at [TileGrid.MAX_EDGE] px per side.
 *
 * [TileGrid.keysFor] is safe independently of all of this: it clamps the raw
 * edges and never reads [width] or [height] at all. A future caller that builds
 * an `IntRect` from something other than a dab or a canvas rect must clamp
 * first — it is [width] and [height] that have no floor under them.
 */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = left >= right || top >= bottom

    /**
     * The smallest rect covering this one and [other] — a dirty rect growing
     * by one more dab, one more batch, one more tail.
     *
     * **An empty rect contributes nothing rather than being treated as a rect
     * at (0,0).** [EMPTY] is `(0,0,0,0)`, so folding it in as an ordinary rect
     * would drag every union out to the canvas origin: one empty batch and the
     * front-buffered frame redraws from the top-left corner to the pen.
     *
     * Here rather than in each caller. Four private copies of this had
     * accumulated — in `DabBatch`, `DabPass`, `EngineSession` and
     * `StrokeBuffer.growDirty` — which is four places for the empty case to be
     * got wrong independently.
     */
    fun union(other: IntRect): IntRect = when {
        isEmpty -> other
        other.isEmpty -> this
        else -> IntRect(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom),
        )
    }

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)

        /**
         * The largest dab radius [forDab] accepts, chosen to stay clear of the
         * ~1.07e9 px at which `Float.toInt()` saturates rather than to describe
         * any real brush.
         */
        const val MAX_RADIUS = 1e9f

        /**
         * The dirty rect of a dab of [radius] canvas px centred on ([x], [y]).
         * One extra pixel on each side is the anti-aliasing band the dab
         * shader writes (`docs/plan/03-canvas-engine.md` §1, §7.3).
         */
        fun forDab(x: Float, y: Float, radius: Float): IntRect {
            // floor(NaN).toInt() is 0, so a NaN leaking out of the stabilizer
            // would yield an empty rect and silently drop the dab — a gap in a
            // stroke with nothing to trace it back to. Everything else in this
            // file fails loudly; so does this.
            // A negative radius inverts the rect (left > right), which comes
            // back empty — the same silent drop, by a different route.
            // The upper bound is not cosmetic: past MAX_RADIUS the four
            // Float.toInt() conversions below saturate at Int.MIN/MAX_VALUE and
            // `width` wraps to -1, so a rect that is not empty reports a
            // negative size. No brush is within nine orders of magnitude of it,
            // which is the point — it can only ever reject the saturating range.
            DabBounds.requireValid(x, y, radius)
            return IntRect(
                left = DabBounds.left(x, radius),
                top = DabBounds.top(y, radius),
                right = DabBounds.right(x, radius),
                bottom = DabBounds.bottom(y, radius),
            )
        }
    }
}

/**
 * The canvas geometry: how a document of [width] × [height] canvas pixels is
 * cut into 256 × 256 tiles, and how a dirty rect becomes the set of tile keys
 * it touches. Pure arithmetic — no pixels live here.
 *
 * A `data class` because a grid is fully determined by its two sides: two grids
 * of the same size are interchangeable, and comparing them should say so.
 * [tilesX] and [tilesY] are derived and stay out of equality, which is correct
 * — they cannot differ when the sides agree.
 */
data class TileGrid(val width: Int, val height: Int) {
    init {
        // The format's per-side range (`docs/plan/03-canvas-engine.md` §1).
        // Validated here, at the class that does the packing, so a grid built
        // from unvalidated numbers — a corrupt `project.json`, a future reopen
        // path — fails loudly instead of overflowing `tilesX` or wrapping a
        // `TileKey` into a plausible-looking address for the wrong tile.
        require(width in MIN_EDGE..MAX_EDGE && height in MIN_EDGE..MAX_EDGE) {
            "canvas is ${width}x$height, outside the format's $MIN_EDGE..$MAX_EDGE px per side"
        }
    }

    val tilesX: Int = (width + TILE_SIZE - 1) shr TILE_SHIFT
    val tilesY: Int = (height + TILE_SIZE - 1) shr TILE_SHIFT

    init {
        // Implied by the per-side bounds today (32 x 32 = 1024), but asserted
        // so a future change to MAX_EDGE or TILE_SIZE cannot silently outgrow
        // the readback chunking and sandwich rebuild that MAX_TILES sizes.
        // Must be a second init block: the one above runs before tilesX/tilesY
        // are initialised and would read 0.
        check(tilesX * tilesY <= MAX_TILES) {
            "canvas is ${width}x$height, needing ${tilesX * tilesY} tiles over the format's $MAX_TILES"
        }
    }
    val tileCount: Int get() = tilesX * tilesY

    /**
     * The tile containing canvas pixel ([x], [y]).
     *
     * ([x], [y]) must be inside the canvas: out-of-range input is neither
     * clamped nor rejected, and a negative coordinate wraps through
     * [TileKey]'s 16-bit mask into a far-away key that [contains] rejects but
     * [index] would happily turn into an out-of-bounds offset. Clip first, or
     * use [keysFor], which clips for you.
     */
    fun keyAt(x: Int, y: Int): TileKey = TileKey(x shr TILE_SHIFT, y shr TILE_SHIFT)

    /** Tile origin in canvas px. */
    fun origin(k: TileKey): IntPoint = IntPoint(k.tx shl TILE_SHIFT, k.ty shl TILE_SHIFT)

    /**
     * Dense row-major index of [k], the layout `LayerTextures` uses.
     *
     * [k] must satisfy [contains]. A key outside this grid does not merely
     * index out of bounds — a `tx` past [tilesX] rolls into the next row and
     * aliases a valid-looking but *wrong* slot, which is silent corruption
     * rather than a crash. No runtime check here: this is a hot path, so
     * validate or clip before calling.
     */
    fun index(k: TileKey): Int = k.ty * tilesX + k.tx

    /** True when [k] addresses a tile of this canvas. */
    fun contains(k: TileKey): Boolean = k.tx in 0 until tilesX && k.ty in 0 until tilesY

    /**
     * Appends the keys touched by the half-open rect [r], clipped to the
     * canvas, to [out], in row-major order — the same order [index] lays the
     * grid out in. An empty rect, or one entirely outside the canvas, appends
     * nothing.
     *
     * Keys already in [out] are appended again: a caller accumulating several
     * overlapping dirty rects must dedupe before consuming, or it will upload
     * and re-composite the same tile more than once per frame.
     *
     * **Not for per-frame paths.** [TileKey] boxes as a generic argument, so
     * every appended key allocates, which is exactly the churn [TileKey]'s own
     * KDoc forbids on the touch and upload paths. Those take the packed
     * `IntArray` overload below; this is the setup-time and test-time API.
     */
    fun keysFor(r: IntRect, out: MutableList<TileKey>) {
        val l = maxOf(r.left, 0)
        val t = maxOf(r.top, 0)
        val rr = minOf(r.right, width)
        val b = minOf(r.bottom, height)
        if (l >= rr || t >= b) return
        for (ty in (t shr TILE_SHIFT)..((b - 1) shr TILE_SHIFT)) {
            for (tx in (l shr TILE_SHIFT)..((rr - 1) shr TILE_SHIFT)) {
                out += TileKey(tx, ty)
            }
        }
    }

    companion object {
        /**
         * The format's per-side limits (`docs/plan/03-canvas-engine.md` §1).
         * The v1 UI ceiling is lower still — `PerfConstants.MAX_CANVAS_EDGE_V1`,
         * narrowed again per device by `MemoryBudget`.
         */
        const val MIN_EDGE = 256
        const val MAX_EDGE = 8192

        /**
         * The format's ceiling on tiles per layer: the readback chunking and
         * the sandwich rebuild are sized for it
         * (`docs/plan/03-canvas-engine.md` §1, which names the constant
         * `CanvasPresets.MAX_TILES` — that name survives as an alias, but the
         * number lives here with the rest of the tile format so the document
         * model does not have to reach into a dialog-facing object for a
         * persistence invariant).
         */
        const val MAX_TILES = 1024
    }

    /**
     * [keysFor] into a packed `IntArray`, returning how many keys were
     * written — the per-frame form, which allocates nothing.
     *
     * The compositor asks this of every visible layer on every frame
     * (`docs/plan/03-canvas-engine.md` §3.2), so the list overload's boxing
     * would be one allocation per visible tile per layer per frame. Callers
     * size [out] at [tileCount] once and reuse it.
     *
     * Writes at [from] onward so several dirty rects can accumulate into one
     * buffer; returns the new end, not the count written, which is what makes
     * `n = keysFor(a, buf, keysFor(b, buf, 0))` read correctly.
     */
    fun keysFor(r: IntRect, out: IntArray, from: Int = 0): Int {
        return keysForBounds(r.left, r.top, r.right, r.bottom, out, from)
    }

    /** Primitive form for per-dab callers that do not own an [IntRect]. */
    fun keysForBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        out: IntArray,
        from: Int = 0,
    ): Int {
        // Validated before the loop, not by the `require` inside it: that one
        // tests `n < out.size`, which a NEGATIVE n always passes, so an
        // arithmetic slip in the accumulate-several-rects pattern this KDoc
        // encourages would sail past the guard and die at `out[n++]` with the
        // bare negative-index exception the guard exists to replace.
        require(from in 0..out.size) { "from must be 0..${out.size}, was $from" }
        val l = maxOf(left, 0)
        val t = maxOf(top, 0)
        val rr = minOf(right, width)
        val b = minOf(bottom, height)
        if (l >= rr || t >= b) return from
        var n = from
        for (ty in (t shr TILE_SHIFT)..((b - 1) shr TILE_SHIFT)) {
            for (tx in (l shr TILE_SHIFT)..((rr - 1) shr TILE_SHIFT)) {
                // Bounds-checked here rather than left to the array store, so
                // the message names the grid: an under-sized buffer is a
                // caller that sized against a different canvas, and
                // ArrayIndexOutOfBounds alone would not say which.
                require(n < out.size) {
                    "keysFor needs room for more than ${out.size} keys in a " +
                        "${tilesX}×$tilesY grid"
                }
                out[n++] = TileKey(tx, ty).packed
            }
        }
        return n
    }

    /** [keysFor] as a fresh list — the convenient form for tests and cold paths. */
    fun keysFor(r: IntRect): List<TileKey> = ArrayList<TileKey>().also { keysFor(r, it) }

    /** The canvas rect of tile [k], clipped to the canvas (edge tiles are partial). */
    fun tileRect(k: TileKey): IntRect {
        val o = origin(k)
        return IntRect(o.x, o.y, minOf(o.x + TILE_SIZE, width), minOf(o.y + TILE_SIZE, height))
    }
}
