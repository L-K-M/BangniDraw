package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopRenderingContractTest {

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

    private fun source(path: String): String = repoFile(path).readText()

    private fun repoFile(path: String): File = File(repoRoot(), path)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }
}
