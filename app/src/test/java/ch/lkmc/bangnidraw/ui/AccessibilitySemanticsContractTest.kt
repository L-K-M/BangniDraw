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
    fun `settings radio choices expose four independent groups`() {
        val source = source(SETTINGS_PATH)

        assertTrue(
            SELECTABLE_GROUP.findAll(source).count() >= SETTINGS_CHOICE_GROUPS,
            "hand, pen button, pressure, and mixer need separate groups",
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

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

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
        const val SETTINGS_CHOICE_GROUPS = 4
        const val FILL_SLIDER_START = "\n    Slider("
        const val FILL_TOGGLE_START = "\n@Composable\nprivate fun FillToggle"
        val SELECTABLE_GROUP = Regex("""\.selectableGroup\(\)""")
        val SWITCH_DELEGATES_TO_ROW = Regex(
            """Switch\(\s*checked\s*=\s*checked,\s*onCheckedChange\s*=\s*null""",
        )
    }
}
