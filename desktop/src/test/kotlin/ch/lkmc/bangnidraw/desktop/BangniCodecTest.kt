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
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
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
