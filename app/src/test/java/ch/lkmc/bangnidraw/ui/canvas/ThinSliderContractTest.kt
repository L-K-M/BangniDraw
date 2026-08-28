package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class ThinSliderContractTest {

    @Test
    fun `vertical track keeps its requested length before rotation`() {
        val source = source(THIN_SLIDER_PATH)

        assertTrue(
            REQUIRED_WIDTH_CALL in source,
            "vertical slider width must escape its 48 dp parent constraint",
        )
    }

    @Test
    fun `thin slider replaces expressive geometry`() {
        val source = source(THIN_SLIDER_PATH)

        assertContract(source, CUSTOM_SLIDER_SLOTS, "custom thumb and track")
        assertContract(source, TRACK_THICKNESS, "4 dp track")
        assertContract(source, THUMB_DIAMETER, "20 dp thumb")
    }

    @Test
    fun `tool rail consumes core width and colors`() {
        val source = source(TOOL_RAIL_PATH)

        assertContract(source, RAIL_WIDTH, "core rail width")
        assertContract(source, COLOR_POLICY, "color policy")
        assertContract(source, CONTAINER_COLOR, "container color")
        assertContract(source, CONTENT_COLOR, "IconButton content color")
        assertContract(source, ICON_TINT, "icon tint")
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun assertContract(source: String, pattern: Regex, description: String) {
        assertTrue(pattern.containsMatchIn(source), "$description contract is missing")
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
        const val THIN_SLIDER_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ThinSlider.kt"
        const val TOOL_RAIL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ToolRail.kt"
        const val REQUIRED_WIDTH_CALL = ".requiredWidth(length)"
        val CUSTOM_SLIDER_SLOTS = Regex(
            """\bSlider\([\s\S]*?\bthumb\s*=\s*\{[\s\S]*?\btrack\s*=\s*\{""",
        )
        val TRACK_THICKNESS = Regex("""TRACK_THICKNESS\s*=\s*4\.dp""")
        val THUMB_DIAMETER = Regex("""THUMB_DIAMETER\s*=\s*20\.dp""")
        val RAIL_WIDTH = Regex("""modifier\.width\(\s*layout\.railWidthDp\.dp\s*\)""")
        val COLOR_POLICY = Regex(
            """railButtonColors\(\s*LocalAppTheme\.current\s*,\s*emphasis\s*\)""",
        )
        val CONTAINER_COLOR = Regex("""color\s*=\s*buttonColors\.container""")
        val CONTENT_COLOR = Regex(
            """iconButtonColors\(\s*contentColor\s*=\s*buttonColors\.icon\s*\)""",
        )
        val ICON_TINT = Regex("""tint\s*=\s*buttonColors\.icon""")
    }
}
