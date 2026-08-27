package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the curve editor's plot: the four knot sliders are legible only with
 * the curve drawn above them (ANALYSIS.md shovel-ready #6), and the plot
 * must sample `Curve.eval` — a polyline through the four knots would hide
 * the Catmull-Rom overshoot the slider user is tuning.
 */
class BrushSettingsCurvePlotContractTest {

    @Test
    fun `curve editor draws a plot above its knot sliders`() {
        val editor = sourceSection(CURVE_EDITOR_START, ORIENTATION_CHIP_START)

        val plotCall = editor.indexOf("CurvePlot(")
        val firstSlider = editor.indexOf("SettingSlider(")
        assertTrue(plotCall >= 0, "CurveEditor never calls CurvePlot")
        assertTrue(firstSlider > plotCall, "CurvePlot must precede the knot sliders")
    }

    @Test
    fun `plot samples the spline and marks the knots`() {
        val plot = sourceSection(CURVE_PLOT_START, ORIENTATION_CHIP_START)
            .replace(WHITESPACE, " ")

        listOf(
            "curve.eval(",
            "KNOT_X[k]",
            "R.string.brush_curve_plot",
            "contentDescription = description",
            ".clipToBounds()",
            "GRID_STROKE.toPx()",
            "CURVE_STROKE.toPx()",
            "KNOT_RADIUS.toPx()",
        ).forEach { marker ->
            assertTrue(marker in plot, "missing marker [$marker]")
        }
    }

    private fun sourceSection(start: String, end: String): String {
        val source = File(repositoryRoot(), SHEET_PATH).readText()
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
        const val SHEET_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/BrushSettingsSheet.kt"
        const val CURVE_EDITOR_START = "internal fun CurveEditor("
        const val CURVE_PLOT_START = "private fun CurvePlot("
        const val ORIENTATION_CHIP_START = "private fun OrientationChip("
        val WHITESPACE = Regex("\\s+")
    }
}
