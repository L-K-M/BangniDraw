package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.HistoryDirection
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerRecord
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HistoryTransitionRecoveryTest {

    @Test
    fun `undo survives a crash after restored tiles flush`() {
        val root = createTempDirectory("bangni-undo-transition").toFile()
        try {
            val history = HistoryStore(root.resolve("history"))
            val transitions = HistoryTransitionStore(root.resolve("history"))
            val lowerId = LayerId("lower")
            val upperId = LayerId("upper")
            val key = TileKey(0, 0)
            val before = ByteArray(TILE_BYTES) { 23 }
            val lower = Layer(LayerProps(lowerId, "Lower"))
            val upperRecord = LayerRecord(id = upperId.value, name = "Upper")
            val entry = history.append(
                HistoryEntry.LayerDelete(
                    activeBefore = upperId,
                    activeAfter = lowerId,
                    layer = upperRecord,
                    index = 1,
                    tiles = listOf(key),
                ),
                seq = 1,
                ts = 10,
                payloads = listOf(
                    HistoryStore.Payload(upperId, key, TileCodec.encode(before)),
                ),
            )
            val checkpointed = document(
                stack = LayerStack(listOf(lower), activeIndex = 0, nextName = 3),
                cursor = 1,
            )
            val upperTiles = tiles(root, upperId)

            transitions.begin(entry, HistoryDirection.UNDO, fromCursor = 1)
            // The restored tile landed, but project.json still describes the
            // deleted layer and the old cursor.
            upperTiles.write(key, before)

            val recovered = HistoryTransitionRecovery.apply(
                document = checkpointed,
                loaded = HistoryStore.Loaded(listOf(entry), cursor = 1),
                history = history,
                transitions = transitions,
                tileStore = { tiles(root, it) },
            )

            assertEquals(null, recovered.failure)
            assertEquals(0, recovered.cursor)
            assertEquals(listOf(lowerId, upperId), recovered.document.stack.layers.map(Layer::id))
            assertEquals(upperId, recovered.document.stack.active.id)
            assertEquals(setOf(key), recovered.document.stack.active.tiles)
            assertPixels(before, upperTiles.read(key))
            assertNotNull(transitions.pending(), "the next checkpoint owns marker removal")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `redo survives a crash after restored tiles flush`() {
        val root = createTempDirectory("bangni-redo-transition").toFile()
        try {
            val history = HistoryStore(root.resolve("history"))
            val transitions = HistoryTransitionStore(root.resolve("history"))
            val lowerId = LayerId("lower")
            val upperId = LayerId("upper")
            val key = TileKey(0, 0)
            val lowerBefore = ByteArray(TILE_BYTES) { 11 }
            val upperBefore = ByteArray(TILE_BYTES) { 17 }
            val merged = ByteArray(TILE_BYTES) { 29 }
            val lowerRecord = LayerRecord(id = lowerId.value, name = "Lower")
            val upperRecord = LayerRecord(id = upperId.value, name = "Upper")
            val entry = history.append(
                HistoryEntry.LayerMerge(
                    activeBefore = upperId,
                    activeAfter = lowerId,
                    upper = upperRecord,
                    upperIndex = 1,
                    upperTiles = listOf(key),
                    lower = lowerRecord,
                    lowerTiles = listOf(key),
                ),
                seq = 1,
                ts = 10,
                payloads = listOf(
                    HistoryStore.Payload(upperId, key, TileCodec.encode(upperBefore)),
                    HistoryStore.Payload(lowerId, key, TileCodec.encode(lowerBefore)),
                ),
            )
            history.writeRedo(
                entry,
                listOf(HistoryStore.Payload(lowerId, key, TileCodec.encode(merged))),
            )
            val checkpointed = document(
                stack = LayerStack(
                    layers = listOf(
                        Layer(lowerRecord.toProps(), setOf(key)),
                        Layer(upperRecord.toProps(), setOf(key)),
                    ),
                    activeIndex = 1,
                    nextName = 3,
                ),
                cursor = 0,
            )
            val lowerTiles = tiles(root, lowerId)
            val upperTiles = tiles(root, upperId)
            lowerTiles.write(key, lowerBefore)
            upperTiles.write(key, upperBefore)

            transitions.begin(entry, HistoryDirection.REDO, fromCursor = 0)
            // The merge reached disk before project.json moved its model and
            // cursor. Recovery must replay the same target, not invert it.
            lowerTiles.write(key, merged)
            root.resolve("layers/${upperId.value}").deleteRecursively()

            val recovered = HistoryTransitionRecovery.apply(
                document = checkpointed,
                loaded = HistoryStore.Loaded(listOf(entry), cursor = 0),
                history = history,
                transitions = transitions,
                tileStore = { tiles(root, it) },
            )

            assertEquals(null, recovered.failure)
            assertEquals(1, recovered.cursor)
            assertEquals(listOf(lowerId), recovered.document.stack.layers.map(Layer::id))
            assertEquals(setOf(key), recovered.document.stack.active.tiles)
            assertPixels(merged, lowerTiles.read(key))
            assertEquals(TileStore.Read.Empty, upperTiles.read(key))
            assertNotNull(transitions.pending(), "the next checkpoint owns marker removal")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `redo copy refuses a missing source tile`() {
        val root = createTempDirectory("bangni-copy-transition").toFile()
        try {
            val history = HistoryStore(root.resolve("history"))
            val transitions = HistoryTransitionStore(root.resolve("history"))
            val sourceId = LayerId("source")
            val copyId = LayerId("copy")
            val key = TileKey(0, 0)
            val source = Layer(LayerProps(sourceId, "Source"), setOf(key))
            val copy = LayerRecord(id = copyId.value, name = "Copy")
            val entry = history.append(
                HistoryEntry.LayerDuplicate(
                    activeBefore = sourceId,
                    activeAfter = copyId,
                    sourceId = sourceId,
                    copy = copy,
                    index = 1,
                ),
                seq = 1,
                ts = 10,
                payloads = emptyList(),
            )
            val checkpointed = document(
                stack = LayerStack(listOf(source), activeIndex = 0, nextName = 3),
                cursor = 0,
            )
            transitions.begin(entry, HistoryDirection.REDO, fromCursor = 0)

            val recovered = HistoryTransitionRecovery.apply(
                document = checkpointed,
                loaded = HistoryStore.Loaded(listOf(entry), cursor = 0),
                history = history,
                transitions = transitions,
                tileStore = { tiles(root, it) },
            )

            assertEquals(HistoryTransitionRecovery.Failure.INCONSISTENT, recovered.failure)
            assertEquals(TileStore.Read.Empty, tiles(root, copyId).read(key))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `redo copy treats a corrupt source tile as transparent`() {
        val root = createTempDirectory("bangni-corrupt-copy-transition").toFile()
        try {
            val history = HistoryStore(root.resolve("history"))
            val transitions = HistoryTransitionStore(root.resolve("history"))
            val sourceId = LayerId("source")
            val copyId = LayerId("copy")
            val key = TileKey(0, 0)
            val source = Layer(LayerProps(sourceId, "Source"), setOf(key))
            val copy = LayerRecord(id = copyId.value, name = "Copy")
            val entry = history.append(
                HistoryEntry.LayerDuplicate(
                    activeBefore = sourceId,
                    activeAfter = copyId,
                    sourceId = sourceId,
                    copy = copy,
                    index = 1,
                ),
                seq = 1,
                ts = 10,
                payloads = emptyList(),
            )
            val checkpointed = document(
                stack = LayerStack(listOf(source), activeIndex = 0, nextName = 3),
                cursor = 0,
            )
            val sourceDir = root.resolve("layers/${sourceId.value}").also { it.mkdirs() }
            sourceDir.resolve(TileStore.fileName(key)).writeBytes(byteArrayOf(1))
            transitions.begin(entry, HistoryDirection.REDO, fromCursor = 0)

            val recovered = HistoryTransitionRecovery.apply(
                document = checkpointed,
                loaded = HistoryStore.Loaded(listOf(entry), cursor = 0),
                history = history,
                transitions = transitions,
                tileStore = { tiles(root, it) },
            )

            assertEquals(null, recovered.failure)
            assertEquals(1, recovered.cursor)
            assertEquals(copyId, recovered.document.stack.active.id)
            assertEquals(emptySet(), recovered.document.stack.active.tiles)
            assertEquals(TileStore.Read.Empty, tiles(root, copyId).read(key))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `corrupt undo payload degrades to an empty tile`() {
        val root = createTempDirectory("bangni-corrupt-transition").toFile()
        try {
            val history = HistoryStore(root.resolve("history"))
            val transitions = HistoryTransitionStore(root.resolve("history"))
            val layerId = LayerId("layer")
            val key = TileKey(0, 0)
            val entry = history.append(
                HistoryEntry.Stroke(
                    activeBefore = layerId,
                    activeAfter = layerId,
                    layerId = layerId,
                    tiles = listOf(key),
                ),
                seq = 1,
                ts = 10,
                payloads = listOf(
                    HistoryStore.Payload(layerId, key, byteArrayOf(1)),
                ),
            )
            val checkpointed = document(
                stack = LayerStack(
                    listOf(Layer(LayerProps(layerId, "Layer"), setOf(key))),
                    activeIndex = 0,
                    nextName = 2,
                ),
                cursor = 1,
            )
            val layerTiles = tiles(root, layerId)
            layerTiles.write(key, ByteArray(TILE_BYTES) { 19 })
            transitions.begin(entry, HistoryDirection.UNDO, fromCursor = 1)

            val recovered = HistoryTransitionRecovery.apply(
                document = checkpointed,
                loaded = HistoryStore.Loaded(listOf(entry), cursor = 1),
                history = history,
                transitions = transitions,
                tileStore = { tiles(root, it) },
            )

            assertEquals(null, recovered.failure)
            assertEquals(0, recovered.cursor)
            assertEquals(emptySet(), recovered.document.stack.active.tiles)
            assertEquals(TileStore.Read.Empty, layerTiles.read(key))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `checkpointed transition retries failed marker deletion`() {
        val root = createTempDirectory("bangni-transition-delete").toFile()
        val historyDir = root.resolve("history")
        try {
            val history = HistoryStore(historyDir)
            val transitions = HistoryTransitionStore(historyDir)
            val layerId = LayerId("layer")
            val entry = HistoryEntry.PaperColor(
                activeBefore = layerId,
                activeAfter = layerId,
                before = 0,
                after = 1,
            ).stamp(seq = 1, timestamp = 10, bytes = 10)
            transitions.begin(entry, HistoryDirection.UNDO, fromCursor = 1)
            val target = document(
                stack = LayerStack(
                    listOf(Layer(LayerProps(layerId, "Layer"))),
                    activeIndex = 0,
                    nextName = 2,
                ),
                cursor = 0,
            )
            val loaded = HistoryStore.Loaded(listOf(entry), cursor = 0)
            val permissions = Files.getPosixFilePermissions(historyDir.toPath())
            val readOnly = permissions.filterNotTo(mutableSetOf()) {
                it == PosixFilePermission.OWNER_WRITE ||
                    it == PosixFilePermission.GROUP_WRITE ||
                    it == PosixFilePermission.OTHERS_WRITE
            }

            val failed = try {
                Files.setPosixFilePermissions(historyDir.toPath(), readOnly)
                HistoryTransitionRecovery.apply(
                    document = target,
                    loaded = loaded,
                    history = history,
                    transitions = transitions,
                    tileStore = { tiles(root, it) },
                )
            } finally {
                Files.setPosixFilePermissions(historyDir.toPath(), permissions)
            }

            assertEquals(HistoryTransitionRecovery.Failure.WRITE_FAILED, failed.failure)
            assertNotNull(transitions.pending(), "a failed deletion must remain retryable")

            val retried = HistoryTransitionRecovery.apply(
                document = target,
                loaded = loaded,
                history = history,
                transitions = transitions,
                tileStore = { tiles(root, it) },
            )
            assertEquals(null, retried.failure)
            assertEquals(null, transitions.pending())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a newer marker version is not interpreted`() {
        val root = createTempDirectory("bangni-transition-version").toFile()
        try {
            val historyDir = root.resolve("history").also { assertTrue(it.mkdirs()) }
            historyDir.resolve("transition.json").writeText(
                """{"version":2,"seq":1,"direction":"UNDO","fromCursor":1,"toCursor":0}""",
            )

            assertEquals(null, HistoryTransitionStore(historyDir).pending())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun document(stack: LayerStack, cursor: Int): Document = Document(
        id = "painting",
        width = 256,
        height = 256,
        paperColor = 0,
        stack = stack,
        historyCursor = cursor,
    )

    private fun tiles(root: java.io.File, layer: LayerId): TileStore =
        TileStore(root.resolve("layers/${layer.value}"))

    private fun assertPixels(expected: ByteArray, actual: TileStore.Read) {
        assertTrue(actual is TileStore.Read.Pixels)
        assertTrue(actual.pixels.contentEquals(expected))
    }
}
