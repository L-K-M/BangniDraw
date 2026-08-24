package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SHIFT
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE

/**
 * A tile address in canvas space, packed into one `Int` so the hot paths
 * never box (`docs/plan/03-canvas-engine.md` §1).
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

/** Half-open integer rect in canvas pixels: `right` and `bottom` are exclusive. */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = left >= right || top >= bottom

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)

        /**
         * The dirty rect of a dab of [radius] canvas px centred on ([x], [y]).
         * One extra pixel on each side is the anti-aliasing band the dab
         * shader writes (`docs/plan/03-canvas-engine.md` §1, §7.3).
         */
        fun forDab(x: Float, y: Float, radius: Float): IntRect {
            val l = kotlin.math.floor(x - radius - 1f).toInt()
            val t = kotlin.math.floor(y - radius - 1f).toInt()
            val r = kotlin.math.ceil(x + radius + 1f).toInt()
            val b = kotlin.math.ceil(y + radius + 1f).toInt()
            return IntRect(l, t, r, b)
        }
    }
}

/**
 * The canvas geometry: how a document of [width] × [height] canvas pixels is
 * cut into 256 × 256 tiles, and how a dirty rect becomes the set of tile keys
 * it touches. Pure arithmetic — no pixels live here.
 */
class TileGrid(val width: Int, val height: Int) {
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

    /** Dense row-major index of [k], the layout `LayerTextures` uses. */
    fun index(k: TileKey): Int = k.ty * tilesX + k.tx

    /** True when [k] addresses a tile of this canvas. */
    fun contains(k: TileKey): Boolean = k.tx in 0 until tilesX && k.ty in 0 until tilesY

    /**
     * Appends the keys touched by the half-open rect [r], clipped to the
     * canvas, to [out]. An empty rect, or one entirely outside the canvas,
     * appends nothing.
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

    /** [keysFor] as a fresh list — the convenient form for tests and cold paths. */
    fun keysFor(r: IntRect): List<TileKey> = ArrayList<TileKey>().also { keysFor(r, it) }

    /** The canvas rect of tile [k], clipped to the canvas (edge tiles are partial). */
    fun tileRect(k: TileKey): IntRect {
        val o = origin(k)
        return IntRect(o.x, o.y, minOf(o.x + TILE_SIZE, width), minOf(o.y + TILE_SIZE, height))
    }
}
