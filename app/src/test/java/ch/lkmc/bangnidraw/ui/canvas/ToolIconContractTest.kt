package ch.lkmc.bangnidraw.ui.canvas

import ch.lkmc.bangnidraw.ui.glyphs.ToolGlyphs
import ch.lkmc.bangnidraw.ui.glyphs.WaterToolGlyphs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ToolIconContractTest {

    @Test
    fun `ambiguous tools use purpose-built silhouettes`() {
        val rail = source(TOOL_RAIL_PATH)
        // The mapping is one shared file now, compiled by :app and :desktop
        // alike, so neither rail can drift from the other on artwork.
        val glyphs = source(BRUSH_GLYPHS_PATH)

        assertTrue("icon = brushGlyphIcon(BrushToolGlyphPolicy.forPreset(preset))" in rail)
        assertTrue("BrushToolGlyph.ERASER -> ToolGlyphs.Eraser" in glyphs)
        assertTrue("BrushToolGlyph.MARKER -> ToolGlyphs.Marker" in glyphs)
        assertTrue("BrushToolGlyph.SPRAY_CAN -> ToolGlyphs.SprayCan" in glyphs)
        assertTrue("BrushToolGlyph.WATERCOLOR -> WaterToolGlyphs.Watercolor" in glyphs)
        assertTrue("BrushToolGlyph.PIGMENT_WASH -> ToolGlyphs.PigmentWash" in glyphs)
        assertTrue("description = { brushPresetName(preset) }" in rail)
        // The wash must not share the Water tool's droplet: two identical
        // glyphs in one rail defeat the glance-recognition the rail is for.
        assertFalse("BrushToolGlyph.PIGMENT_WASH -> Icons.Filled.WaterDrop" in glyphs)
        // Guard against any duplicate icon across brush mappings, not just
        // the historical droplet collision with the Water tool.
        val brushIcons = Regex("BrushToolGlyph\\.\\w+\\s*->\\s*(\\S+)")
            .findAll(glyphs).map { it.groupValues[1] }.toList()
        assertFalse(brushIcons.isEmpty(), "expected BrushToolGlyph icon mappings in rail")
        val duplicateIcons = brushIcons.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicateIcons.isEmpty(), "BrushToolGlyph mappings share an icon $duplicateIcons")
        // Brush-vs-tool collisions were the original bug; no brush glyph may
        // reuse the Water tool's droplet, not just PIGMENT_WASH.
        assertFalse("Icons.Filled.WaterDrop" in brushIcons, "brush glyph reuses the Water tool's droplet")
        assertFalse("if (preset.eraseMode) stringResource(R.string.tool_eraser)" in rail)
        assertFalse("DeleteSweep" in glyphs)
        assertFalse("Icons.Filled.Highlight" in glyphs)
    }

    @Test
    fun `custom silhouettes use the rail icon viewport`() {
        assertEquals(ICON_VIEWPORT, ToolGlyphs.Marker.viewportWidth)
        assertEquals(ICON_VIEWPORT, ToolGlyphs.Marker.viewportHeight)
        assertEquals(ICON_VIEWPORT, ToolGlyphs.Eraser.viewportWidth)
        assertEquals(ICON_VIEWPORT, ToolGlyphs.Eraser.viewportHeight)
        assertEquals(ICON_VIEWPORT, ToolGlyphs.SprayCan.viewportWidth)
        assertEquals(ICON_VIEWPORT, ToolGlyphs.SprayCan.viewportHeight)
        assertEquals(ICON_VIEWPORT, WaterToolGlyphs.Watercolor.viewportWidth)
        assertEquals(ICON_VIEWPORT, WaterToolGlyphs.Watercolor.viewportHeight)
        assertEquals(ICON_VIEWPORT, ToolGlyphs.PigmentWash.viewportWidth)
        assertEquals(ICON_VIEWPORT, ToolGlyphs.PigmentWash.viewportHeight)
        assertTrue(ToolGlyphs.Marker.name.contains("Marker"))
        assertTrue(ToolGlyphs.Eraser.name.contains("Eraser"))
        assertTrue(ToolGlyphs.SprayCan.name.contains("SprayCan"))
        assertTrue(WaterToolGlyphs.Watercolor.name.contains("Watercolor"))
        assertTrue(ToolGlyphs.PigmentWash.name.contains("PigmentWash"))
        assertEquals(SEMANTIC_PARTS, ToolGlyphs.Marker.root.size)
        assertEquals(SEMANTIC_PARTS, ToolGlyphs.Eraser.root.size)
        assertEquals(SEMANTIC_PARTS, ToolGlyphs.SprayCan.root.size)
        assertEquals(SEMANTIC_PARTS, WaterToolGlyphs.Watercolor.root.size)
        assertEquals(SEMANTIC_PARTS, ToolGlyphs.PigmentWash.root.size)
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
        const val BRUSH_GLYPHS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/glyphs/BrushGlyphs.kt"
    }
}
