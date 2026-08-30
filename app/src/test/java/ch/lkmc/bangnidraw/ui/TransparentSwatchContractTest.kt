package ch.lkmc.bangnidraw.ui

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transparent paper renders as the shared quadrant checker everywhere a
 * swatch is too small for the canvas's fine checkerboard (ANALYSIS U9). A
 * flat `surfaceVariant` fill — or the old "∅" glyph — read as gray paper,
 * which is the exact misunderstanding the see-through option keeps causing.
 */
class TransparentSwatchContractTest {

    @Test
    fun `the layer panel's paper swatch draws the shared checker`() {
        val panel = ContractTestSources.read(LAYER_PANEL_PATH)
        val swatch = panel.substringAfter("private fun PaperSwatch(").substringBefore("@Composable")

        assertTrue("drawQuadrantChecker(checkerA, checkerB)" in swatch)
        assertTrue("drawRect(color)" in swatch, "an opaque paper choice still covers the checker")
    }

    @Test
    fun `the new canvas paper swatch draws the shared checker, not a glyph`() {
        val dialog = ContractTestSources.read(NEW_CANVAS_PATH)
        val swatch = dialog.substringAfter("private fun PaperSwatch(").substringBefore("@Composable")

        assertTrue("drawQuadrantChecker(checkerA, checkerB)" in swatch)
        assertFalse("paper_transparent_symbol" in dialog, "the ∅ stand-in is retired")
    }

    @Test
    fun `both sites share the layer thumbnail's checker roles`() {
        for (path in listOf(LAYER_PANEL_PATH, NEW_CANVAS_PATH)) {
            val source = ContractTestSources.read(path)
            val swatch = source.substringAfter("private fun PaperSwatch(")
                .substringBefore("@Composable")
            assertTrue(
                "MaterialTheme.colorScheme.surface" in swatch &&
                    "MaterialTheme.colorScheme.surfaceVariant" in swatch,
                "$path must use the same two roles as the layer thumbnail checker",
            )
        }
    }

    private companion object {
        const val LAYER_PANEL_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/LayerPanel.kt"
        const val NEW_CANVAS_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/home/NewCanvasDialog.kt"
    }
}
