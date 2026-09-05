package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.data.shared.BangniCodec
import ch.lkmc.bangnidraw.data.shared.BangniDocument
import ch.lkmc.bangnidraw.data.shared.BangniManifest
import ch.lkmc.bangnidraw.data.shared.BangniReadResult
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The writer actually writing.
 *
 * Every other `.bangni` test drives [BangniCodec] through a byte array, so
 * nothing exercised the file path — the temp file, the fsync, the rename —
 * and a save that failed on every platform passed CI. It did: syncing a
 * descriptor a `BufferedOutputStream` had already closed throws
 * `SyncFailedException`, the catch turns that into `DesktopSaveResult.Failed`,
 * and no test noticed because no test ever called this.
 */
class DesktopBangniWriterTest {

    @Test
    fun `a save writes a file that reads back`() {
        val target = File(tempDir(), "painting.bangni")

        val result = DesktopBangniWriter.write(document(), target)

        assertIs<DesktopSaveResult.Saved>(result, "the save failed: $result")
        assertTrue(target.isFile, "no file was published")
        assertTrue(target.length() > 0, "the published file is empty")

        val read = assertIs<BangniReadResult.Ok>(BangniCodec.read(target.inputStream()))
        assertEquals(1, read.document.manifest.layers.size)
        assertContentEquals(
            tile(),
            read.document.tiles.getValue(LayerId("layer-a")).getValue(TileKey(0, 0)),
        )
    }

    @Test
    fun `a save leaves no scratch file behind`() {
        val dir = tempDir()

        DesktopBangniWriter.write(document(), File(dir, "painting.bangni"))

        assertEquals(
            listOf("painting.bangni"),
            dir.list()!!.sorted(),
            "the temp file outlived the write",
        )
    }

    @Test
    fun `saving over an existing painting replaces it whole`() {
        val target = File(tempDir(), "painting.bangni")
        target.writeBytes(ByteArray(4096) { 0x7f })

        assertIs<DesktopSaveResult.Saved>(DesktopBangniWriter.write(document(), target))

        assertIs<BangniReadResult.Ok>(BangniCodec.read(target.inputStream()))
    }

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("bangni-writer").toFile().also { it.deleteOnExit() }

    private fun document() = BangniDocument(
        manifest = BangniManifest(
            title = "Sketch",
            width = 512,
            height = 512,
            layers = listOf(LayerProps(LayerId("layer-a"), "Paper").toRecord()),
            activeLayerId = "layer-a",
            nextLayerName = 2,
        ),
        tiles = mapOf(LayerId("layer-a") to mapOf(TileKey(0, 0) to tile())),
    )

    private fun tile() = ByteArray(TILE_BYTES) { index -> ((index * 3 + 11) % 251).toByte() }
}
