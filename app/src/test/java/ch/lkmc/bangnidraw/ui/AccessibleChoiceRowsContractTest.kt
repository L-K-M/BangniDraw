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

        assertTrue(SELECTABLE_GROUP.containsMatchIn(source))
        assertTrue(SELECTABLE_GROUP_SPACING.containsMatchIn(source))
        val totalRadios = TOTAL_RADIO.findAll(source).count()
        assertTrue(totalRadios > 0, "New Canvas must retain a radio choice")
        assertEquals(
            totalRadios,
            DELEGATING_RADIO.findAll(source).count(),
        )
        assertTrue(CUSTOM_RADIO_ROLE.containsMatchIn(source))
        assertTrue(PRESET_RADIO_ROLE.containsMatchIn(source))
    }

    @Test
    fun `delete gallery label and checkbox share one row action`() {
        val source = source(STUDIO_SCREEN_PATH)

        assertTrue(DELETE_ROW_TOGGLE.containsMatchIn(source))
        assertTrue(DELETE_CHECKBOX_DELEGATES.containsMatchIn(source))
        assertTrue(CHECKBOX_ROLE.containsMatchIn(source))
    }

    @Test
    fun `brush switch labels and controls share one row action`() {
        val source = source(BRUSH_SETTINGS_PATH)
        val toggleRow = bracedBlock(source, TOGGLE_ROW_START)

        assertTrue(TOGGLEABLE.containsMatchIn(toggleRow))
        assertTrue(SWITCH_ROLE.containsMatchIn(toggleRow))
        assertTrue(DELEGATING_SWITCH.containsMatchIn(toggleRow))
        assertTrue(TOGGLE_ROW_CALL.containsMatchIn(source(RMW_SETTINGS_PATH)))
    }

    @Test
    fun `brace scanner ignores comments and quoted braces`() {
        val source = """
            fun target() {
                val text = "}"
                // }
                /* { */
                keep()
            }
            fun next() = Unit
        """.trimIndent()

        val block = bracedBlock(source, "fun target()")

        assertTrue("keep()" in block)
        assertTrue("fun next()" !in block)
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun bracedBlock(source: String, marker: String): String {
        val markerIndex = source.indexOf(marker)
        if (markerIndex < 0) fail("missing block marker: $marker")

        val openIndex = source.indexOf('{', markerIndex + marker.length)
        if (openIndex < 0) fail("missing block start: $marker")

        var depth = 0
        var index = openIndex
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    val newline = source.indexOf('\n', index)
                    if (newline < 0) break
                    index = newline
                }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2)
                    if (end < 0) break
                    index = end + 1
                }
                source[index] == '"' || source[index] == '\'' -> {
                    var close = index + 1
                    while (close < source.length && source[close] != source[index]) {
                        if (source[close] == '\\') close += 1
                        close += 1
                    }
                    index = close
                }
                source[index] == '{' -> depth += 1
                source[index] == '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(markerIndex, index + 1)
                }
            }
            index += 1
        }

        fail("unclosed block: $marker")
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
        val CUSTOM_RADIO_ROLE = Regex(
            """selected\s*=\s*isCustom,\s*role\s*=\s*Role\.RadioButton""",
        )
        val PRESET_RADIO_ROLE = Regex(
            """enabled\s*=\s*preset\.enabled,\s*role\s*=\s*Role\.RadioButton""",
        )
        val SELECTABLE_GROUP_SPACING = Regex(
            """Modifier\.selectableGroup\s*\(\s*\),\s*verticalArrangement\s*=\s*Arrangement\.spacedBy\s*\(\s*4\.dp\s*\)""",
        )
        val SELECTABLE_GROUP = Regex("""\.selectableGroup\s*\(\s*\)""")
        val TOTAL_RADIO = Regex("""RadioButton\s*\(""")
        val DELEGATING_RADIO = Regex(
            """RadioButton\s*\([\s\S]*?onClick\s*=\s*null""",
        )
        val CHECKBOX_ROLE = Regex("""role\s*=\s*Role\.Checkbox""")
        val TOGGLEABLE = Regex("""\.toggleable\s*\(""")
        val SWITCH_ROLE = Regex("""role\s*=\s*Role\.Switch""")
        val DELEGATING_SWITCH = Regex("""onCheckedChange\s*=\s*null""")
        val TOGGLE_ROW_CALL = Regex("""ToggleRow\s*\(""")
        val DELETE_ROW_TOGGLE = Regex(
            """\.toggleable\(\s*value\s*=\s*deleteGalleryToo""",
        )
        val DELETE_CHECKBOX_DELEGATES = Regex(
            """Checkbox\s*\(\s*checked\s*=\s*deleteGalleryToo,\s*onCheckedChange\s*=\s*null""",
        )
    }
}
