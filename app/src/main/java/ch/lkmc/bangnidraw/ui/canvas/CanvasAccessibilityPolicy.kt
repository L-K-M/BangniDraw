package ch.lkmc.bangnidraw.ui.canvas

internal enum class CanvasHistoryAction {
    UNDO,
    REDO,
}

internal fun availableCanvasHistoryActions(
    undo: ActionAvailability,
    redo: ActionAvailability,
): List<CanvasHistoryAction> = buildList {
    if (undo == ActionAvailability.ENABLED) add(CanvasHistoryAction.UNDO)
    if (redo == ActionAvailability.ENABLED) add(CanvasHistoryAction.REDO)
}
