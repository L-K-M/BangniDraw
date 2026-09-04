package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.ui.shared.pickerSizeFor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val picker = sourceSection(PICKER_PATH, HSV_PICKER_START, PICKER_END)
            .replace(WHITESPACE, " ")

        listOf(
            "remember { Brush.sweepGradient(HUE_COLORS) }",
            "remember(hueColor, pickerPx) {",
            "remember(pickerPx) {",
        ).forEach { marker ->
            assertTrue(marker in picker, "missing marker [$marker]")
        }

        val canvasStart = picker.indexOf("Canvas(")
        if (canvasStart < 0) fail("missing source marker: Canvas(")
        val draw = picker.substring(canvasStart)
        assertFalse(
            "= Brush." in draw,
            "a Brush is still constructed inside the Canvas draw block",
        )
        assertTrue(
            "pickerPx * RING_WIDTH_FRACTION" in draw &&
                "size.minDimension * RING_WIDTH_FRACTION" !in draw,
            "ring geometry does not share the picker's measured basis",
        )
        assertTrue(
            "pickerSizeFor(maxWidth, maxHeight)" in picker &&
                ".size(pickerSize)" in picker &&
                "pickerSize.toPx()" in picker,
            "the picker does not size to its panel (capped at PICKER_MAX)",
        )
        assertEquals(
            2,
            RESIZE_SAFE_POINTER_INPUT.findAll(picker).count(),
            "both picker gestures must restart after a resize",
        )
        assertFalse(
            UNKEYED_POINTER_INPUT.containsMatchIn(picker),
            "no picker gesture may omit the resize key",
        )
    }

    @Test
    fun `picker sizes to its panel capped at PICKER_MAX`() {
        assertEquals(160.dp, pickerSizeFor(160.dp, 400.dp))
        assertEquals(280.dp, pickerSizeFor(320.dp, 320.dp))
        assertEquals(220.dp, pickerSizeFor(220.dp, 600.dp))
        // Height as the smaller constraint, not only width.
        assertEquals(220.dp, pickerSizeFor(600.dp, 220.dp))
    }

    @Test
    fun `dish gradient is remembered across recompositions`() {
        val dish = sourceSection(COLOR_PANEL_PATH, DISH_START, SWATCH_STRIP_START)
            .replace(WHITESPACE, " ")

        assertTrue(
            "remember(state.dish.a, state.dish.b)" in dish,
            "mixSteps is not remembered on the wells",
        )
    }

    private fun sourceSection(path: String, start: String, end: String): String {
        val source = File(repositoryRoot(), path).readText()
        val startIndex = source.indexOf(start)
        if (startIndex < 0) fail("missing source marker: $start")

        val endIndex = source.indexOf(end, startIndex)
        if (endIndex <= startIndex) fail("missing source marker: $end")

        return source.substring(startIndex, endIndex)
    }

    private fun repositoryRoot(): File {
        val userDirectory = checkNotNull(System.getProperty("user.dir")) {
            "user.dir is unavailable"
        }
        var directory: File? = File(userDirectory).absoluteFile
        while (directory != null) {
            val candidate = directory
            if (
                candidate.resolve("settings.gradle").isFile ||
                candidate.resolve("settings.gradle.kts").isFile
            ) {
                return candidate
            }

            directory = candidate.parentFile
        }

        fail("repository root not found above $userDirectory")
    }

    private companion object {
        const val COLOR_PANEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ColorPanel.kt"

        /** The picker is shared with the desktop shell; the claim follows it. */
        const val PICKER_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/shared/HsvRingSquare.kt"
        const val HSV_PICKER_START = "internal fun HsvRingSquare("
        const val PICKER_END = "internal fun ColorCircle("
        const val DISH_START = "private fun MixingDishControls("
        const val SWATCH_STRIP_START = "private fun SwatchStrip("
        val RESIZE_SAFE_POINTER_INPUT =
            Regex("""pointerInput\(\s*pickerSize\s*,?\s*\)""")
        val UNKEYED_POINTER_INPUT =
            Regex("""pointerInput\(\s*\)""")
        val WHITESPACE = Regex("\\s+")
    }
}
