package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ColorPanelAccessibilityContractTest {

    @Test
    fun `HSV channels are named adjustable controls`() {
        val source = source()
        val controls = sourceSection(source, HSV_CONTROLS_START, COLOR_CHIPS_START)
            .replace(WHITESPACE, " ")

        listOf(
            "HsvChannel.entries.forEach",
            "contentDescription = label",
            "stateDescription = valueText",
            "onValueChangeFinished",
            "remember(hsv) { mutableStateOf(hsv) }",
            "pendingHsv = next",
            "latestCommit.value(pendingHsv)",
            "R.string.color_percent_value",
        ).forEach { marker ->
            assertTrue(marker in controls, "missing marker [$marker]")
        }
    }

    @Test
    fun `current color exposes add without a no-op click`() {
        val source = source()
        val chips = sourceSection(source, COLOR_CHIPS_START, COLOR_FIELDS_START)
            .replace(WHITESPACE, " ")

        assertFalse(NO_OP_CLICK.containsMatchIn(chips), "current chip has a no-op click")
        listOf(
            "onLongClick(label = addLabel)",
            "performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)",
        ).forEach { marker ->
            assertTrue(marker in chips, "missing marker [$marker]")
        }
    }

    private fun source(): String = File(repositoryRoot(), COLOR_PANEL_PATH).readText()

    private fun sourceSection(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        if (startIndex < 0) fail("missing source marker: $start")

        val endIndex = source.indexOf(end, startIndex)
        if (endIndex <= startIndex) fail("missing source marker: $end")

        return source.substring(startIndex, endIndex)
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
        const val HSV_CONTROLS_START = "private fun HsvControls("
        const val COLOR_CHIPS_START = "private fun ColorChips("
        const val COLOR_FIELDS_START = "private fun ColorFields("
        val WHITESPACE = Regex("\\s+")
        val NO_OP_CLICK = Regex("""combinedClickable\(\s*onClick\s*=\s*\{\s*\}""")
    }
}
