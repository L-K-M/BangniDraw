package ch.lkmc.bangnidraw.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the Settings sheet's keyboard-shortcuts section: the canvas shortcut
 * table (engine/core/CanvasShortcut.kt) is discoverable somewhere in the UI,
 * and a row added to the table without a row here fails this test's count.
 */
class ShortcutSectionContractTest {

    @Test
    fun `the settings sheet lists the canvas shortcut table`() {
        val sheet = source(SETTINGS_SHEET_PATH)

        assertTrue("R.string.settings_shortcuts" in sheet)
        assertEquals(SHORTCUT_ROWS, SHORTCUT_ROW.findAll(sheet).count())
        // Spot-check the anchors of the table: undo, the bracket size pair,
        // and the hold-Alt eyedropper.
        assertTrue("ShortcutRow(R.string.canvas_undo, \"Ctrl+Z\")" in sheet)
        assertTrue("ShortcutRow(R.string.brush_size, \"[   ]\")" in sheet)
        assertTrue("ShortcutRow(R.string.shortcut_hold_eyedropper, \"Alt\")" in sheet)
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

        val SHORTCUT_ROW = Regex("ShortcutRow\\(R\\.string\\.")
        const val SHORTCUT_ROWS = 14
    }
}
