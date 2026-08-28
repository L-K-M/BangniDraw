package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class TracingReferenceContractTest {

    @Test
    fun `tracing import uses Photo Picker without a manifest permission`() {
        val root = repositoryRoot()
        val screen = File(root, CANVAS_SCREEN_PATH).readText()
        val manifest = File(root, MANIFEST_PATH).readText()

        assertTrue("ActivityResultContracts.PickVisualMedia()" in screen)
        assertFalse(
            "RequestPermission" in screen || "RequestMultiplePermissions" in screen,
        )
        assertFalse(
            "<uses-permission" in manifest,
            "media access must stay permission-free",
        )
    }

    @Test
    fun `tracing import and panel guard transient state`() {
        val root = repositoryRoot()
        val viewModel = File(root, VIEW_MODEL_PATH).readText()
        val panel = File(root, REFERENCE_PANEL_PATH).readText()
        val removeReference = viewModel
            .substringAfter("internal fun removeTracingReference()")
            .substringBefore("private fun applyTracingReference")

        assertTrue(
            Regex("ready\\s+!is\\s+UiState\\.Ready").containsMatchIn(viewModel),
        )
        assertTrue(
            Regex("modifier\\s*=\\s*Modifier\\s*\\.\\s*weight\\(\\s*1f\\s*\\)")
                .findAll(panel).count() ==
                REFERENCE_ACTION_COUNT,
        )
        assertTrue("dismissPanel()" in removeReference)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val CANVAS_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt"
        const val VIEW_MODEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt"
        const val REFERENCE_PANEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/TracingReferencePanel.kt"
        const val MANIFEST_PATH = "app/src/main/AndroidManifest.xml"
        const val REFERENCE_ACTION_COUNT = 3
    }
}
