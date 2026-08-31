package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
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

        // Every slider in this file goes through the deferred wrapper. Stated
        // as a count rather than a presence check, because "DeferredSettingSlider("
        // contains "SettingSlider(": equal counts mean every occurrence of the
        // latter belongs to the former, so a bare SettingSlider call anywhere —
        // in a sheet body or in a shared helper at the bottom of the file —
        // breaks it. A presence check would only prove the deferred slider is
        // used somewhere, which a partial revert survives.
        val sliders = SETTING_SLIDER.findAll(sheet).count()
        val deferred = DEFERRED_SLIDER.findAll(sheet).count()
        assertTrue(deferred > 0, "the tool sheets publish through the deferred slider")
        assertEquals(
            deferred,
            sliders,
            "$sliders slider call(s) but only $deferred deferred — a bare " +
                "SettingSlider publishes once per pointer sample",
        )

        // The historical spelling of the defect, kept as a second net and now
        // scanned over the whole file. It used to stop at the first
        // CurveEditor, which was both unnecessary and nearly fatal to the
        // pin: the curve editors take `onFinished`, not `onValueChangeFinished`,
        // so they could never have matched this needle — and the bound shrank
        // the guarded window to the first sheet's opening lines, leaving every
        // other sheet and both shared helpers unchecked.
        assertFalse(
            "onValueChangeFinished={}" in sheet,
            "a slider with a no-op finish handler is publishing on every frame",
        )

        // The two helpers every tool sheet shares, named explicitly: they are
        // the highest-traffic sliders in the app and the likeliest target of a
        // partial revert.
        for (helper in listOf("privatefunToolSizeSlider(", "privatefunToolSpacingSlider(")) {
            val body = sheet.substringAfter(helper, missingDelimiterValue = "")
            if (body.isEmpty()) fail("missing $helper in $RMW_PATH — renamed?")
            assertTrue(
                body.substringBefore("privatefun").contains("DeferredSettingSlider("),
                "$helper must publish on release",
            )
        }
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

    private fun compact(path: String): String = ContractTestSources
        .stripComments(ContractTestSources.read(path))
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

        // "DeferredSettingSlider(" contains "SettingSlider(", which is what
        // makes the equal-counts check above mean "no bare call".
        val SETTING_SLIDER = Regex("SettingSlider\\(")
        val DEFERRED_SLIDER = Regex("DeferredSettingSlider\\(")
        val WHITESPACE = Regex("""\s+""")
    }
}
