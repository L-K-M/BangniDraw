package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryAfterRecoveryTest {

    @Test
    fun `clear committed before tile flush rolls forward on reopen`() {
        val root = createTempDirectory("bangni-after-recovery").toFile()
        try {
            val layerId = LayerId("layer-a")
            val key = TileKey(0, 0)
            val layerDir = root.resolve("layers/${layerId.value}")
            val tiles = TileStore(layerDir)
            tiles.write(key, ByteArray(TILE_BYTES) { 7 })
            val document = Document(
                id = "painting",
                width = 256,
                height = 256,
                paperColor = 0,
                stack = LayerStack(
                    listOf(Layer(LayerProps(layerId, "Layer"), setOf(key))),
                    activeIndex = 0,
                    nextName = 2,
                ),
            )
            val history = HistoryStore(root.resolve("history"))
            val entry = history.append(
                HistoryEntry.LayerClear(
                    activeBefore = layerId,
                    activeAfter = layerId,
                    layerId = layerId,
                    tiles = listOf(key),
                ),
                seq = 1,
                ts = 10,
                payloads = listOf(
                    HistoryStore.Payload(layerId, key, TileCodec.encode(ByteArray(TILE_BYTES) { 7 })),
                ),
            )
            history.writeRecoveryAfter(
                seq = 1,
                payloads = listOf(HistoryStore.Payload(layerId, key, ByteArray(0))),
            )

            val recovered = HistoryAfterRecovery.apply(document, listOf(entry), history) {
                TileStore(root.resolve("layers/${it.value}"))
            }

            assertEquals(1, recovered.appliedCount)
            assertEquals(emptySet(), recovered.document.stack.active.tiles)
            assertEquals(TileStore.Read.Empty, tiles.read(key))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `write failure preserves the recovery instruction for retry`() {
        val root = createTempDirectory("bangni-after-retry").toFile()
        try {
            val layerId = LayerId("layer-a")
            val keys = listOf(TileKey(0, 0), TileKey(1, 0))
            val document = Document(
                id = "painting",
                width = 512,
                height = 256,
                paperColor = 0,
                stack = LayerStack(
                    listOf(Layer(LayerProps(layerId, "Layer"), keys.toSet())),
                    activeIndex = 0,
                    nextName = 2,
                ),
            )
            val history = HistoryStore(root.resolve("history"))
            val entry = history.append(
                HistoryEntry.Stroke(
                    activeBefore = layerId,
                    activeAfter = layerId,
                    layerId = layerId,
                    tiles = keys,
                ),
                seq = 1,
                ts = 10,
                payloads = keys.map {
                    HistoryStore.Payload(layerId, it, ByteArray(0))
                },
            )
            history.writeRecoveryAfter(
                seq = 1,
                payloads = keys.map {
                    HistoryStore.Payload(layerId, it, TileCodec.encode(ByteArray(TILE_BYTES) { 9 }))
                },
            )
            var writes = 0

            val recovered = HistoryAfterRecovery.apply(
                document = document,
                entries = listOf(entry),
                history = history,
                writer = HistoryAfterRecovery.Writer { _, _, _ ->
                    writes += 1
                    if (writes == 2) throw IOException("full")
                },
            )

            assertEquals(HistoryAfterRecovery.Failure.WRITE_FAILED, recovered.failure)
            assertEquals(0, recovered.appliedCount)
            assertTrue(history.entryFile(1).isFile)
            assertTrue(history.afterFile(1).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a corrupt later payload writes no earlier payload`() {
        val root = createTempDirectory("bangni-after-corrupt").toFile()
        try {
            val layerId = LayerId("layer-a")
            val keys = listOf(TileKey(0, 0), TileKey(1, 0))
            val document = Document(
                id = "painting",
                width = 512,
                height = 256,
                paperColor = 0,
                stack = LayerStack(
                    listOf(Layer(LayerProps(layerId, "Layer"), keys.toSet())),
                    activeIndex = 0,
                    nextName = 2,
                ),
            )
            val history = HistoryStore(root.resolve("history"))
            val entry = history.append(
                HistoryEntry.Stroke(
                    activeBefore = layerId,
                    activeAfter = layerId,
                    layerId = layerId,
                    tiles = keys,
                ),
                seq = 1,
                ts = 10,
                payloads = keys.map { HistoryStore.Payload(layerId, it, ByteArray(0)) },
            )
            history.writeRecoveryAfter(
                seq = 1,
                payloads = listOf(
                    HistoryStore.Payload(
                        layerId,
                        keys[0],
                        TileCodec.encode(ByteArray(TILE_BYTES) { 9 }),
                    ),
                    HistoryStore.Payload(layerId, keys[1], byteArrayOf(1)),
                ),
            )
            var writes = 0

            val recovered = HistoryAfterRecovery.apply(
                document = document,
                entries = listOf(entry),
                history = history,
                writer = HistoryAfterRecovery.Writer { _, _, _ -> writes += 1 },
            )

            assertEquals(HistoryAfterRecovery.Failure.INCONSISTENT, recovered.failure)
            assertEquals(0, recovered.appliedCount)
            assertEquals(0, writes)
        } finally {
            root.deleteRecursively()
        }
    }
}
