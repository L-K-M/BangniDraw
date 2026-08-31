package ch.lkmc.bangnidraw.engine.core

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FloodFillTest {

    @Test
    fun `closed line art contains a contiguous fill`() {
        val source = GridSource.fromRows(
            "#####",
            "#...#",
            "#...#",
            "#...#",
            "#####",
        )

        val coverage = fill(source, 2, 2)

        assertEquals(IntRect(1, 1, 4, 4), coverage.bounds)
        assertCovered(
            coverage,
            ".###.",
            ".###.",
            ".###.",
        )
    }

    @Test
    fun `a one pixel gap leaks and a closed gap does not`() {
        val closed = GridSource.fromRows(
            ".....",
            ".###.",
            ".#.#.",
            ".###.",
            ".....",
        )
        val open = GridSource.fromRows(
            ".....",
            ".#.#.",
            ".#.#.",
            ".###.",
            ".....",
        )

        assertEquals(IntRect(2, 2, 3, 3), fill(closed, 2, 2).bounds)
        assertEquals(IntRect(0, 0, 5, 5), fill(open, 2, 2).bounds)
    }

    @Test
    fun `global mode fills disconnected matches`() {
        val source = GridSource.fromRows(".#.")
        val contiguous = fill(source, 0, 0)
        val global = fill(source, 0, 0, FillParams(tolerance = 0f, contiguous = false, expand = 0, antialias = false))

        assertEquals(255, contiguous[0, 0])
        assertEquals(0, contiguous[2, 0])
        assertEquals(255, global[0, 0])
        assertEquals(255, global[2, 0])
    }

    @Test
    fun `tolerance uses unpremultiplied RGB plus alpha and includes its boundary`() {
        val seed = Composite.premultiply(Composite.argb(128, 200, 100, 50))
        val near = Composite.premultiply(Composite.argb(128, 210, 100, 50))
        val alphaFar = Composite.premultiply(Composite.argb(140, 200, 100, 50))
        val source = GridSource(3, 1, intArrayOf(seed, near, alphaFar))

        val below = fill(source, 0, 0, FillParams(tolerance = 9f / 255f, expand = 0, antialias = false))
        val boundary = fill(source, 0, 0, FillParams(tolerance = 10f / 255f, expand = 0, antialias = false))

        assertEquals(0, below[1, 0])
        assertEquals(255, boundary[1, 0])
        assertEquals(0, boundary[2, 0])
    }

    @Test
    fun `transparent pixels ignore hidden RGB`() {
        val source = GridSource(2, 1, intArrayOf(0x00010203, 0x00F0E0D0))

        val coverage = fill(source, 0, 0)

        assertEquals(255, coverage[0, 0])
        assertEquals(255, coverage[1, 0])
    }

    @Test
    fun `expand crosses an antialias skirt but stops at a color wall`() {
        val white = 0xFFFFFFFF.toInt()
        val skirt = 0xFFC8C8C8.toInt()
        val black = 0xFF000000.toInt()
        val source = GridSource(6, 1, intArrayOf(white, white, skirt, black, white, white))
        val params = FillParams(tolerance = 0.1f, expand = 2, antialias = false)

        val coverage = fill(source, 0, 0, params)

        assertEquals(255, coverage[0, 0])
        assertEquals(255, coverage[2, 0])
        assertEquals(0, coverage[3, 0])
        assertEquals(0, coverage[4, 0])
    }

    @Test
    fun `expand clips to the canvas`() {
        val source = GridSource.fromRows(".##")

        val coverage = fill(source, 0, 0, FillParams(tolerance = 0f, expand = 2, antialias = false))

        assertEquals(IntRect(0, 0, 1, 1), coverage.bounds)
        assertEquals(255, coverage[0, 0])
    }

    @Test
    fun `antialias makes a one pixel bounded ramp`() {
        val white = 0xFFFFFFFF.toInt()
        val skirt = 0xFFC8C8C8.toInt()
        val pixels = IntArray(25) { skirt }.also { it[12] = white }
        val source = GridSource(5, 5, pixels)

        val coverage = fill(source, 2, 2, FillParams(tolerance = 0f, expand = 0, antialias = true))

        assertEquals(IntRect(1, 1, 4, 4), coverage.bounds)
        for (y in 1..3) for (x in 1..3) assertEquals(28, coverage[x, y])
        assertEquals(0, coverage[0, 0])
    }

    @Test
    fun `cancellation returns no partial coverage`() {
        val source = GridSource(512, 512, IntArray(512 * 512) { 0xFFFFFFFF.toInt() })
        var polls = 0

        val coverage = FloodFill(source.width, source.height, source, FillParams()).run(
            seedX = 0,
            seedY = 0,
            progress = {},
            isCancelled = { ++polls >= 2 },
        )

        assertNull(coverage)
    }

    @Test
    fun `full canvas smoke stays bounded`() {
        val edge = 4096
        val source = PixelSource { _, _ -> 0xFFFFFFFF.toInt() }
        var coverage: Coverage? = null

        val elapsed = measureTimeMillis {
            coverage = FloodFill(
                edge,
                edge,
                source,
                FillParams(tolerance = 0f, expand = 0, antialias = false),
            ).run(0, 0, progress = {}, isCancelled = { false })
        }

        assertEquals(IntRect(0, 0, edge, edge), coverage?.bounds)
        assertTrue(elapsed < FILL_SMOKE_LIMIT_MS, "fill took $elapsed ms")
    }

    private fun fill(
        source: GridSource,
        seedX: Int,
        seedY: Int,
        params: FillParams = FillParams(tolerance = 0f, expand = 0, antialias = false),
    ): Coverage = requireNotNull(
        FloodFill(source.width, source.height, source, params)
            .run(seedX, seedY, progress = {}, isCancelled = { false }),
    )

    private fun assertCovered(coverage: Coverage, vararg rows: String) {
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, value ->
                assertEquals(if (value == '#') 255 else 0, coverage[x, y + coverage.bounds.top])
            }
        }
    }

    private class GridSource(
        val width: Int,
        val height: Int,
        private val pixels: IntArray,
    ) : PixelSource {
        override fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

        companion object {
            fun fromRows(vararg rows: String): GridSource {
                val width = rows.first().length
                require(rows.all { it.length == width })
                val pixels = rows.flatMap { row ->
                    row.map { if (it == '#') 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
                }.toIntArray()
                return GridSource(width, rows.size, pixels)
            }
        }
    }

    private companion object {
        const val FILL_SMOKE_LIMIT_MS = 10_000L
    }
}
