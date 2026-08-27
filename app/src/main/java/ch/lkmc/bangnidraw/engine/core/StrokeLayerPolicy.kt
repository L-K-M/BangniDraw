package ch.lkmc.bangnidraw.engine.core

internal enum class StrokeLayerDecision {
    DRAW,
    DRAW_HIDDEN,
    REFUSE_LOCKED,
    REFUSE_ALPHA_LOCKED,
}

internal enum class StrokeOperation { PAINT, ERASE }

/** Layer lock blocks pixels; alpha lock additionally blocks erasing. */
internal object StrokeLayerPolicy {
    fun decide(layer: LayerProps, operation: StrokeOperation): StrokeLayerDecision = when {
        layer.locked -> StrokeLayerDecision.REFUSE_LOCKED
        layer.alphaLock && operation == StrokeOperation.ERASE ->
            StrokeLayerDecision.REFUSE_ALPHA_LOCKED
        !layer.visible -> StrokeLayerDecision.DRAW_HIDDEN
        else -> StrokeLayerDecision.DRAW
    }
}
