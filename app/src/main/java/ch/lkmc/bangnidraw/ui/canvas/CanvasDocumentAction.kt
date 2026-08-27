package ch.lkmc.bangnidraw.ui.canvas

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

/** Document mutations parked while the front-buffered stroke owns the GPU. */
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
}

internal enum class StrokeRelease { IMMEDIATE, AFTER_HISTORY }

/** Pure queue behind the no-document-mutation-during-stroke UI invariant. */
internal class CanvasActionGate {
    private val pending = ArrayDeque<CanvasDocumentAction>()

    private var strokeHistoryPending = false

    var strokeInFlight = false
        private set

    var busy = false
        private set

    val pendingCount: Int get() = pending.size

    val idleWorkReady: Boolean
        get() = !strokeInFlight && !strokeHistoryPending && !busy && pending.isEmpty()

    /**
     * Pins the last committed model while allowing an open stroke to remain
     * outside it. History completion and other document work must finish first.
     */
    fun beginCommittedCheckpoint(): Boolean {
        if (strokeHistoryPending || busy) return false

        beginWork()
        return true
    }

    fun beginStroke(): Boolean {
        if (busy || strokeInFlight || strokeHistoryPending || leavePending()) return false

        strokeInFlight = true
        return true
    }

    fun endStroke(release: StrokeRelease): CanvasDocumentAction? {
        if (!strokeInFlight) return null

        strokeInFlight = false
        if (release == StrokeRelease.AFTER_HISTORY) {
            strokeHistoryPending = true
            return null
        }

        return next()
    }

    fun finishStrokeHistory(): CanvasDocumentAction? {
        if (!strokeHistoryPending) return null

        strokeHistoryPending = false
        return next()
    }

    fun request(action: CanvasDocumentAction): CanvasActionDecision {
        if (!strokeInFlight && !strokeHistoryPending && !busy) {
            return CanvasActionDecision.Run(action)
        }
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
        if (strokeInFlight || strokeHistoryPending || busy) return null
        return pending.removeFirstOrNull()
    }

    private fun leavePending(): Boolean = pending.any { it == CanvasDocumentAction.Leave }
}
