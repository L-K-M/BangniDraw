package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileCapacityPolicyTest {

    @Test
    fun `legacy stacks above the new cap keep transient allocations disabled`() {
        val canvas = CanvasSize(4096, 4096)
        val budget = MemoryBudget.compute(device(lowRam = false), canvas)

        assertTrue(TileCapacityPolicy.hasTransientReserve(12, canvas, budget))
        assertFalse(TileCapacityPolicy.hasTransientReserve(15, canvas, budget))
    }

    @Test
    fun `oversized reopen does not mistake the one-layer floor for reserve`() {
        val canvas = CanvasSize(4096, 4096)
        val budget = MemoryBudget.compute(device(lowRam = true), canvas)

        assertEquals(1, budget.maxLayers)
        assertTrue(canvas.width > budget.maxCanvasEdge)
        assertFalse(TileCapacityPolicy.hasTransientReserve(1, canvas, budget))
    }

    private fun device(lowRam: Boolean) = DeviceMemory(
        totalMemBytes = DEVICE_MEMORY_GIB * GIB,
        isLowRamDevice = lowRam,
        largeMemoryClassMb = 512,
        glMaxArrayLayers = 256,
        glMaxTextureSize = 4096,
    )

    private companion object {
        const val DEVICE_MEMORY_GIB = 8L
        const val GIB = 1L shl 30
    }
}
