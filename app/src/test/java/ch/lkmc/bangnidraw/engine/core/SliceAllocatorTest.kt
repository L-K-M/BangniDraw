package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure half of `engine/gl/TilePool` (`docs/plan/03-canvas-engine.md` §2.1). */
class SliceAllocatorTest {

    private fun full(slicesPerPage: Int, maxPages: Int): SliceAllocator =
        SliceAllocator(slicesPerPage, maxPages).also { repeat(maxPages) { _ -> it.addPage() } }

    @Test
    fun `a fresh allocator has no pages and no slices`() {
        val a = SliceAllocator(slicesPerPage = 4, maxPages = 2)
        assertEquals(0, a.pageCount)
        assertEquals(0, a.freeCount)
        assertEquals(0, a.usedCount)
        assertEquals(0L, a.residentBytes)
        // Never creates a page of its own: the GL side owns the allocation, so
        // there is nothing this could allocate on the caller's behalf.
        assertTrue(a.tryAllocate().isNone)
        assertEquals(0, a.pageCount)
    }

    @Test
    fun `slices come out in ascending order on a fresh page`() {
        // Not cosmetic: with a descending free stack the first handles are
        // 0, 1, 2 …, so a GL capture reads like a counter and a test's
        // expected handles are obvious. A reversed stack would still be
        // correct and would make every other test here unreadable.
        val a = SliceAllocator(slicesPerPage = 4, maxPages = 1)
        a.addPage()
        for (i in 0 until 4) assertEquals(SliceHandle.of(0, i), a.tryAllocate())
    }

    @Test
    fun `a full pool answers NONE rather than growing itself`() {
        val a = full(slicesPerPage = 2, maxPages = 1)
        repeat(2) { assertFalse(a.tryAllocate().isNone) }
        assertTrue(a.tryAllocate().isNone)
        assertEquals(2, a.usedCount)
        assertEquals(0, a.freeCount)
    }

    @Test
    fun `addPage past the cap is PoolExhausted, and says what the cap was`() {
        // The message is the layer-add refusal's text (decision 4: honest,
        // never silent), so the numbers being in it is part of the contract.
        val a = full(slicesPerPage = 8, maxPages = 2)
        assertFalse(a.canAddPage)
        val e = assertFailsWith<PoolExhausted> { a.addPage() }
        assertEquals(2, e.pageCount)
        assertEquals(2, e.maxPages)
        assertEquals(8, e.slicesPerPage)
        assertTrue("2" in e.message!! && "8" in e.message!!, "message was ${e.message}")
    }

    @Test
    fun `a freed slice is handed out again`() {
        val a = SliceAllocator(slicesPerPage = 3, maxPages = 1)
        a.addPage()
        val first = a.tryAllocate()
        val second = a.tryAllocate()
        a.free(first)
        assertEquals(1, a.usedCount)
        assertEquals(2, a.freeCount)
        // LIFO: the most recently freed slice comes back first, which keeps a
        // stroke's scratch slices hot in the same page.
        assertEquals(first, a.tryAllocate())
        assertFalse(a.isLive(SliceHandle.NONE))
        assertTrue(a.isLive(second))
    }

    @Test
    fun `a double free is refused instead of corrupting the free list`() {
        // Without the allocated bitset this pushes the same index twice and
        // the NEXT two allocations return the same slice — two layers writing
        // one tile, with nothing to trace it back to.
        val a = SliceAllocator(slicesPerPage = 4, maxPages = 1)
        a.addPage()
        val h = a.tryAllocate()
        a.free(h)
        assertFailsWith<IllegalArgumentException> { a.free(h) }
        // And the free list is intact: four distinct slices still come out.
        val seen = (0 until 4).map { a.tryAllocate() }.toSet()
        assertEquals(4, seen.size, "the free list handed out a duplicate: $seen")
    }

    @Test
    fun `freeing NONE is a no-op, freeing a foreign handle is not`() {
        // NONE is accepted because a dense TileIndex is full of it and every
        // caller's shape is "free whatever this key held".
        val a = SliceAllocator(slicesPerPage = 2, maxPages = 1)
        a.addPage()
        a.free(SliceHandle.NONE)
        assertEquals(0, a.usedCount)
        // usedCount alone cannot see the failure that matters: pushing a
        // garbage index onto the free stack leaves usedCount at 0 and hands
        // out an invalid handle two allocations later.
        assertEquals(2, a.freeCount, "freeing NONE must not push onto the free list")
        assertFailsWith<IllegalArgumentException> { a.free(SliceHandle.of(1, 0)) }
        assertFailsWith<IllegalArgumentException> { a.free(SliceHandle.of(0, 9)) }
    }

    @Test
    fun `tryAllocateNotOn skips excluded pages`() {
        // The ES 3.0 feedback-loop rule of §2.1: a pass may not render into a
        // slice of a page it is sampling, even a different slice.
        val a = SliceAllocator(slicesPerPage = 2, maxPages = 3)
        a.addPage()
        a.addPage()
        val onPage0 = a.tryAllocateNotOn(intArrayOf(1))
        assertEquals(0, onPage0.page)
        val onPage1 = a.tryAllocateNotOn(intArrayOf(0))
        assertEquals(1, onPage1.page)
        // Excluding every page with room answers NONE — the caller pays for a
        // fresh page and retries, which is TilePool.allocateNotOn's job.
        assertTrue(a.tryAllocateNotOn(intArrayOf(0, 1)).isNone)
        assertEquals(2, a.pageCount, "the allocator must not create the page itself")
    }

    @Test
    fun `tryAllocateNotOn falls through a full page to a later allowed one`() {
        // A page can be excluded AND another full; the scan must not stop at
        // the first page it cannot use.
        val a = SliceAllocator(slicesPerPage = 1, maxPages = 3)
        repeat(3) { a.addPage() }
        a.tryAllocateNotOn(intArrayOf(1, 2)) // fills page 0
        val h = a.tryAllocateNotOn(intArrayOf(1))
        assertEquals(2, h.page, "page 0 is full and page 1 excluded, so page 2 is the answer")
    }

    @Test
    fun `residentBytes counts pages, not slices in use`() {
        // It is what the debug overlay and the PoolExhausted notice report as
        // GPU memory held, and a page is held whether or not it is used.
        val a = SliceAllocator(slicesPerPage = 4, maxPages = 2)
        a.addPage()
        assertEquals(4L * PerfConstants.TILE_BYTES, a.residentBytes)
        a.tryAllocate()
        assertEquals(4L * PerfConstants.TILE_BYTES, a.residentBytes)
        a.addPage()
        assertEquals(8L * PerfConstants.TILE_BYTES, a.residentBytes)
    }

    @Test
    fun `reset forgets everything without freeing`() {
        // §12: after a context loss the textures are gone, so there is nothing
        // to return and every outstanding handle is stale by definition.
        val a = SliceAllocator(slicesPerPage = 2, maxPages = 2)
        a.addPage()
        val h = a.tryAllocate()
        a.reset()
        assertEquals(0, a.pageCount)
        assertEquals(0, a.usedCount)
        assertFalse(a.isLive(h))
    }

    @Test
    fun `handles pack and unpack, and no legal handle collides with NONE`() {
        assertEquals(-1, SliceHandle.NONE.packed)
        assertTrue(SliceHandle.NONE.isNone)
        for (page in listOf(0, 1, 255, SliceHandle.MAX_PAGE)) {
            for (slice in listOf(0, 1, 255, SliceHandle.MAX_SLICE)) {
                val h = SliceHandle.of(page, slice)
                assertEquals(page, h.page, "page of $page:$slice")
                assertEquals(slice, h.slice, "slice of $page:$slice")
                assertFalse(h.isNone, "$page:$slice collided with NONE")
                assertTrue(h.packed >= 0, "$page:$slice packed negative")
            }
        }
    }

    @Test
    fun `a page past the sign bit is refused, not silently folded onto NONE`() {
        // 0x8000 shifted left 16 sets the sign bit; without the bound, every
        // page >= 0x8000 packs negative — of(0x8000, 0xFFFF) is 0x8000FFFF,
        // and only of(0xFFFF, 0xFFFF) is literally -1. So the hazard is wider
        // than one colliding value: the invariant this file asserts elsewhere,
        // "a legal handle packs non-negative", is what the bound protects, and
        // narrowing the check to the single -1 case would reintroduce it.
        assertFailsWith<IllegalArgumentException> { SliceHandle.of(SliceHandle.MAX_PAGE + 1, 0) }
        assertFailsWith<IllegalArgumentException> { SliceHandle.of(0, SliceHandle.MAX_SLICE + 1) }
        assertFailsWith<IllegalArgumentException> { SliceHandle.of(-1, 0) }
        assertFailsWith<IllegalArgumentException> { SliceHandle.of(0, -1) }
    }

    @Test
    fun `degenerate geometry is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { SliceAllocator(0, 1) }
        assertFailsWith<IllegalArgumentException> { SliceAllocator(1, 0) }
        assertFailsWith<IllegalArgumentException> { SliceAllocator(-4, 1) }
        // One past what a handle can address, on each axis.
        assertFailsWith<IllegalArgumentException> { SliceAllocator(SliceHandle.MAX_SLICE + 2, 1) }
        assertFailsWith<IllegalArgumentException> { SliceAllocator(1, SliceHandle.MAX_PAGE + 2) }
    }

    @Test
    fun `every slice of every page is reachable and distinct`() {
        // The whole-pool property: exhausting a 3x5 pool must yield 15
        // distinct handles and then stop. A free-stack off-by-one shows up
        // here as a duplicate or a missing slice, and nowhere else.
        val a = full(slicesPerPage = 5, maxPages = 3)
        val handles = generateSequence { a.tryAllocate().takeIf { !it.isNone } }.toList()
        assertEquals(15, handles.size)
        assertEquals(15, handles.toSet().size, "duplicate handle in $handles")
        assertEquals(15, a.usedCount)
        assertEquals(0, a.freeCount)
        for (h in handles) assertTrue(a.isLive(h), "$h should be live")
        handles.forEach(a::free)
        assertEquals(0, a.usedCount)
        assertEquals(15, a.freeCount)
    }
}
