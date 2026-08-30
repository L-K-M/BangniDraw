package ch.lkmc.bangnidraw.ui

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Transparent paper renders as the shared quadrant checker everywhere a
 * swatch is too small for the canvas's fine checkerboard (ANALYSIS U9). A
 * flat `surfaceVariant` fill — or the old "∅" glyph — read as gray paper,
 * which is the exact misunderstanding the see-through option keeps causing.
 */
class TransparentSwatchContractTest {

    @Test
    fun `the layer panel's paper swatch draws the shared checker`() {
        val swatch = swatchOf(LAYER_PANEL_PATH)

        assertTrue("drawQuadrantChecker(checkerA, checkerB)" in swatch)
        assertTrue("drawRect(color)" in swatch, "an opaque paper choice still covers the checker")
    }

    @Test
    fun `the new canvas paper swatch draws the shared checker, not a glyph`() {
        val swatch = swatchOf(NEW_CANVAS_PATH)

        // Unconditional, like LayerPanel's: an opaque color covers the
        // checker on its own, and partial alpha previews identically in
        // both pickers.
        assertTrue("drawQuadrantChecker(checkerA, checkerB)" in swatch)
        assertTrue("drawRect(color)" in swatch)
        val dialog = ContractTestSources.read(NEW_CANVAS_PATH)
        assertFalse("paper_transparent_symbol" in dialog, "the ∅ stand-in is retired")
    }

    @Test
    fun `both sites share the layer thumbnail's checker roles`() {
        for (path in listOf(LAYER_PANEL_PATH, NEW_CANVAS_PATH)) {
            val swatch = swatchOf(path)
            // surfaceVariant contains "surface" as a prefix, so the bare
            // role is checked against text with the variant stripped —
            // otherwise the first condition is subsumed by the second.
            assertTrue(
                "MaterialTheme.colorScheme.surface" in
                    swatch.replace("MaterialTheme.colorScheme.surfaceVariant", "") &&
                    "MaterialTheme.colorScheme.surfaceVariant" in swatch,
                "$path must use the same two roles as the layer thumbnail checker",
            )
        }
    }

    /**
     * Whitespace-normalized per the house rule for source-contract tests,
     * and loud when the marker moves — a silent full-file fallback from
     * `substringAfter` would point failures at the wrong code.
     */
    private fun swatchOf(path: String): String {
        val source = ContractTestSources.read(path).replace(WHITESPACE, " ")
        val swatch = source
            .substringAfter(SWATCH_START, missingDelimiterValue = "")
            .substringBefore("@Composable")
        if (swatch.isBlank()) fail("$path no longer declares $SWATCH_START — renamed?")

        return swatch
    }

    private companion object {
        const val LAYER_PANEL_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/LayerPanel.kt"
        const val NEW_CANVAS_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/home/NewCanvasDialog.kt"
        const val SWATCH_START = "private fun PaperSwatch("
        val WHITESPACE = Regex("\\s+")
    }
}
