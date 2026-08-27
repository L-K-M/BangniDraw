package ch.lkmc.bangnidraw.engine.core

/** Whether a readback keeps a sparse tile key or proves it empty. */
internal enum class TilePresence { PAINTED, EMPTY }

/**
 * The zero-tile rule both folds call: absent or all-zero bytes are an empty
 * tile (AGENTS.md — `TileStore` deletes zero tiles, so the model drops the
 * key). One classifier, because two folds that classify the same bytes
 * differently is exactly the model-vs-pixels lag this rule exists to kill.
 */
internal fun presenceOf(bytes: ByteArray?): TilePresence =
    if (bytes == null || bytes.all { it == ZERO_BYTE }) TilePresence.EMPTY else TilePresence.PAINTED

private const val ZERO_BYTE: Byte = 0

/** Folds readback outcomes into the immutable layer model at checkpoint time. */
internal object LayerTileUpdates {

    fun apply(
        stack: LayerStack,
        updates: Map<Pair<LayerId, TileKey>, TilePresence>,
    ): LayerStack {
        if (updates.isEmpty()) return stack
        val byLayer = HashMap<LayerId, MutableMap<TileKey, TilePresence>>()
        for ((subject, presence) in updates) {
            val (layer, key) = subject
            byLayer.getOrPut(layer) { HashMap() }[key] = presence
        }
        val layers = stack.layers.map { layer ->
            val changes = byLayer[layer.id] ?: return@map layer
            val keys = layer.tiles.toMutableSet()
            for ((key, presence) in changes) {
                when (presence) {
                    TilePresence.PAINTED -> keys += key
                    TilePresence.EMPTY -> keys -= key
                }
            }
            layer.copy(tiles = keys)
        }
        return stack.copy(layers = layers)
    }
}
