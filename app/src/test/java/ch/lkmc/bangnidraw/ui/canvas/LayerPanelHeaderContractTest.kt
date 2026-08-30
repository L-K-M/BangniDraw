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
        val panel = ContractTestSources.read(LAYER_PANEL_PATH)
        val start = panel.indexOf(HEADER_START)
        if (start < 0) fail("missing $HEADER_START")
        val end = panel.indexOf(HEADER_END, start)
        if (end <= start) fail("missing $HEADER_END after the header")
        val header = panel.substring(start, end)

        assertTrue(
            "modifier = Modifier.weight(1f)," in header,
            "the text group must own the flexible slot",
        )
        assertTrue("overflow = TextOverflow.Ellipsis" in header, "the title yields by ellipsizing")
        assertTrue(
            "Spacer(Modifier.weight(1f))" !in header,
            "a weighted spacer after unweighted texts is what starved the buttons",
        )
        // All four trailing actions live after the weighted group.
        val weighted = header.indexOf("modifier = Modifier.weight(1f),")
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
    }
}
