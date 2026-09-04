package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ColorPanelSelectionContractTest {

    @Test
    fun `panel retains its HSV commits and accepts external colors`() {
        val source = File(repositoryRoot(), COLOR_PANEL_PATH).readText()
        val panel = source.substringBefore(PANEL_BODY_END)
            .replace(WHITESPACE, " ")

        listOf(
            "var selection by remember {",
            "LaunchedEffect(state.current)",
            "selection = selection.sync(state.current)",
            "selection = selection.preview(next)",
            "selection = selection.commit(argb)",
            "val committed = selection.commit(next)",
        ).forEach { marker ->
            assertTrue(marker in panel, "missing marker [$marker]")
        }
        assertFalse(
            "remember(state.current)" in panel,
            "a panel-originated grey commit must not rebuild HSV from ARGB",
        )
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
        const val COLOR_PANEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ColorPanel.kt"
        // The panel's own state handling, above the first control it draws.
        const val PANEL_BODY_END = "@Composable\nprivate fun HsvControls("
        val WHITESPACE = Regex("\\s+")
    }
}
