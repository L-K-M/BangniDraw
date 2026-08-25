package ch.lkmc.bangnidraw.engine.core

/**
 * Where one tile lives on the GPU: `(page shl 16) or slice`, `-1` for none
 * (`docs/plan/03-canvas-engine.md` §2.1).
 *
 * A value class rather than a pair because a layer's index is an `IntArray` of
 * these — 4 KiB at 1024 tiles — and because the compositor sorts visible tiles
 * by page every frame. Boxing either of those would put an allocation on the
 * frame path, which `docs/plan/10-performance.md` §2.4 forbids.
 *
 * The handle carries its page, which is what makes the "never sample the
 * render-target page" rule of §2.1 enforceable: a pass can ask a handle which
 * page it is on without a lookup.
 */
@JvmInline
value class SliceHandle(val packed: Int) {
    val page: Int get() = packed ushr PAGE_SHIFT
    val slice: Int get() = packed and SLICE_MASK
    val isNone: Boolean get() = packed == NONE_PACKED

    override fun toString(): String = if (isNone) "SliceHandle.NONE" else "SliceHandle($page:$slice)"

    companion object {
        const val PAGE_SHIFT = 16
        const val SLICE_MASK = 0xFFFF
        private const val NONE_PACKED = -1

        val NONE = SliceHandle(NONE_PACKED)

        /**
         * `page` is bounded at 32767 rather than 65535: `packed` is a signed
         * `Int`, so a page of 32768 would set the sign bit and collide with
         * [NONE]. A pool that large is 2 TiB of tiles, so the bound costs
         * nothing and removes the one input that could forge "no tile".
         */
        const val MAX_PAGE = 0x7FFF
        const val MAX_SLICE = SLICE_MASK

        fun of(page: Int, slice: Int): SliceHandle {
            require(page in 0..MAX_PAGE) { "page must be 0..$MAX_PAGE, was $page" }
            require(slice in 0..MAX_SLICE) { "slice must be 0..$MAX_SLICE, was $slice" }
            return SliceHandle((page shl PAGE_SHIFT) or slice)
        }
    }
}

/**
 * The pool has no slice left and may not create another page
 * (`docs/plan/03-canvas-engine.md` §2.1).
 *
 * An ordinary outcome, not a crash: the caller refuses the operation that
 * needed the tile — a stroke is cancelled with a notice, a layer add is
 * refused with the budget readout (decision 4: honest, never silent). It
 * carries the numbers so that notice can quote them.
 */
class PoolExhausted(
    val pageCount: Int,
    val maxPages: Int,
    val slicesPerPage: Int,
) : IllegalStateException(
    "tile pool exhausted: $pageCount of $maxPages pages × $slicesPerPage slices are all in use",
)

/**
 * The slice bookkeeping of `engine/gl/TilePool` with no GL in it: which slices
 * are free, which page a new tile comes from, and when the pool is full.
 *
 * Separated because it is decision-shaped, and §15 of `03-canvas-engine.md`
 * says decision-shaped things get a pure-JVM twin with tests while the GL
 * class "calls those and issues GL calls, nothing else". Free-list arithmetic
 * and the exclusion rule of §2.1 are exactly the kind of code that is wrong
 * silently — a double free hands the same slice to two layers and the painting
 * grows a second copy of somebody else's tile — and a JVM test can pin all of
 * it without a context.
 *
 * Not thread-safe: every caller is on the GL thread (`02-architecture.md` §8).
 */
class SliceAllocator(
    val slicesPerPage: Int,
    val maxPages: Int,
) {
    init {
        require(slicesPerPage in 1..SliceHandle.MAX_SLICE + 1) {
            "slicesPerPage must be 1..${SliceHandle.MAX_SLICE + 1}, was $slicesPerPage"
        }
        require(maxPages in 1..SliceHandle.MAX_PAGE + 1) {
            "maxPages must be 1..${SliceHandle.MAX_PAGE + 1}, was $maxPages"
        }
    }

    /** Per page: a stack of free slice indices, and how many of them are live. */
    private val freeStacks = ArrayList<IntArray>()
    private val freeCounts = ArrayList<Int>()

    /**
     * Per page, one bit per slice, set while the slice is handed out.
     *
     * Only [free] reads it, and only to refuse a double free. Without it a
     * double free pushes the same index onto the stack twice and the *next*
     * two allocations get the same slice — two layers writing one tile, with
     * nothing to trace it back to. 8 bytes per 64 slices.
     */
    private val allocatedBits = ArrayList<LongArray>()

    val pageCount: Int get() = freeStacks.size

    /** Slices handed out and not yet freed. */
    var usedCount: Int = 0
        private set

    /** Slices sitting on a free list. `pageCount × slicesPerPage - usedCount`. */
    val freeCount: Int get() = pageCount * slicesPerPage - usedCount

    /** Bytes of GPU texture the pages created so far occupy. */
    val residentBytes: Long get() = pageCount.toLong() * slicesPerPage * PerfConstants.TILE_BYTES

    /**
     * Records a page the caller has just created on the GPU, and returns its
     * index. Every slice starts free.
     *
     * The caller creates the texture *first* and calls this only once the
     * allocation succeeded: a page recorded here that does not exist in GL
     * would hand out handles into nothing. [PoolExhausted] is thrown before
     * any of that, so a caller that checks [canAddPage] never allocates a
     * texture it has to throw away.
     */
    fun addPage(): Int {
        if (!canAddPage) throw PoolExhausted(pageCount, maxPages, slicesPerPage)
        // Descending, so the first slices handed out are 0, 1, 2 … — the pool
        // behaves the same as a naive counter until something is freed, which
        // makes a GL capture readable and a test's expected handles obvious.
        val stack = IntArray(slicesPerPage) { slicesPerPage - 1 - it }
        freeStacks += stack
        freeCounts += slicesPerPage
        allocatedBits += LongArray((slicesPerPage + 63) / 64)
        return freeStacks.size - 1
    }

    val canAddPage: Boolean get() = pageCount < maxPages

    /**
     * A free slice on any page, or [SliceHandle.NONE] if every page is full.
     *
     * Never creates a page: the caller owns the GL allocation, so it decides
     * whether to pay for one ([addPage]) and retry. That is also what makes
     * this function total — it has no failure mode of its own.
     */
    fun tryAllocate(): SliceHandle {
        for (page in freeStacks.indices) {
            if (freeCounts[page] > 0) return take(page)
        }
        return SliceHandle.NONE
    }

    /**
     * [tryAllocate] restricted to pages not in [excluded]
     * (`03-canvas-engine.md` §2.1, `TilePool.allocateNotOn`).
     *
     * ES 3.0 makes rendering undefined when the fragment shader samples the
     * texture *object* bound as the draw attachment, and a page is one object:
     * rendering into slice 7 while sampling slice 3 of the same page is a
     * feedback loop even though the texels never overlap. So every
     * read-modify-write pass takes its target from a page it does not sample,
     * and this is the query that finds one.
     *
     * [excluded] is an `IntArray` and not a `Set`: it holds one or two page
     * indices, is built once per pass, and a `Set` would allocate on the frame
     * path for a linear scan of two elements.
     */
    fun tryAllocateNotOn(excluded: IntArray): SliceHandle {
        for (page in freeStacks.indices) {
            if (freeCounts[page] > 0 && !excluded.contains(page)) return take(page)
        }
        return SliceHandle.NONE
    }

    private fun take(page: Int): SliceHandle {
        val n = freeCounts[page] - 1
        freeCounts[page] = n
        val slice = freeStacks[page][n]
        markAllocated(page, slice, true)
        usedCount++
        return SliceHandle.of(page, slice)
    }

    /**
     * Returns a slice to its page's free list. Its contents are garbage until
     * something clears them — §2.1's contract, and why `TilePool` has a
     * separate `allocateCleared`.
     *
     * Refuses a handle it never handed out, and refuses a double free, rather
     * than corrupting the free list. [SliceHandle.NONE] is accepted and
     * ignored: "free whatever this index held" is the shape of every caller
     * in `LayerTextures`, and a dense index is full of `NONE`.
     */
    fun free(handle: SliceHandle) {
        if (handle.isNone) return
        val page = handle.page
        val slice = handle.slice
        require(page < pageCount) { "no page $page in a pool of $pageCount" }
        require(slice < slicesPerPage) { "no slice $slice in a page of $slicesPerPage" }
        require(isAllocated(page, slice)) { "slice $page:$slice is already free" }
        markAllocated(page, slice, false)
        val n = freeCounts[page]
        freeStacks[page][n] = slice
        freeCounts[page] = n + 1
        usedCount--
    }

    /** True while [handle] is one this allocator has handed out and not taken back. */
    fun isLive(handle: SliceHandle): Boolean =
        !handle.isNone &&
            handle.page < pageCount &&
            handle.slice < slicesPerPage &&
            isAllocated(handle.page, handle.slice)

    /**
     * Forgets every page and every handle — the document is closing, or the
     * context was lost and everything on the GPU went with it
     * (`03-canvas-engine.md` §12).
     *
     * Deliberately not "free every slice": after a context loss the textures
     * are gone, so there is nothing to return, and the handles the layers
     * still hold are stale by definition. `LayerTextures.rebuild()` is what
     * makes them valid again.
     */
    fun reset() {
        freeStacks.clear()
        freeCounts.clear()
        allocatedBits.clear()
        usedCount = 0
    }

    private fun isAllocated(page: Int, slice: Int): Boolean =
        (allocatedBits[page][slice ushr 6] ushr (slice and 63)) and 1L == 1L

    private fun markAllocated(page: Int, slice: Int, live: Boolean) {
        val words = allocatedBits[page]
        val i = slice ushr 6
        val bit = 1L shl (slice and 63)
        words[i] = if (live) words[i] or bit else words[i] and bit.inv()
    }
}
