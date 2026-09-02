package ch.lkmc.bangnidraw.desktop

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
    fun `transparent premultiplied pixel leaves opaque paper`() {
        assertEquals(OPAQUE_WHITE, DesktopPng.sourceOver(0x00000000, OPAQUE_WHITE))
    }

    @Test
    fun `partial premultiplied pixel produces opaque output`() {
        val halfRedPremultiplied = 0x80800000.toInt()

        assertEquals(0xFFFF7F7F.toInt(), DesktopPng.sourceOver(halfRedPremultiplied, OPAQUE_WHITE))
    }

    @Test
    fun `opaque pixel remains unchanged`() {
        val opaqueBlue = 0xFF0000FF.toInt()

        assertEquals(opaqueBlue, DesktopPng.sourceOver(opaqueBlue, OPAQUE_WHITE))
    }

    @Test
    fun `snapshot composition places an edge tile over opaque paper`() {
        val tile = ByteArray(TILE_BYTES)
        tile[0] = 0x80.toByte()
        tile[3] = 0x80.toByte()
        val snapshot = DesktopExportSnapshot(
            width = TILE_EDGE + 1,
            height = TILE_EDGE,
            paperArgb = OPAQUE_WHITE,
            tiles = mapOf(TileKey(1, 0) to tile),
        )

        val image = DesktopPng.compose(snapshot)

        assertEquals(OPAQUE_WHITE, image.getRGB(TILE_EDGE - 1, 0))
        assertEquals(0xFFFF7F7F.toInt(), image.getRGB(TILE_EDGE, 0))
    }

    @Test
    fun `composition rejects translucent paper`() {
        val snapshot = DesktopExportSnapshot(
            width = 1,
            height = 1,
            paperArgb = 0x80FFFFFF.toInt(),
            tiles = emptyMap(),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            DesktopPng.compose(snapshot)
        }

        assertEquals("paper color must be opaque", failure.message)
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
        val capturedBytes = ByteArray(TILE_BYTES) { 1 }
        val liveTiles = mutableMapOf(key to capturedBytes)

        val snapshot = DesktopPng.snapshot(
            width = TILE_EDGE,
            height = TILE_EDGE,
            paperArgb = OPAQUE_WHITE,
            tiles = liveTiles,
        )
        liveTiles[key] = ByteArray(TILE_BYTES) { 2 }

        assertSame(capturedBytes, snapshot.tiles[key])
    }

    @Test
    fun `export reports composition failure without throwing`() {
        val invalid = DesktopExportSnapshot(
            width = 0,
            height = 1,
            paperArgb = OPAQUE_WHITE,
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

    private companion object {
        const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
        const val TILE_EDGE = 256
        const val TILE_BYTES = TILE_EDGE * TILE_EDGE * 4
    }
}
