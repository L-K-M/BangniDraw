package ch.lkmc.bangnidraw.engine.core

/** Creates the matching journal entry for one buffered pixel commit. */
object PixelHistoryEntry {
    fun create(
        kind: PixelCommitKind,
        active: LayerId,
        layer: LayerId,
        tiles: List<TileKey>,
    ): HistoryEntry = when (kind) {
        PixelCommitKind.Stroke -> HistoryEntry.Stroke(
            activeBefore = active,
            activeAfter = active,
            layerId = layer,
            tiles = tiles,
        )
        PixelCommitKind.Fill -> HistoryEntry.Fill(
            activeBefore = active,
            activeAfter = active,
            layerId = layer,
            tiles = tiles,
        )
    }
}
