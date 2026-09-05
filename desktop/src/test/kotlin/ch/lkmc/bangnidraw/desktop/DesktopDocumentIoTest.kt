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
        // Through markClean(), because `dirty` has a private setter: the
        // edits counter only guards a save while every edit goes through
        // noteEdited(), so a bare assignment anywhere is a compile error.
        assertTrue(write.contains("document.markClean()"))
        assertTrue(write.contains("is DesktopSaveResult.Failed"))
    }

    /** Source with every run of whitespace collapsed, so formatting cannot break a claim. */
    private fun collapsed(source: String): String = source.replace(Regex("\\s+"), " ")

    @Test
    fun `an in-flight save does not discard edits made while it runs`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
        val write = collapsed(main.substringAfter("private fun writeTo(").substringBefore("\n}"))

        // The canvas is live for the whole asynchronous write, so a stroke
        // made while it runs is not in the file. Clearing `dirty` for it
        // would lose it quietly; closing the window too would lose it for
        // good, which is what the close-on-completion above would otherwise
        // have turned this into.
        assertTrue("val editsAtStart = document.edits" in write, write)
        assertTrue("val edited = document.edits != editsAtStart" in write, write)
        assertTrue("if (!edited) document.markClean()" in write, write)
        assertTrue("if (!edited) onSaved()" in write, write)

        // And the invariant that makes the counter mean anything: `dirty`
        // cannot be assigned from outside the two functions that pair it
        // with `edits`.
        val documents =
            source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopDocuments.kt")
        assertTrue(collapsed(documents).contains("var dirty by mutableStateOf(false) private set"))
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
        // Anchored at each callback rather than sliced by a delimiter: with
        // whitespace collapsed, any plausible delimiter also occurs inside
        // the prose around the code.
        val collapsed = collapsed(engine)
        for (callback in listOf("onFrame", "onStack", "onPaper", "onEdited")) {
            val marks = Regex(
                Regex.escape(callback) +
                    """ = \{[^{}]*java\.awt\.EventQueue\.invokeLater""",
            )
            assertTrue(
                marks.containsMatchIn(collapsed),
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
        // Whitespace-normalized: an assertion that a reformat can break is
        // one that gets deleted rather than fixed.
        assertTrue(collapsed(documents).contains("} catch (_: java.io.IOException) { absoluteFile"))
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
