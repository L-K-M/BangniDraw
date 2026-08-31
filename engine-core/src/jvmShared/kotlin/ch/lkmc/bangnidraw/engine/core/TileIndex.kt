package ch.lkmc.bangnidraw.engine.core

/**
 * One layer's map from tile key to GPU slice — the bookkeeping half of
 * `engine/gl/LayerTextures` (`docs/plan/03-canvas-engine.md` §2.2).
 *
 * A dense `IntArray(tilesX × tilesY)` of packed [SliceHandle]s, `-1` where
 * nothing was ever painted. Dense because it is tiny (4 KiB at the 1024-tile
 * ceiling) and O(1) to look up; **sparse content is expressed by the −1s, not
 * by the index structure** — a hash map would allocate on the paint path and
 * buy nothing at this size.
 *
 * Pure, so §15's rule holds: `LayerTextures` owns the GL calls (create the
 * slice, clear it, upload into it) and this owns the answer to "which slice,
 * and which ones are worth drawing".
 */
class TileIndex(val grid: TileGrid) {

    private val slices = IntArray(grid.tileCount) { SliceHandle.NONE.packed }

    /** Tiles that have a slice. Maintained incrementally; [put] is the only writer. */
    var presentCount: Int = 0
        private set

    /**
     * The slice holding [k], or [SliceHandle.NONE] — including for a key
     * outside the grid.
     *
     * Out-of-grid reads answer NONE rather than throwing because that is what
     * they mean: the dirty rect of a stroke near the edge covers keys past the
     * last tile, and the compositor's loop is "if there's a slice, draw it".
     * Writing one is still refused — see [put].
     */
    operator fun get(k: TileKey): SliceHandle =
        if (grid.contains(k)) SliceHandle(slices[grid.index(k)]) else SliceHandle.NONE

    /**
     * Points [k] at [handle], or clears it with [SliceHandle.NONE], and
     * **returns whatever [k] held before** so the caller can free it.
     *
     * The return value is the point: the swap-on-merge of §7.4 is exactly this
     * call, and a setter that dropped the old handle on the floor would leak a
     * slice per merge with no way to notice until the pool ran dry. It is a
     * named function rather than `operator set` for that reason — Kotlin
     * assignment is a statement, so `val old = index[k] = h` does not compile
     * and the value an operator returned would be silently unreachable at
     * every call site.
     *
     * A key outside the grid throws: unlike a read, there is no sensible
     * meaning for it, and silently discarding the write would lose a painted
     * tile.
     */
    fun put(k: TileKey, handle: SliceHandle): SliceHandle {
        require(grid.contains(k)) { "$k is outside a ${grid.tilesX}×${grid.tilesY} grid" }
        val i = grid.index(k)
        val previous = SliceHandle(slices[i])
        if (previous.isNone != handle.isNone) {
            presentCount += if (handle.isNone) -1 else 1
        }
        slices[i] = handle.packed
        return previous
    }

    /** True when [k] is inside the grid and has a slice. */
    fun hasContent(k: TileKey): Boolean = !get(k).isNone

    /**
     * Fills [out] with the packed keys inside [rect] that have a slice,
     * **sorted by page**, and returns how many — the order `CompositePass`
     * draws in, one `glBindTexture` and one `glDrawArrays` per page (§3.2).
     *
     * Sorting here rather than in the pass keeps the pass free of decisions.
     * [scratch] holds one `Long` per candidate key while the sort runs: the
     * high half is the page and the low half the key, so one primitive sort
     * orders by page and carries the key along. Both arrays are the caller's,
     * sized at [TileGrid.tileCount] once, because this runs per layer per
     * frame and `docs/plan/10-performance.md` §2.4 puts no allocation on that
     * path.
     */
    fun visibleKeys(rect: IntRect, out: IntArray, scratch: LongArray): Int {
        if (presentCount == 0) return 0
        val candidates = grid.keysFor(rect, out)
        if (candidates == 0) return 0
        require(scratch.size >= candidates) {
            "visibleKeys needs a scratch of at least $candidates, was ${scratch.size}"
        }
        var n = 0
        for (i in 0 until candidates) {
            val k = TileKey(out[i])
            val packed = slices[grid.index(k)]
            if (packed == SliceHandle.NONE.packed) continue
            // The key half is masked to 32 unsigned bits: a key is always
            // non-negative today, but `or` with a sign-extended Int would
            // otherwise flood the page half with ones and sort that tile first.
            scratch[n++] = (SliceHandle(packed).page.toLong() shl 32) or
                (k.packed.toLong() and 0xFFFFFFFFL)
        }
        if (n == 0) return 0
        java.util.Arrays.sort(scratch, 0, n)
        for (i in 0 until n) out[i] = scratch[i].toInt()
        return n
    }

    /**
     * Fills [out] with the unique pages sampled inside [rect].
     *
     * [visibleKeys] already sorts by page, so compacting its output in place
     * stays linear and allocation-free. The returned count is the live prefix.
     */
    fun visiblePages(rect: IntRect, out: IntArray, scratch: LongArray): Int {
        val keyCount = visibleKeys(rect, out, scratch)
        if (keyCount == 0) return 0

        var page = get(TileKey(out[0])).page
        var pageCount = 1
        out[0] = page
        for (index in 1 until keyCount) {
            val nextPage = get(TileKey(out[index])).page
            if (nextPage == page) continue

            page = nextPage
            out[pageCount++] = page
        }

        return pageCount
    }

    /**
     * Appends every key with a slice to [out], in row-major order.
     *
     * Used where the whole layer is the subject — flatten, export, the
     * post-context-loss rebuild of §12 — not per frame.
     */
    fun allKeys(out: MutableList<TileKey>) {
        out.clear()
        if (presentCount == 0) return
        for (ty in 0 until grid.tilesY) {
            for (tx in 0 until grid.tilesX) {
                val i = ty * grid.tilesX + tx
                if (slices[i] != SliceHandle.NONE.packed) out += TileKey(tx, ty)
            }
        }
    }

    /**
     * Hands every live handle to [free] and empties the index.
     *
     * The callback rather than a returned list because the caller is
     * `LayerTextures`, whose "free" is `TilePool.free` — a list would allocate
     * once per layer deletion for values consumed immediately.
     */
    fun clear(free: (SliceHandle) -> Unit) {
        if (presentCount != 0) {
            for (i in slices.indices) {
                val packed = slices[i]
                if (packed != SliceHandle.NONE.packed) {
                    slices[i] = SliceHandle.NONE.packed
                    free(SliceHandle(packed))
                }
            }
        }
        presentCount = 0
    }

    /**
     * Drops every handle **without freeing anything** — the context died and
     * the textures went with it (§12).
     *
     * Distinct from [clear] on purpose: after a context loss the slices do not
     * exist to be returned, and handing them to `TilePool.free` would push
     * indices onto the free list of a pool that has itself been reset.
     */
    fun forgetAll() {
        slices.fill(SliceHandle.NONE.packed)
        presentCount = 0
    }
}
