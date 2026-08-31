package ch.lkmc.bangnidraw.engine.core

/** Maps a structural edit to cache and storage effects before any GL work. */
internal object LayerEditPolicy {

    fun invalidation(stack: LayerStack, entry: HistoryEntry): SandwichPolicy.Op? = when (entry) {
        is HistoryEntry.Stroke -> pixelEdit(stack, entry.layerId)
        is HistoryEntry.Fill -> pixelEdit(stack, entry.layerId)
        is HistoryEntry.LayerAdd -> SandwichPolicy.Op.Add
        is HistoryEntry.LayerDelete -> SandwichPolicy.Op.Delete(entry.index)
        is HistoryEntry.LayerReorder ->
            SandwichPolicy.Op.Move(entry.fromIndex, entry.toIndex)
        is HistoryEntry.LayerProps -> propertyEdit(stack, entry)
        is HistoryEntry.LayerMerge -> SandwichPolicy.Op.MergeDown(entry.upperIndex)
        is HistoryEntry.LayerDuplicate -> stack.indexOf(entry.sourceId)
            .takeIf { it >= 0 }
            ?.let(SandwichPolicy.Op::Duplicate)
        is HistoryEntry.LayerClear -> stack.indexOf(entry.layerId)
            .takeIf { it >= 0 }
            ?.let(SandwichPolicy.Op::Clear)
        is HistoryEntry.Flatten -> SandwichPolicy.Op.Flatten
        is HistoryEntry.PaperColor -> SandwichPolicy.Op.PaperColor
    }

    /** Layer directories made unreachable by a committed stack transition. */
    fun deletedLayers(before: LayerStack, after: LayerStack): List<LayerId> {
        val live = after.layers.mapTo(HashSet()) { it.id }
        return before.layers.map { it.id }.filterNot { it in live }
    }

    /** Every sparse tile whose on-disk value changes after [op] commits. */
    fun changedTiles(stack: LayerStack, op: PixelOp?): List<Pair<LayerId, TileKey>> {
        if (op == null) return emptyList()

        val changed = LinkedHashSet<Pair<LayerId, TileKey>>()
        when (op) {
            is PixelOp.Copy -> changed.addTiles(op.dst, op.keys)
            is PixelOp.Merge -> {
                changed.addTiles(op.bottom, op.keys)
                changed.addTiles(op.top, stack.layerTiles(op.top))
            }
            is PixelOp.Clear -> changed.addTiles(op.layer, stack.layerTiles(op.layer))
            is PixelOp.Delete -> changed.addTiles(op.layer, stack.layerTiles(op.layer))
            is PixelOp.Flatten -> {
                for (layer in stack.layers) changed.addTiles(layer.id, layer.tiles)
                val visible = op.order.mapTo(HashSet()) { it.id }
                val result = stack.layers
                    .filter { it.id in visible }
                    .flatMapTo(LinkedHashSet()) { it.tiles }
                changed.addTiles(op.result, result)
            }
            is PixelOp.Restore -> changed.addTiles(op.layer, op.tiles.keys)
        }
        return changed.toList()
    }

    private fun pixelEdit(stack: LayerStack, layer: LayerId): SandwichPolicy.Op? =
        stack.indexOf(layer).takeIf { it >= 0 }?.let(SandwichPolicy.Op::PixelEdit)

    private fun propertyEdit(
        stack: LayerStack,
        entry: HistoryEntry.LayerProps,
    ): SandwichPolicy.Op? {
        val index = stack.indexOf(entry.layerId)
        if (index < 0) return null
        val before = entry.before.toPropsOrNull() ?: return null
        val after = entry.after.toPropsOrNull() ?: return null
        if (before.id != entry.layerId || after.id != entry.layerId) return null

        val composites = before.visible != after.visible ||
            before.opacity != after.opacity ||
            before.blendMode != after.blendMode
        if (composites) return SandwichPolicy.Op.SetCompositingProperty(index)
        return SandwichPolicy.Op.SetInertProperty
    }

    private fun LayerStack.layerTiles(id: LayerId): Set<TileKey> =
        layers.firstOrNull { it.id == id }?.tiles.orEmpty()

    private fun MutableSet<Pair<LayerId, TileKey>>.addTiles(
        layer: LayerId,
        keys: Collection<TileKey>,
    ) {
        for (key in keys) add(layer to key)
    }
}
