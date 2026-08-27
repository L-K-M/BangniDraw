package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DeviceMemory
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals

class TilePoolTest {

    @Test
    fun `non-divisor driver pages use every whole page within budget`() {
        val budget = MemoryBudget.compute(
            DeviceMemory(
                totalMemBytes = DEVICE_MEMORY_BYTES,
                isLowRamDevice = false,
                largeMemoryClassMb = LARGE_MEMORY_CLASS_MIB,
                glMaxArrayLayers = 0,
                glMaxTextureSize = 0,
            ),
            CanvasSize(CANVAS_EDGE, CANVAS_EDGE),
        )
        val pool = TilePool(caps(arrayLayers = DRIVER_ARRAY_LAYERS), budget)

        val bytesPerPage = DRIVER_ARRAY_LAYERS.toLong() * TILE_BYTES
        val expectedSlices = budget.gpuTileBudgetBytes / bytesPerPage * DRIVER_ARRAY_LAYERS
        assertEquals(EXPECTED_SLICE_CAPACITY, expectedSlices)
        assertEquals(expectedSlices, pool.sliceCapacity.toLong())
    }

    private fun caps(arrayLayers: Int) = GlCaps(
        glesMajor = 3,
        glesMinor = 0,
        maxArrayTextureLayers = arrayLayers,
        maxTextureSize = MAX_TEXTURE_EDGE,
        maxRenderbufferSize = MAX_TEXTURE_EDGE,
        maxViewportWidth = MAX_TEXTURE_EDGE,
        maxViewportHeight = MAX_TEXTURE_EDGE,
        hasShaderFramebufferFetch = false,
        hasColorBufferHalfFloat = false,
        renderer = "test",
        vendor = "test",
        version = "OpenGL ES 3.0",
    )

    private companion object {
        const val DEVICE_MEMORY_BYTES = 2L shl 30
        const val LARGE_MEMORY_CLASS_MIB = 256
        const val DRIVER_ARRAY_LAYERS = 100
        const val EXPECTED_SLICE_CAPACITY = 1000L
        const val MAX_TEXTURE_EDGE = 4096
        const val CANVAS_EDGE = 1024
    }
}
