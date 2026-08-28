package ch.lkmc.bangnidraw.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class AccessibilitySemanticsContractTest {

    @Test
    fun `fill controls expose labels groups and one toggle target`() {
        val source = source(FILL_SETTINGS_PATH)
        val sliderStart = source.indexOf(FILL_SLIDER_START)
        if (sliderStart < 0) fail("missing Slider in $FILL_SETTINGS_PATH")

        val sliderEnd = source.indexOf(FILL_TOGGLE_START, sliderStart)
        if (sliderEnd <= sliderStart) fail("missing FillToggle in $FILL_SETTINGS_PATH")

        val sliderBody = source.substring(sliderStart, sliderEnd)

        assertTrue("contentDescription = label" in sliderBody, "fill sliders need names")
        assertTrue(".selectableGroup()" in source, "fill reference choices need a group")
        assertTrue(".toggleable(" in source, "the whole fill switch row must toggle")
        assertTrue(
            SWITCH_DELEGATES_TO_ROW.containsMatchIn(source),
            "the nested switch must not create a second toggle target",
        )
    }

    @Test
    fun `settings radio choices expose independent groups`() {
        val source = source(SETTINGS_PATH)

        assertTrue(
            SELECTABLE_GROUP.findAll(source).count() >= SETTINGS_CHOICE_GROUPS,
            "theme, hand, pen button, eraser end, pressure, and mixer need separate groups",
        )
    }

    @Test
    fun `settings themes are a named radio group`() {
        val source = source(SETTINGS_PATH)
        val appearance = sourceSection(
            source,
            SETTINGS_APPEARANCE_START,
            SETTINGS_DRAWING_START,
        )
        val themeChoice = sourceSection(source, THEME_CHOICE_START, CHOICE_ROW_START)

        assertTrue(
            ".selectableGroup()" in appearance,
            "theme choices need one selectable group",
        )
        for (label in THEME_LABELS) {
            assertTrue(label in appearance, "theme choice needs its visible name: $label")
        }
        assertTrue(
            "role = Role.RadioButton" in themeChoice,
            "theme choices need radio-button semantics",
        )
        assertTrue(
            THEME_RADIO_DELEGATES_TO_ROW.containsMatchIn(themeChoice),
            "the nested theme radio must delegate its click to the row",
        )
    }

    @Test
    fun `storage full announcement is assertive`() {
        val source = source(CANVAS_SCREEN_PATH)

        assertTrue(
            "liveRegion = LiveRegionMode.Assertive" in source,
            "storage-full must be announced when it appears",
        )
    }

    @Test
    fun `quick palette has one entry action and an accessible exit`() {
        val canvasSource = source(CANVAS_SCREEN_PATH)
        val stripSource = source(TOP_STRIP_PATH)

        assertTrue(
            NAMED_CLICK.containsMatchIn(canvasSource),
            "the quick-palette scrim needs a named dismiss action",
        )
        assertTrue(
            NULL_SAFE_SCREEN_READER.containsMatchIn(canvasSource),
            "screen-reader checks must tolerate a missing platform service",
        )
        assertTrue(
            !QUICK_PALETTE_ACTION.containsMatchIn(stripSource),
            "combinedClickable already exposes the named long-press action",
        )
        assertTrue(
            FOCUS_REQUESTER_ATTACHMENT.containsMatchIn(stripSource),
            "the color trigger must accept restored focus",
        )
        assertTrue(
            FOCUS_RESTORE.containsMatchIn(canvasSource),
            "dismissing the quick palette must restore its trigger focus",
        )
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun sourceSection(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        if (startIndex < 0) fail("missing source marker: $start")

        val contentStart = startIndex + start.length
        val endIndex = source.indexOf(end, contentStart)
        if (endIndex < contentStart) fail("missing source marker: $end")

        return source.substring(contentStart, endIndex)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val FILL_SETTINGS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/FillSettingsSheet.kt"
        const val SETTINGS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/SettingsSheet.kt"
        const val CANVAS_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt"
        const val TOP_STRIP_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/TopStrip.kt"
        const val SETTINGS_CHOICE_GROUPS = 6
        const val SETTINGS_APPEARANCE_START =
            "item { SectionTitle(R.string.settings_appearance) }"
        const val SETTINGS_DRAWING_START =
            "item { SectionTitle(R.string.settings_drawing) }"
        const val THEME_CHOICE_START = "private fun ThemeChoiceRow("
        const val CHOICE_ROW_START = "private fun ChoiceRow("
        const val FILL_SLIDER_START = "\n    Slider("
        const val FILL_TOGGLE_START = "private fun FillToggle"
        val SELECTABLE_GROUP = Regex("""\.selectableGroup\(\)""")
        val THEME_LABELS = listOf(
            "R.string.settings_theme_saffron",
            "R.string.settings_theme_coral",
            "R.string.settings_theme_violet",
            "R.string.settings_theme_teal",
        )
        val SWITCH_DELEGATES_TO_ROW = Regex(
            """Switch\(\s*checked\s*=\s*checked,\s*onCheckedChange\s*=\s*null""",
        )
        val THEME_RADIO_DELEGATES_TO_ROW = Regex(
            """RadioButton\(\s*selected\s*=\s*selected,\s*onClick\s*=\s*null""",
        )
        val NAMED_CLICK = Regex("""onClick\(\s*label\s*=\s*recentDismissLabel""")
        val NULL_SAFE_SCREEN_READER = Regex(
            """accessibilityManager\?\.\s*hasActiveScreenReader\(\)\s*!=\s*true""",
        )
        val QUICK_PALETTE_ACTION = Regex(
            """CustomAccessibilityAction\(\s*quickPaletteLabel""",
        )
        val FOCUS_REQUESTER_ATTACHMENT = Regex(
            """\.focusRequester\(\s*recentPaletteFocusRequester\s*\)""",
        )
        val FOCUS_RESTORE = Regex(
            """recentPaletteFocusRequester\s*\.\s*requestFocus\(\s*\)""",
        )
    }
}
