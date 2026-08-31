package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The tool sheets publish a slider once, on release.
 *
 * Their parameters are session state — each sheet's own header says the
 * settings apply to the next touch — so nothing on screen previews them while
 * the thumb moves. Committing per frame therefore bought nothing and cost a
 * full `UiState` republish per pointer sample, each one re-executing
 * `CanvasContent`, the entire Canvas chrome, for the length of every drag.
 *
 * Deliberately not extended to `BrushSettingsSheet`: its live preview reads
 * the committed preset, so deferring there is ANALYSIS U12's call, together
 * with the curve knots it also covers. The two `CurveEditor` call sites in
 * `RmwSettingsSheet` are that same U12 change and are excluded here.
 */
class ToolSheetCommitContractTest {

    @Test
    fun `no tool-sheet slider commits on every frame`() {
        val sheet = compact(RMW_PATH)

        // The whole defect in one needle: a SettingSlider whose finish handler
        // is a no-op commits from onValueChange, i.e. once per pointer sample.
        assertFalse(
            "onValueChangeFinished={}" in sheet.substringBefore(FIRST_CURVE_EDITOR),
            "a slider with a no-op finish handler is publishing on every frame",
        )
        assertTrue(
            "DeferredSettingSlider(" in sheet,
            "the tool sheets publish through the deferred slider",
        )
    }

    @Test
    fun `the deferred slider publishes its draft on release, not per frame`() {
        // Bounded by the next declaration, not by "@Composable": the
        // parameter list itself carries a @Composable-typed lambda, so that
        // marker matches inside the signature and truncates the window.
        val helper = section(BRUSH_PATH, "internalfunDeferredSettingSlider(", "internalfunToggleRow(")

        assertTrue("vardraftbyremember(value)" in helper, "the draft is local and re-keys on value")
        assertTrue("onValueChange={draft=it}" in helper, "dragging must only move the draft")
        assertTrue(
            "onValueChangeFinished={onCommit(draft)}" in helper,
            "release is the one commit",
        )
    }

    @Test
    fun `the fill sheet publishes on release too`() {
        val fill = section(FILL_PATH, "privatefunFillSlider(", "privatefunFillToggle(")

        assertTrue("vardraftbyremember(value)" in fill)
        assertTrue("onValueChange={draft=it}" in fill)
        assertTrue("onValueChangeFinished={onChanged(draft)}" in fill)
    }

    /**
     * The expand readout formats through `%1$d`, so the draft — a Float — has
     * to round back to an Int before it reaches the format. Passing the raw
     * Float throws `IllegalFormatConversionException` the moment the sheet
     * renders, which is exactly the mistake the draft rewrite invites.
     */
    @Test
    fun `the integer readouts round their draft`() {
        val fill = compact(FILL_PATH)

        assertTrue(
            "stringResource(R.string.fill_expand_value,it.roundToInt())" in fill,
            "a %d readout must round the Float draft, not pass it raw",
        )
    }

    private fun compact(path: String): String = ContractTestSources.read(path)
        .replace(COMMENTS, "")
        .replace(WHITESPACE, "")

    private fun section(path: String, startMarker: String, endMarker: String): String {
        val source = compact(path)
        val start = source.indexOf(startMarker)
        if (start < 0) fail("missing $startMarker in $path — renamed?")
        val end = source.indexOf(endMarker, start + startMarker.length)
        if (end <= start) fail("missing $endMarker after $startMarker in $path")

        return source.substring(start, end)
    }

    private companion object {
        const val RMW_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/RmwSettingsSheet.kt"
        const val FILL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/FillSettingsSheet.kt"
        const val BRUSH_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/BrushSettingsSheet.kt"

        /**
         * The first `CurveEditor` bounds the slider window: the knot sliders
         * behind it are U12's, and their `onFinished = {}` must not be read as
         * one of the sliders this test governs.
         */
        const val FIRST_CURVE_EDITOR = "CurveEditor("
        val COMMENTS = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val WHITESPACE = Regex("""\s+""")
    }
}
