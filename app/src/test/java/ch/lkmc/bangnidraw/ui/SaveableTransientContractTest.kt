package ch.lkmc.bangnidraw.ui

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Transient dialogs survive rotation. A configuration change recreates the
 * composition, so a plain `remember` silently dismisses an open dialog and
 * discards a half-typed draft — a vanished delete confirmation reads as "it
 * deleted", and a lost rename is typed twice. The screen's own
 * `showSettings`/`showNewCanvas` and every New Canvas field already follow
 * this convention; these are the stragglers. Menus stay transient on
 * purpose, like every platform menu.
 */
class SaveableTransientContractTest {

    @Test
    fun `studio card dialogs and their drafts are saveable`() {
        val studio = ContractTestSources.read(STUDIO_PATH).replace(WHITESPACE, " ")

        for (state in listOf("confirmDelete", "renaming", "sharing", "deleteGalleryToo")) {
            assertTrue(
                "var $state by rememberSaveable { mutableStateOf(false) }" in studio,
                "$state must survive rotation",
            )
        }
        // The declaration alone: the contract is saveability, not how the
        // draft is seeded.
        assertTrue(
            "var text by rememberSaveable" in studio,
            "the rename draft must survive rotation",
        )
    }

    @Test
    fun `help popups are saveable`() {
        val info = ContractTestSources.read(INFO_HELP_PATH).replace(WHITESPACE, " ")
        assertTrue(
            "var open by rememberSaveable { mutableStateOf(false) }" in info,
            "the (i) dialog must survive rotation — it is the one composable all in-app documentation goes through",
        )

        val strip = ContractTestSources.read(TOP_STRIP_PATH).replace(WHITESPACE, " ")
        assertTrue(
            "var showHelp by rememberSaveable { mutableStateOf(false) }" in strip,
            "the canvas Help dialog must survive rotation",
        )
    }

    private companion object {
        const val STUDIO_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioScreen.kt"
        const val INFO_HELP_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/common/InfoHelp.kt"
        const val TOP_STRIP_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/TopStrip.kt"

        /** House rule for source-contract tests: reformats must not fail them. */
        val WHITESPACE = Regex("\\s+")
    }
}
