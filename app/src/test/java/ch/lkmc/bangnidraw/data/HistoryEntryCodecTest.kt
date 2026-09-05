package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.data.shared.TileCodec
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerRecord
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §3.8's codec cases: every kind through the
 * on-disk encoding of 06 §5.3, via a real file on a temp dir.
 */
class HistoryEntryCodecTest {

    private val dir = createTempDirectory("bangni-history").toFile()
    private val store = HistoryStore(dir)

    private val a = LayerId("layer-a")
    private val b = LayerId("layer-b")
    private val recordA = LayerRecord(id = "layer-a", name = "@string/layer_default 1", opacity = 0.5f)
    private val recordB = LayerRecord(id = "layer-b", name = "inks", blend = "MULTIPLY")

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun payloadsFor(entry: HistoryEntry, seed: Int = 1): List<HistoryStore.Payload> =
        HistoryCodec.payloadKeys(entry).mapIndexed { i, (layer, key) ->
            HistoryStore.Payload(
                layer, key,
                // Every third payload records "was empty before" (len 0).
                if (i % 3 == 2) ByteArray(0)
                else TileCodec.encode(Random(seed + i).nextBytes(TILE_BYTES)),
            )
        }

    /** Round-trips [entry] through a real file; returns the decoded twin. */
    private fun roundTrip(entry: HistoryEntry, seq: Long): HistoryEntry {
        val payloads = payloadsFor(entry)
        val stamped = store.append(entry, seq = seq, ts = 1000 + seq, payloads = payloads)
        // cursor 1: the entry is on the undo branch. On the redo branch the
        // pixel kinds would need their sidecar, which is its own test below.
        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = seq + 1, oldestSeq = seq))
        assertEquals(1, loaded.entries.size, "entry $seq should load")
        val decoded = loaded.entries.single()
        assertEquals(stamped.seq, decoded.seq)
        assertEquals(stamped.timestamp, decoded.timestamp)
        assertEquals(stamped.bytes, decoded.bytes)
        // Payload bytes survive byte-for-byte, empties included.
        val read = store.readPayloads(seq, sidecar = false)!!
        assertEquals(payloads.size, read.size)
        for ((expected, actual) in payloads.zip(read)) {
            assertEquals(expected.layer, actual.layer)
            assertEquals(expected.key, actual.key)
            assertTrue(expected.encoded.contentEquals(actual.encoded))
        }
        store.delete(listOf(seq))
        return decoded
    }

    @Test
    fun `every entry kind round-trips through the on-disk encoding`() {
        val keys = listOf(TileKey(0, 0), TileKey(3, 5), TileKey(31, 2))
        var seq = 0L
        val cases: List<HistoryEntry> = listOf(
            HistoryEntry.Stroke(activeBefore = a, activeAfter = a, layerId = a, tiles = keys),
            HistoryEntry.Fill(activeBefore = a, activeAfter = b, layerId = b, tiles = keys),
            HistoryEntry.LayerAdd(activeBefore = a, activeAfter = b, layer = recordB, index = 1),
            HistoryEntry.LayerDelete(
                activeBefore = b, activeAfter = a, layer = recordB, index = 1, tiles = keys,
            ),
            HistoryEntry.LayerReorder(
                activeBefore = a, activeAfter = a, layerId = a, fromIndex = 0, toIndex = 1,
            ),
            HistoryEntry.LayerProps(
                activeBefore = a, activeAfter = a, layerId = a,
                before = recordA, after = recordA.copy(opacity = 1f, visible = false),
            ),
            HistoryEntry.LayerMerge(
                activeBefore = b, activeAfter = a,
                upper = recordB, upperIndex = 1, upperTiles = keys.take(2),
                lower = recordA, lowerTiles = keys.drop(2),
            ),
            HistoryEntry.LayerDuplicate(
                activeBefore = a, activeAfter = b, sourceId = a, copy = recordB, index = 1,
            ),
            HistoryEntry.LayerClear(activeBefore = a, activeAfter = a, layerId = a, tiles = keys),
            HistoryEntry.Flatten(
                activeBefore = a, activeAfter = b,
                layers = listOf(recordA, recordB),
                tilesPerLayer = mapOf(a to keys.take(2), b to keys.drop(2)),
                result = LayerRecord(id = "layer-b2", name = "@string/layer_flattened"),
            ),
            HistoryEntry.PaperColor(
                activeBefore = a, activeAfter = a, before = -1, after = 0x00FF8800,
            ),
        )
        for (case in cases) {
            val decoded = roundTrip(case, ++seq)
            val expected = case.stamp(decoded.seq, decoded.timestamp, decoded.bytes)
            assertEquals(expected, decoded, "kind ${HistoryCodec.kindOf(case)}")
        }
    }

    @Test
    fun `the header is versioned and a newer minor revision loads`() {
        // Same major version with fields this reader has never heard of:
        // ignoreUnknownKeys carries it, exactly as for project.json (06 §13).
        val entry = HistoryEntry.PaperColor(activeBefore = a, activeAfter = a, before = 0, after = 1)
        store.append(entry, seq = 1, ts = 5, payloads = emptyList())
        val file = store.entryFile(1)
        val text = file.readText()
        file.writeText(text.trimEnd('\n').removeSuffix("}") + ""","futureHint":true}""" + "\n")
        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1))
        assertEquals(1, loaded.entries.size)

        // A major bump is refused: the entry (and everything after) drops.
        file.writeText(text.replace("\"v\":1", "\"v\":2"))
        val refused = store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1))
        assertEquals(0, refused.entries.size)
    }

    @Test
    fun `a truncated payload is dropped, with everything after it, not thrown`() {
        val keys = listOf(TileKey(1, 1))
        val first = HistoryEntry.Stroke(activeBefore = a, activeAfter = a, layerId = a, tiles = keys)
        val second = HistoryEntry.PaperColor(activeBefore = a, activeAfter = a, before = 0, after = 1)
        store.append(first, seq = 1, ts = 1, payloads = payloadsFor(first, seed = 9))
        store.append(second, seq = 2, ts = 2, payloads = emptyList())

        // Cut the first entry's payload short: its header now points past the
        // end of the file, so the journal truncates at seq 1 — undo history
        // is a prefix or it is lies (06 §5.6).
        val file = store.entryFile(1)
        file.writeBytes(file.readBytes().copyOfRange(0, file.length().toInt() - 10))
        val loaded = store.load(HistoryRecord(cursor = 2, nextSeq = 3, oldestSeq = 1))
        assertEquals(0, loaded.entries.size)
        assertNull(store.readPayloads(1, sidecar = false))
        // The unreadable file stays on disk — a support question, not an
        // eviction, same as a corrupt tile.
        assertTrue(file.isFile)
    }

    @Test
    fun `tile payloads inflate to identical bytes through the entry file`() {
        val pixels = Random(3).nextBytes(TILE_BYTES)
        val entry = HistoryEntry.Stroke(
            activeBefore = a, activeAfter = a, layerId = a, tiles = listOf(TileKey(2, 2)),
        )
        store.append(
            entry, seq = 1, ts = 1,
            payloads = listOf(HistoryStore.Payload(a, TileKey(2, 2), TileCodec.encode(pixels))),
        )
        val read = store.readPayloads(1, sidecar = false)!!.single()
        val decoded = assertIs<TileCodec.Decoded.Ok>(TileCodec.decode(read.encoded))
        assertTrue(decoded.pixels.contentEquals(pixels))
    }

    @Test
    fun `a header is one inspectable JSON line`() {
        val entry = HistoryEntry.PaperColor(activeBefore = a, activeAfter = a, before = 0, after = 1)
        store.append(entry, seq = 7, ts = 9, payloads = emptyList())
        val firstLine = File(dir, "00000007.entry").readText().substringBefore('\n')
        assertTrue(firstLine.startsWith("{\"") && firstLine.endsWith("}"), firstLine)
        assertTrue("\"kind\":\"PaperColor\"" in firstLine)
    }
}
