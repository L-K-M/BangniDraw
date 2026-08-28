package ch.lkmc.bangnidraw.engine.core

/** Transient wet state invalidated by an authorized document edit. */
sealed interface WatercolorInvalidation {
    data object Keep : WatercolorInvalidation
    data object All : WatercolorInvalidation
    data class Layers(val ids: Set<LayerId>) : WatercolorInvalidation
}

/** Keeps unrelated wet layers alive while destructive edits replace pixels. */
object WatercolorEditPolicy {

    fun forEdit(
        ops: List<PixelOp>,
        invalidation: SandwichPolicy.Op,
    ): WatercolorInvalidation {
        if (invalidation == SandwichPolicy.Op.UndoRedo) return WatercolorInvalidation.All
        val layers = LinkedHashSet<LayerId>()
        for (op in ops) {
            when (op) {
                is PixelOp.Copy -> Unit
                is PixelOp.Merge -> {
                    layers += op.top
                    layers += op.bottom
                }
                is PixelOp.Clear -> layers += op.layer
                is PixelOp.Delete -> layers += op.layer
                is PixelOp.Restore -> layers += op.layer
                is PixelOp.Flatten -> return WatercolorInvalidation.All
            }
        }

        return if (layers.isEmpty()) {
            WatercolorInvalidation.Keep
        } else {
            WatercolorInvalidation.Layers(layers)
        }
    }
}
