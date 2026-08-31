package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.Coverage
import ch.lkmc.bangnidraw.engine.core.FillTilePixels
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.SliceHandle
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey

/**
 * The tile set one stroke paints into before it touches the layer
 * (`docs/plan/03-canvas-engine.md` §7.1).
 *
 * A grid at the layer's resolution with slices allocated **lazily and
 * cleared** the first time a dab touches a key, living for exactly one stroke
 * and freed at merge or cancel. It is what makes three of PLAN.md §3.1's
 * promises true at once:
 *
 * - a stroke has an **opacity** that many overlapping dabs cannot exceed,
 *   because dabs accumulate flow here and the cap is applied once, at merge
 *   ([MergePass]);
 * - a stroke can be **cancelled** — by palm rejection, by `ACTION_CANCEL` —
 *   without the layer ever having been touched;
 * - `flow` stays a genuine per-dab weight rather than doubling as the
 *   stroke's ceiling.
 *
 * **Composed from [LayerTextures] rather than copied.** The index-plus-pool
 * mechanics — lazy allocate, clear on first touch, free everything at the end
 * — are identical to a layer's, and duplicating them would be two copies of
 * the allocation ordering that §2.1 depends on. What differs is lifetime and
 * intent, so this class exposes only stroke-shaped operations. Fill is the
 * one upload path: its CPU coverage becomes a pre-made stroke buffer.
 *
 * §7.1's memory note: this is the one place where memory temporarily exceeds
 * the layer budget — a wild stroke across a 4096² canvas can touch all 256
 * keys — which is why `MemoryBudget` reserves a full layer's worth for it.
 *
 * GL-thread-only.
 */
class StrokeBuffer(
    val grid: TileGrid,
    pool: TilePool,
) {

    private val tiles = LayerTextures(grid, pool)

    /**
     * The union of every dab rect stamped since [reset], clamped to the
     * canvas — what the front-buffer recomposite redraws and what bounds the
     * merge.
     *
     * Tracked as a rect *and* the tile set separately because they answer
     * different questions: the rect is what to redraw on screen, the keys are
     * what to merge. A stroke down a tile boundary touches two keys whose
     * union rect is far larger than either.
     */
    var dirty: IntRect = IntRect.EMPTY
        private set

    /** Whether any dab has landed since [reset]. */
    val isEmpty: Boolean get() = tiles.tileCount == 0

    /** How many tiles the stroke has touched (§7.1's budget note). */
    val tileCount: Int get() = tiles.tileCount

    /** The slice holding [k], or [SliceHandle.NONE]. Never allocates. */
    fun slice(k: TileKey): SliceHandle = tiles.slice(k)

    /**
     * The slice for [k], allocating a **cleared** one on first touch.
     *
     * The clear is not optional here, unlike on a layer's upload path: dabs
     * composite into whatever the slice already holds, so a recycled slice
     * carrying the previous stroke's paint would bleed it into this one.
     *
     * @throws ch.lkmc.bangnidraw.engine.core.PoolExhausted when the pool is
     * full; §7.1's reservation is what makes that unlikely rather than
     * impossible, and the caller drops the dab rather than crashing.
     */
    fun sliceForWrite(k: TileKey): SliceHandle = tiles.sliceForWrite(k)

    /** The GL texture backing a pool page — what the passes bind. */
    fun pageTexture(page: Int): Int = tiles.pageTexture(page)

    /** As [LayerTextures.pageTextureOrNull] — null for an absent page. */
    fun pageTextureOrNull(page: Int): Int? = tiles.pageTextureOrNull(page)

    /** Every key the stroke has touched, for [MergePass] to walk. */
    fun keys(out: MutableList<TileKey>) = tiles.allKeys(out)

    /** Uploads CPU fill coverage as premultiplied stroke-buffer tiles. */
    fun uploadFill(coverage: Coverage, color: Int, opacity: Float): Int {
        val pixels = ByteArray(TILE_BYTES)
        var count = 0
        for (key in grid.keysFor(coverage.bounds)) {
            if (!FillTilePixels.write(grid, key, coverage, color, opacity, pixels)) continue

            tiles.upload(key, java.nio.ByteBuffer.wrap(pixels))
            count++
        }
        if (count > 0) growDirty(coverage.bounds)
        return count
    }

    /** Grows [dirty] to cover [rect]; call once per stamped dab batch. */
    fun growDirty(rect: IntRect) {
        dirty = dirty.union(rect)
    }

    /**
     * Frees every slice and empties the dirty rect — the end of a stroke,
     * whether it merged or was cancelled.
     *
     * The same call for both because §7.1's point is that the buffer never
     * carries anything the layer needs: once [MergePass] has read it, or once
     * the stroke is abandoned, there is nothing left to distinguish the two
     * cases. A separate `cancel()` would be two names for one operation and an
     * invitation to forget one of them.
     */
    fun reset() {
        tiles.release()
        dirty = IntRect.EMPTY
    }
}
