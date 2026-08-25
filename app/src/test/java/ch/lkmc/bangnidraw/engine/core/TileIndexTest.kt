package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The per-layer index of `docs/plan/03-canvas-engine.md` §2.2. */
class TileIndexTest {

    // 3x2 tiles.
    private val grid = TileGrid(768, 512)

    private fun keys(index: TileIndex, rect: IntRect): List<TileKey> {
        val out = IntArray(grid.tileCount)
        val n = index.visibleKeys(rect, out, LongArray(grid.tileCount))
        return (0 until n).map { TileKey(out[it]) }
    }

    @Test
    fun `an empty index has no content anywhere`() {
        val index = TileIndex(grid)
        assertEquals(0, index.presentCount)
        for (ty in 0 until grid.tilesY) {
            for (tx in 0 until grid.tilesX) {
                assertTrue(index[TileKey(tx, ty)].isNone)
                assertFalse(index.hasContent(TileKey(tx, ty)))
            }
        }
        assertEquals(emptyList(), keys(index, IntRect(0, 0, 768, 512)))
    }

    @Test
    fun `put returns the previous handle so the caller can free it`() {
        // The swap-on-merge of §7.4. A setter that dropped the old handle
        // would leak one slice per stroke with nothing to notice it until the
        // pool ran dry — so the return value is the contract, not a courtesy.
        val index = TileIndex(grid)
        val k = TileKey(1, 1)
        assertTrue(index.put(k, SliceHandle.of(0, 7)).isNone)
        assertEquals(SliceHandle.of(0, 7), index.put(k, SliceHandle.of(1, 3)))
        assertEquals(SliceHandle.of(1, 3), index[k])
        assertEquals(1, index.presentCount)
    }

    @Test
    fun `presentCount tracks both directions and does not double-count`() {
        val index = TileIndex(grid)
        val k = TileKey(0, 0)
        index.put(k, SliceHandle.of(0, 1))
        assertEquals(1, index.presentCount)
        // Overwriting a live key with another live handle must not increment.
        index.put(k, SliceHandle.of(0, 2))
        assertEquals(1, index.presentCount)
        index.put(k, SliceHandle.NONE)
        assertEquals(0, index.presentCount)
        // And clearing an already-clear key must not go negative.
        index.put(k, SliceHandle.NONE)
        assertEquals(0, index.presentCount)
    }

    @Test
    fun `a read outside the grid is NONE, a write outside it throws`() {
        // Asymmetric on purpose: the dirty rect of a stroke near the edge
        // covers keys past the last tile and the compositor's loop is "if
        // there is a slice, draw it", so a read has an honest answer. A write
        // has none, and discarding it would lose a painted tile.
        val index = TileIndex(grid)
        assertTrue(index[TileKey(3, 0)].isNone)
        assertTrue(index[TileKey(0, 2)].isNone)
        assertFalse(index.hasContent(TileKey(99, 99)))
        assertFailsWith<IllegalArgumentException> { index.put(TileKey(3, 0), SliceHandle.of(0, 0)) }
        assertFailsWith<IllegalArgumentException> { index.put(TileKey(0, 2), SliceHandle.of(0, 0)) }
    }

    @Test
    fun `visibleKeys returns only keys that both intersect the rect and have content`() {
        val index = TileIndex(grid)
        index.put(TileKey(0, 0), SliceHandle.of(0, 0))
        index.put(TileKey(2, 1), SliceHandle.of(0, 1))
        // Painted, but outside the rect asked for.
        assertEquals(listOf(TileKey(0, 0)), keys(index, IntRect(0, 0, 100, 100)))
        // Inside the rect, but never painted: tile (1,0) has no slice.
        assertEquals(listOf(TileKey(0, 0)), keys(index, IntRect(0, 0, 512, 256)))
        assertEquals(
            listOf(TileKey(0, 0), TileKey(2, 1)),
            keys(index, IntRect(0, 0, 768, 512)),
        )
    }

    @Test
    fun `visibleKeys sorts by page so the compositor binds each page once`() {
        // §3.2 batches quads by page: one glBindTexture and one glDrawArrays
        // per page. Keys arriving in row-major order would make the
        // compositor rebind per tile, which is the exact cost the tile pool's
        // page design exists to avoid.
        val index = TileIndex(grid)
        index.put(TileKey(0, 0), SliceHandle.of(2, 0))
        index.put(TileKey(1, 0), SliceHandle.of(0, 5))
        index.put(TileKey(2, 0), SliceHandle.of(1, 9))
        index.put(TileKey(0, 1), SliceHandle.of(0, 6))
        val visible = keys(index, IntRect(0, 0, 768, 512))
        assertEquals(4, visible.size)
        val pages = visible.map { index[it].page }
        assertEquals(listOf(0, 0, 1, 2), pages, "keys were not grouped by page: $visible")
    }

    @Test
    fun `visibleKeys allocates nothing and reuses the caller's buffers`() {
        // The per-frame contract of `10-performance.md` §2.4. The buffers are
        // the caller's precisely so this can be true, and the test states it
        // by reusing one pair across calls and checking the earlier results
        // are gone rather than appended.
        val index = TileIndex(grid)
        index.put(TileKey(0, 0), SliceHandle.of(0, 0))
        index.put(TileKey(2, 1), SliceHandle.of(0, 1))
        val out = IntArray(grid.tileCount)
        val scratch = LongArray(grid.tileCount)
        assertEquals(2, index.visibleKeys(IntRect(0, 0, 768, 512), out, scratch))
        assertEquals(1, index.visibleKeys(IntRect(0, 0, 100, 100), out, scratch))
        assertEquals(TileKey(0, 0), TileKey(out[0]))
        assertEquals(0, index.visibleKeys(IntRect(300, 0, 500, 100), out, scratch))
    }

    @Test
    fun `visibleKeys refuses a scratch too small rather than overrunning it`() {
        val index = TileIndex(grid)
        index.put(TileKey(0, 0), SliceHandle.of(0, 0))
        val out = IntArray(grid.tileCount)
        assertFailsWith<IllegalArgumentException> {
            index.visibleKeys(IntRect(0, 0, 768, 512), out, LongArray(1))
        }
    }

    @Test
    fun `an empty index short-circuits before touching the buffers`() {
        // presentCount == 0 is the common case for a layer outside the dirty
        // rect, and it must cost no scan and no bounds check.
        val index = TileIndex(grid)
        assertEquals(0, index.visibleKeys(IntRect(0, 0, 768, 512), IntArray(0), LongArray(0)))
    }

    @Test
    fun `allKeys is row-major over everything painted`() {
        val index = TileIndex(grid)
        index.put(TileKey(2, 1), SliceHandle.of(0, 3))
        index.put(TileKey(0, 0), SliceHandle.of(0, 1))
        index.put(TileKey(1, 1), SliceHandle.of(0, 2))
        val out = mutableListOf<TileKey>()
        index.allKeys(out)
        assertEquals(listOf(TileKey(0, 0), TileKey(1, 1), TileKey(2, 1)), out)
        // Cleared first, like visibleKeys: a caller reusing a list must not
        // silently accumulate two layers' keys into one draw.
        index.allKeys(out)
        assertEquals(3, out.size)
    }

    @Test
    fun `clear hands every live handle to the caller exactly once and empties`() {
        val index = TileIndex(grid)
        val handles = listOf(SliceHandle.of(0, 1), SliceHandle.of(1, 2), SliceHandle.of(0, 4))
        index.put(TileKey(0, 0), handles[0])
        index.put(TileKey(1, 1), handles[1])
        index.put(TileKey(2, 0), handles[2])
        val freed = mutableListOf<SliceHandle>()
        index.clear(freed::add)
        assertEquals(handles.toSet(), freed.toSet())
        assertEquals(3, freed.size, "a handle was freed twice: $freed")
        assertEquals(0, index.presentCount)
        assertTrue(index[TileKey(0, 0)].isNone)
        // Idempotent: a second clear must not re-free anything.
        freed.clear()
        index.clear(freed::add)
        assertEquals(emptyList(), freed)
    }

    @Test
    fun `forgetAll drops handles without freeing them`() {
        // §12: after a context loss the slices do not exist to be returned,
        // and handing them to a pool that has itself been reset would push
        // indices onto the free list of pages that are gone.
        val index = TileIndex(grid)
        index.put(TileKey(1, 0), SliceHandle.of(0, 1))
        index.forgetAll()
        assertEquals(0, index.presentCount)
        assertTrue(index[TileKey(1, 0)].isNone)
        val freed = mutableListOf<SliceHandle>()
        index.clear(freed::add)
        assertEquals(emptyList(), freed, "forgetAll must not leave anything to free")
    }
}
