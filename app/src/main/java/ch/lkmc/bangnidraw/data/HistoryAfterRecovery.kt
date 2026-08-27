package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.HistoryDirection
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerEditPolicy
import ch.lkmc.bangnidraw.engine.core.LayerHistory
import ch.lkmc.bangnidraw.engine.core.LayerHistoryResult
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerTileUpdates
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.presenceOf
import java.io.IOException

/** Rolls durable post-checkpoint pixels forward before sparse tiles are listed. */
internal object HistoryAfterRecovery {

    enum class Failure { INCONSISTENT, WRITE_FAILED }

    fun interface Writer {
        @Throws(IOException::class)
        fun write(layer: LayerId, key: TileKey, pixels: ByteArray)
    }

    data class Result(
        val document: Document,
        val appliedCount: Int,
        val failure: Failure? = null,
    )

    fun apply(
        document: Document,
        entries: List<HistoryEntry>,
        history: HistoryStore,
        tileStore: (LayerId) -> TileStore,
    ): Result = apply(
        document = document,
        entries = entries,
        history = history,
        writer = Writer { layer, key, pixels -> tileStore(layer).write(key, pixels) },
    )

    fun apply(
        document: Document,
        entries: List<HistoryEntry>,
        history: HistoryStore,
        writer: Writer,
    ): Result {
        var current = document
        var appliedCount = 0
        for (entry in entries) {
            val before = current.stack
            val edit = when (val result = LayerHistory.apply(
                before,
                entry,
                HistoryDirection.REDO,
            )) {
                is LayerHistoryResult.Applied -> result.edit
                LayerHistoryResult.Corrupt -> {
                    return Result(current, appliedCount, Failure.INCONSISTENT)
                }
            }
            val expected = LinkedHashSet<Pair<LayerId, TileKey>>()
            expected += HistoryCodec.payloadKeys(entry)
            // Merge and flatten move pixels to a new after-image owner.
            expected += HistoryCodec.redoPayloadKeys(entry)
            for (op in edit.pixelOps) expected += LayerEditPolicy.changedTiles(before, op)

            val after = history.readRecoveryAfter(entry.seq)
            if (expected.isNotEmpty() && after == null) {
                return Result(current, appliedCount, Failure.INCONSISTENT)
            }
            if (after != null) {
                val actual = after.mapTo(LinkedHashSet()) { it.layer to it.key }
                if (actual.size != after.size || actual != expected) {
                    return Result(current, appliedCount, Failure.INCONSISTENT)
                }
            }
            val writes = after?.let(::prepareWrites)
            if (writes != null && !writeAfter(writes, writer)) {
                return Result(current, appliedCount, Failure.WRITE_FAILED)
            }

            // Later recovered edits consume the tile membership just written.
            val outcomes = writes.orEmpty().associate { write ->
                (write.layer to write.key) to presenceOf(write.pixels)
            }
            current = current.copy(
                stack = LayerTileUpdates.apply(edit.stack, outcomes),
                paperColor = edit.paperColor ?: current.paperColor,
            )
            appliedCount += 1
        }
        return Result(current, appliedCount)
    }

    /** Decode the complete image before the first destructive write. */
    private fun prepareWrites(payloads: List<HistoryStore.Payload>): List<Write> {
        val writes = ArrayList<Write>(payloads.size)
        for (payload in payloads) {
            val pixels = if (payload.encoded.isEmpty()) {
                EMPTY_TILE
            } else {
                when (val decoded = TileCodec.decode(payload.encoded)) {
                    is TileCodec.Decoded.Ok -> decoded.pixels
                    // Match ordinary project loading: preserve the edit and
                    // degrade only the damaged tile to transparent.
                    TileCodec.Decoded.Corrupt -> EMPTY_TILE
                }
            }
            writes += Write(payload.layer, payload.key, pixels)
        }
        return writes
    }

    private fun writeAfter(
        writes: List<Write>,
        writer: Writer,
    ): Boolean = try {
        for (write in writes) {
            writer.write(write.layer, write.key, write.pixels)
        }
        true
    } catch (_: IOException) {
        false
    }

    private data class Write(val layer: LayerId, val key: TileKey, val pixels: ByteArray)

    private val EMPTY_TILE = ByteArray(TILE_BYTES)
}
