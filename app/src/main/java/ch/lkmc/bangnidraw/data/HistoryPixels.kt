package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.TileKey
import kotlinx.coroutines.CompletableDeferred

/**
 * The pixel half of applying a journal entry (06 §5.4, §5.5, §10.3), as a
 * protocol over [TileFlusher] and [HistoryStore] so it is testable on the JVM
 * without a GL thread: the caller owns uploading the returned tiles to the
 * GPU and marking them dirty; this class owns the capture-and-read ordering.
 *
 * A returned value of `null` for a key means "the tile becomes empty":
 * uploaded as zeros, flushed as a deleted file.
 */
internal class HistoryPixels(
    private val flusher: TileFlusher,
    private val store: HistoryStore,
) {
    /** One restore: raw pixels per key, or null = the tile becomes empty. */
    class Restore(val layer: LayerId, val tiles: Map<TileKey, ByteArray?>)

    /**
     * A prepared undo: what to restore, and — when this undo is the step's
     * first — the queued sidecar write's byte count, for
     * `HistoryJournal.noteRedoBytes` once the worker lands it. Null when no
     * capture was needed (a repeat undo reuses the sidecar, §5.4; a
     * pixel-free kind never has one).
     */
    class Undo(val restores: List<Restore>, val redoBytes: CompletableDeferred<Long?>?)

    /**
     * Prepares an *undo* of [entry]: captures the step's "after" for
     * `<seq>.redo` — on the first undo only; later cycles reuse the sidecar
     * (§5.4) — then reads the entry's before-payloads. Null when those
     * payloads cannot be read (the journal lied; the caller reverts the
     * cursor).
     *
     * The *capture* is synchronous, before any restored pixel can reach the
     * mirror — that is what makes "current at the second undo equals after
     * at the first" hold (§5.4). The sidecar's *write* rides the job queue,
     * which orders it before the restore's [flushRestored] on disk (§5.6
     * step 2); the caller must not await [Undo.redoBytes] before draining
     * the queue.
     */
    suspend fun beforeUndo(entry: HistoryEntry): Undo? {
        require(entry.isStamped)
        var redoBytes: CompletableDeferred<Long?>? = null
        if (HistoryCodec.redoNeedsPixels(entry) && !store.hasRedo(entry.seq)) {
            val keys = HistoryCodec.payloadKeys(entry)
            val job = TileFlusher.FlushJob.WriteRedo(
                entry = entry,
                mirrorCurrent = flusher.captureMirror(keys),
            )
            flusher.enqueue(job)
            redoBytes = job.result
        }
        val restores = restores(entry.seq, sidecar = false) ?: return null
        return Undo(restores, redoBytes)
    }

    /**
     * Prepares a *redo* of [entry]: the "after" tiles from the sidecar. Kinds
     * that never write one redo without pixels and get an empty list. Null
     * when a needed sidecar is missing or unreadable — the caller reverts.
     */
    suspend fun beforeRedo(entry: HistoryEntry): List<Restore>? {
        require(entry.isStamped)
        if (!HistoryCodec.redoNeedsPixels(entry)) return emptyList()
        return restores(entry.seq, sidecar = true)
    }

    /**
     * The restored tiles are in the mirror (the caller marked them dirty
     * after uploading): flush them, behind whatever jobs are already queued —
     * for an undo that is the `WriteRedo`, which §5.6 step 2 orders before
     * the restored tiles reach disk.
     */
    suspend fun flushRestored(restores: List<Restore>) {
        val keys = restores.flatMap { r -> r.tiles.keys.map { r.layer to it } }
        if (keys.isNotEmpty()) flusher.enqueue(TileFlusher.FlushJob.FlushKeys(keys))
    }

    private fun restores(seq: Long, sidecar: Boolean): List<Restore>? {
        val payloads = store.readPayloads(seq, sidecar) ?: return null
        val byLayer = LinkedHashMap<LayerId, LinkedHashMap<TileKey, ByteArray?>>()
        for (payload in payloads) {
            val tiles = byLayer.getOrPut(payload.layer) { LinkedHashMap() }
            tiles[payload.key] = when {
                payload.encoded.isEmpty() -> null
                else -> when (val decoded = TileCodec.decode(payload.encoded)) {
                    is TileCodec.Decoded.Ok -> decoded.pixels
                    // A corrupt payload restores as empty rather than failing
                    // the whole undo — the §4 tile rule, applied to the
                    // journal's copies of tiles.
                    TileCodec.Decoded.Corrupt -> null
                }
            }
        }
        return byLayer.map { (layer, tiles) -> Restore(layer, tiles) }
    }
}
