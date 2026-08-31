package ch.lkmc.bangnidraw.ui.home

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every shelf card shows its ⋮ — not only broken ones. The long press it
 * mirrors is invisible to a first-time user and unreachable from a keyboard
 * (DeX and keyboard covers are a supported configuration Settings itself
 * advertises), and `help_studio_body` promises "or tap ⋮" for every
 * painting. The button lives beside the title so the shelf stays
 * artwork-led; the overlay button that existed only on unavailable cards is
 * retired in its favor.
 */
class StudioCardMenuContractTest {

    @Test
    fun `the card menu button is unconditional and off the artwork`() {
        val studio = ContractTestSources.read(STUDIO_PATH).replace(WHITESPACE, " ")

        // The leading `}` is *intended* as the title Column's closing brace:
        // pinning the button directly after it means an `if (...) {` wrapper
        // between them breaks the match. Read this as a smoke pin, not proof
        // — it cannot establish which `}` matched, and a conditional wrapped
        // around the whole footer (rather than between the brace and the
        // button) would not break it. The count assertion below is the
        // stronger half of "unconditional".
        // Space-free like every needle here, so an interior line wrap
        // cannot false-fail the pin.
        assertTrue(
            "}Box{IconButton(onClick={menuOpen=true})" in studio.replace(" ", ""),
            "every card's footer must carry the actions button, unconditionally",
        )
        // Exactly one menu-opening button, in any argument order or
        // formatting: the retired overlay — or any second affordance —
        // shifts this count.
        assertTrue(
            studio.replace(" ", "").split("onClick={menuOpen=true}").size == 2,
            "the unavailable-only overlay button is retired; one affordance serves every card",
        )
        // The help text's promise stays true: the button opens the same menu
        // the long press opens, delete included.
        //
        // Space-free anchor with an explicit missingDelimiterValue: the
        // single-argument substringAfter returns the WHOLE receiver when its
        // delimiter misses, and this file carries `DropdownMenu(expanded =
        // menuOpen` elsewhere, so the assertion below would have false-passed
        // on an anchor that merely moved. Empty instead, and loud.
        val compact = studio.replace(" ", "")
        val footer = compact.substringAfter(MENU_BUTTON, missingDelimiterValue = "")
        if (footer.isEmpty()) fail("the footer's menu button moved — $MENU_BUTTON not found")
        assertTrue(
            "DropdownMenu(expanded=menuOpen" in footer,
            "the footer button must open the same menu the long press does",
        )
    }

    private companion object {
        const val STUDIO_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioScreen.kt"
        const val MENU_BUTTON = "Box{IconButton(onClick={menuOpen=true})"
        val WHITESPACE = Regex("\\s+")
    }
}
