package ch.lkmc.bangnidraw.engine.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §3.11. The worked table is `docs/plan/10-performance.md`
 * §4's, pinned here so that changing a constant is a visible diff.
 */
class MemoryBudgetTest {

    private val gib = 1L shl 30
    private val mib = 1L shl 20

    private fun device(
        totalGib: Double,
        lowRam: Boolean = false,
        glMaxArrayLayers: Int = 256,
        glMaxTextureSize: Int = 4096,
    ) = DeviceMemory(
        totalMemBytes = (totalGib * gib).toLong(),
        isLowRamDevice = lowRam,
        // The one field deliberately not a parameter: `MemoryBudget.compute`
        // never reads it, so sweeping it would assert that a number nothing
        // consumes changes nothing. It is on `DeviceMemory` because
        // `10-performance.md` §1 declares the startup probe normatively, not
        // because this class consumes it. Constant here, and the reason is
        // here rather than in review history — REVIEW.md R-015.
        largeMemoryClassMb = 512,
        glMaxArrayLayers = glMaxArrayLayers,
        glMaxTextureSize = glMaxTextureSize,
    )

    private val canvas4096 = CanvasSize(4096, 4096)
    private val canvas2048 = CanvasSize(2048, 2048)

    @Test
    fun `the worked table of 10 section 4 holds`() {
        MemoryBudget.compute(device(8.0), canvas4096).let {
            assertEquals(1024 * mib, it.gpuTileBudgetBytes, "8 GiB device, tile budget")
            assertEquals(WatercolorScratchBudget.MAX_BYTES, it.watercolorScratchMaxBytes)
            assertEquals(14, it.maxLayers, "8 GiB device, 4096 canvas")
            assertEquals(4096, it.maxCanvasEdge, "the v1 ceiling")
            assertEquals(200, it.historyMaxSteps)
            assertEquals(256 * mib, it.historyMaxBytes)
            assertEquals(24 * mib, it.thumbnailCacheBytes)
            assertEquals(256, it.poolArraySlices)
            assertEquals(16, it.poolArrayCount)
            assertEquals(64 * mib, it.transientImageBytes)
        }
        MemoryBudget.compute(device(8.0), canvas2048).let {
            assertEquals(16, it.maxLayers, "a smaller canvas hits the MAX_LAYERS clamp")
            assertEquals(4096, it.maxCanvasEdge)
        }
        MemoryBudget.compute(device(4.0, lowRam = true), canvas2048).let {
            assertEquals(14, it.maxLayers, "wet state and both gesture reserves fit")
        }
        MemoryBudget.compute(device(4.0), canvas4096).let {
            assertEquals(512 * mib, it.gpuTileBudgetBytes)
            assertEquals(6, it.maxLayers)
            assertEquals(4096, it.maxCanvasEdge)
            assertEquals(100, it.historyMaxSteps)
            assertEquals(128 * mib, it.historyMaxBytes)
            assertEquals(12 * mib, it.thumbnailCacheBytes)
            assertEquals(8, it.poolArrayCount)
        }
        MemoryBudget.compute(device(4.0, lowRam = true), canvas4096).let {
            assertEquals(256 * mib, it.gpuTileBudgetBytes, "a low-RAM device gets the flat budget")
            assertEquals(2, it.maxLayers)
            assertEquals(3328, it.maxCanvasEdge, "3584 squared exceeds the wet-aware reserve")
            assertEquals(100, it.historyMaxSteps)
            assertEquals(128 * mib, it.historyMaxBytes)
            assertEquals(8 * mib, it.thumbnailCacheBytes)
            assertEquals(4, it.poolArrayCount)
        }
        MemoryBudget.compute(device(12.0), canvas4096).let {
            assertEquals(1536 * mib, it.gpuTileBudgetBytes, "clamped at GPU_TILE_MAX_BYTES")
            assertEquals(16, it.maxLayers)
            assertEquals(4096, it.maxCanvasEdge)
            assertEquals(200, it.historyMaxSteps)
            assertEquals(256 * mib, it.historyMaxBytes)
            assertEquals(24 * mib, it.thumbnailCacheBytes)
            assertEquals(24, it.poolArrayCount)
        }
    }

    @Test
    fun `mutually exclusive gesture buffers share the larger reserve`() {
        // Two 4096² layers use 136 MiB. A gesture then needs either a 64 MiB
        // stroke buffer or a 4 MiB wet backup, never both.
        assertEquals(
            1,
            MemoryBudget.maxLayersFor(199 * mib, canvas4096),
        )
        assertEquals(2, MemoryBudget.maxLayersFor(200 * mib, canvas4096))
    }

    @Test
    fun `a low-RAM device gets the flat 256 MiB tile budget`() {
        for (totalGib in listOf(2.0, 4.0, 8.0, 16.0)) {
            assertEquals(
                PerfConstants.LOW_RAM_GPU_TILE_BYTES,
                MemoryBudget.compute(device(totalGib, lowRam = true), canvas4096).gpuTileBudgetBytes,
                "low-RAM devices never scale with totalMem ($totalGib GiB)",
            )
        }
    }

    @Test
    fun `the layer cap is monotone non-decreasing in totalMem`() {
        val random = Random(42)
        val sizes = List(200) { random.nextDouble(1.0, 24.0) }.sorted()
        var previous = 0
        for (gibs in sizes) {
            val caps = MemoryBudget.compute(device(gibs), canvas4096).maxLayers
            assertTrue(caps >= previous, "more memory must never mean fewer layers (at $gibs GiB)")
            previous = caps
        }
    }

    @Test
    fun `the layer cap is monotone non-increasing in tile storage`() {
        // Squares only walk the diagonal. A cap that depended on width or
        // height alone rather than on tile area would pass every one of them,
        // so pair each square with a rectangle of the same tile count.
        val squares = (256..4096 step 256).map { CanvasSize(it, it) }
        val rectangles = (512..4096 step 512).map { CanvasSize(it / 2, it * 2) }
        for ((tiles, group) in (squares + rectangles).groupBy { it.tilesPerLayer to it.wetTilesPerLayer }) {
            val caps = group.map { MemoryBudget.compute(device(8.0), it).maxLayers }.toSet()
            assertEquals(1, caps.size, "$tiles color/wet tiles must mean one cap, got $caps for $group")
        }
        val edges = (256..4096 step 256).toList()
        var previous = Int.MAX_VALUE
        for (edge in edges) {
            val caps = MemoryBudget.compute(device(8.0), CanvasSize(edge, edge)).maxLayers
            assertTrue(caps <= previous, "a bigger canvas must never mean more layers (at ${edge}px)")
            previous = caps
        }
    }

    @Test
    fun `maxLayers is clamped to MIN_LAYERS and MAX_LAYERS`() {
        // The format's largest canvas is 8192 square = 256 MiB per layer, which
        // is exactly the flat low-RAM tile budget: nothing is left over, and the
        // clamp is what keeps the answer at one layer instead of zero.
        val tiny = MemoryBudget.compute(device(2.0, lowRam = true), CanvasSize(8192, 8192))
        assertEquals(PerfConstants.MIN_LAYERS, tiny.maxLayers, "even a starved device offers one layer")
        val huge = MemoryBudget.compute(device(16.0), CanvasSize(256, 256))
        assertEquals(PerfConstants.MAX_LAYERS, huge.maxLayers, "the cap is a product decision, not a memory one")
    }

    @Test
    fun `the v1 canvas ceiling is ours, not the driver's`() {
        // Every other case here leaves glMaxTextureSize at 4096, which is also
        // MAX_CANVAS_EDGE_V1 — so the two caps are confounded and a regression
        // that dropped the product ceiling would be invisible. A 16 GiB device
        // has the memory for more; only the v1 cap can hold it to 4096.
        assertEquals(
            PerfConstants.MAX_CANVAS_EDGE_V1,
            MemoryBudget.compute(device(16.0, glMaxTextureSize = 8192), canvas4096).maxCanvasEdge,
        )
    }

    @Test
    fun `CanvasSize and TileGrid agree on how many tiles a canvas needs`() {
        // Two different ceil-divisions: CanvasSize uses quotient-plus-remainder
        // because it must survive absurd input, TileGrid the shift form because
        // its sides are pre-validated. The budget and the allocation layout
        // must never disagree about a canvas.
        val edges = (TileGrid.MIN_EDGE..TileGrid.MAX_EDGE step PerfConstants.TILE_SIZE).toList() +
            listOf(TileGrid.MIN_EDGE + 1, 1080, 1920, 2560, TileGrid.MAX_EDGE - 1)
        for (edge in edges) {
            assertEquals(TileGrid(edge, edge).tilesX, CanvasSize(edge, edge).tilesX, "tilesX at $edge")
            assertEquals(TileGrid(edge, edge).tilesY, CanvasSize(edge, edge).tilesY, "tilesY at $edge")
        }
    }

    @Test
    fun `maxCanvasEdge only admits sizes that hold a few layers`() {
        for (totalGib in listOf(1.0, 2.0, 4.0, 8.0, 16.0)) {
            for (lowRam in listOf(false, true)) {
                val result = MemoryBudget.compute(device(totalGib, lowRam), canvas4096)
                val edge = result.maxCanvasEdge
                assertTrue(edge % PerfConstants.TILE_SIZE == 0, "maxCanvasEdge is a whole number of tiles")
                assertTrue(edge <= PerfConstants.MAX_CANVAS_EDGE_V1, "v1 never offers past 4096")
                // poolCapacityBytes, not gpuTileBudgetBytes: the raw budget is
                // the misuse maxLayersFor's KDoc warns about. Include document
                // pigment, per-layer wet state, and both gesture reserves.
                val atEdge = CanvasSize(edge, edge)
                val requiredSlices = requiredTileSlices(atEdge, PerfConstants.MIN_USEFUL_LAYERS)
                val capacitySlices = result.poolCapacityBytes / PerfConstants.TILE_BYTES
                // No exemption for the TILE_SIZE floor: compute() now
                // asserts that the pool holds MIN_USEFUL_LAYERS + reserve of a
                // one-tile canvas, so the floor carries the promise like every
                // other edge does.
                assertTrue(
                    requiredSlices <= capacitySlices,
                    "a $totalGib GiB device offers ${edge}px but needs $requiredSlices of $capacitySlices slices",
                )
            }
        }
    }

    @Test
    fun `the pool spans every advertised layer and both gesture reserves`() {
        // The odd sizes matter: the pool allocates whole 64 MiB arrays, so a
        // budget that is not a multiple of one has capacity the raw byte count
        // does not describe. 2800 MiB / 2304 square is the case that used to
        // advertise 16 layers (1296 tiles) against a 5-array pool (1280).
        for (totalGib in listOf(2.0, 2.734375, 4.0, 5.5, 8.0, 12.0, 16.0)) {
            // Low-RAM devices get the smallest pools and so the tightest
            // margin here — exactly where an array-count rounding bug bites.
            for (lowRam in listOf(false, true)) {
                // 64 too: the smallest GL_MAX_ARRAY_TEXTURE_LAYERS worth
                // planning for makes each array a quarter of a page, so the
                // array-count rounding this test guards has four times as
                // many chances to lose a slice.
                for (glLayers in listOf(256, 128, 64)) {
                    // 8192 square is the format's largest and the tightest
                    // slice case there is: 1024 slices for one layer, against a
                    // low-RAM pool of exactly 1024. Zero slack, so any
                    // array-count rounding bug fails here first.
                    for (canvas in listOf(
                        canvas2048,
                        canvas4096,
                        CanvasSize(1080, 1920),
                        CanvasSize(2304, 2304),
                        CanvasSize(8192, 8192),
                    )) {
                        val r = MemoryBudget.compute(device(totalGib, lowRam, glLayers), canvas)
                        val slices = r.poolArraySlices.toLong() * r.poolArrayCount
                        if (canvas.width > r.maxCanvasEdge || canvas.height > r.maxCanvasEdge) continue

                        val requiredSlices = requiredTileSlices(canvas, r.maxLayers)
                        assertTrue(
                            requiredSlices <= slices,
                            "$totalGib GiB / lowRam=$lowRam / ${canvas.width}x${canvas.height} / " +
                                "$glLayers per array: ${r.maxLayers} layers and reserves need " +
                                "$requiredSlices slices, pool has $slices",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the pool never advertises capacity the budget cannot cover`() {
        for (totalGib in listOf(2.0, 2.734375, 4.0, 5.5, 8.0, 12.0, 16.0)) {
            for (glLayers in listOf(256, 128, 64)) {
                for (lowRam in listOf(false, true)) {
                    val r = MemoryBudget.compute(device(totalGib, lowRam, glLayers), canvas4096)
                    assertEquals(
                        r.poolArrayCount.toLong() * r.poolArraySlices * PerfConstants.TILE_BYTES,
                        r.poolCapacityBytes,
                    )
                    assertTrue(
                        r.poolCapacityBytes <= r.gpuTileBudgetBytes,
                        "$totalGib GiB / $glLayers per array: capacity ${r.poolCapacityBytes} " +
                            "exceeds the budget ${r.gpuTileBudgetBytes}",
                    )
                }
            }
        }
    }

    @Test
    fun `layerBytesWorstCase saturates rather than wrapping negative`() {
        // The sweep below only asserts the product stays positive across real
        // sides. This pins the branch that keeps it positive: Int.MAX_VALUE per
        // side is 2^23 tiles, and multiplying by TILE_BYTES overflows Long, so
        // the guard must answer Long.MAX_VALUE. Wrapped, it would go negative
        // and maxLayersFor would report a generous cap for the largest canvas
        // imaginable — the one answer this class must never produce.
        assertEquals(
            Long.MAX_VALUE,
            CanvasSize(Int.MAX_VALUE, Int.MAX_VALUE).layerBytesWorstCase,
            "the largest canvas expressible must saturate, not wrap",
        )
        // And the saturated value must still divide to the floor, not the cap.
        assertEquals(
            PerfConstants.MIN_LAYERS,
            MemoryBudget.maxLayersFor(1L shl 30, CanvasSize(Int.MAX_VALUE, Int.MAX_VALUE)),
        )
    }

    @Test
    fun `an unclamped budget saturates instead of truncating to a negative layer count`() {
        // The Long quotient here is 2^45 - 1, whose low 32 bits are -1; a bare
        // toInt() would answer MIN_LAYERS where the honest answer is the cap.
        assertEquals(
            PerfConstants.MAX_LAYERS,
            MemoryBudget.maxLayersFor(Long.MAX_VALUE, CanvasSize(256, 256)),
        )
    }

    @Test
    fun `the pool never asks for more slices per array than the driver allows`() {
        assertEquals(128, MemoryBudget.compute(device(8.0, glMaxArrayLayers = 128), canvas4096).poolArraySlices)
        assertEquals(
            256,
            MemoryBudget.compute(device(8.0, glMaxArrayLayers = 2048), canvas4096).poolArraySlices,
            "a generous driver does not change our 64 MiB allocation granule",
        )
        assertEquals(
            256,
            MemoryBudget.compute(device(8.0, glMaxArrayLayers = 0), canvas4096).poolArraySlices,
            "before the first GL context we assume the ES 3.0 minimum",
        )
    }

    @Test
    fun `the tile ceiling never overflows, however absurd the size`() {
        // MemoryBudget.compute takes a CanvasSize directly, so it cannot rely
        // on CanvasPresets.custom having screened the numbers first. The usual
        // (n + 255) / 256 ceiling would report a NEGATIVE tile count here.
        for (edge in listOf(Int.MAX_VALUE, Int.MAX_VALUE - 1, Int.MAX_VALUE - 255)) {
            val size = CanvasSize(edge, edge)
            assertTrue(size.tilesX > 0, "tilesX went negative at $edge")
            assertTrue(size.tilesY > 0, "tilesY went negative at $edge")
            assertTrue(size.tilesPerLayer > 0, "tilesPerLayer went negative at $edge")
            assertTrue(size.layerBytesWorstCase > 0, "layerBytesWorstCase went negative at $edge")
            // A wrapped negative byte count would hand the largest canvas
            // imaginable a generous layer cap. Saturating gives it the floor.
            assertEquals(
                PerfConstants.MIN_LAYERS,
                MemoryBudget.compute(device(8.0), size).maxLayers,
                "an absurd canvas must get the minimum layer cap, not a wrapped one",
            )
        }
        assertEquals(8388608, CanvasSize(Int.MAX_VALUE, 256).tilesX)
        // …and it still agrees with the plain ceiling on every real size.
        for (edge in listOf(256, 257, 1000, 1080, 1920, 2048, 2560, 4096, 8191, 8192)) {
            assertEquals(
                (edge + PerfConstants.TILE_SIZE - 1) / PerfConstants.TILE_SIZE,
                CanvasSize(edge, edge).tilesX,
                "the overflow-safe ceiling disagrees with the plain one at $edge",
            )
        }
    }

    @Test
    fun `a canvas with no area is described, not divided by`() {
        // CanvasSize carries no validation on purpose — CanvasPresets.custom
        // has to be able to describe a size in order to refuse it — so the
        // budget must survive one on the way past rather than throwing.
        for (bad in listOf(CanvasSize(0, 0), CanvasSize(0, 1024), CanvasSize(1024, 0))) {
            assertEquals(0L, bad.tilesPerLayer, "$bad should have no tiles")
            assertEquals(
                PerfConstants.MIN_LAYERS,
                MemoryBudget.maxLayersFor(1L shl 30, bad),
                "$bad must not divide by zero",
            )
            assertEquals(PerfConstants.MIN_LAYERS, MemoryBudget.compute(device(8.0), bad).maxLayers)
        }
        // Kotlin's division truncates toward zero, so the plain ceiling would
        // answer 1 here and budget a nonsense canvas as if it were 256 px.
        assertEquals(0, CanvasSize(-1, 1024).tilesX, "a negative side has no tiles, not one")
        // -1 alone cannot tell a clamp from truncation-toward-zero; a strongly
        // negative side can, because the naive ceiling goes negative there.
        assertEquals(0, CanvasSize(Int.MIN_VALUE, 1024).tilesX, "nor does a strongly negative one")
        assertEquals(0L, CanvasSize(-1, -1).tilesPerLayer)
    }

    @Test
    fun `a canvas size reports its tile geometry`() {
        assertEquals(256L, canvas4096.tilesPerLayer)
        assertEquals(16L, canvas4096.wetTilesPerLayer)
        // 16 x 16 = 256 tiles, TILE_BYTES each. No extra per-pixel factor:
        // TILE_BYTES already counts the four bytes of RGBA8.
        assertEquals(256L * PerfConstants.TILE_BYTES, canvas4096.layerBytesWorstCase)
        assertEquals(16L * PerfConstants.TILE_BYTES, canvas4096.wetLayerBytesWorstCase)
        CanvasSize(1080, 1920).let {
            assertEquals(5, it.tilesX, "1080 px is 4.2 tiles, so 5")
            assertEquals(8, it.tilesY, "1920 px is exactly 7.5 tiles, so 8")
            assertEquals(40L, it.tilesPerLayer)
            assertEquals(4L, it.wetTilesPerLayer)
        }
    }

    @Test
    fun `wet tiles match the padded quarter-resolution grid`() {
        for (canvas in listOf(
            CanvasSize(256, 256),
            CanvasSize(1024, 1024),
            CanvasSize(1025, 1025),
            canvas2048,
            canvas4096,
            CanvasSize(8192, 8192),
        )) {
            val wetGrid = TileGrid(
                WatercolorKernel.wetPixels(canvas.width),
                WatercolorKernel.wetPixels(canvas.height),
            )
            assertEquals(wetGrid.tileCount.toLong(), canvas.wetTilesPerLayer, "$canvas")
        }
    }

    private fun requiredTileSlices(canvas: CanvasSize, layers: Int): Long {
        val persistent = (canvas.tilesPerLayer + canvas.wetTilesPerLayer) * layers
        val gesture = maxOf(
            canvas.tilesPerLayer * PerfConstants.STROKE_BUFFER_RESERVE_LAYERS,
            canvas.wetTilesPerLayer * PerfConstants.WET_GESTURE_BACKUP_LAYERS,
        )

        return persistent + gesture
    }
}
