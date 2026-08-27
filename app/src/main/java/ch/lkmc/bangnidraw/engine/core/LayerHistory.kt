package ch.lkmc.bangnidraw.engine.core

/** Which side of a journal entry becomes current. */
internal enum class HistoryDirection { UNDO, REDO }

/** A validated stack transition and the payload-free GPU work it needs. */
internal data class LayerHistoryEdit(
    val stack: LayerStack,
    val pixelOps: List<PixelOp> = emptyList(),
    val paperColor: Int? = null,
)

internal sealed interface LayerHistoryResult {
    data class Applied(val edit: LayerHistoryEdit) : LayerHistoryResult
    data object Corrupt : LayerHistoryResult
}

/**
 * Applies the structural half of undo and redo without consulting locks or the
 * layer cap. History restores past state; it is not a new user edit.
 *
 * Pixel payloads remain HistoryPixels' responsibility at the data boundary.
 * This object returns only work derivable from the entry header: copies,
 * clears and releases.
 */
internal object LayerHistory {

    fun apply(
        stack: LayerStack,
        entry: HistoryEntry,
        direction: HistoryDirection,
    ): LayerHistoryResult {
        val layers = stack.layers.toMutableList()
        val pixels = ArrayList<PixelOp>()

        val valid = when (entry) {
            is HistoryEntry.Stroke -> layers.contains(entry.layerId)
            is HistoryEntry.Fill -> layers.contains(entry.layerId)
            is HistoryEntry.LayerAdd -> applyAdd(layers, pixels, entry, direction)
            is HistoryEntry.LayerDelete -> applyDelete(layers, pixels, entry, direction)
            is HistoryEntry.LayerReorder -> applyReorder(layers, entry, direction)
            is HistoryEntry.LayerProps -> applyProps(layers, entry, direction)
            is HistoryEntry.LayerMerge -> applyMerge(layers, pixels, entry, direction)
            is HistoryEntry.LayerDuplicate -> applyDuplicate(layers, pixels, entry, direction)
            is HistoryEntry.LayerClear -> applyClear(layers, pixels, entry, direction)
            is HistoryEntry.Flatten -> applyFlatten(layers, pixels, entry, direction)
            is HistoryEntry.PaperColor -> true
        }
        if (!valid || layers.isEmpty()) return LayerHistoryResult.Corrupt

        val activeId = when (direction) {
            HistoryDirection.UNDO -> entry.activeBefore
            HistoryDirection.REDO -> entry.activeAfter
        }
        val activeIndex = layers.indexOfFirst { it.id == activeId }
        if (activeIndex < 0) return LayerHistoryResult.Corrupt

        val next = try {
            LayerStack(layers, activeIndex, stack.nextName)
        } catch (_: IllegalArgumentException) {
            return LayerHistoryResult.Corrupt
        }
        val paperColor = if (entry is HistoryEntry.PaperColor) {
            when (direction) {
                HistoryDirection.UNDO -> entry.before
                HistoryDirection.REDO -> entry.after
            }
        } else {
            null
        }
        return LayerHistoryResult.Applied(LayerHistoryEdit(next, pixels, paperColor))
    }

    private fun applyAdd(
        layers: MutableList<Layer>,
        pixels: MutableList<PixelOp>,
        entry: HistoryEntry.LayerAdd,
        direction: HistoryDirection,
    ): Boolean {
        val props = entry.layer.toPropsOrNull() ?: return false
        return when (direction) {
            HistoryDirection.UNDO -> {
                if (!layers.matches(entry.index, props.id)) return false
                if (layers[entry.index] != Layer(props)) return false

                layers.removeAt(entry.index)
                pixels += PixelOp.Delete(props.id)
                true
            }
            HistoryDirection.REDO -> {
                if (!layers.canInsert(entry.index, props.id)) return false

                layers.add(entry.index, Layer(props))
                true
            }
        }
    }

    private fun applyDelete(
        layers: MutableList<Layer>,
        pixels: MutableList<PixelOp>,
        entry: HistoryEntry.LayerDelete,
        direction: HistoryDirection,
    ): Boolean {
        val props = entry.layer.toPropsOrNull() ?: return false
        return when (direction) {
            HistoryDirection.UNDO -> {
                if (!layers.canInsert(entry.index, props.id)) return false

                layers.add(entry.index, Layer(props, entry.tiles.toSet()))
                true
            }
            HistoryDirection.REDO -> {
                if (layers.size <= 1 || !layers.matches(entry.index, props.id)) return false
                val current = layers[entry.index]
                if (current.props != props || !entry.tiles.toSet().containsAll(current.tiles)) return false

                layers.removeAt(entry.index)
                pixels += PixelOp.Delete(props.id)
                true
            }
        }
    }

    private fun applyReorder(
        layers: MutableList<Layer>,
        entry: HistoryEntry.LayerReorder,
        direction: HistoryDirection,
    ): Boolean {
        val from = when (direction) {
            HistoryDirection.UNDO -> entry.toIndex
            HistoryDirection.REDO -> entry.fromIndex
        }
        val to = when (direction) {
            HistoryDirection.UNDO -> entry.fromIndex
            HistoryDirection.REDO -> entry.toIndex
        }
        if (!layers.matches(from, entry.layerId) || to !in layers.indices) return false

        layers.add(to, layers.removeAt(from))
        return true
    }

    private fun applyProps(
        layers: MutableList<Layer>,
        entry: HistoryEntry.LayerProps,
        direction: HistoryDirection,
    ): Boolean {
        val before = entry.before.toPropsOrNull() ?: return false
        val after = entry.after.toPropsOrNull() ?: return false
        if (before.id != entry.layerId || after.id != entry.layerId) return false

        val expected = when (direction) {
            HistoryDirection.UNDO -> after
            HistoryDirection.REDO -> before
        }
        val replacement = when (direction) {
            HistoryDirection.UNDO -> before
            HistoryDirection.REDO -> after
        }
        val index = layers.indexOfFirst { it.id == entry.layerId }
        if (index < 0 || layers[index].props != expected) return false

        layers[index] = layers[index].copy(props = replacement)
        return true
    }

    private fun applyDuplicate(
        layers: MutableList<Layer>,
        pixels: MutableList<PixelOp>,
        entry: HistoryEntry.LayerDuplicate,
        direction: HistoryDirection,
    ): Boolean {
        val props = entry.copy.toPropsOrNull() ?: return false
        return when (direction) {
            HistoryDirection.UNDO -> {
                if (!layers.matches(entry.index, props.id)) return false
                val source = layers.firstOrNull { it.id == entry.sourceId } ?: return false
                val copy = layers[entry.index]
                if (copy.props != props || !source.tiles.containsAll(copy.tiles)) return false

                layers.removeAt(entry.index)
                pixels += PixelOp.Delete(props.id)
                true
            }
            HistoryDirection.REDO -> {
                val source = layers.firstOrNull { it.id == entry.sourceId } ?: return false
                if (!layers.canInsert(entry.index, props.id)) return false

                layers.add(entry.index, Layer(props, source.tiles))
                pixels += PixelOp.Copy(source.id, props.id, source.tiles)
                true
            }
        }
    }

    private fun applyClear(
        layers: MutableList<Layer>,
        pixels: MutableList<PixelOp>,
        entry: HistoryEntry.LayerClear,
        direction: HistoryDirection,
    ): Boolean {
        val index = layers.indexOfFirst { it.id == entry.layerId }
        if (index < 0) return false

        val current = layers[index]
        layers[index] = when (direction) {
            HistoryDirection.UNDO -> {
                if (current.tiles.isNotEmpty()) return false

                current.copy(tiles = entry.tiles.toSet())
            }
            HistoryDirection.REDO -> {
                if (!entry.tiles.toSet().containsAll(current.tiles)) return false

                pixels += PixelOp.Clear(entry.layerId)
                current.copy(tiles = emptySet())
            }
        }
        return true
    }

    private fun applyMerge(
        layers: MutableList<Layer>,
        pixels: MutableList<PixelOp>,
        entry: HistoryEntry.LayerMerge,
        direction: HistoryDirection,
    ): Boolean {
        val upper = entry.upper.toPropsOrNull() ?: return false
        val lower = entry.lower.toPropsOrNull() ?: return false
        if (entry.upperIndex <= 0) return false

        return when (direction) {
            HistoryDirection.UNDO -> undoMerge(layers, pixels, entry, upper, lower)
            HistoryDirection.REDO -> redoMerge(layers, pixels, entry, upper, lower)
        }
    }

    private fun undoMerge(
        layers: MutableList<Layer>,
        pixels: MutableList<PixelOp>,
        entry: HistoryEntry.LayerMerge,
        upper: LayerProps,
        lower: LayerProps,
    ): Boolean {
        val lowerIndex = entry.upperIndex - 1
        if (!layers.matches(lowerIndex, lower.id) || layers.any { it.id == upper.id }) return false

        val merged = layers[lowerIndex]
        val upperTiles = entry.upperTiles.toSet()
        val expectedProps = lower.copy(
            visible = true,
            opacity = 1f,
            blendMode = BlendMode.NORMAL,
            alphaLock = false,
            locked = false,
        )
        if (merged.props != expectedProps) return false

        val lowerTiles = (merged.tiles - upperTiles) + entry.lowerTiles
        layers[lowerIndex] = Layer(lower, lowerTiles)
        layers.add(entry.upperIndex, Layer(upper, upperTiles))
        val lowerBefore = entry.lowerTiles.toSet()
        val removed = LinkedHashMap<TileKey, ByteArray?>()
        for (key in entry.upperTiles) {
            if (key !in lowerBefore) removed[key] = null
        }
        if (removed.isNotEmpty()) pixels += PixelOp.Restore(lower.id, removed)
        return true
    }

    private fun redoMerge(
        layers: MutableList<Layer>,
        pixels: MutableList<PixelOp>,
        entry: HistoryEntry.LayerMerge,
        upper: LayerProps,
        lower: LayerProps,
    ): Boolean {
        val lowerIndex = entry.upperIndex - 1
        if (!layers.matches(lowerIndex, lower.id) || !layers.matches(entry.upperIndex, upper.id)) return false
        if (layers[lowerIndex].props != lower || layers[entry.upperIndex].props != upper) return false

        val lowerLayer = layers[lowerIndex]
        val upperLayer = layers[entry.upperIndex]
        val mergedProps = lower.copy(
            visible = true,
            opacity = 1f,
            blendMode = BlendMode.NORMAL,
            alphaLock = false,
            locked = false,
        )
        layers.removeAt(entry.upperIndex)
        layers[lowerIndex] = Layer(mergedProps, lowerLayer.tiles + upperLayer.tiles)
        pixels += PixelOp.Delete(upper.id)
        return true
    }

    private fun applyFlatten(
        layers: MutableList<Layer>,
        pixels: MutableList<PixelOp>,
        entry: HistoryEntry.Flatten,
        direction: HistoryDirection,
    ): Boolean {
        val original = entry.layers.map { it.toPropsOrNull() ?: return false }
        val result = entry.result.toPropsOrNull() ?: return false
        return when (direction) {
            HistoryDirection.UNDO -> {
                if (layers.size != 1 || layers.single().id != result.id) return false
                if (layers.single().props != result) return false
                if (original.map { it.id }.toSet().size != original.size) return false
                if (original.any { it.id !in entry.tilesPerLayer }) return false
                val resultTiles = original
                    .filter { it.visible }
                    .flatMapTo(LinkedHashSet()) { entry.tilesPerLayer.getValue(it.id) }
                if (!resultTiles.containsAll(layers.single().tiles)) return false

                pixels += PixelOp.Delete(result.id)
                layers.clear()
                for (props in original) {
                    val tiles = entry.tilesPerLayer[props.id] ?: return false
                    layers += Layer(props, tiles.toSet())
                }
                true
            }
            HistoryDirection.REDO -> {
                if (layers.map { it.props } != original) return false
                if (original.any { it.id !in entry.tilesPerLayer }) return false
                if (layers.any { !entry.tilesPerLayer.getValue(it.id).toSet().containsAll(it.tiles) }) {
                    return false
                }
                val visibleTiles = original
                    .filter { it.visible }
                    .flatMapTo(LinkedHashSet()) { entry.tilesPerLayer.getValue(it.id) }

                pixels += layers.map { PixelOp.Delete(it.id) }
                layers.clear()
                layers += Layer(result, visibleTiles)
                true
            }
        }
    }

    private fun List<Layer>.contains(id: LayerId): Boolean = any { it.id == id }

    private fun List<Layer>.matches(index: Int, id: LayerId): Boolean =
        getOrNull(index)?.id == id

    private fun List<Layer>.canInsert(index: Int, id: LayerId): Boolean =
        index in 0..size && none { it.id == id }
}
