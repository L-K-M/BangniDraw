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
        assertRetiredGlyph(NEW_CANVAS_PATH)
    }

    @Test
    fun `the retired glyph is gone from both swatch sites`() {
        // The retirement pin used to cover the New Canvas dialog only, so the
        // ∅ stand-in could have come back in the Layer panel — the other site
        // sharing the quadrant checker — without failing anything.
        assertRetiredGlyph(NEW_CANVAS_PATH)
        assertRetiredGlyph(LAYER_PANEL_PATH)
    }

    private fun assertRetiredGlyph(path: String) {
        assertFalse(
            "paper_transparent_symbol" in ContractTestSources.read(path),
            "the retired glyph must not come back; $path draws the checker",
        )
    }

    @Test
    fun `both sites share the layer thumbnail's checker roles`() {
        for (path in listOf(LAYER_PANEL_PATH, NEW_CANVAS_PATH)) {
            val swatch = swatchOf(path)
            // Every surface-prefixed role is stripped before the bare role is
            // checked, not just surfaceVariant: "surface" is a prefix of
            // surfaceTint, surfaceContainer, surfaceBright and the rest, so
            // stripping one name by hand left the bare-role half satisfiable
            // by any of the others — a swatch that used surfaceContainer for
            // both cells would have passed.
            val withoutPrefixed = swatch.replace(SURFACE_PREFIXED, "")
            assertTrue(
                "MaterialTheme.colorScheme.surface" in withoutPrefixed &&
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
        val SURFACE_PREFIXED = Regex("""MaterialTheme\.colorScheme\.surface[A-Za-z]+""")
    }
}
