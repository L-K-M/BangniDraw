package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class DesktopRenderingContractTest {

    @Test
    fun `the chrome is the shared adaptive layout, not a desktop-only one`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
        val shell = between(main, "private fun Shell(", "/** The rail hugs the hand")

        // Every rail/strip/panel dimension comes from LayoutSpec, so the two
        // products cannot drift apart on geometry.
        // The hand is a settings choice now, so the call carries it; the claim
        // is that the layout comes from LayoutSpec, not its argument count.
        assertTrue(shell.contains("DesktopChromeLayout.forWindow(widthDp, heightDp,"))
        assertTrue(shell.contains("DesktopTopStrip("))
        assertTrue(shell.contains("DesktopToolRail("))
        assertTrue(shell.contains("layout.panelInsets(widthDp, heightDp)"))
        // The old shell put the canvas in a row beside a fixed sidebar; the
        // Android chrome floats over a full-bleed canvas instead.
        assertFalse(shell.contains("SidePanel("))
    }

    @Test
    fun `an opened tracing image is placed while the renderer is built`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val init = between(engine, "private fun initializeRenderer(", "private fun uploadInitialTiles(")

        // `renderer` is assigned at the end of initializeRenderer, so a task
        // posted from the caller right after start() finds it null and drops
        // the upload silently — which is exactly what a cold start does. The
        // reference therefore lands on this path, like the layers' own
        // pixels, where the ordering is structural rather than a race.
        val place = init.indexOf("uploadInitialReference(next)")
        val publish = init.indexOf("renderer = next")
        if (place < 0) fail("the opened painting's tracing image is no longer placed here")
        if (publish < 0) fail("initializeRenderer no longer publishes the renderer")
        assertTrue(place < publish, "the tracing image is placed after the renderer is published")

        // And the shell must not push it a second time from the outside.
        val documents =
            source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopDocuments.kt")
        assertFalse(
            documents.contains("uploadReferenceTiles"),
            "the document list races the renderer with its own reference upload",
        )
    }

    @Test
    fun `an opened painting drains its readback before anything reads the mirror`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val upload = between(engine, "private fun uploadInitialTiles(", "private fun uploadInitialReference(")

        // Both saves compose from the mirror, and only finishReadback fills
        // it. Nothing else drains until the first stroke or stack edit — so
        // without this, opening a painting and saving it straight back writes
        // a blank file over the one just read.
        assertTrue(
            upload.contains("DesktopReadbackPolicy.drain(renderer::finishReadback)"),
            "an opened painting can be saved from an empty mirror",
        )
        // Drained, not required: a timeout is not worth refusing to open the
        // painting over, which is what the checking variant would do here.
        // The call form, not the name — the source names it in a comment.
        assertFalse(upload.contains("requireReadback(renderer)"), upload)
    }

    @Test
    fun `desktop validates its frame target and renderer output`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(engine.contains("GLES30.glCheckFramebufferStatus"))
        assertTrue(engine.contains("check(r.drawFrame("))
        assertTrue(engine.contains("fun savePng(target: java.io.File? = null"))
        // The clean-save path still reports the path it wrote; a stale one
        // says so instead, which is why this is no longer the only form.
        assertTrue(main.contains("document.state.savedMessage = if (edited) {"))
        // The key, not `result.path` — that appears in both branches of the
        // condition above, so asserting it constrained nothing while leaving
        // the behaviour this round added unpinned.
        assertTrue(main.contains("desktop_save_stale"))
        assertTrue(engine.contains("restoreCancelledRmw(spec.layerId, images)"))
        assertTrue(engine.contains("readbackRevisions"))
        assertTrue(engine.contains("ReadbackDelivery.Complete"))
        assertTrue(engine.contains("exportExecutor.execute"))
        // The gate, not its exact spelling: input stays dead until the
        // restored brush and colour are resolved, whatever else the
        // condition grew to also require — it is a `when` now, because
        // placing the tracing image borrows the same pointer.
        assertTrue(main.contains("state.preferencesReady && activeTool != null ->"))
        assertTrue(main.contains("state.preferencesReady = true"))
    }

    @Test
    fun `export snapshots briefly and cannot fail the GL loop`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        check("fun savePng" in engine && "fun undo" in engine) { "savePng/undo markers not found" }
        val saveBody = engine
            .substringAfter("fun savePng")
            .substringBefore("fun undo")

        assertTrue(saveBody.contains("DesktopPng.snapshot("))
        assertTrue(saveBody.contains("DesktopPng.export(snapshot, file)"))
        assertTrue(saveBody.contains("catch (failure: Exception)"))
        assertTrue(saveBody.contains("DesktopPng.failureResult(failure)"))
        assertFalse(saveBody.contains("pixels.copyOf()"))
        assertFalse(saveBody.contains("requireReadback("))
    }

    @Test
    fun `empty canvas fills the window before its first frame`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
        val canvas = between(main, "// The canvas is full-bleed", "DesktopTopStrip(")

        // The viewport reports its size to the engine from onSizeChanged, so
        // it must be measured even while `bitmap` is still null.
        assertTrue(
            canvas.replace(Regex("\\s+"), " ").contains("Modifier .fillMaxSize() .onSizeChanged"),
            "the empty canvas must have size before its bitmap exists",
        )
    }

    @Test
    fun `renderer initialization schedules the first frame`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        check("private fun initializeRenderer()" in engine && "private fun pumpWetOverlay()" in engine) { "renderer markers not found" }
        val initialization = engine
            .substringAfter("private fun initializeRenderer()")
            .substringBefore("private fun pumpWetOverlay()")

        assertTrue(initialization.contains("requestRepaintOnGl()"))
    }

    @Test
    fun `one Mixbox binding supplies attribution and painting`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertEquals(1, Regex("MixboxBinding\\.create\\(\\)").findAll(main).count())
        // One mixer reaches every document, and each shell state reads it.
        assertTrue(main.contains("DesktopDocuments(ready.memory, host, catalogue, mixer, prefs)"))
        assertTrue(
            source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopDocuments.kt")
                .contains("DesktopShellState(engine, catalogue, mixer, prefs)"),
        )
    }

    /**
     * The region between two markers, requiring the second to follow the
     * first. `substringAfter`/`substringBefore` silently return the rest of
     * the file when the end marker does not follow the start one, which
     * would quietly assert against the wrong code after a reorder.
     */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, startIndex = from + 1)
        check(from >= 0 && to > from) { "markers not found or misordered: $start .. $end" }
        return source.substring(from, to)
    }

    private fun source(path: String): String = repoFile(path).readText(Charsets.UTF_8)

    private fun repoFile(path: String): File = File(repoRoot(), path)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }
}
