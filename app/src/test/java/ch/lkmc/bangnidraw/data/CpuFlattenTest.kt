package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.ReferenceTransform
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TracingReference
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 4's "PNG bytes from Composite flatten of a fixture"
 * (`docs/plan/12-roadmap.md`): the RGBA half, against real tile files on a
 * temp dir. The PNG encode itself is a platform `Bitmap` call and is
 * device-gated with the rest of `GalleryExporter`.
 */
class CpuFlattenTest {

    private val root = createTempDirectory("bangni-flatten").toFile()
    private val a = LayerId("layer-a")
    private val b = LayerId("layer-b")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun layerDir(layer: LayerId): File = File(root, layer.value)

    /** One 512×512 document (2×2 tiles); tile sets from what tests write. */
    private fun document(layers: List<Layer>, paper: Int): Document = Document(
        id = "flat",
        width = 512,
        height = 512,
        paperColor = paper,
        stack = LayerStack(layers = layers, activeIndex = 0, nextName = 2),
    )

    /** A full tile of one premultiplied RGBA colour. */
    private fun tileOf(r: Int, g: Int, b: Int, alpha: Int): ByteArray {
        val bytes = ByteArray(TILE_BYTES)
        var i = 0
        while (i < bytes.size) {
            bytes[i] = r.toByte(); bytes[i + 1] = g.toByte()
            bytes[i + 2] = b.toByte(); bytes[i + 3] = alpha.toByte()
            i += 4
        }
        return bytes
    }

    private fun pixelAt(out: ByteArray, x: Int, y: Int, width: Int = 512): List<Int> {
        val o = (y * width + x) * 4
        return listOf(
            out[o].toInt() and 0xFF, out[o + 1].toInt() and 0xFF,
            out[o + 2].toInt() and 0xFF, out[o + 3].toInt() and 0xFF,
        )
    }

    @Test
    fun `bare paper flattens to the paper colour, premultiplied`() {
        val doc = document(
            layers = listOf(Layer(LayerProps(id = a, name = "n"))),
            paper = 0xFF102030.toInt(),
        )
        val out = CpuFlatten.flatten(doc) { layerDir(it) }
        assertEquals(512 * 512 * 4, out.size)
        assertEquals(listOf(0x10, 0x20, 0x30, 0xFF), pixelAt(out, 0, 0))
        assertEquals(listOf(0x10, 0x20, 0x30, 0xFF), pixelAt(out, 511, 511))
    }

    @Test
    fun `an opaque tile covers the paper exactly where it exists`() {
        TileStore(layerDir(a)).write(TileKey(1, 0), tileOf(0xAA, 0x00, 0x00, 0xFF))
        val doc = document(
            layers = listOf(Layer(LayerProps(id = a, name = "n"), setOf(TileKey(1, 0)))),
            paper = -1,
        )
        val out = CpuFlatten.flatten(doc) { layerDir(it) }
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF), pixelAt(out, 0, 0), "outside the tile")
        assertEquals(listOf(0xAA, 0x00, 0x00, 0xFF), pixelAt(out, 256, 0), "inside")
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF), pixelAt(out, 256, 256), "below the tile")
    }

    @Test
    fun `transparent paper keeps alpha instead of matting`() {
        // §9.1: a user who chose transparent paper wants a transparent PNG.
        TileStore(layerDir(a)).write(TileKey(0, 0), tileOf(0x40, 0x40, 0x00, 0x80))
        val doc = document(
            layers = listOf(Layer(LayerProps(id = a, name = "n"), setOf(TileKey(0, 0)))),
            paper = 0x00000000,
        )
        val out = CpuFlatten.flatten(doc) { layerDir(it) }
        assertEquals(listOf(0x40, 0x40, 0x00, 0x80), pixelAt(out, 10, 10))
        assertEquals(listOf(0, 0, 0, 0), pixelAt(out, 300, 300), "no paper, no matting")
    }

    @Test
    fun `hidden layers and corrupt tiles contribute nothing`() {
        val visible = TileStore(layerDir(a))
        visible.write(TileKey(0, 0), tileOf(0x00, 0x80, 0x00, 0xFF))
        // A corrupt file for a listed key of the visible layer…
        File(layerDir(a), TileStore.fileName(TileKey(1, 1))).writeBytes("junk".toByteArray())
        // …and a hidden layer that would otherwise paint everything red.
        TileStore(layerDir(b)).write(TileKey(0, 0), tileOf(0xFF, 0x00, 0x00, 0xFF))
        val doc = document(
            layers = listOf(
                Layer(
                    LayerProps(id = a, name = "n"),
                    setOf(TileKey(0, 0), TileKey(1, 1)),
                ),
                Layer(LayerProps(id = b, name = "m", visible = false), setOf(TileKey(0, 0))),
            ),
            paper = -1,
        )
        val out = CpuFlatten.flatten(doc) { layerDir(it) }
        assertEquals(listOf(0x00, 0x80, 0x00, 0xFF), pixelAt(out, 0, 0), "visible layer wins")
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF), pixelAt(out, 300, 300), "corrupt = transparent")
    }

    @Test
    fun `layer opacity is composited, not copied`() {
        // 50 % black over white paper is mid-gray — the Composite arithmetic
        // reaching the flatten, not a tile memcpy.
        TileStore(layerDir(a)).write(TileKey(0, 0), tileOf(0x00, 0x00, 0x00, 0xFF))
        val doc = document(
            layers = listOf(
                Layer(LayerProps(id = a, name = "n", opacity = 0.5f), setOf(TileKey(0, 0))),
            ),
            paper = -1,
        )
        val out = CpuFlatten.flatten(doc) { layerDir(it) }
        val (r, g, b2, alpha) = pixelAt(out, 5, 5)
        assertEquals(0xFF, alpha)
        for (c in listOf(r, g, b2)) {
            kotlin.test.assertTrue(c in 0x7F..0x81, "mid-gray, was $c")
        }
    }

    @Test
    fun `tracing reference is excluded from flatten`() {
        val base = document(
            layers = listOf(Layer(LayerProps(id = a, name = "n"))),
            paper = 0xFF102030.toInt(),
        )
        val reference = TracingReference(
            assetName = "reference.png",
            imageWidth = 100,
            imageHeight = 100,
            transform = ReferenceTransform.IDENTITY,
        )

        assertTrue(
            CpuFlatten.flatten(base) { layerDir(it) }.contentEquals(
                CpuFlatten.flatten(base.copy(tracingReference = reference)) { layerDir(it) },
            ),
        )
    }
}
