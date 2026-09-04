package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.ReferenceTransform
import ch.lkmc.bangnidraw.engine.core.ReferenceVisibility
import ch.lkmc.bangnidraw.engine.core.TracingReference
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The cross-platform promise: what a phone writes, another machine reads.
 * The container is [ch.lkmc.bangnidraw.data.shared.BangniCodec], which both
 * products compile; this pins the Android half's conversion to and from a
 * project folder.
 */
class BangniProjectIoTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a painting survives export and import with its layers and pixels`() {
        val source = temp.newFolder("source")
        val lower = tile(1)
        val upper = tile(2)
        val document = document(source, mapOf(TileKey(0, 0) to lower), mapOf(TileKey(1, 1) to upper))

        val bytes = ByteArrayOutputStream().also { out ->
            BangniProjectIo.export(document, out, { File(source, it.value) })
        }.toByteArray()

        val target = temp.newFolder("target")
        var minted = 0
        val result = BangniProjectIo.import(
            input = ByteArrayInputStream(bytes),
            id = "imported",
            newLayerId = { LayerId("fresh-${minted++}") },
            layerDirFor = { File(target, it.value) },
        )

        val imported = result as BangniProjectIo.ImportResult.Imported
        assertEquals("imported", imported.document.id)
        assertEquals("Sketch", imported.document.title)
        assertEquals(CANVAS, imported.document.width)
        assertEquals(PAPER, imported.document.paperColor)
        assertEquals(2, imported.document.stack.size)
        // Props travel; only the ids are re-minted.
        assertEquals("Ink", imported.document.stack.layers[1].props.name)
        assertEquals(0.4f, imported.document.stack.layers[1].props.opacity)
        assertEquals(BlendMode.MULTIPLY, imported.document.stack.layers[1].props.blendMode)
        assertEquals(1, imported.document.stack.activeIndex)
        assertTrue(imported.warnings.isEmpty())

        assertArrayEquals(lower, read(target, imported.document.stack.layers[0].id, TileKey(0, 0)))
        assertArrayEquals(upper, read(target, imported.document.stack.layers[1].id, TileKey(1, 1)))
    }

    @Test
    fun `importing the same file twice mints separate layer directories`() {
        val source = temp.newFolder("source2")
        val document = document(source, mapOf(TileKey(0, 0) to tile(3)), emptyMap())
        val bytes = ByteArrayOutputStream().also { out ->
            BangniProjectIo.export(document, out, { File(source, it.value) })
        }.toByteArray()

        val first = importInto(temp.newFolder("first"), bytes, "a")
        val second = importInto(temp.newFolder("second"), bytes, "b")

        // Two devices can hold the same painting; importing it twice must not
        // produce two projects sharing a layer directory name.
        assertNotEquals(
            first.document.stack.layers.map { it.id },
            second.document.stack.layers.map { it.id },
        )
    }

    @Test
    fun `the layer-name counter never falls below the layer count`() {
        val source = temp.newFolder("source3")
        val document = document(source, emptyMap(), emptyMap())
        val bytes = ByteArrayOutputStream().also { out ->
            BangniProjectIo.export(document, out, { File(source, it.value) })
        }.toByteArray()

        val imported = importInto(temp.newFolder("third"), bytes, "c")

        assertTrue(imported.document.stack.nextName > imported.document.stack.size)
    }

    @Test
    fun `a file that is not a bangni document fails without throwing`() {
        val result = BangniProjectIo.import(
            input = ByteArrayInputStream("nonsense".toByteArray()),
            id = "x",
            newLayerId = { LayerId("l") },
            layerDirFor = { temp.newFolder("unused-${it.value}") },
        )

        assertTrue(result is BangniProjectIo.ImportResult.Failed)
    }

    @Test
    fun `the tracing image travels with the painting`() {
        val source = temp.newFolder("reference-source")
        val png = byteArrayOf(1, 2, 3, 4, 5)
        val document = document(source, emptyMap(), emptyMap()).copy(
            tracingReference = TracingReference(
                assetName = "reference.png",
                imageWidth = 320,
                imageHeight = 200,
                transform = ReferenceTransform.fit(320, 200, CANVAS, CANVAS),
                opacity = 0.25f,
                visibility = ReferenceVisibility.HIDDEN,
            ),
        )

        val bytes = ByteArrayOutputStream().also { out ->
            BangniProjectIo.export(document, out, { File(source, it.value) }, { png })
        }.toByteArray()

        val referenceTarget = temp.newFolder("reference-target")
        val staged = HashMap<String, ByteArray>()
        var minted = 0
        val imported = BangniProjectIo.import(
            input = ByteArrayInputStream(bytes),
            id = "with-reference",
            newLayerId = { LayerId("fresh-${minted++}") },
            layerDirFor = { File(referenceTarget, it.value) },
            writeReferenceAsset = { name, data -> staged[name] = data },
        ) as BangniProjectIo.ImportResult.Imported

        val reference = requireNotNull(imported.document.tracingReference)
        assertEquals("reference.png", reference.assetName)
        assertEquals(320, reference.imageWidth)
        assertEquals(0.25f, reference.opacity)
        assertEquals(ReferenceVisibility.HIDDEN, reference.visibility)
        assertArrayEquals(png, staged["reference.png"])
    }

    @Test
    fun `a placement with no pixels behind it is not exported`() {
        val source = temp.newFolder("no-asset-source")
        val document = document(source, emptyMap(), emptyMap()).copy(
            tracingReference = TracingReference(
                assetName = "reference.png",
                imageWidth = 8,
                imageHeight = 8,
                transform = ReferenceTransform.IDENTITY,
            ),
        )

        // The asset is gone from disk; the record must not go out alone, or
        // the other end opens a reference it can never draw.
        val bytes = ByteArrayOutputStream().also { out ->
            BangniProjectIo.export(document, out, { File(source, it.value) }, { null })
        }.toByteArray()

        val noAssetTarget = temp.newFolder("no-asset-target")
        var minted = 0
        val imported = BangniProjectIo.import(
            input = ByteArrayInputStream(bytes),
            id = "no-reference",
            newLayerId = { LayerId("fresh-${minted++}") },
            layerDirFor = { File(noAssetTarget, it.value) },
            writeReferenceAsset = { _, _ -> fail("nothing should have been staged") },
        ) as BangniProjectIo.ImportResult.Imported

        assertNull(imported.document.tracingReference)
    }

    @Test
    fun `an import with nowhere to stage the image drops it and warns`() {
        val source = temp.newFolder("unstaged-source")
        val document = document(source, emptyMap(), emptyMap()).copy(
            tracingReference = TracingReference(
                assetName = "reference.png",
                imageWidth = 8,
                imageHeight = 8,
                transform = ReferenceTransform.IDENTITY,
            ),
        )
        val bytes = ByteArrayOutputStream().also { out ->
            BangniProjectIo.export(document, out, { File(source, it.value) }, { byteArrayOf(9) })
        }.toByteArray()

        val unstagedTarget = temp.newFolder("unstaged-target")
        var minted = 0
        val imported = BangniProjectIo.import(
            input = ByteArrayInputStream(bytes),
            id = "unstaged",
            newLayerId = { LayerId("fresh-${minted++}") },
            layerDirFor = { File(unstagedTarget, it.value) },
        ) as BangniProjectIo.ImportResult.Imported

        assertNull(imported.document.tracingReference)
        assertTrue(imported.warnings.any { "tracing image" in it })
    }

    private fun importInto(dir: File, bytes: ByteArray, salt: String): BangniProjectIo.ImportResult.Imported {
        var minted = 0
        val result = BangniProjectIo.import(
            input = ByteArrayInputStream(bytes),
            id = "project-$salt",
            newLayerId = { LayerId("$salt-${minted++}") },
            layerDirFor = { File(dir, it.value) },
        )
        return result as BangniProjectIo.ImportResult.Imported
    }

    private fun document(
        dir: File,
        lowerTiles: Map<TileKey, ByteArray>,
        upperTiles: Map<TileKey, ByteArray>,
    ): Document {
        val lower = LayerProps(LayerId("layer-a"), "Paper")
        val upper = LayerProps(
            LayerId("layer-b"),
            "Ink",
            opacity = 0.4f,
            blendMode = BlendMode.MULTIPLY,
        )
        for ((id, tiles) in listOf(lower.id to lowerTiles, upper.id to upperTiles)) {
            val store = TileStore(File(dir, id.value))
            for ((key, pixels) in tiles) store.write(key, pixels)
        }
        return Document(
            id = "source",
            title = "Sketch",
            width = CANVAS,
            height = CANVAS,
            paperColor = PAPER,
            stack = LayerStack(
                layers = listOf(Layer(lower, lowerTiles.keys), Layer(upper, upperTiles.keys)),
                activeIndex = 1,
                nextName = 3,
            ),
        )
    }

    private fun read(dir: File, layer: LayerId, key: TileKey): ByteArray =
        (TileStore(File(dir, layer.value)).read(key) as TileStore.Read.Pixels).pixels

    private fun tile(seed: Int) = ByteArray(TILE_BYTES) { index -> ((index * 7 + seed) % 251).toByte() }

    private companion object {
        const val CANVAS = 512
        const val PAPER = 0xFF102030.toInt()
    }
}
