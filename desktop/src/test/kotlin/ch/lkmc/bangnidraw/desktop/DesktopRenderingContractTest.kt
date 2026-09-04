package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRenderingContractTest {

    @Test
    fun `the chrome is the shared adaptive layout, not a desktop-only one`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
        val shell = between(main, "private fun Shell(", "/** The rail hugs the hand")

        // Every rail/strip/panel dimension comes from LayoutSpec, so the two
        // products cannot drift apart on geometry.
        assertTrue(shell.contains("DesktopChromeLayout.forWindow(widthDp, heightDp)"))
        assertTrue(shell.contains("DesktopTopStrip("))
        assertTrue(shell.contains("DesktopToolRail("))
        assertTrue(shell.contains("layout.panelInsets(widthDp, heightDp)"))
        // The old shell put the canvas in a row beside a fixed sidebar; the
        // Android chrome floats over a full-bleed canvas instead.
        assertFalse(shell.contains("SidePanel("))
    }

    @Test
    fun `desktop validates its frame target and renderer output`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(engine.contains("GLES30.glCheckFramebufferStatus"))
        assertTrue(engine.contains("check(r.drawFrame("))
        assertTrue(main.contains("engine.savePng { result ->"))
        assertTrue(main.contains("EventQueue.invokeLater { state.savedMessage = message }"))
        assertTrue(engine.contains("restoreCancelledRmw(spec.layerId, images)"))
        assertTrue(engine.contains("readbackRevisions"))
        assertTrue(engine.contains("ReadbackDelivery.Complete"))
        assertTrue(engine.contains("exportExecutor.execute"))
        // The gate, not its exact spelling: input stays dead until the
        // restored brush and colour are resolved, whatever else the
        // condition grew to also require.
        assertTrue(main.contains("val canvasInput = if (state.preferencesReady"))
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
        check("private fun initializeRenderer()" in engine && "private fun runTasksAndFrames()" in engine) { "renderer markers not found" }
        val initialization = engine
            .substringAfter("private fun initializeRenderer()")
            .substringBefore("private fun runTasksAndFrames()")

        assertTrue(initialization.contains("requestRepaintOnGl()"))
    }

    @Test
    fun `one Mixbox binding supplies attribution and painting`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertEquals(1, Regex("MixboxBinding\\.create\\(\\)").findAll(main).count())
        // One mixer reaches the shell state, which every surface reads it from.
        assertTrue(main.contains("DesktopShellState(it, catalogue, mixer, prefs)"))
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
