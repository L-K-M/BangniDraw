package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.HistoryDirection
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerHistory
import ch.lkmc.bangnidraw.engine.core.LayerHistoryResult
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.LayerTileUpdates
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PixelOp
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TilePresence
import java.io.IOException

/** Replays a durable undo/redo target when its tile flush beat project.json. */
internal object HistoryTransitionRecovery {

    enum class Failure { INCONSISTENT, WRITE_FAILED }

    data class Result(
        val document: Document,
        val cursor: Int,
        val applied: Boolean,
        val failure: Failure? = null,
    )

    fun apply(
        document: Document,
        loaded: HistoryStore.Loaded,
        history: HistoryStore,
        transitions: HistoryTransitionStore,
        tileStore: (LayerId) -> TileStore,
    ): Result {
        val pending = transitions.pending()
        if (pending == null) {
            val failure = if (transitions.hasPendingFile()) Failure.INCONSISTENT else null
            return Result(document, loaded.cursor, applied = false, failure = failure)
        }

        // A checkpoint landed and only marker deletion was interrupted.
        if (loaded.cursor == pending.toCursor && document.historyCursor == pending.toCursor) {
            if (!transitions.complete(pending)) {
                return Result(
                    document,
                    loaded.cursor,
                    applied = false,
                    failure = Failure.WRITE_FAILED,
                )
            }
            return Result(document, loaded.cursor, applied = false)
        }
        if (loaded.cursor != pending.fromCursor) return inconsistent(document, loaded.cursor)

        val entry = transitionEntry(loaded.entries, pending)
            ?: return inconsistent(document, loaded.cursor)
        val edit = when (val result = LayerHistory.apply(
            document.stack,
            entry,
            pending.direction,
        )) {
            is LayerHistoryResult.Applied -> result.edit
            LayerHistoryResult.Corrupt -> return inconsistent(document, loaded.cursor)
        }
        val writes = prepareWrites(
            before = document.stack,
            entry = entry,
            direction = pending.direction,
            pixelOps = edit.pixelOps,
            history = history,
            tileStore = tileStore,
        ) ?: return inconsistent(document, loaded.cursor)
        if (!write(writes, tileStore)) {
            return Result(document, loaded.cursor, applied = false, failure = Failure.WRITE_FAILED)
        }

        val outcomes = writes.mapValues { (_, pixels) ->
            if (pixels == null || TileCodec.isAllZero(pixels)) {
                TilePresence.EMPTY
            } else {
                TilePresence.PAINTED
            }
        }
        val stack = LayerTileUpdates.apply(edit.stack, outcomes)
        val recovered = document.copy(
            stack = stack,
            paperColor = edit.paperColor ?: document.paperColor,
            historyCursor = pending.toCursor,
        )
        return Result(recovered, pending.toCursor, applied = true)
    }

    private fun transitionEntry(
        entries: List<HistoryEntry>,
        pending: HistoryTransitionStore.Pending,
    ): HistoryEntry? {
        val index = when (pending.direction) {
            HistoryDirection.UNDO -> pending.fromCursor - 1
            HistoryDirection.REDO -> pending.fromCursor
        }
        return entries.getOrNull(index)?.takeIf { it.seq == pending.seq }
    }

    /** Resolves every source before the first idempotent target write. */
    private fun prepareWrites(
        before: LayerStack,
        entry: HistoryEntry,
        direction: HistoryDirection,
        pixelOps: List<PixelOp>,
        history: HistoryStore,
        tileStore: (LayerId) -> TileStore,
    ): LinkedHashMap<Pair<LayerId, TileKey>, ByteArray?>? {
        val writes = LinkedHashMap<Pair<LayerId, TileKey>, ByteArray?>()
        for (op in pixelOps) {
            when (op) {
                is PixelOp.Copy -> for (key in op.keys) {
                    val source = readCopySource(tileStore(op.src), key)
                    writes[op.dst to key] = when (source) {
                        is CopySource.Painted -> source.pixels
                        CopySource.Transparent -> null
                        CopySource.Missing -> return null
                    }
                }
                is PixelOp.Clear -> clearLayer(before, op.layer, writes)
                is PixelOp.Delete -> clearLayer(before, op.layer, writes)
                is PixelOp.Restore -> for ((key, pixels) in op.tiles) {
                    writes[op.layer to key] = pixels?.copyOf()
                }
                is PixelOp.Flatten,
                is PixelOp.Merge,
                -> return null
            }
        }

        val needsPayload = direction == HistoryDirection.UNDO || HistoryCodec.redoNeedsPixels(entry)
        if (!needsPayload) return writes
        val sidecar = direction == HistoryDirection.REDO
        val payloads = history.readPayloads(entry.seq, sidecar) ?: return null
        val expected = if (sidecar) {
            HistoryCodec.redoPayloadKeys(entry)
        } else {
            HistoryCodec.payloadKeys(entry)
        }
        if (payloads.map { it.layer to it.key } != expected) return null
        for (payload in payloads) {
            // Match ordinary undo: one corrupt tile degrades to transparent.
            writes[payload.layer to payload.key] = decode(payload.encoded)
        }
        return writes
    }

    private fun clearLayer(
        stack: LayerStack,
        layer: LayerId,
        writes: MutableMap<Pair<LayerId, TileKey>, ByteArray?>,
    ) {
        val keys = stack.layers.firstOrNull { it.id == layer }?.tiles.orEmpty()
        for (key in keys) writes[layer to key] = null
    }

    private fun decode(encoded: ByteArray): ByteArray? {
        if (encoded.isEmpty()) return null
        return when (val decoded = TileCodec.decode(encoded)) {
            is TileCodec.Decoded.Ok -> decoded.pixels
            TileCodec.Decoded.Corrupt -> null
        }
    }

    private fun readCopySource(store: TileStore, key: TileKey): CopySource = when (
        val read = store.read(key)
    ) {
        is TileStore.Read.Pixels -> if (TileCodec.isAllZero(read.pixels)) {
            CopySource.Transparent
        } else {
            CopySource.Painted(read.pixels)
        }
        // Project loading displays corrupt tiles as transparent.
        TileStore.Read.Corrupt -> CopySource.Transparent
        TileStore.Read.Empty -> CopySource.Missing
    }

    private sealed interface CopySource {
        data class Painted(val pixels: ByteArray) : CopySource
        data object Transparent : CopySource
        data object Missing : CopySource
    }

    private fun write(
        writes: Map<Pair<LayerId, TileKey>, ByteArray?>,
        tileStore: (LayerId) -> TileStore,
    ): Boolean = try {
        for ((target, pixels) in writes) {
            tileStore(target.first).write(target.second, pixels ?: EMPTY_TILE)
        }
        true
    } catch (_: IOException) {
        false
    }

    private fun inconsistent(document: Document, cursor: Int): Result =
        Result(document, cursor, applied = false, failure = Failure.INCONSISTENT)

    private val EMPTY_TILE = ByteArray(TILE_BYTES)
}
