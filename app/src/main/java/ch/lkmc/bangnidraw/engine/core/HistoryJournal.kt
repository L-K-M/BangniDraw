package ch.lkmc.bangnidraw.engine.core

/**
 * The undo journal in memory: a list of stamped entries with a cursor
 * (`docs/plan/06-document-and-persistence.md` §5.1). Pure bookkeeping — the
 * journal never touches pixels or files; the caller applies what [undo] and
 * [redo] return, and `HistoryStore` owns the `history/<seq>.entry` files the
 * seqs in [PushResult] name.
 *
 * `entries[0, cursor)` are applied; `entries[cursor, size)` are the redo
 * branch. [bytes] sums [HistoryEntry.bytes], which the store fills with the
 * real on-disk size of `.entry` plus `.redo` — the pure class never guesses
 * (§5.1), which is why [noteRedoBytes] exists: the sidecar is written on the
 * *first* undo, long after the push.
 *
 * Mutable, main-thread-confined (the ViewModel owns it), like the document
 * cursor it mirrors.
 */
class HistoryJournal(
    private val limits: Limits,
    initial: List<HistoryEntry> = emptyList(),
    initialCursor: Int = initial.size,
) {
    /**
     * From `MemoryBudget.Result.historyMaxSteps`/`historyMaxBytes` (200 steps
     * / 256 MiB on large devices, 100 / 128 MiB otherwise — `10-performance.md`
     * §4); shown in Settings/About rather than silently applied.
     */
    data class Limits(val maxEntries: Int, val maxBytes: Long) {
        init {
            require(maxEntries >= 1 && maxBytes >= 0) {
                "limits must allow at least one entry, were $maxEntries/$maxBytes"
            }
        }
    }

    /** The seqs whose files are gone: [truncated] by a new edit, [pruned] by the caps. */
    data class PushResult(val truncated: List<Long>, val pruned: List<Long>)

    private val list = ArrayList<HistoryEntry>(initial)

    var cursor: Int = initialCursor
        private set

    var bytes: Long = list.sumOf { it.bytes }
        private set

    init {
        require(initialCursor in 0..list.size) {
            "cursor $initialCursor is outside 0..${list.size}"
        }
        var previous = 0L
        for (entry in list) {
            // Re-validated on ingest, as HistoryEntry.stamp's KDoc requires:
            // the stamp guard catches the honest mistake, this catches a
            // loaded list whose seqs are shuffled or reused.
            require(entry.isStamped && entry.seq > previous) {
                "entries must carry strictly increasing seqs; ${entry.seq} after $previous"
            }
            previous = entry.seq
        }
    }

    val entries: List<HistoryEntry> get() = list

    fun canUndo(): Boolean = cursor > 0
    fun canRedo(): Boolean = cursor < list.size

    /**
     * Appends a stamped entry: the redo branch is dropped first (a new edit
     * after undo makes the undone future unreachable — the universal
     * convention), then the oldest entries are pruned while either cap is
     * exceeded. Pruning never removes the entry just pushed, even when it
     * alone exceeds [Limits.maxBytes]: a flatten of a huge painting is still
     * undoable once (§5.1).
     */
    fun push(entry: HistoryEntry): PushResult {
        require(entry.isStamped) { "push takes a stamped entry" }
        require(list.isEmpty() || entry.seq > list.last().seq) {
            "seq ${entry.seq} is not after ${list.last().seq}"
        }

        val truncated = ArrayList<Long>(list.size - cursor)
        while (list.size > cursor) {
            val dropped = list.removeAt(list.size - 1)
            truncated.add(dropped.seq)
            bytes -= dropped.bytes
        }

        list.add(entry)
        bytes += entry.bytes
        cursor = list.size

        val pruned = ArrayList<Long>()
        while (list.size > 1 && (list.size > limits.maxEntries || bytes > limits.maxBytes)) {
            val dropped = list.removeAt(0)
            pruned.add(dropped.seq)
            bytes -= dropped.bytes
            cursor -= 1
        }
        return PushResult(truncated, pruned)
    }

    /** The entry to un-apply, or null at the beginning. Only the cursor moves. */
    fun undo(): HistoryEntry? {
        if (cursor == 0) return null
        cursor -= 1
        return list[cursor]
    }

    /** The entry to re-apply, or null at the end. Only the cursor moves. */
    fun redo(): HistoryEntry? {
        if (cursor == list.size) return null
        val entry = list[cursor]
        cursor += 1
        return entry
    }

    /**
     * The store wrote `<seq>.redo`: its size joins the entry's byte count, so
     * the prune-by-bytes cap sees what the journal actually costs on disk
     * (§5.1, §5.4). Unknown seqs are ignored — the sidecar may land after its
     * entry was truncated by a concurrent push.
     */
    fun noteRedoBytes(seq: Long, redoBytes: Long) {
        require(redoBytes >= 0) { "redo bytes must be >= 0, was $redoBytes" }
        val index = list.indexOfFirst { it.seq == seq }
        if (index < 0) return
        val entry = list[index]
        // stamp() is single-shot by design; the generated copy is the
        // documented escape hatch for the journal's own bookkeeping.
        list[index] = when (entry) {
            is HistoryEntry.Stroke -> entry.copy(bytes = entry.bytes + redoBytes)
            is HistoryEntry.Fill -> entry.copy(bytes = entry.bytes + redoBytes)
            is HistoryEntry.LayerAdd -> entry.copy(bytes = entry.bytes + redoBytes)
            is HistoryEntry.LayerDelete -> entry.copy(bytes = entry.bytes + redoBytes)
            is HistoryEntry.LayerReorder -> entry.copy(bytes = entry.bytes + redoBytes)
            is HistoryEntry.LayerProps -> entry.copy(bytes = entry.bytes + redoBytes)
            is HistoryEntry.LayerMerge -> entry.copy(bytes = entry.bytes + redoBytes)
            is HistoryEntry.LayerDuplicate -> entry.copy(bytes = entry.bytes + redoBytes)
            is HistoryEntry.LayerClear -> entry.copy(bytes = entry.bytes + redoBytes)
            is HistoryEntry.Flatten -> entry.copy(bytes = entry.bytes + redoBytes)
            is HistoryEntry.PaperColor -> entry.copy(bytes = entry.bytes + redoBytes)
        }
        bytes += redoBytes
    }

    /** The UI readout: "history capped at N steps / M MB" sits beside these. */
    data class Stats(val entries: Int, val bytes: Long)

    fun stats(): Stats = Stats(list.size, bytes)
}
