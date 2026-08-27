package ch.lkmc.bangnidraw.ui.canvas

import ch.lkmc.bangnidraw.engine.core.BlendMode

/** Document mutations parked while the front-buffered stroke owns the GPU. */
internal sealed interface CanvasDocumentAction {
    data object Undo : CanvasDocumentAction
    data object Redo : CanvasDocumentAction
    data class SelectLayer(val index: Int) : CanvasDocumentAction
    data object AddLayer : CanvasDocumentAction
    data class DeleteLayer(val index: Int) : CanvasDocumentAction
    data class DuplicateLayer(val index: Int) : CanvasDocumentAction
    data class MoveLayer(val from: Int, val to: Int) : CanvasDocumentAction
    data class MergeDown(val index: Int) : CanvasDocumentAction
    data object Flatten : CanvasDocumentAction
    data class ClearLayer(val index: Int) : CanvasDocumentAction
    data class RenameLayer(val index: Int, val name: String) : CanvasDocumentAction
    data class SetLayerOpacity(val index: Int, val opacity: Float) : CanvasDocumentAction
    data class ToggleLayerVisibility(val index: Int) : CanvasDocumentAction
    data class SetLayerBlendMode(val index: Int, val mode: BlendMode) : CanvasDocumentAction
    data class ToggleLayerAlphaLock(val index: Int) : CanvasDocumentAction
    data class ToggleLayerLock(val index: Int) : CanvasDocumentAction
    data class SetPaperColor(val color: Int) : CanvasDocumentAction
}

internal sealed interface CanvasActionDecision {
    data class Run(val action: CanvasDocumentAction) : CanvasActionDecision
    data object Parked : CanvasActionDecision
}

/** Pure queue behind the no-document-mutation-during-stroke UI invariant. */
internal class CanvasActionGate {
    private val pending = ArrayDeque<CanvasDocumentAction>()

    var strokeInFlight = false
        private set

    var busy = false
        private set

    val pendingCount: Int get() = pending.size

    fun beginStroke(): Boolean {
        if (busy || strokeInFlight) return false

        strokeInFlight = true
        return true
    }

    fun endStroke(): CanvasDocumentAction? {
        if (!strokeInFlight) return null

        strokeInFlight = false
        return next()
    }

    fun request(action: CanvasDocumentAction): CanvasActionDecision {
        if (!strokeInFlight && !busy) return CanvasActionDecision.Run(action)
        pending += action
        return CanvasActionDecision.Parked
    }

    fun beginWork() {
        check(!busy) { "document work is already running" }
        busy = true
    }

    fun finishWork(): CanvasDocumentAction? {
        check(busy) { "no document work is running" }
        busy = false
        return next()
    }

    fun next(): CanvasDocumentAction? {
        if (strokeInFlight || busy) return null
        return pending.removeFirstOrNull()
    }
}
