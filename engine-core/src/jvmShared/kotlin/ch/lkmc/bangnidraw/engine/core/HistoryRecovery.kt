package ch.lkmc.bangnidraw.engine.core

/** Replays journal entries committed after the last project checkpoint. */
object HistoryRecovery {

    data class Result(
        val document: Document,
        val appliedCount: Int,
    )

    fun replay(document: Document, entries: List<HistoryEntry>): Result {
        var current = document
        var applied = 0
        for (entry in entries) {
            val edit = when (val result = LayerHistory.apply(
                current.stack,
                entry,
                HistoryDirection.REDO,
            )) {
                is LayerHistoryResult.Applied -> result.edit
                LayerHistoryResult.Corrupt -> break
            }
            current = current.copy(
                stack = edit.stack,
                paperColor = edit.paperColor ?: current.paperColor,
            )
            applied += 1
        }
        return Result(current, applied)
    }
}
