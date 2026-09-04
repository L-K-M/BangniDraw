package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class DesktopPngTest {

    @Test
    fun `a transparent tile leaves the paper alone`() {
        val image = DesktopPng.compose(oneLayer(transparentTile(), OPAQUE_WHITE))

        assertEquals(OPAQUE_WHITE, image.getRGB(0, 0))
    }

    @Test
    fun `a half-covering premultiplied pixel composites over the paper`() {
        // Premultiplied red at 50 %: R and A both 0x80.
        val tile = transparentTile()
        tile[0] = HALF
        tile[3] = HALF

        val image = DesktopPng.compose(oneLayer(tile, OPAQUE_WHITE))

        assertEquals(0xFFFF7F7F.toInt(), image.getRGB(0, 0))
    }

    @Test
    fun `an opaque pixel reaches the file unchanged`() {
        val tile = transparentTile()
        tile[2] = 0xFF.toByte()
        tile[3] = 0xFF.toByte()

        val image = DesktopPng.compose(oneLayer(tile, OPAQUE_WHITE))

        assertEquals(0xFF0000FF.toInt(), image.getRGB(0, 0))
    }

    @Test
    fun `transparent paper exports transparent pixels, unpremultiplied`() {
        val tile = transparentTile()
        // Premultiplied half-red again: straight ARGB recovers full red.
        tile[0] = HALF
        tile[3] = HALF

        val image = DesktopPng.compose(oneLayer(tile, 0x00000000))

        assertEquals(0x00000000, image.getRGB(1, 0))
        assertEquals(0x80FF0000.toInt(), image.getRGB(0, 0))
    }

    @Test
    fun `a hidden layer never reaches the file`() {
        val tile = transparentTile()
        tile[2] = 0xFF.toByte()
        tile[3] = 0xFF.toByte()
        val id = LayerId("layer-1")
        val stack = LayerStack(
            listOf(Layer(LayerProps(id, "Layer 1", visible = false), setOf(TileKey(0, 0)))),
            activeIndex = 0,
            nextName = 2,
        )

        val snapshot = DesktopPng.snapshot(
            width = TILE_EDGE,
            height = TILE_EDGE,
            paperArgb = OPAQUE_WHITE,
            stack = stack,
            mirror = mapOf(id to mapOf(TileKey(0, 0) to tile)),
        )

        assertEquals(OPAQUE_WHITE, DesktopPng.compose(snapshot).getRGB(0, 0))
    }

    @Test
    fun `layer opacity and blend mode go through the shared compositor`() {
        val tile = transparentTile()
        tile[2] = 0xFF.toByte()
        tile[3] = 0xFF.toByte()
        val id = LayerId("layer-1")
        val stack = LayerStack(
            listOf(
                Layer(
                    LayerProps(id, "Layer 1", opacity = 0.5f, blendMode = BlendMode.MULTIPLY),
                    setOf(TileKey(0, 0)),
                ),
            ),
            activeIndex = 0,
            nextName = 2,
        )

        val image = DesktopPng.compose(
            DesktopPng.snapshot(
                TILE_EDGE,
                TILE_EDGE,
                OPAQUE_WHITE,
                stack,
                mapOf(id to mapOf(TileKey(0, 0) to tile)),
            ),
        )

        // Blue multiplied into white at 50 %: red and green halve, blue stays.
        // 127.5 rounds up — Composite quantizes to nearest, never truncates.
        assertEquals(0xFF8080FF.toInt(), image.getRGB(0, 0))
    }

    @Test
    fun `snapshot composition places an edge tile over opaque paper`() {
        val tile = transparentTile()
        tile[0] = HALF
        tile[3] = HALF
        val id = LayerId("layer-1")
        val key = TileKey(1, 0)
        val stack = LayerStack(
            listOf(Layer(LayerProps(id, "Layer 1"), setOf(key))),
            activeIndex = 0,
            nextName = 2,
        )

        val image = DesktopPng.compose(
            DesktopPng.snapshot(
                width = TILE_EDGE + 1,
                height = TILE_EDGE,
                paperArgb = OPAQUE_WHITE,
                stack = stack,
                mirror = mapOf(id to mapOf(key to tile)),
            ),
        )

        assertEquals(OPAQUE_WHITE, image.getRGB(TILE_EDGE - 1, 0))
        assertEquals(0xFFFF7F7F.toInt(), image.getRGB(TILE_EDGE, 0))
    }

    @Test
    fun `composition rejects a degenerate canvas`() {
        val snapshot = DesktopExportSnapshot(
            width = 0,
            height = 1,
            paperArgb = OPAQUE_WHITE,
            layers = emptyList(),
            tiles = emptyMap(),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            DesktopPng.compose(snapshot)
        }

        assertEquals("export dimensions must be positive", failure.message)
    }

    @Test
    fun `PNG write reports success`() {
        val file = Files.createTempDirectory("bangnidraw-png")
            .resolve("drawing.png")
            .toFile()

        val result = DesktopPng.write(onePixelImage(), file)

        assertIs<DesktopSaveResult.Saved>(result)
        assertEquals(file.absolutePath, result.path)
    }

    @Test
    fun `interrupted PNG write does not publish a partial file`() {
        val file = Files.createTempDirectory("bangnidraw-png-interrupted")
            .resolve("drawing.png")
            .toFile()

        Thread.currentThread().interrupt()
        val result = try {
            DesktopPng.write(onePixelImage(), file)
        } finally {
            Thread.interrupted()
        }

        assertIs<DesktopSaveResult.Failed>(result)
        assertFalse(file.exists())
    }

    @Test
    fun `PNG write reports failure without throwing`() {
        val directory = Files.createTempDirectory("bangnidraw-png-directory").toFile()

        val result = DesktopPng.write(onePixelImage(), directory)

        assertIs<DesktopSaveResult.Failed>(result)
    }

    @Test
    fun `snapshot freezes tile versions without copying their immutable bytes`() {
        val key = TileKey(0, 0)
        val id = LayerId("layer-1")
        val capturedBytes = ByteArray(TILE_BYTES) { 1 }
        val liveTiles = mutableMapOf(key to capturedBytes)
        val mirror = mutableMapOf(id to liveTiles)
        val stack = LayerStack(
            listOf(Layer(LayerProps(id, "Layer 1"), setOf(key))),
            activeIndex = 0,
            nextName = 2,
        )

        val snapshot = DesktopPng.snapshot(
            width = TILE_EDGE,
            height = TILE_EDGE,
            paperArgb = OPAQUE_WHITE,
            stack = stack,
            mirror = mirror,
        )
        liveTiles[key] = ByteArray(TILE_BYTES) { 2 }

        assertSame(capturedBytes, snapshot.tiles.getValue(id)[key])
    }

    @Test
    fun `export reports composition failure without throwing`() {
        val invalid = DesktopExportSnapshot(
            width = 0,
            height = 1,
            paperArgb = OPAQUE_WHITE,
            layers = emptyList(),
            tiles = emptyMap(),
        )
        val file = Files.createTempDirectory("bangnidraw-png-failure")
            .resolve("drawing.png")
            .toFile()

        val result = DesktopPng.export(invalid, file)

        assertIs<DesktopSaveResult.Failed>(result)
    }

    private fun onePixelImage(): BufferedImage =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

    private fun transparentTile(): ByteArray = ByteArray(TILE_BYTES)

    /** A one-tile canvas whose one visible layer holds [tile] at the origin. */
    private fun oneLayer(tile: ByteArray, paperArgb: Int): DesktopExportSnapshot {
        val id = LayerId("layer-1")
        val key = TileKey(0, 0)
        return DesktopPng.snapshot(
            width = TILE_EDGE,
            height = TILE_EDGE,
            paperArgb = paperArgb,
            stack = LayerStack(
                listOf(Layer(LayerProps(id, "Layer 1"), setOf(key))),
                activeIndex = 0,
                nextName = 2,
            ),
            mirror = mapOf(id to mapOf(key to tile)),
        )
    }

    private companion object {
        const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
        const val TILE_EDGE = 256
        const val TILE_BYTES = TILE_EDGE * TILE_EDGE * 4
        const val HALF = 0x80.toByte()
    }
}
