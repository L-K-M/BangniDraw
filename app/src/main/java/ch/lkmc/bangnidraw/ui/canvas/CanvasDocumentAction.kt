package ch.lkmc.bangnidraw.ui.canvas

import androidx.annotation.MainThread
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.LayerId

internal enum class LayerAnchorPlacement { BEFORE, AFTER }

internal data class LayerMoveTarget(
    val layer: LayerId,
    val anchor: LayerId,
    val placement: LayerAnchorPlacement,
)

internal data class LayerMoveIndices(val from: Int, val to: Int)

internal data class LayerMergeTarget(val upper: LayerId, val lower: LayerId)

/** Captures panel indices as ids so queued work cannot drift to another layer. */
internal object LayerActionTargetResolver {
    fun capture(layers: List<LayerId>, index: Int): LayerId? = layers.getOrNull(index)

    fun resolve(layers: List<LayerId>, target: LayerId): Int? =
        layers.indexOf(target).takeIf { it >= 0 }

    fun captureMove(layers: List<LayerId>, from: Int, to: Int): LayerMoveTarget? {
        if (from !in layers.indices || to !in layers.indices || from == to) return null

        val placement = if (from < to) LayerAnchorPlacement.AFTER else LayerAnchorPlacement.BEFORE
        return LayerMoveTarget(
            layer = layers[from],
            anchor = layers[to],
            placement = placement,
        )
    }

    fun resolveMove(layers: List<LayerId>, target: LayerMoveTarget): LayerMoveIndices? {
        val from = resolve(layers, target.layer) ?: return null
        val anchor = resolve(layers, target.anchor) ?: return null
        val to = when (target.placement) {
            LayerAnchorPlacement.BEFORE -> if (from < anchor) anchor - 1 else anchor
            LayerAnchorPlacement.AFTER -> if (from < anchor) anchor else anchor + 1
        }
        if (from == to || to !in layers.indices) return null

        return LayerMoveIndices(from, to)
    }

    fun captureMerge(layers: List<LayerId>, upper: Int): LayerMergeTarget? {
        if (upper !in 1 until layers.size) return null

        return LayerMergeTarget(upper = layers[upper], lower = layers[upper - 1])
    }

    fun resolveMerge(layers: List<LayerId>, target: LayerMergeTarget): Int? {
        val upper = resolve(layers, target.upper) ?: return null
        if (upper == 0 || layers[upper - 1] != target.lower) return null

        return upper
    }
}

/** Canvas actions parked while a stroke or document transaction owns state. */
internal sealed interface CanvasDocumentAction {
    data object Undo : CanvasDocumentAction
    data object Redo : CanvasDocumentAction
    data class SelectLayer(val layer: LayerId) : CanvasDocumentAction
    data object AddLayer : CanvasDocumentAction
    data class DeleteLayer(val layer: LayerId) : CanvasDocumentAction
    data class DuplicateLayer(val layer: LayerId) : CanvasDocumentAction
    data class MoveLayer(val target: LayerMoveTarget) : CanvasDocumentAction
    data class MergeDown(val target: LayerMergeTarget) : CanvasDocumentAction
    data object Flatten : CanvasDocumentAction
    data class ClearLayer(val layer: LayerId) : CanvasDocumentAction
    data class RenameLayer(val layer: LayerId, val name: String) : CanvasDocumentAction
    data class SetLayerOpacity(val layer: LayerId, val opacity: Float) : CanvasDocumentAction
    data class ToggleLayerVisibility(val layer: LayerId) : CanvasDocumentAction
    data class SetLayerBlendMode(val layer: LayerId, val mode: BlendMode) : CanvasDocumentAction
    data class ToggleLayerAlphaLock(val layer: LayerId) : CanvasDocumentAction
    data class ToggleLayerLock(val layer: LayerId) : CanvasDocumentAction
    data class SetPaperColor(val color: Int) : CanvasDocumentAction
    data class RenamePainting(val title: String) : CanvasDocumentAction
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
    private var workBusy = false
    private var sessionSyncPending = false

    val strokeInFlight: Boolean get() = strokePhase != StrokePhase.IDLE
    val strokeInputInFlight: Boolean
        get() = strokePhase == StrokePhase.INPUT || strokePhase == StrokePhase.INPUT_COMPLETE

    val busy: Boolean get() = workBusy || sessionSyncPending

    val pendingCount: Int get() = pending.size

    val idleWorkReady: Boolean
        get() = !strokeInFlight && !busy && pending.isEmpty()

    /**
     * Pins the last committed model while allowing an open stroke to remain
     * outside it. History completion and other document work must finish first.
     */
    @MainThread
    fun beginCommittedCheckpoint(): Boolean {
        if (strokePhase == StrokePhase.COMMIT || busy) return false

        beginWork()
        return true
    }

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
        workBusy = true
    }

    @MainThread
    fun finishWork(): CanvasDocumentAction? {
        check(workBusy) { "no document work is running" }
        workBusy = false
        return next()
    }

    @MainThread
    fun beginSessionSync() {
        sessionSyncPending = true
    }

    @MainThread
    fun finishSessionSync(): CanvasDocumentAction? {
        if (!sessionSyncPending) return null

        sessionSyncPending = false
        return next()
    }

    /** Reopens the terminal gate after a leave failed or navigation was cancelled. */
    @MainThread
    fun finishLeave() {
        check(leaveRequested) { "no leave is pending" }
        check(busy) { "leave work is not running" }
        check(pending.isEmpty()) { "actions cannot follow a terminal leave" }

        workBusy = false
        leaveRequested = false
    }

    @MainThread
    fun next(): CanvasDocumentAction? {
        if (strokeInFlight || busy) return null
        return pending.removeFirstOrNull()
    }
}
