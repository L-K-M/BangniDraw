package ch.lkmc.bangnidraw.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class RememberedCustomSizeContractTest {

    @Test
    fun `remembered custom size is observable screen state`() {
        val viewModel = source(STUDIO_VIEW_MODEL_PATH)
        val screen = source(STUDIO_SCREEN_PATH)
        val dialog = source(NEW_CANVAS_DIALOG_PATH)

        assertTrue("val lastCustomSize: CanvasSize? = null" in viewModel)
        assertTrue("prefs.lastCustomSize.collect" in viewModel)
        assertTrue("copy(lastCustomSize = value)" in viewModel)
        assertTrue("lastCustomSize = state.lastCustomSize" in screen)
        assertFalse("internal var lastCustomSize" in viewModel)

        // User edits win over the remembered pre-fill: the fields are not
        // keyed on the async value (which would snap mid-edit), and a
        // keystroke latches the edited flag that stops the sync. Assertions
        // match identifiers and shape, not whole statements, so reformatting
        // the dialog cannot fail this contract.
        assertFalse("rememberSaveable(lastCustomSize)" in dialog)
        assertTrue("var customEdited by rememberSaveable" in dialog)
        assertTrue("LaunchedEffect(lastCustomSize)" in dialog)
        assertTrue(
            Regex("if\\s*\\(customEdited\\)[^\\n]*return@LaunchedEffect").containsMatchIn(dialog),
        )
        assertTrue("customEdited = true" in dialog)
        assertTrue("fun prefill" in dialog)
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

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
        const val STUDIO_VIEW_MODEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioViewModel.kt"
        const val STUDIO_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioScreen.kt"
        const val NEW_CANVAS_DIALOG_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/NewCanvasDialog.kt"
    }
}
