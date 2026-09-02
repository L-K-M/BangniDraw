package ch.lkmc.bangnidraw.desktop

internal enum class HistoryDirection {
    Undo,
    Redo,
}

/** Cursor bookkeeping for desktop's in-memory pixel journal. */
internal class DesktopHistory<T>(
    private val maxSteps: Int,
    private val maxBytes: Long,
    private val sizeOf: (T) -> Long,
) {
    private val entries = ArrayList<T>()
    private var cursor = 0
    private var bytes = 0L

    val canUndo: Boolean get() = synchronized(this) { cursor > 0 }
    val canRedo: Boolean get() = synchronized(this) { cursor < entries.size }

    init {
        require(maxSteps > 0) { "maxSteps must be positive" }
        require(maxBytes >= 0) { "maxBytes must not be negative" }
    }

    @Synchronized
    fun record(entry: T) {
        truncateRedo()

        entries.add(entry)
        bytes += sizeOf(entry)
        cursor = entries.size

        // Keep the newest entry even when it alone exceeds the byte cap.
        while (entries.size > 1 && (entries.size > maxSteps || bytes > maxBytes)) {
            bytes -= sizeOf(entries.removeAt(0))
            cursor -= 1
        }
    }

    @Synchronized
    fun move(direction: HistoryDirection): T? = when (direction) {
        HistoryDirection.Undo -> undo()
        HistoryDirection.Redo -> redo()
    }

    private fun undo(): T? {
        if (cursor == 0) return null

        cursor -= 1
        return entries[cursor]
    }

    private fun redo(): T? {
        if (cursor == entries.size) return null

        val entry = entries[cursor]
        cursor += 1
        return entry
    }

    private fun truncateRedo() {
        while (entries.size > cursor) {
            bytes -= sizeOf(entries.removeAt(entries.lastIndex))
        }
    }
}
