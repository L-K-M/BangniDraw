package ch.lkmc.bangnidraw.i18n

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Chinese strings name one thing one way, and every help heading names
 * the control it explains.
 *
 * The rule English follows, stated precisely: a help heading uses the
 * control's **own term**, shortened where the label is long — "Snap right
 * angles" heads "Snap rotation to right angles", "Stylus only" heads
 * "Stylus-only drawing" — and reproduced exactly where it is already short,
 * as "Eraser end" and "Pressure" are. What it never does is reach for a
 * *different* word, and least of all for another control's word.
 *
 * The translation had drifted on both counts, twice onto a different
 * feature's term, which reads as help for the wrong control rather than as a
 * synonym. The pins below therefore cover the headings whose Chinese used
 * another term, not the ones that merely shorten a long label the way English
 * shortens the same label.
 *
 * Pinned rather than merely fixed: nothing else in the build compares a label
 * against the help that explains it, and a translation drifts one string at a
 * time.
 */
class ZhHansTerminologyContractTest {

    @Test
    fun `one word for stylus`() {
        // 触控笔 is the majority usage and the more common generic term in
        // Chinese Android UIs; 手写笔 was the minority spelling, and a user
        // reading the help for the setting in front of them met the other one.
        assertTrue(
            RETIRED_STYLUS !in strings(),
            "$RETIRED_STYLUS is retired in favor of $STYLUS",
        )
        assertTrue(STYLUS in strings(), "the strings must still name the stylus")
    }

    @Test
    fun `the Overlay blend mode owns its term`() {
        // 叠加 is the expected rendering of the Overlay blend mode. It had
        // also been the label for the unrelated brush build-up mode, so a
        // user who learned it in the Layer panel met it in Brush settings
        // meaning whether repeated dabs keep darkening.
        val owners = labels().filterValues { it == OVERLAY }.keys

        assertEquals(
            setOf("blend_overlay"),
            owners,
            "$OVERLAY is the Overlay blend mode's term; a second label claiming it is the collision",
        )
    }

    @Test
    fun `every help heading names the control it explains`() {
        val labels = labels()
        val failures = HELP_HEADINGS.mapNotNull { (labelKey, helpKey) ->
            val label = labels[labelKey] ?: fail("missing label $labelKey")
            val body = labels[helpKey] ?: fail("missing help body $helpKey")
            if ("$label：" in body) null else "$helpKey does not head a paragraph with $labelKey's label ($label)"
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `help text calls each option what the option is called`() {
        // The same rule one level down: an option a help paragraph lists —
        // the watercolor group, the buffer choices, the fill references —
        // has to be named with its own label, even where the sentence lists
        // it inline rather than heading a paragraph with it.
        val labels = labels()
        val failures = HELP_MENTIONS.mapNotNull { (labelKey, helpKey) ->
            val label = labels[labelKey] ?: fail("missing label $labelKey")
            val body = labels[helpKey] ?: fail("missing help body $helpKey")
            if (label in body) null else "$helpKey never names $labelKey by its label ($label)"
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun strings(): String = ContractTestSources.read(ZH_HANS_PATH)

    /**
     * Every `name` to its text, entities left as written.
     *
     * The parse is checked against the file rather than trusted: a regex that
     * silently drops an entry weakens every assertion built on this map, and
     * a dropped label cannot collide with anything. Counting open tags turns
     * what was a one-off manual verification into a standing one.
     */
    private fun labels(): Map<String, String> {
        val source = strings()
        val parsed = STRING_ENTRY
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val declared = STRING_OPEN_TAG.findAll(source).count()
        assertEquals(
            declared,
            parsed.size,
            "$ZH_HANS_PATH declares $declared string entries but only " +
                "${parsed.size} parsed — the entry regex has a gap",
        )

        return parsed
    }

    private companion object {
        const val ZH_HANS_PATH = "app/src/main/res/values-b+zh+Hans/strings.xml"
        const val STYLUS = "触控笔"
        const val RETIRED_STYLUS = "手写笔"
        const val OVERLAY = "叠加"

        /**
         * label key to the help body that heads a paragraph with that label.
         * A body explaining several controls appears once per control it
         * heads.
         */
        val HELP_HEADINGS = listOf(
            "brush_size" to "help_brush_stroke_body",
            "brush_grain" to "help_brush_paint_body",
            "brush_buffer_mode" to "help_brush_paint_body",
            "fill_reference" to "help_fill_body",
            "settings_snap_right_angles" to "help_drawing_body",
            "settings_eraser_end" to "help_drawing_body",
        )

        /**
         * label key to a help body that names it in passing rather than
         * heading a paragraph with it — the watercolor group is one inline
         * list, and the buffer and reference options are named inside their
         * own paragraph's sentence.
         */
        val HELP_MENTIONS = listOf(
            "water_amount" to "help_brush_paint_body",
            "water_spread" to "help_brush_paint_body",
            "water_granulation" to "help_brush_paint_body",
            "water_edge_darkening" to "help_brush_paint_body",
            "brush_buffer_max" to "help_brush_paint_body",
            "brush_buffer_accumulate" to "help_brush_paint_body",
            "fill_reference_current" to "help_fill_body",
            "fill_reference_composite" to "help_fill_body",
        )
        /**
         * Attributes in any position, because XML permits any order and a
         * dropped entry is a silent hole: `translatable="false"` and
         * `formatted="false"` both appear in the English file, and a label
         * missing from the map cannot collide with anything.
         *
         * The `\s` after `<string` is load-bearing — it keeps `<string-array`
         * out, whose items are not labels.
         */
        /** Open tags, for the parse-coverage check in [labels]. */
        val STRING_OPEN_TAG = Regex("""<string\s[^>]*>""")
        val STRING_ENTRY = Regex(
            """<string\s[^>]*?name="([^"]+)"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
