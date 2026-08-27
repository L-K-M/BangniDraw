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
    fun `merge recovery accepts unordered lower outputs for upper-only tiles`() {
        val root = createTempDirectory("bangni-after-merge").toFile()
        try {
            val lowerId = LayerId("lower")
            val upperId = LayerId("upper")
            val first = TileKey(0, 0)
            val second = TileKey(1, 0)
            val lower = Layer(LayerProps(lowerId, "Lower"))
            val upper = Layer(
                LayerProps(upperId, "Upper"),
                linkedSetOf(second, first),
            )
            val document = Document(
                id = "painting",
                width = 512,
                height = 256,
                paperColor = 0,
                stack = LayerStack(listOf(lower, upper), activeIndex = 1, nextName = 3),
            )
            val history = HistoryStore(root.resolve("history"))
            val entry = history.append(
                HistoryEntry.LayerMerge(
                    activeBefore = upperId,
                    activeAfter = lowerId,
                    upper = upper.props.toRecord(),
                    upperIndex = 1,
                    upperTiles = listOf(first, second),
                    lower = lower.props.toRecord(),
                    lowerTiles = emptyList(),
                ),
                seq = 1,
                ts = 10,
                payloads = listOf(
                    HistoryStore.Payload(
                        upperId,
                        first,
                        TileCodec.encode(ByteArray(TILE_BYTES) { 7 }),
                    ),
                    HistoryStore.Payload(
                        upperId,
                        second,
                        TileCodec.encode(ByteArray(TILE_BYTES) { 8 }),
                    ),
                ),
            )
            val firstMerged = ByteArray(TILE_BYTES) { 9 }
            val secondMerged = ByteArray(TILE_BYTES) { 11 }
            history.writeRecoveryAfter(
                seq = 1,
                payloads = listOf(
                    HistoryStore.Payload(upperId, first, ByteArray(0)),
                    HistoryStore.Payload(upperId, second, ByteArray(0)),
                    HistoryStore.Payload(lowerId, second, TileCodec.encode(secondMerged)),
                    HistoryStore.Payload(lowerId, first, TileCodec.encode(firstMerged)),
                ),
            )
            val writes = LinkedHashMap<Pair<LayerId, TileKey>, ByteArray>()

            val recovered = HistoryAfterRecovery.apply(
                document = document,
                entries = listOf(entry),
                history = history,
                writer = HistoryAfterRecovery.Writer { layer, tile, pixels ->
                    writes[layer to tile] = pixels
                },
            )

            assertEquals(null, recovered.failure)
            assertEquals(1, recovered.appliedCount)
            assertEquals(listOf(lowerId), recovered.document.stack.layers.map(Layer::id))
            assertTrue(writes.getValue(lowerId to first).contentEquals(firstMerged))
            assertTrue(writes.getValue(lowerId to second).contentEquals(secondMerged))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `flatten recovery accepts pixels owned by the result layer`() {
        val root = createTempDirectory("bangni-after-flatten").toFile()
        try {
            val lowerId = LayerId("lower")
            val upperId = LayerId("upper")
            val resultId = LayerId("result")
            val key = TileKey(0, 0)
            val lower = Layer(LayerProps(lowerId, "Lower"), setOf(key))
            val upper = Layer(LayerProps(upperId, "Upper"), setOf(key))
            val document = Document(
                id = "painting",
                width = 256,
                height = 256,
                paperColor = 0,
                stack = LayerStack(listOf(lower, upper), activeIndex = 1, nextName = 3),
            )
            val history = HistoryStore(root.resolve("history"))
            val entry = history.append(
                HistoryEntry.Flatten(
                    activeBefore = upperId,
                    activeAfter = resultId,
                    layers = listOf(lower.props.toRecord(), upper.props.toRecord()),
                    tilesPerLayer = linkedMapOf(lowerId to listOf(key), upperId to listOf(key)),
                    result = LayerProps(resultId, "Flattened").toRecord(),
                ),
                seq = 1,
                ts = 10,
                payloads = listOf(
                    HistoryStore.Payload(lowerId, key, TileCodec.encode(ByteArray(TILE_BYTES) { 3 })),
                    HistoryStore.Payload(upperId, key, TileCodec.encode(ByteArray(TILE_BYTES) { 5 })),
                ),
            )
            val flattened = ByteArray(TILE_BYTES) { 11 }
            history.writeRecoveryAfter(
                seq = 1,
                payloads = listOf(
                    HistoryStore.Payload(lowerId, key, ByteArray(0)),
                    HistoryStore.Payload(upperId, key, ByteArray(0)),
                    HistoryStore.Payload(resultId, key, TileCodec.encode(flattened)),
                ),
            )
            val writes = LinkedHashMap<Pair<LayerId, TileKey>, ByteArray>()

            val recovered = HistoryAfterRecovery.apply(
                document = document,
                entries = listOf(entry),
                history = history,
                writer = HistoryAfterRecovery.Writer { layer, tile, pixels ->
                    writes[layer to tile] = pixels
                },
            )

            assertEquals(null, recovered.failure)
            assertEquals(1, recovered.appliedCount)
            assertEquals(listOf(resultId), recovered.document.stack.layers.map(Layer::id))
            assertTrue(writes.getValue(resultId to key).contentEquals(flattened))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `recovered stroke membership feeds a later duplicate`() {
        val root = createTempDirectory("bangni-after-chain").toFile()
        try {
            val baseId = LayerId("base")
            val sourceId = LayerId("source")
            val copyId = LayerId("copy")
            val key = TileKey(0, 0)
            val base = Layer(LayerProps(baseId, "Base"))
            val source = LayerProps(sourceId, "Source")
            val copy = LayerProps(copyId, "Copy")
            val document = Document(
                id = "painting",
                width = 256,
                height = 256,
                paperColor = 0,
                stack = LayerStack(listOf(base), activeIndex = 0, nextName = 2),
            )
            val history = HistoryStore(root.resolve("history"))
            val add = history.append(
                HistoryEntry.LayerAdd(
                    activeBefore = baseId,
                    activeAfter = sourceId,
                    layer = source.toRecord(),
                    index = 1,
                ),
                seq = 1,
                ts = 10,
                payloads = emptyList(),
            )
            val stroke = history.append(
                HistoryEntry.Stroke(
                    activeBefore = sourceId,
                    activeAfter = sourceId,
                    layerId = sourceId,
                    tiles = listOf(key),
                ),
                seq = 2,
                ts = 20,
                payloads = listOf(HistoryStore.Payload(sourceId, key, ByteArray(0))),
            )
            val pixels = ByteArray(TILE_BYTES) { 13 }
            history.writeRecoveryAfter(
                seq = 2,
                payloads = listOf(
                    HistoryStore.Payload(sourceId, key, TileCodec.encode(pixels)),
                ),
            )
            val duplicate = history.append(
                HistoryEntry.LayerDuplicate(
                    activeBefore = sourceId,
                    activeAfter = copyId,
                    sourceId = sourceId,
                    copy = copy.toRecord(),
                    index = 2,
                ),
                seq = 3,
                ts = 30,
                payloads = emptyList(),
            )
            history.writeRecoveryAfter(
                seq = 3,
                payloads = listOf(
                    HistoryStore.Payload(copyId, key, TileCodec.encode(pixels)),
                ),
            )

            val recovered = HistoryAfterRecovery.apply(
                document = document,
                entries = listOf(add, stroke, duplicate),
                history = history,
                writer = HistoryAfterRecovery.Writer { _, _, _ -> },
            )

            assertEquals(null, recovered.failure)
            assertEquals(3, recovered.appliedCount)
            assertEquals(
                listOf(baseId, sourceId, copyId),
                recovered.document.stack.layers.map(Layer::id),
            )
            assertEquals(setOf(key), recovered.document.stack.layers[1].tiles)
            assertEquals(setOf(key), recovered.document.stack.layers[2].tiles)
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
    fun `a corrupt payload keeps structural replay and becomes empty`() {
        val root = createTempDirectory("bangni-after-corrupt").toFile()
        try {
            val sourceId = LayerId("source")
            val copyId = LayerId("copy")
            val key = TileKey(0, 0)
            val source = Layer(LayerProps(sourceId, "Source"), setOf(key))
            val document = Document(
                id = "painting",
                width = 256,
                height = 256,
                paperColor = 0,
                stack = LayerStack(
                    listOf(source),
                    activeIndex = 0,
                    nextName = 2,
                ),
            )
            val history = HistoryStore(root.resolve("history"))
            val entry = history.append(
                HistoryEntry.LayerDuplicate(
                    activeBefore = sourceId,
                    activeAfter = copyId,
                    sourceId = sourceId,
                    copy = LayerProps(copyId, "Copy").toRecord(),
                    index = 1,
                ),
                seq = 1,
                ts = 10,
                payloads = emptyList(),
            )
            history.writeRecoveryAfter(
                seq = 1,
                payloads = listOf(
                    HistoryStore.Payload(copyId, key, byteArrayOf(1)),
                ),
            )
            val writes = LinkedHashMap<Pair<LayerId, TileKey>, ByteArray>()

            val recovered = HistoryAfterRecovery.apply(
                document = document,
                entries = listOf(entry),
                history = history,
                writer = HistoryAfterRecovery.Writer { layer, tile, pixels ->
                    writes[layer to tile] = pixels
                },
            )

            assertEquals(null, recovered.failure)
            assertEquals(1, recovered.appliedCount)
            assertEquals(listOf(sourceId, copyId), recovered.document.stack.layers.map(Layer::id))
            assertEquals(emptySet(), recovered.document.stack.layers[1].tiles)
            assertTrue(writes.getValue(copyId to key).all { it == 0.toByte() })
        } finally {
            root.deleteRecursively()
        }
    }
}
