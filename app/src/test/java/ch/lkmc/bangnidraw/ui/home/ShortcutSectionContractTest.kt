package ch.lkmc.bangnidraw.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the Settings sheet's shortcut section as a *view* of the engine's
 * legend: the rows render from `ShortcutLegend`, so the advertised table
 * cannot drift from the dispatcher (`CanvasShortcutLegendTest` replays the
 * legend through `CanvasShortcuts.resolve`).
 */
class ShortcutSectionContractTest {

    @Test
    fun `the settings sheet renders the engine's legend`() {
        val sheet = source(SETTINGS_SHEET_PATH).replace(WHITESPACE, " ")

        assertTrue("R.string.settings_shortcuts" in sheet)
        assertTrue("ShortcutLegend.entries" in sheet)
        assertTrue("ShortcutLegend.keyLabel(entry)" in sheet)
        // No hand-typed key caps: a binding label copied by hand is how the
        // table first contradicted the dispatcher.
        assertFalse("\"Ctrl+Z\"" in sheet)
        assertFalse("\"Alt\"" in sheet)
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
        const val SETTINGS_SHEET_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/SettingsSheet.kt"
        val WHITESPACE = Regex("\\s+")
    }
}
