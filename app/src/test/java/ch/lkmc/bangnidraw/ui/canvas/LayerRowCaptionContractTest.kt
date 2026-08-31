package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Both texts in a layer row ellipsize.
 *
 * `maxLines = 1` without an `overflow` hard-clips at the measured width —
 * mid-glyph, with no ellipsis to say anything was cut — and the caption is
 * the text most likely to run out of room: the row spends its width on the
 * drag handle, the thumbnail and three 48 dp actions before the name column
 * gets its weighted remainder. The name above it already ellipsized; the
 * caption was simply missed, so a narrow panel at a large font scale showed
 * a truncated blend mode ("Differen") rather than an elided one ("Diffe…").
 */
class LayerRowCaptionContractTest {

    @Test
    fun `every clamped text in a layer row ellipsizes`() {
        val row = layerRow()

        // Whitespace-normalized, so the pin counts declarations rather than
        // one formatting of them.
        val clamped = MAX_LINES.findAll(row).count()
        val elided = ELLIPSIS.findAll(row).count()
        assertEquals(
            clamped,
            elided,
            "$clamped text(s) clamp to one line but only $elided ellipsize — " +
                "a clamp without an overflow clips mid-glyph",
        )
        assertTrue(clamped >= 2, "expected the name and the caption to be clamped")
    }

    @Test
    fun `the blend-mode caption is one of them`() {
        // Named explicitly: the count above would still balance if a future
        // row dropped the caption's clamp instead of adding its overflow.
        val row = layerRow()
        val caption = row.indexOf(CAPTION)
        if (caption < 0) fail("missing $CAPTION — renamed?")
        // Bounded by the visibility action that follows the name column, so
        // the window holds the caption's own arguments and nothing else.
        val close = row.indexOf(AFTER_CAPTION, caption)
        if (close <= caption) fail("missing $AFTER_CAPTION after the caption")

        assertTrue(
            "overflow = TextOverflow.Ellipsis" in row.substring(caption, close),
            "the blend-mode caption must ellipsize",
        )
    }

    private fun layerRow(): String {
        // Comments stripped before the counts, matching the sibling test in
        // this change. These needles are *counted*, not merely sought, so a
        // comment inside LayerRow quoting `maxLines = 1` without also quoting
        // the overflow line shifts one count and fails with a message about
        // production clipping — pointing debugging at the wrong layer over a
        // documentation edit. The row already carries such a comment, added
        // here; it survives only because its wording happens to avoid the
        // literals.
        val panel = ContractTestSources.read(LAYER_PANEL_PATH)
            .replace(COMMENTS, " ")
            .replace(WHITESPACE, " ")
        val start = panel.indexOf(ROW_START)
        if (start < 0) fail("missing $ROW_START — renamed?")
        val end = panel.indexOf(ROW_END, start)
        if (end <= start) fail("missing $ROW_END after the row")

        return panel.substring(start, end)
    }

    private companion object {
        const val LAYER_PANEL_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/LayerPanel.kt"
        const val ROW_START = "private fun LayerRow("
        const val ROW_END = "private fun LayerThumbnail("
        const val CAPTION = "text = blendModeName(layer.props.blendMode)"
        const val AFTER_CAPTION = "onClick = onToggleVisibility"
        val MAX_LINES = Regex("maxLines = 1")
        val ELLIPSIS = Regex("overflow = TextOverflow\\.Ellipsis")
        val COMMENTS = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val WHITESPACE = Regex("\\s+")
    }
}
