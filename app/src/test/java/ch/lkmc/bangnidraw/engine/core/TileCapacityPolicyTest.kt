package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileCapacityPolicyTest {

    @Test
    fun `legacy stacks above the new cap keep transient allocations disabled`() {
        assertTrue(TileCapacityPolicy.hasTransientReserve(layerCount = 12, maxLayers = 12))
        assertFalse(TileCapacityPolicy.hasTransientReserve(layerCount = 15, maxLayers = 12))
    }
}
