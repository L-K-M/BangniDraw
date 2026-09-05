package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.data.shared.BangniCodec
import ch.lkmc.bangnidraw.data.shared.BangniDocument
import ch.lkmc.bangnidraw.data.shared.BangniManifest
import ch.lkmc.bangnidraw.data.shared.BangniReadResult
import ch.lkmc.bangnidraw.data.shared.TileCodec
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerRecord
import ch.lkmc.bangnidraw.engine.core.PerfConstants
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BangniCodecTest {

    @Test
    fun `a document survives a round trip with its layers and pixels`() {
        val red = tile(1)
        val blue = tile(2)
        val document = BangniDocument(
            manifest = manifest(
                layers = listOf(record("layer-1"), record("layer-2", opacity = 0.5f, blend = "MULTIPLY")),
                activeLayerId = "layer-2",
                nextLayerName = 3,
            ),
            tiles = mapOf(
                LayerId("layer-1") to mapOf(TileKey(0, 0) to red),
                LayerId("layer-2") to mapOf(TileKey(1, 0) to blue),
            ),
        )

        val read = assertIs<BangniReadResult.Ok>(roundTrip(document))

        assertEquals(2, read.document.manifest.layers.size)
        assertEquals("layer-2", read.document.manifest.activeLayerId)
        assertEquals(3, read.document.manifest.nextLayerName)
        assertEquals(0.5f, read.document.manifest.layers[1].opacity)
        assertEquals(BlendMode.MULTIPLY.name, read.document.manifest.layers[1].blend)
        assertContentEquals(red, read.document.tiles.getValue(LayerId("layer-1")).getValue(TileKey(0, 0)))
        assertContentEquals(blue, read.document.tiles.getValue(LayerId("layer-2")).getValue(TileKey(1, 0)))
        assertTrue(read.warnings.isEmpty())
    }

    @Test
    fun `a file with no manifest is refused rather than half-opened`() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("something.txt"))
                zip.write("hello".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val failed = assertIs<BangniReadResult.Failed>(BangniCodec.read(ByteArrayInputStream(bytes)))

        assertTrue(failed.message.contains(BangniCodec.MANIFEST_ENTRY))
    }

    @Test
    fun `a file that is not a zip fails without throwing`() {
        val failed = BangniCodec.read(ByteArrayInputStream("not a zip at all".toByteArray()))

        assertIs<BangniReadResult.Failed>(failed)
    }

    @Test
    fun `a newer format version is refused with a message that says so`() {
        val document = BangniDocument(
            manifest = manifest(layers = listOf(record("layer-1")))
                .copy(formatVersion = BangniManifest.FORMAT_VERSION + 1),
            tiles = emptyMap(),
        )

        val failed = assertIs<BangniReadResult.Failed>(roundTrip(document))

        assertTrue(failed.message.contains("newer version"), failed.message)
    }

    @Test
    fun `a damaged tile is skipped and the painting still opens`() {
        val good = tile(3)
        val bytes = zipOf(
            BangniCodec.MANIFEST_ENTRY to json(manifest(layers = listOf(record("layer-1")))),
            "layers/layer-1/0_0.tile" to TileCodec.encode(good),
            "layers/layer-1/1_0.tile" to ByteArray(32) { 0x7F },
        )

        val read = assertIs<BangniReadResult.Ok>(BangniCodec.read(ByteArrayInputStream(bytes)))

        assertEquals(setOf(TileKey(0, 0)), read.document.tiles.getValue(LayerId("layer-1")).keys)
        assertTrue(read.warnings.any { it.contains("damaged") }, read.warnings.toString())
    }

    @Test
    fun `an entry that tries to escape the container matches nothing`() {
        val bytes = zipOf(
            BangniCodec.MANIFEST_ENTRY to json(manifest(layers = listOf(record("layer-1")))),
            "layers/../../etc/passwd/0_0.tile" to TileCodec.encode(tile(4)),
            "../escape.tile" to TileCodec.encode(tile(5)),
        )

        val read = assertIs<BangniReadResult.Ok>(BangniCodec.read(ByteArrayInputStream(bytes)))

        // Nothing was decoded from either, and nothing was written anywhere:
        // the reader parses entry names, it never joins them onto a path.
        assertTrue(read.document.tiles.isEmpty(), read.document.tiles.keys.toString())
        assertEquals(2, read.warnings.size, read.warnings.toString())
    }

    @Test
    fun `tiles outside the canvas grid are dropped`() {
        val bytes = zipOf(
            BangniCodec.MANIFEST_ENTRY to json(manifest(layers = listOf(record("layer-1")))),
            "layers/layer-1/99_99.tile" to TileCodec.encode(tile(6)),
        )

        val read = assertIs<BangniReadResult.Ok>(BangniCodec.read(ByteArrayInputStream(bytes)))

        assertTrue(read.document.tiles.isEmpty())
        assertTrue(read.warnings.any { it.contains("outside the canvas") }, read.warnings.toString())
    }

    @Test
    fun `tiles for a layer the manifest does not list are dropped`() {
        val bytes = zipOf(
            BangniCodec.MANIFEST_ENTRY to json(manifest(layers = listOf(record("layer-1")))),
            "layers/layer-9/0_0.tile" to TileCodec.encode(tile(7)),
        )

        val read = assertIs<BangniReadResult.Ok>(BangniCodec.read(ByteArrayInputStream(bytes)))

        assertTrue(read.document.tiles.isEmpty())
        assertTrue(read.warnings.any { it.contains("does not list") }, read.warnings.toString())
    }

    @Test
    fun `an unknown entry is a warning, not a refusal`() {
        val bytes = zipOf(
            BangniCodec.MANIFEST_ENTRY to json(manifest(layers = listOf(record("layer-1")))),
            "future/thing.dat" to ByteArray(4),
        )

        val read = assertIs<BangniReadResult.Ok>(BangniCodec.read(ByteArrayInputStream(bytes)))

        assertTrue(read.warnings.single().contains("future/thing.dat"))
    }

    @Test
    fun `a document with no layers is refused`() {
        val failed = assertIs<BangniReadResult.Failed>(
            roundTrip(BangniDocument(manifest(layers = emptyList()), emptyMap())),
        )

        assertTrue(failed.message.contains("no layers"), failed.message)
    }

    @Test
    fun `tiles are stored, not deflated a second time`() {
        val bytes = ByteArrayOutputStream().also { out ->
            BangniCodec.write(
                out,
                BangniDocument(
                    manifest(layers = listOf(record("layer-1"))),
                    mapOf(LayerId("layer-1") to mapOf(TileKey(0, 0) to tile(8))),
                ),
            )
        }.toByteArray()

        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var seen = false
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.endsWith(BangniCodec.TILE_SUFFIX)) continue

                seen = true
                assertEquals(ZipEntry.STORED, entry.method, "TileCodec already deflated the payload")
            }
            assertTrue(seen, "the file carried no tile entry")
        }
    }

    // ------------------------------------------------ the expansion budget

    @Test
    fun `the default budget is the reader's heap, not a fixed number`() {
        // A shared codec with a phone-sized constant refuses a painting on a
        // laptop that can hold it; one with a laptop-sized constant lets a
        // phone meet it as an OutOfMemoryError. The ceiling is the format's,
        // the budget is the reader's.
        val format = BangniCodec.MAX_TOTAL_BYTES
        assertEquals(
            PerfConstants.MAX_LAYERS.toLong() * TileGrid.MAX_TILES * TILE_BYTES,
            format,
            "the format ceiling must admit every document the writers can produce",
        )

        val tiny = BangniCodec.Limits.forHeap(64L * 1024 * 1024)
        val huge = BangniCodec.Limits.forHeap(64L * 1024 * 1024 * 1024)

        assertEquals(BangniCodec.Limits.MIN_TOTAL_BYTES, tiny.maxTotalBytes)
        assertEquals(format, huge.maxTotalBytes)
        assertTrue(BangniCodec.Limits.DEFAULT.maxTotalBytes in BangniCodec.Limits.MIN_TOTAL_BYTES..format)
    }


    @Test
    fun `a tile is charged the size it decodes to, not the size it arrives as`() {
        // Six empty tiles. Compressed they are a few hundred bytes each, so a
        // budget counting arrival bytes would let all six through; decoded
        // they are TILE_BYTES each, and the budget stops at four.
        val entries = (0 until 6).map { index ->
            "layers/layer-1/${index}_0.tile" to TileCodec.encode(ByteArray(TILE_BYTES))
        }
        val zip = zipOf(
            BangniCodec.MANIFEST_ENTRY to json(manifest(listOf(record("layer-1")))),
            *entries.toTypedArray(),
        )
        val compressed = entries.sumOf { it.second.size }
        assertTrue(compressed < 4L * TILE_BYTES, "an empty tile must compress well for this to bite")

        val failed = assertIs<BangniReadResult.Failed>(
            BangniCodec.read(
                ByteArrayInputStream(zip),
                BangniCodec.Limits(maxTotalBytes = 4L * TILE_BYTES),
            ),
        )
        // The total budget tripped, and the message says so.
        assertTrue(failed.message.startsWith("the file expands past"), failed.message)
    }

    @Test
    fun `a tile is charged once, not its decoded and its stored size both`() {
        // Four tiles against a four-tile budget: charging the compressed
        // bytes as well would put it over and refuse a file that fits.
        val entries = (0 until 4).map { index ->
            "layers/layer-1/${index}_0.tile" to TileCodec.encode(ByteArray(TILE_BYTES))
        }
        val zip = zipOf(
            BangniCodec.MANIFEST_ENTRY to json(manifest(listOf(record("layer-1")))),
            *entries.toTypedArray(),
        )

        val read = BangniCodec.read(
            ByteArrayInputStream(zip),
            BangniCodec.Limits(maxTotalBytes = 4L * TILE_BYTES),
        )

        assertIs<BangniReadResult.Ok>(read, "four tiles must fit a four-tile budget")
    }

    @Test
    fun `one entry cannot expand past its own ceiling`() {
        // Deflated, so the payload that arrives is far smaller than what it
        // becomes: the per-entry cap is what stops the reader accumulating
        // the whole remaining budget in one buffer before refusing.
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(BangniCodec.MANIFEST_ENTRY))
                zip.write(ByteArray(512 * 1024))
                zip.closeEntry()
            }
        }.toByteArray()

        val failed = assertIs<BangniReadResult.Failed>(
            BangniCodec.read(
                ByteArrayInputStream(bomb),
                BangniCodec.Limits(maxTotalBytes = 64L * 1024 * 1024, maxEntryBytes = 4096),
            ),
        )
        // The per-entry cap tripped, not the total — naming the wrong one
        // sends the next reader hunting a budget problem that is not there.
        assertTrue(failed.message.startsWith("an entry expands past"), failed.message)
        assertTrue(bomb.size < 4096, "the bomb must arrive smaller than the cap it trips")
    }

    @Test
    fun `warnings stop growing however many junk entries arrive`() {
        val junk = (0 until 40).map { "extra/$it.bin" to ByteArray(1) }
        val read = assertIs<BangniReadResult.Ok>(
            BangniCodec.read(
                ByteArrayInputStream(
                    zipOf(
                        BangniCodec.MANIFEST_ENTRY to json(manifest(listOf(record("layer-1")))),
                        *junk.toTypedArray(),
                    ),
                ),
                BangniCodec.Limits(maxWarnings = 5),
            ),
        )
        assertEquals(5, read.warnings.size)
    }

    private fun roundTrip(document: BangniDocument): BangniReadResult {
        val bytes = ByteArrayOutputStream().also { BangniCodec.write(it, document) }.toByteArray()
        return BangniCodec.read(ByteArrayInputStream(bytes))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for ((name, bytes) in entries) {
                    val entry = ZipEntry(name)
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun json(manifest: BangniManifest): ByteArray =
        kotlinx.serialization.json.Json.encodeToString(BangniManifest.serializer(), manifest).toByteArray()

    private fun manifest(
        layers: List<LayerRecord>,
        activeLayerId: String = layers.firstOrNull()?.id.orEmpty(),
        nextLayerName: Int = layers.size + 1,
    ) = BangniManifest(
        title = "Sketch",
        width = CANVAS_EDGE,
        height = CANVAS_EDGE,
        layers = layers,
        activeLayerId = activeLayerId,
        nextLayerName = nextLayerName,
    )

    private fun record(id: String, opacity: Float = 1f, blend: String = BlendMode.NORMAL.name) =
        LayerProps(LayerId(id), id).toRecord().copy(opacity = opacity, blend = blend)

    private fun tile(seed: Int) = ByteArray(TILE_BYTES) { index -> ((index + seed) % 251).toByte() }

    private companion object {
        const val CANVAS_EDGE = 512
    }
}
