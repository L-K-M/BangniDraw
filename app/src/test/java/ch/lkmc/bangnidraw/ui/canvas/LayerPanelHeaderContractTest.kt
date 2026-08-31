package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Layer panel header's four trailing 48 dp buttons — Help, Add, ⋮,
 * Close — must always measure in full. A `Row` offers trailing children
 * only what earlier ones left, so unweighted title texts plus a weighted
 * spacer starved the buttons on narrow panels and at large font: the
 * *last* child, the close affordance #120 added, was the one squeezed out.
 * The texts own the weighted slot and ellipsize instead, exactly as
 * `PanelHeader` does for every other panel.
 */
class LayerPanelHeaderContractTest {

    @Test
    fun `the header's texts are weighted so its buttons cannot be starved`() {
        // Whitespace-normalized per the house rule for source-contract
        // tests, so a mechanical reformat cannot fail a behavioral pin.
        val panel = ContractTestSources.read(LAYER_PANEL_PATH).replace(WHITESPACE, " ")
        val start = panel.indexOf(HEADER_START)
        if (start < 0) fail("missing $HEADER_START")
        val end = panel.indexOf(HEADER_END, start)
        if (end <= start) fail("missing $HEADER_END after the header")
        val header = panel.substring(start, end)

        assertTrue(
            "modifier = Modifier.weight(1f)," in header,
            "the text group must own the flexible slot",
        )
        // Hoisted above the scoped check below, with its guard: that check
        // narrows the header with substringBefore on this same anchor, and
        // substringBefore silently degrades to the WHOLE header when the
        // anchor is missing — at which point the count Text's own ellipsis
        // would satisfy the title's pin. Failing loudly here first means the
        // degradation can no longer masquerade as coverage, and the actions
        // loop reuses the value rather than recomputing it.
        val weighted = header.indexOf(YIELDING_TITLE)
        if (weighted < 0) fail("missing the yielding title's weight anchor")

        // Scoped before the weight anchor: the count Text ellipsizes too
        // now, and its ellipsis alone must not satisfy the title's check.
        assertTrue(
            "overflow = TextOverflow.Ellipsis" in header.substring(0, weighted),
            "the title yields by ellipsizing",
        )
        // Any weighted Spacer spelling — named argument, fill = false —
        // is the same regression.
        assertTrue(
            !WEIGHTED_SPACER.containsMatchIn(header),
            "a weighted spacer after unweighted texts is what starved the buttons",
        )
        // All four trailing actions live after the weighted group; the
        // yielding title's own modifier anchors it (computed above) so a
        // future weighted element elsewhere in the header cannot hijack the
        // check.
        for (action in listOf("InfoButton(", "onClick = onAdd", "onMenuChange(true)", "PanelCloseButton(onClose)")) {
            val at = header.indexOf(action)
            if (at < 0) fail("missing $action in the header")
            assertTrue(at > weighted, "$action must trail the weighted text group")
        }
    }

    private companion object {
        const val LAYER_PANEL_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/LayerPanel.kt"
        const val HEADER_START = "private fun LayerPanelHeader("
        const val HEADER_END = "private fun LayerRow("
        const val YIELDING_TITLE = "Modifier.weight(1f, fill = false)"
        // One level of nested parens, so a chained spelling like
        // Spacer(Modifier.padding(8.dp).weight(1f)) is still banned.
        val WEIGHTED_SPACER = Regex("Spacer\\((?:[^()]|\\([^()]*\\))*weight")
        val WHITESPACE = Regex("\\s+")
    }
}
