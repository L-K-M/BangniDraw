package ch.lkmc.bangnidraw.ui.home

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertTrue

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

        // The leading `}` is the title Column's closing brace: pinning the
        // button directly after it means an `if (...) {` wrapper — any
        // conditional reintroduction — breaks the match, keeping the
        // "unconditional" in the test's name actually asserted.
        assertTrue(
            "} Box { IconButton(onClick = { menuOpen = true })" in studio,
            "every card's footer must carry the actions button, unconditionally",
        )
        // Space-free so the retirement check is formatting-independent; the
        // menuOpen fragment keeps it specific to this button.
        assertTrue(
            "IconButton(onClick={menuOpen=true},modifier=Modifier.align(Alignment.TopEnd)" !in
                studio.replace(" ", ""),
            "the unavailable-only overlay button is retired; one affordance serves every card",
        )
        // The help text's promise stays true: the button opens the same menu
        // the long press opens, delete included.
        val footer = studio.substringAfter("Box { IconButton(onClick = { menuOpen = true })")
        assertTrue("DropdownMenu(expanded = menuOpen" in footer)
    }

    private companion object {
        const val STUDIO_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioScreen.kt"
        val WHITESPACE = Regex("\\s+")
    }
}
