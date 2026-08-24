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
            assertEquals(15, it.maxLayers, "8 GiB device, 4096 canvas")
            assertEquals(4096, it.maxCanvasEdge, "the v1 ceiling")
            assertEquals(200, it.historyMaxSteps)
            assertEquals(256 * mib, it.historyMaxBytes)
            assertEquals(24 * mib, it.thumbnailCacheBytes)
            assertEquals(256, it.poolArraySlices)
            assertEquals(16, it.poolArrayCount)
        }
        MemoryBudget.compute(device(8.0), canvas2048).let {
            assertEquals(16, it.maxLayers, "a smaller canvas hits the MAX_LAYERS clamp")
            assertEquals(4096, it.maxCanvasEdge)
        }
        MemoryBudget.compute(device(4.0), canvas4096).let {
            assertEquals(512 * mib, it.gpuTileBudgetBytes)
            assertEquals(7, it.maxLayers)
            assertEquals(4096, it.maxCanvasEdge)
            assertEquals(100, it.historyMaxSteps)
            assertEquals(128 * mib, it.historyMaxBytes)
            assertEquals(12 * mib, it.thumbnailCacheBytes)
            assertEquals(8, it.poolArrayCount)
        }
        MemoryBudget.compute(device(4.0, lowRam = true), canvas4096).let {
            assertEquals(256 * mib, it.gpuTileBudgetBytes, "a low-RAM device gets the flat budget")
            assertEquals(3, it.maxLayers)
            assertEquals(3584, it.maxCanvasEdge, "3840 squared would need 5 x 56.3 MiB")
            assertEquals(8 * mib, it.thumbnailCacheBytes)
            assertEquals(4, it.poolArrayCount)
        }
        MemoryBudget.compute(device(12.0), canvas4096).let {
            assertEquals(1536 * mib, it.gpuTileBudgetBytes, "clamped at GPU_TILE_MAX_BYTES")
            assertEquals(16, it.maxLayers)
            assertEquals(4096, it.maxCanvasEdge)
            assertEquals(24, it.poolArrayCount)
        }
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
    fun `the layer cap is monotone non-increasing in canvas area`() {
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
    fun `maxCanvasEdge only admits sizes that hold a few layers`() {
        for (totalGib in listOf(1.0, 2.0, 4.0, 8.0, 16.0)) {
            for (lowRam in listOf(false, true)) {
                val result = MemoryBudget.compute(device(totalGib, lowRam), canvas4096)
                val edge = result.maxCanvasEdge
                assertTrue(edge % PerfConstants.TILE_SIZE == 0, "maxCanvasEdge is a whole number of tiles")
                assertTrue(edge <= PerfConstants.MAX_CANVAS_EDGE_V1, "v1 never offers past 4096")
                val atEdge = MemoryBudget.maxLayersFor(result.gpuTileBudgetBytes, CanvasSize(edge, edge))
                assertTrue(
                    edge == PerfConstants.TILE_SIZE || atEdge >= PerfConstants.MIN_USEFUL_LAYERS,
                    "a $totalGib GiB device offers ${edge}px but only holds $atEdge layers there",
                )
            }
        }
    }

    @Test
    fun `the pool spans enough arrays for every layer`() {
        for (totalGib in listOf(2.0, 4.0, 8.0, 12.0, 16.0)) {
            for (canvas in listOf(canvas2048, canvas4096, CanvasSize(1080, 1920))) {
                val r = MemoryBudget.compute(device(totalGib), canvas)
                val slices = r.poolArraySlices.toLong() * r.poolArrayCount
                assertTrue(
                    r.maxLayers * canvas.tilesPerLayer <= slices,
                    "$totalGib GiB / ${canvas.width}x${canvas.height}: " +
                        "${r.maxLayers} layers need ${r.maxLayers * canvas.tilesPerLayer} slices, pool has $slices",
                )
            }
        }
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
    fun `a canvas size reports its tile geometry`() {
        assertEquals(256L, canvas4096.tilesPerLayer)
        assertEquals(64L * PerfConstants.TILE_BYTES * 4, canvas4096.layerBytesWorstCase)
        CanvasSize(1080, 1920).let {
            assertEquals(5, it.tilesX, "1080 px is 4.2 tiles, so 5")
            assertEquals(8, it.tilesY, "1920 px is exactly 7.5 tiles, so 8")
            assertEquals(40L, it.tilesPerLayer)
        }
    }
}
