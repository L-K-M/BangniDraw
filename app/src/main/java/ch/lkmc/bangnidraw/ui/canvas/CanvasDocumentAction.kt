package ch.lkmc.bangnidraw.ui.canvas

import androidx.annotation.MainThread
import ch.lkmc.bangnidraw.engine.core.BlendMode

/** Canvas actions parked while a stroke or document transaction owns state. */
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
    data object Leave : CanvasDocumentAction
}

internal sealed interface CanvasActionDecision {
    data class Run(val action: CanvasDocumentAction) : CanvasActionDecision
    data object Parked : CanvasActionDecision
    data object Rejected : CanvasActionDecision
}

/** Main-confined queue behind the no-mutation-during-stroke UI invariant. */
internal class CanvasActionGate {
    private enum class StrokePhase {
        IDLE,
        INPUT,
        INPUT_COMPLETE,
        COMMIT,
    }

    private val pending = ArrayDeque<CanvasDocumentAction>()
    private var strokePhase = StrokePhase.IDLE
    private var leaveRequested = false

    val strokeInFlight: Boolean get() = strokePhase != StrokePhase.IDLE
    val strokeInputInFlight: Boolean
        get() = strokePhase == StrokePhase.INPUT || strokePhase == StrokePhase.INPUT_COMPLETE

    var busy = false
        private set

    val pendingCount: Int get() = pending.size

    @MainThread
    fun beginStroke(): Boolean {
        if (busy || strokeInFlight || leaveRequested) return false

        strokePhase = StrokePhase.INPUT
        return true
    }

    /** Restores input UI at pen-up without exposing the pending history edit. */
    @MainThread
    fun endStrokeInput(): CanvasDocumentAction? {
        strokePhase = when (strokePhase) {
            StrokePhase.INPUT -> StrokePhase.COMMIT
            StrokePhase.INPUT_COMPLETE -> StrokePhase.IDLE
            StrokePhase.IDLE, StrokePhase.COMMIT -> return null
        }

        return next()
    }

    /** Opens the gate only after the stroke has a journal entry or fallback. */
    @MainThread
    fun completeStroke(): CanvasDocumentAction? {
        strokePhase = when (strokePhase) {
            StrokePhase.INPUT -> StrokePhase.INPUT_COMPLETE
            StrokePhase.COMMIT -> StrokePhase.IDLE
            StrokePhase.IDLE, StrokePhase.INPUT_COMPLETE -> return null
        }

        return next()
    }

    @MainThread
    fun request(action: CanvasDocumentAction): CanvasActionDecision {
        if (leaveRequested) return CanvasActionDecision.Rejected
        if (action == CanvasDocumentAction.Leave) leaveRequested = true

        if (!strokeInFlight && !busy) return CanvasActionDecision.Run(action)
        pending += action
        return CanvasActionDecision.Parked
    }

    @MainThread
    fun beginWork() {
        check(!busy) { "document work is already running" }
        busy = true
    }

    @MainThread
    fun finishWork(): CanvasDocumentAction? {
        check(busy) { "no document work is running" }
        busy = false
        return next()
    }

    @MainThread
    fun next(): CanvasDocumentAction? {
        if (strokeInFlight || busy) return null
        return pending.removeFirstOrNull()
    }
}
