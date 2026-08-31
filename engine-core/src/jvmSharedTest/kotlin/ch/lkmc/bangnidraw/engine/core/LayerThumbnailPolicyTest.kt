package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayerThumbnailPolicyTest {
    private val first = LayerId("first")

    @Test
    fun `requests dirty layers only while the panel is open and no stroke is live`() {
        val policy = LayerThumbnailPolicy()
        policy.markDirty(listOf(first))

        assertTrue(policy.due(nowMs = 0L, panelOpen = false, strokeInFlight = false).isEmpty())
        assertTrue(policy.due(nowMs = 0L, panelOpen = true, strokeInFlight = true).isEmpty())

        val request = policy.due(nowMs = 0L, panelOpen = true, strokeInFlight = false).single()
        assertEquals(first, request.layer)
    }

    @Test
    fun `throttles refreshes and keeps changes made during readback dirty`() {
        val policy = LayerThumbnailPolicy()
        policy.markDirty(listOf(first))
        val firstRequest = policy.due(0L, panelOpen = true, strokeInFlight = false).single()
        policy.markDirty(listOf(first))
        assertFalse(policy.complete(firstRequest))

        assertTrue(policy.due(499L, panelOpen = true, strokeInFlight = false).isEmpty())
        val secondRequest = policy.due(500L, panelOpen = true, strokeInFlight = false).single()
        assertTrue(policy.complete(secondRequest))

        assertTrue(policy.due(1_000L, panelOpen = true, strokeInFlight = false).isEmpty())
    }

    @Test
    fun `only content and property changes dirty thumbnails`() {
        val second = LayerId("second")
        val firstLayer = Layer(LayerProps(first, "First"))
        val secondLayer = Layer(LayerProps(second, "Second"))
        val before = LayerStack(listOf(firstLayer, secondLayer), activeIndex = 0, nextName = 3)
        val reordered = before.copy(layers = before.layers.asReversed(), activeIndex = 1)
        val changed = before.copy(
            layers = listOf(firstLayer.copy(props = firstLayer.props.withOpacity(0.5f)), secondLayer),
        )
        val renamed = before.copy(
            layers = listOf(firstLayer.copy(props = firstLayer.props.copy(name = "Renamed")), secondLayer),
        )

        assertTrue(LayerThumbnailPolicy.changedLayers(before, reordered).isEmpty())
        assertTrue(LayerThumbnailPolicy.changedLayers(before, renamed).isEmpty())
        assertEquals(listOf(first), LayerThumbnailPolicy.changedLayers(before, changed))
    }
}
