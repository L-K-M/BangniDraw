package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `docs/plan/11-testing.md` §3.6. */
class TileGridTest {

    private val grid = TileGrid(1024, 768)

    @Test
    fun `a dirty rect maps to exactly the tiles it overlaps`() {
        assertEquals(
            listOf(TileKey(0, 0)),
            grid.keysFor(IntRect(0, 0, 256, 256)),
            "a rect that stops exactly on the tile boundary must not reach into the next tile",
        )
        assertEquals(
            listOf(TileKey(0, 0), TileKey(1, 0)),
            grid.keysFor(IntRect(0, 0, 257, 256)),
            "one pixel past the boundary is one more tile, enumerated row-major",
        )
        assertEquals(
            listOf(TileKey(0, 0), TileKey(1, 0), TileKey(0, 1), TileKey(1, 1)),
            grid.keysFor(IntRect(255, 255, 257, 257)),
            "a 2x2 rect on a tile corner touches four tiles, enumerated row-major",
        )
    }

    @Test
    fun `a rect covering the whole canvas yields every tile exactly once`() {
        val keys = grid.keysFor(IntRect(0, 0, 1024, 768))
        assertEquals(grid.tileCount, keys.size)
        assertEquals(keys.size, keys.toSet().size, "no tile may be emitted twice from one rect")
        assertTrue(TileKey(3, 2) in keys, "the last tile of the dense grid is included")
    }

    @Test
    fun `a one pixel rect on a tile corner is one key`() {
        assertEquals(listOf(TileKey(1, 1)), grid.keysFor(IntRect(256, 256, 257, 257)))
    }

    @Test
    fun `a rect partly outside the canvas maps only to in-canvas tiles`() {
        val keys = grid.keysFor(IntRect(-500, -500, 100, 100))
        assertEquals(listOf(TileKey(0, 0)), keys, "the outside part contributes nothing")

        val past = grid.keysFor(IntRect(1000, 700, 5000, 5000))
        assertEquals(listOf(TileKey(3, 2)), past, "the canvas is 4x3 tiles, so 3,2 is the last one")
        // keysFor clips before it divides; if that order ever flipped, adding
        // TILE_SIZE-1 to a bound near Int.MAX_VALUE would overflow negative
        // and the rect would silently mark nothing dirty.
        assertEquals(
            grid.tileCount,
            grid.keysFor(IntRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)).size,
            "extreme bounds must be clipped before the division, not overflow it",
        )
    }

    @Test
    fun `a rect entirely outside the canvas maps to nothing`() {
        assertTrue(grid.keysFor(IntRect(2000, 2000, 3000, 3000)).isEmpty())
        assertTrue(grid.keysFor(IntRect(-100, -100, -10, -10)).isEmpty())
    }

    @Test
    fun `an empty rect maps to nothing`() {
        assertTrue(grid.keysFor(IntRect(10, 10, 10, 300)).isEmpty(), "zero width")
        assertTrue(grid.keysFor(IntRect(10, 10, 300, 10)).isEmpty(), "zero height")
        assertTrue(grid.keysFor(IntRect(300, 300, 10, 10)).isEmpty(), "inverted")
    }

    @Test
    fun `a dab's dirty rect includes its full radius plus the anti-aliasing band`() {
        val r = IntRect.forDab(x = 300.5f, y = 300.5f, radius = 4f)
        assertEquals(IntRect(295, 295, 306, 306), r, "floor(x-r-1) .. ceil(x+r+1)")

        val onePixel = IntRect.forDab(x = 128f, y = 128f, radius = 0f)
        assertEquals(IntRect(127, 127, 129, 129), onePixel, "a zero-radius dab still covers its band")
    }

    @Test
    fun `a dab that is not finite and non-negative is refused rather than dropped`() {
        // floor(NaN).toInt() is 0 and a negative radius inverts the rect; both
        // yield an empty rect, i.e. a stroke gap with nothing to trace it to.
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { IntRect.forDab(bad, 0f, 4f) }
            assertFailsWith<IllegalArgumentException> { IntRect.forDab(0f, bad, 4f) }
            assertFailsWith<IllegalArgumentException> { IntRect.forDab(0f, 0f, bad) }
        }
        assertFailsWith<IllegalArgumentException> { IntRect.forDab(10f, 10f, -1f) }
        assertEquals(
            IntRect(9, 9, 11, 11),
            IntRect.forDab(10f, 10f, 0f),
            "radius zero is accepted and still gets its anti-aliasing band",
        )
    }

    @Test
    fun `tile keys are stable and hashable`() {
        val a = TileKey(3, 7)
        val b = TileKey(3, 7)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(3, a.tx)
        assertEquals(7, a.ty)
        assertEquals(1, setOf(a, b).size, "equal keys collapse in a set")
        assertEquals(31, TileKey(31, 31).tx, "the largest 8192-canvas coordinate round-trips")
        assertEquals(65535, TileKey(65535, 65535).tx, "and the packed 16-bit field itself round-trips")
        assertEquals(65535, TileKey(65535, 65535).ty)
        // Round-tripping tx and ty is not enough: a packing like
        // tx * 65535 + ty returns both fields correctly for every input and
        // still aliases TileKey(1, 0) onto TileKey(0, 65535). The 4x3 grid
        // above cannot catch it — no two of its keys are 65535 apart — and a
        // collision silently reads one tile's pixels for another.
        assertEquals(
            2,
            setOf(TileKey(1, 0), TileKey(0, 65535)).size,
            "distinct coordinates must not collide once packed",
        )
    }

    @Test
    fun `tileCount for a canvas size is ceil in both axes`() {
        TileGrid(4096, 4096).let {
            assertEquals(16, it.tilesX)
            assertEquals(16, it.tilesY)
            assertEquals(256, it.tileCount)
        }
        TileGrid(1000, 1000).let {
            assertEquals(4, it.tilesX)
            assertEquals(4, it.tilesY)
            assertEquals(16, it.tileCount)
        }
        TileGrid(257, 256).let {
            assertEquals(2, it.tilesX)
            assertEquals(1, it.tilesY)
        }
    }

    @Test
    fun `origin and index address the dense row-major grid`() {
        assertEquals(IntPoint(512, 256), grid.origin(TileKey(2, 1)))
        assertEquals(6, grid.index(TileKey(2, 1)), "row 1 of a 4-wide grid starts at 4")
        assertTrue(grid.contains(TileKey(3, 2)))
        assertTrue(!grid.contains(TileKey(4, 2)), "the canvas is only 4 tiles wide")
    }

    @Test
    fun `a canvas outside the format's per-side range is refused at construction`() {
        // Not defence in depth for its own sake: TileKey packs 16-bit tile
        // coordinates, so a side past 16 777 216 px wraps into a plausible key
        // for the wrong tile, and (width + 255) overflows to a negative tilesX
        // near Int.MAX_VALUE. Both are silent pixel corruption; this is the
        // one place that can turn them into a thrown exception.
        for (bad in listOf(0, -1, TileGrid.MIN_EDGE - 1, TileGrid.MAX_EDGE + 1, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("a ${bad}px side must be refused") {
                TileGrid(bad, 1024)
            }
            assertFailsWith<IllegalArgumentException>("a ${bad}px side must be refused") {
                TileGrid(1024, bad)
            }
        }
        TileGrid(TileGrid.MIN_EDGE, TileGrid.MIN_EDGE)
        TileGrid(TileGrid.MAX_EDGE, TileGrid.MAX_EDGE).let {
            assertEquals(32, it.tilesX, "the largest canvas the format allows is 32 tiles per side")
            assertEquals(
                TileGrid.MAX_TILES,
                it.tileCount,
                "the largest canvas the format allows is exactly the tile ceiling",
            )
        }
    }

    @Test
    fun `keyAt wraps rather than clamps outside the canvas, which is why callers must clip`() {
        // Pinned so the KDoc's precondition is a tested claim, not a hope: a
        // stale pointer sample that left the canvas produces a well-formed
        // key nowhere near the input, which `contains` rejects and `index`
        // would not.
        assertEquals(TileKey(1, 1), grid.keyAt(256, 256), "inside the canvas, keyAt is plain division")
        assertEquals(TileKey(0, 0), grid.keyAt(0, 0))
        val outside = grid.keyAt(-1, -1)
        assertEquals(65535, outside.tx, "a negative coordinate wraps through the 16-bit mask")
        assertEquals(65535, outside.ty)
        assertTrue(!grid.contains(outside), "contains is what catches it")
        assertTrue(grid.keysFor(IntRect(-1, -1, 0, 0)).isEmpty(), "keysFor clips, so it never sees this")
    }

    @Test
    fun `the tile size and shift are one number, not two that can drift`() {
        assertEquals(
            PerfConstants.TILE_SIZE,
            1 shl PerfConstants.TILE_SHIFT,
            "every address in this class assumes TILE_SIZE == 1 shl TILE_SHIFT",
        )
        assertEquals(256, PerfConstants.TILE_SIZE, "and the plan's tile is 256 px")
        assertEquals(262144, PerfConstants.TILE_BYTES)
    }

    @Test
    fun `an edge tile's canvas rect is clipped to the canvas`() {
        val g = TileGrid(300, 300)
        assertEquals(IntRect(256, 256, 300, 300), g.tileRect(TileKey(1, 1)))
        assertEquals(IntRect(0, 0, 256, 256), g.tileRect(TileKey(0, 0)))
    }
}
