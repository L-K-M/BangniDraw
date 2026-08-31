package ch.lkmc.bangnidraw.engine.core

internal enum class StrokeLayerDecision {
    DRAW,
    DRAW_HIDDEN,
    REFUSE_LOCKED,
}

/** Lock blocks pixels; hidden layers still accept and preview a stroke. */
internal object StrokeLayerPolicy {
    fun decide(visible: Boolean, locked: Boolean): StrokeLayerDecision = when {
        locked -> StrokeLayerDecision.REFUSE_LOCKED
        !visible -> StrokeLayerDecision.DRAW_HIDDEN
        else -> StrokeLayerDecision.DRAW
    }
}
