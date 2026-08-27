package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ToolIconContractTest {

    @Test
    fun `marker and eraser use purpose-built silhouettes`() {
        val rail = source(TOOL_RAIL_PATH)

        assertTrue("icon = iconFor(BrushToolGlyphPolicy.forPreset(preset))" in rail)
        assertTrue("BrushToolGlyph.ERASER -> ToolGlyphs.Eraser" in rail)
        assertTrue("BrushToolGlyph.MARKER -> ToolGlyphs.Marker" in rail)
        assertTrue("description = { brushPresetName(preset) }" in rail)
        assertFalse("if (preset.eraseMode) stringResource(R.string.tool_eraser)" in rail)
        assertFalse("DeleteSweep" in rail)
        assertFalse("Icons.Filled.Highlight" in rail)
    }

    @Test
    fun `custom silhouettes use the rail icon viewport`() {
        assertEquals(ICON_VIEWPORT, ToolGlyphs.Marker.viewportWidth)
        assertEquals(ICON_VIEWPORT, ToolGlyphs.Marker.viewportHeight)
        assertEquals(ICON_VIEWPORT, ToolGlyphs.Eraser.viewportWidth)
        assertEquals(ICON_VIEWPORT, ToolGlyphs.Eraser.viewportHeight)
        assertTrue(ToolGlyphs.Marker.name.contains("Marker"))
        assertTrue(ToolGlyphs.Eraser.name.contains("Eraser"))
        assertEquals(SEMANTIC_PARTS, ToolGlyphs.Marker.root.size)
        assertEquals(SEMANTIC_PARTS, ToolGlyphs.Eraser.root.size)
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun repositoryRoot(): File {
        val userDirectory = System.getProperty(USER_DIRECTORY_PROPERTY)
            ?: fail("$USER_DIRECTORY_PROPERTY is unavailable")
        val workingDirectory = File(userDirectory).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val ICON_VIEWPORT = 24f
        const val SEMANTIC_PARTS = 3
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val TOOL_RAIL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ToolRail.kt"
    }
}
