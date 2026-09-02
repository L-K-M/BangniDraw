package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRenderingContractTest {

    @Test
    fun `the full side panel scrolls at minimum window height`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
        check("private fun SidePanel(" in main && "private fun HsvSliders(" in main) { "SidePanel markers not found" }
        val panel = main.substringAfter("private fun SidePanel(")
            .substringBefore("private fun HsvSliders(")

        assertTrue(panel.contains("fillMaxSize().verticalScroll(rememberScrollState())"))
        assertFalse(panel.contains("Modifier.height(240.dp).verticalScroll"))
    }

    @Test
    fun `desktop validates its frame target and renderer output`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(engine.contains("GLES30.glCheckFramebufferStatus"))
        assertTrue(engine.contains("check(r.drawFrame("))
        assertTrue(main.contains("engine.savePng { result ->"))
        assertTrue(main.contains("EventQueue.invokeLater { savedMessage = message }"))
        assertTrue(engine.contains("restoreCancelledRmw(spec.layerId, images)"))
        assertTrue(engine.contains("readbackRevisions"))
        assertTrue(engine.contains("ReadbackDelivery.Complete"))
        assertTrue(engine.contains("exportExecutor.execute"))
        assertTrue(main.contains("if (preferencesReady)"))
        assertTrue(main.contains("preferencesReady = true"))
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
    fun `empty canvas fills the row before its first frame`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
        check("// The canvas viewport" in main && "SidePanel(" in main) { "canvas markers not found" }
        val canvas = main
            .substringAfter("// The canvas viewport")
            .substringBefore("SidePanel(")

        assertTrue(
            canvas.replace(Regex("\\s+"), " ").contains(".weight(1f) .fillMaxHeight()"),
            "the empty canvas must have height before its bitmap exists",
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
        assertTrue(main.contains("mixer = mixer"))
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
