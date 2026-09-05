package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.Composite
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopDocumentIoTest {

    @Test
    fun `a PNG opens as an image of its own size`() {
        val file = write(BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB))

        val opened = assertIs<DesktopImageResult.Opened>(DesktopImageIo.read(file))

        assertEquals(TILE_SIZE, opened.image.width)
        assertEquals(TILE_SIZE, opened.image.height)
    }

    @Test
    fun `a picture outside the canvas bounds is refused, not clamped`() {
        val tiny = write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB))

        val failed = assertIs<DesktopImageResult.Failed>(DesktopImageIo.read(tiny))

        assertContains(failed.message, Document.MIN_EDGE.toString())
    }

    @Test
    fun `a file that is not an image fails without throwing`() {
        val text = Files.createTempFile("bangnidraw-open", ".png").toFile()
        text.writeText("not a picture")

        val failed = assertIs<DesktopImageResult.Failed>(DesktopImageIo.read(text))

        assertTrue(failed.message.isNotBlank())
    }

    @Test
    fun `a missing file fails without throwing`() {
        val missing = File(Files.createTempDirectory("bangnidraw-open").toFile(), "gone.png")

        assertIs<DesktopImageResult.Failed>(DesktopImageIo.read(missing))
    }

    @Test
    fun `tiles are premultiplied RGBA, and empty ones are dropped`() {
        val image = BufferedImage(TILE_SIZE * 2, TILE_SIZE, BufferedImage.TYPE_INT_ARGB)
        // Straight ARGB: half-alpha pure red.
        image.setRGB(0, 0, 0x80FF0000.toInt())

        val tiles = DesktopImageIo.tiles(
            DesktopImage(image.width, image.height, image.rgbArray()),
        )

        // Only the tile that has a pixel: an all-transparent one would cost a
        // GPU slice and a mirror entry for nothing.
        assertEquals(setOf(TileKey(0, 0)), tiles.keys)
        val bytes = tiles.getValue(TileKey(0, 0))
        val premultiplied = Composite.premultiply(0x80FF0000.toInt())
        assertEquals(Composite.red(premultiplied), bytes[0].toInt() and 0xFF)
        assertEquals(Composite.green(premultiplied), bytes[1].toInt() and 0xFF)
        assertEquals(Composite.blue(premultiplied), bytes[2].toInt() and 0xFF)
        assertEquals(Composite.alpha(premultiplied), bytes[3].toInt() and 0xFF)
        assertEquals(TILE_SIZE * TILE_SIZE * 4, bytes.size)
    }

    @Test
    fun `a picture that does not fill its last tile leaves the rest transparent`() {
        val width = TILE_SIZE + 1
        val image = BufferedImage(width, TILE_SIZE, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(TILE_SIZE, 0, 0xFF00FF00.toInt())

        val tiles = DesktopImageIo.tiles(DesktopImage(width, TILE_SIZE, image.rgbArray()))

        val edge = tiles.getValue(TileKey(1, 0))
        // The one real column is opaque green; the next is outside the canvas.
        assertEquals(0xFF, edge[3].toInt() and 0xFF)
        assertEquals(0, edge[7].toInt() and 0xFF)
    }

    // ------------------------------------------------------------ contract

    @Test
    fun `closing a document asks before losing unsaved work`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(main.contains("private fun requestClose(document: DesktopDocument"))
        assertTrue(main.contains("if (document.dirty) {"))
        assertTrue(main.contains("document.confirmingClose = true"))
        assertTrue(main.contains("UnsavedChangesDialog("))
    }

    @Test
    fun `a save clears the dirty mark and adopts the file`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
        val write = main.substringAfter("private fun writeTo(").substringBefore("\n}")

        // Both, and only on success: a failed save must not claim the file.
        assertTrue(write.contains("document.file = file"))
        assertTrue(write.contains("document.dirty = false"))
        assertTrue(write.contains("is DesktopSaveResult.Failed"))
    }

    @Test
    fun `saving from the close prompt closes the window when the write lands`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
        val dialog = main.substringAfter("UnsavedChangesDialog(").substringBefore("onDiscard")

        // `writeTo` completes on a later EDT event, so a `document.dirty`
        // read on the line after it reads the value from before the save --
        // every time, since the prompt only appears when dirty. The close has
        // to travel with the completion instead.
        assertTrue("onSaved = { documents.close(document) }" in dialog, dialog)
        assertTrue("saveAs(window, document) { documents.close(document) }" in dialog, dialog)
        assertTrue("if (!document.dirty) documents.close(document)" !in main)

        // And it runs only where the write succeeded.
        val write = main.substringAfter("private fun writeTo(").substringBefore("\n}")
        val saved = write.substringAfter("is DesktopSaveResult.Saved").substringBefore("is DesktopSaveResult.Failed")
        assertTrue("onSaved()" in saved, saved)
    }

    @Test
    fun `the stack and paper reach Compose on the event thread`() {
        val documents =
            source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopDocuments.kt")
        val engine = documents.substringAfter("val engine = DesktopEngine(").substringBefore("document = DesktopDocument(")

        // The GL thread publishes all four of these, from the same call
        // sites: `onEdited` sits one line below `onPaper` in DesktopEngine.
        // Compose state written from there races the panels reading it.
        for (callback in listOf("onFrame", "onStack", "onPaper", "onEdited")) {
            val body = engine.substringAfter("$callback = ").substringBefore("\n            on")
            assertTrue(
                "java.awt.EventQueue.invokeLater" in body,
                "$callback publishes Compose state off the event thread",
            )
        }
    }

    @Test
    fun `reopening an open file does not open it a second time`() {
        val documents =
            source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopDocuments.kt")

        // Two documents on one path would overwrite each other on save, and
        // the comparison has to resolve the path first: a symlink or a `..`
        // reaching the same file must not read as a different one.
        assertTrue(documents.contains("val key = file.canonicalOrAbsolute()"))
        assertTrue(documents.contains("open.firstOrNull { it.file?.canonicalOrAbsolute() == key }"))
        // A path that cannot be canonicalized still has to compare as
        // something, or an unreadable parent lets the file open twice.
        assertTrue(documents.contains("} catch (_: java.io.IOException) {\n            absoluteFile"))
    }

    @Test
    fun `the last window closing exits the application`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(main.contains("if (documents.open.isEmpty()) exitApplication()"))
        // The first document is created during composition, not in an effect:
        // the rule above reads the list while composing, and an empty first
        // frame would exit before anything opened.
        assertTrue(main.contains("DesktopDocuments(ready.memory, host, catalogue, mixer, prefs).apply {"))
    }

    private fun write(image: BufferedImage): File {
        val file = Files.createTempDirectory("bangnidraw-open").resolve("picture.png").toFile()
        javax.imageio.ImageIO.write(image, DesktopImageIo.EXTENSION, file)
        return file
    }

    private fun BufferedImage.rgbArray(): IntArray {
        val out = IntArray(width * height)
        getRGB(0, 0, width, height, out, 0, width)
        return out
    }

    private fun source(path: String): String = File(repoRoot(), path).readText(Charsets.UTF_8)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }
}
