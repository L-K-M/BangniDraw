package ch.lkmc.bangnidraw.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AccessibleChoiceRowsContractTest {

    @Test
    fun `new canvas radios delegate to one grouped row action`() {
        val source = source(NEW_CANVAS_PATH)

        assertTrue(".selectableGroup()" in source)
        assertEquals(RADIO_ROWS, DELEGATING_RADIO.findAll(source).count())
        assertTrue(CUSTOM_RADIO_ROLE.containsMatchIn(source))
        assertTrue(PRESET_RADIO_ROLE.containsMatchIn(source))
    }

    @Test
    fun `delete gallery label and checkbox share one row action`() {
        val source = source(STUDIO_SCREEN_PATH)

        assertTrue(DELETE_ROW_TOGGLE.containsMatchIn(source))
        assertTrue(DELETE_CHECKBOX_DELEGATES.containsMatchIn(source))
        assertTrue("role = Role.Checkbox" in source)
    }

    @Test
    fun `brush switch labels and controls share one row action`() {
        val source = source(BRUSH_SETTINGS_PATH)
        val toggleRow = section(source, TOGGLE_ROW_START, TOGGLE_ROW_END)

        assertTrue(".toggleable(" in toggleRow)
        assertTrue("role = Role.Switch" in toggleRow)
        assertTrue("onCheckedChange = null" in toggleRow)
        assertTrue("ToggleRow(" in source(RMW_SETTINGS_PATH))
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()
    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        if (startIndex < 0) fail("missing source marker: $start")

        val endIndex = source.indexOf(end, startIndex + start.length)
        if (endIndex <= startIndex) fail("missing source marker: $end")

        return source.substring(startIndex, endIndex)
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
        const val NEW_CANVAS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/NewCanvasDialog.kt"
        const val STUDIO_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioScreen.kt"
        const val BRUSH_SETTINGS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/BrushSettingsSheet.kt"
        const val RMW_SETTINGS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/RmwSettingsSheet.kt"
        const val TOGGLE_ROW_START = "internal fun ToggleRow("
        const val TOGGLE_ROW_END = "internal fun CurveEditor("
        const val RADIO_ROWS = 2
        val CUSTOM_RADIO_ROLE = Regex(
            """selected\s*=\s*isCustom,\s*role\s*=\s*Role\.RadioButton""",
        )
        val PRESET_RADIO_ROLE = Regex(
            """enabled\s*=\s*preset\.enabled,\s*role\s*=\s*Role\.RadioButton""",
        )
        val DELEGATING_RADIO = Regex(
            """RadioButton\([\s\S]*?onClick\s*=\s*null""",
        )
        val DELETE_ROW_TOGGLE = Regex(
            """\.toggleable\(\s*value\s*=\s*deleteGalleryToo""",
        )
        val DELETE_CHECKBOX_DELEGATES = Regex(
            """Checkbox\(\s*checked\s*=\s*deleteGalleryToo,\s*onCheckedChange\s*=\s*null""",
        )
    }
}
