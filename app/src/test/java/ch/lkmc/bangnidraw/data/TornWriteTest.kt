package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §5's `TornWriteTest`: the crash-mid-write
 * simulation, the reason the format is what it is (06 §5.6). Tiles and
 * history land ahead of `project.json` between checkpoints; a kill in that
 * window must leave a painting that opens on the last checkpoint's metadata
 * plus every committed stroke's pixels and undo.
 *
 * The single-case siblings live where their machinery does: the
 * payload-offsets case in `HistoryEntryCodecTest`, the missing-tile and
 * corrupt-tile cases in `TileStoreTest`, the stray-tmp and unparseable-json
 * cases in `ProjectStoreTest`.
 */
class TornWriteTest {

    private val root = createTempDirectory("bangni-torn").toFile()
    private val store = ProjectStore(root)
    private val layerId = LayerId("layer-a")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `project json is the commit point`() {
        val id = "torn-1"
        // The last checkpoint the crash preserved: one stroke journaled
        // (seq 1, applied), title as of then.
        val doc = Document(
            id = id,
            title = "before the crash",
            width = 512,
            height = 512,
            paperColor = -1,
            stack = LayerStack(
                layers = listOf(Layer(LayerProps(id = layerId, name = "n"), setOf(TileKey(0, 0)))),
                activeIndex = 0,
                nextName = 2,
            ),
            historyCursor = 1,
        )
        val history = HistoryStore(File(store.projectDir(id), "history"))
        fun stroke(key: TileKey) = HistoryEntry.Stroke(
            activeBefore = layerId, activeAfter = layerId, layerId = layerId, tiles = listOf(key),
        )
        val tiles = TileStore(store.layerDir(id, layerId))
        // Entry 1 and its tile, checkpointed.
        history.append(
            stroke(TileKey(0, 0)), seq = 1, ts = 1,
            payloads = listOf(HistoryStore.Payload(layerId, TileKey(0, 0), ByteArray(0))),
        )
        tiles.write(TileKey(0, 0), Random(1).nextBytes(TILE_BYTES))
        store.checkpoint(doc, HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1, entries = 1))

        // After the checkpoint, before the crash: stroke 2 committed — its
        // entry and its tile reached disk (§5.6's order guarantees entry
        // before tile) — and an entry 4 orphaned by an uncheckpointed
        // truncation.
        history.append(
            stroke(TileKey(1, 0)), seq = 2, ts = 2,
            payloads = listOf(HistoryStore.Payload(layerId, TileKey(1, 0), ByteArray(0))),
        )
        tiles.write(TileKey(1, 0), Random(2).nextBytes(TILE_BYTES))
        history.append(
            stroke(TileKey(2, 0)), seq = 4, ts = 4,
            payloads = listOf(HistoryStore.Payload(layerId, TileKey(2, 0), ByteArray(0))),
        )

        // Reopen. The metadata is the checkpoint's — old, not torn.
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load(id))
        assertEquals("before the crash", loaded.document.title)
        // The committed stroke's pixels are there (the tile listing sees
        // both), so the painting is intact to the last completed stroke.
        assertEquals(
            setOf(TileKey(0, 0), TileKey(1, 0)),
            loaded.document.stack.layers[0].tiles,
        )
        // The contiguous entry at nextSeq joins the undo branch as applied;
        // the after-gap entry is deleted (06 §5.6).
        val journal = history.load(loaded.history)
        assertEquals(listOf(1L, 2L), journal.entries.map { it.seq })
        assertEquals(2, journal.cursor)
        assertTrue(!history.entryFile(4).exists())
    }

    @Test
    fun `a torn project json tmp never shadows the committed file`() {
        val id = "torn-2"
        val doc = Document(
            id = id,
            title = "committed",
            width = 512,
            height = 512,
            paperColor = -1,
            stack = LayerStack(
                layers = listOf(Layer(LayerProps(id = layerId, name = "n"))),
                activeIndex = 0,
                nextName = 2,
            ),
        )
        store.checkpoint(doc)
        // A checkpoint died mid-write: the tmp holds half a newer file.
        File(store.projectDir(id), "project.json.tmp").writeText("{\"title\":\"half-writ")
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load(id))
        assertEquals("committed", loaded.document.title)
    }
}
