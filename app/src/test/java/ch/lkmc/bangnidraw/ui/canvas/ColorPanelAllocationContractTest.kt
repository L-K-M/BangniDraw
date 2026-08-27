package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the color panel's draw path allocation-free during drags: a picker
 * drag recomposes and redraws per input frame, so gradient `Brush`es (each
 * carrying a native `Shader`) and the nine-mix dish gradient must be
 * `remember`ed, not rebuilt per draw (k3.md §2.1, §2.2 — the same class PR
 * #23 fixed on the canvas path).
 */
class ColorPanelAllocationContractTest {

    @Test
    fun `picker brushes are remembered, not built per draw`() {
        val picker = sourceSection(HSV_PICKER_START, COLOR_CHIPS_START)
            .replace(WHITESPACE, " ")

        listOf(
            "remember { Brush.sweepGradient(HUE_COLORS) }",
            "remember(hueColor, pickerPx) {",
            "remember(pickerPx) {",
        ).forEach { marker ->
            assertTrue(marker in picker, "missing marker [$marker]")
        }

        val draw = picker.substringAfter("Canvas(", "")
        assertFalse(
            "= Brush." in draw,
            "a Brush is still constructed inside the Canvas draw block",
        )
    }

    @Test
    fun `dish gradient is remembered across recompositions`() {
        val dish = sourceSection(DISH_START, SWATCH_STRIP_START)
            .replace(WHITESPACE, " ")

        assertTrue(
            "remember(state.dish.a, state.dish.b)" in dish,
            "mixSteps is not remembered on the wells",
        )
    }

    private fun sourceSection(start: String, end: String): String {
        val source = File(repositoryRoot(), COLOR_PANEL_PATH).readText()
        val startIndex = source.indexOf(start)
        if (startIndex < 0) fail("missing source marker: $start")

        val endIndex = source.indexOf(end, startIndex)
        if (endIndex <= startIndex) fail("missing source marker: $end")

        return source.substring(startIndex, endIndex)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(System.getProperty("user.dir"))
        if (workingDirectory.name == "app") return workingDirectory.parentFile
        return workingDirectory
    }

    private companion object {
        const val COLOR_PANEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ColorPanel.kt"
        const val HSV_PICKER_START = "private fun HsvRingSquare("
        const val COLOR_CHIPS_START = "private fun ColorChips("
        const val DISH_START = "private fun MixingDishControls("
        const val SWATCH_STRIP_START = "private fun SwatchStrip("
        val WHITESPACE = Regex("\\s+")
    }
}
