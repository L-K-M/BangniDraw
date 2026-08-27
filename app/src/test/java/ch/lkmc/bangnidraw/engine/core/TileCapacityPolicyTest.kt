package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileCapacityPolicyTest {

    @Test
    fun `resident tiles must fit the pool before reopen`() {
        assertTrue(TileCapacityPolicy.residentTilesFit(residentTiles = 1_024L, poolSlices = 1_024L))
        assertFalse(TileCapacityPolicy.residentTilesFit(residentTiles = 1_025L, poolSlices = 1_024L))
    }

    @Test
    fun `legacy stacks above the new cap keep transient allocations disabled`() {
        val canvas = CanvasSize(4096, 4096)
        val budget = MemoryBudget.compute(device(lowRam = false), canvas)

        assertTrue(TileCapacityPolicy.hasTransientReserve(12, canvas, poolSlices(budget)))
        assertFalse(TileCapacityPolicy.hasTransientReserve(15, canvas, poolSlices(budget)))
    }

    @Test
    fun `oversized reopen does not mistake the one-layer floor for reserve`() {
        val canvas = CanvasSize(4096, 4096)
        val budget = MemoryBudget.compute(device(lowRam = true), canvas)

        assertEquals(1, budget.maxLayers)
        assertTrue(canvas.width > budget.maxCanvasEdge)
        assertFalse(TileCapacityPolicy.hasTransientReserve(1, canvas, poolSlices(budget)))
    }

    @Test
    fun `a non-divisor page cap uses the live pool reserve`() {
        val canvas = CanvasSize(CANVAS_EDGE, CANVAS_EDGE)
        val budget = MemoryBudget.compute(device(lowRam = true, arrayLayers = 0), canvas)
        val rawSlices = budget.gpuTileBudgetBytes / TILE_BYTES
        val livePoolSlices = rawSlices / DRIVER_ARRAY_LAYERS * DRIVER_ARRAY_LAYERS

        assertEquals(ASSUMED_POOL_SLICES, poolSlices(budget))
        assertEquals(LIVE_POOL_SLICES, livePoolSlices)
        assertTrue(
            TileCapacityPolicy.hasTransientReserve(
                LEGACY_LAYER_COUNT,
                canvas,
                poolSlices(budget),
            ),
        )
        assertFalse(
            TileCapacityPolicy.hasTransientReserve(
                LEGACY_LAYER_COUNT,
                canvas,
                livePoolSlices,
            ),
        )
    }

    private fun poolSlices(budget: MemoryBudget.Result): Long =
        budget.poolArraySlices.toLong() * budget.poolArrayCount

    private fun device(lowRam: Boolean, arrayLayers: Int = 256) = DeviceMemory(
        totalMemBytes = DEVICE_MEMORY_GIB * GIB,
        isLowRamDevice = lowRam,
        largeMemoryClassMb = 512,
        glMaxArrayLayers = arrayLayers,
        glMaxTextureSize = 4096,
    )

    private companion object {
        const val DEVICE_MEMORY_GIB = 8L
        const val GIB = 1L shl 30
        const val DRIVER_ARRAY_LAYERS = 100L
        const val ASSUMED_POOL_SLICES = 1024L
        const val LIVE_POOL_SLICES = 1000L
        const val CANVAS_EDGE = 1024
        const val LEGACY_LAYER_COUNT = 59
    }
}
