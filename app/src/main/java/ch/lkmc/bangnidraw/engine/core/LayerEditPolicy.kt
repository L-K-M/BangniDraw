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
}
