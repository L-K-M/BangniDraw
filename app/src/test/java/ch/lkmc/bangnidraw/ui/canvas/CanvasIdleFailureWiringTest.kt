package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CanvasIdleFailureWiringTest {

    private val source = File("src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt")
        .readText()

    @Test
    fun `share failure callback and gate release survive encode exceptions`() {
        val share = source.substringAfter("internal fun share(")
            .substringBefore("internal fun export(")

        assertTrue(share.contains("runCatching"))
        assertTrue(share.contains("onFailure()"))
        assertTrue(share.contains("finally"))
        assertTrue(share.contains("finishDocumentWork()"))
    }

    @Test
    fun `export failure callback and gate release survive export exceptions`() {
        val export = source.substringAfter("internal fun export(")
            .substringBefore("private fun requestIdleWork(")

        assertTrue(export.contains("runCatching"))
        assertTrue(export.contains("GalleryExportOutcome.FAILURE"))
        assertTrue(export.contains("finally"))
        assertTrue(export.contains("finishDocumentWork()"))
    }
}
