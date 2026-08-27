package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertIs

class PixelHistoryEntryTest {
    private val layer = LayerId("layer")
    private val tiles = listOf(TileKey(1, 2))

    @Test
    fun `stroke commit creates a stroke entry`() {
        assertIs<HistoryEntry.Stroke>(
            PixelHistoryEntry.create(PixelCommitKind.Stroke, layer, layer, tiles),
        )
    }

    @Test
    fun `fill commit creates a fill entry`() {
        assertIs<HistoryEntry.Fill>(
            PixelHistoryEntry.create(PixelCommitKind.Fill, layer, layer, tiles),
        )
    }
}
