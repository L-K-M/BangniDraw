package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LayerTileUpdatesTest {

    private val id = LayerId("layer")
    private val kept = TileKey(0, 0)
    private val erased = TileKey(1, 0)
    private val added = TileKey(2, 0)
    private val stack = LayerStack(
        layers = listOf(
            Layer(LayerProps(id, "Layer"), setOf(kept, erased)),
        ),
        activeIndex = 0,
        nextName = 2,
    )

    @Test
    fun `empty updates remove keys while painted updates add them`() {
        val updates = mapOf(
            (id to erased) to TilePresence.EMPTY,
            (id to added) to TilePresence.PAINTED,
        )

        val result = LayerTileUpdates.apply(stack, updates)

        assertEquals(setOf(kept, added), result.active.tiles)
    }

    @Test
    fun `updates for removed layers are ignored`() {
        val result = LayerTileUpdates.apply(
            stack,
            mapOf((LayerId("gone") to added) to TilePresence.PAINTED),
        )

        assertEquals(stack, result)
    }
}
