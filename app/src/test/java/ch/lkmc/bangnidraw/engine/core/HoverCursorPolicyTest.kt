package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class HoverCursorPolicyTest {

    private val brush = BrushPreset("brush", "Brush", size = 4f)
    private val eraser = BrushPreset("eraser", "Eraser", size = 20f, eraseMode = true)

    @Test
    fun `finger hover has no cursor`() {
        assertNull(
            HoverCursorPolicy.resolve(
                PointerTool.FINGER,
                ToolKind.Brush(brush),
                eraser,
                canvasToScreenScale = 1f,
            ),
        )
    }

    @Test
    fun `brush cursor follows canvas scale and adds a crosshair when tiny`() {
        assertEquals(
            HoverCursorSpec(2f, HoverRing.Solid, crosshair = true, ink = true),
            HoverCursorPolicy.resolve(
                PointerTool.STYLUS,
                ToolKind.Brush(brush),
                eraser,
                canvasToScreenScale = 0.5f,
            ),
        )
    }

    @Test
    fun `eraser end overrides the active brush`() {
        assertEquals(
            HoverCursorSpec(40f, HoverRing.Dashed, crosshair = false, ink = false),
            HoverCursorPolicy.resolve(
                PointerTool.ERASER,
                ToolKind.Brush(brush),
                eraser,
                canvasToScreenScale = 2f,
            ),
        )
    }

    @Test
    fun `eyedropper uses its glyph instead of a ring`() {
        assertEquals(
            HoverCursorSpec(0f, HoverRing.None, crosshair = false, ink = false),
            HoverCursorPolicy.resolve(
                PointerTool.MOUSE,
                ToolKind.Eyedropper(),
                eraser,
                canvasToScreenScale = 1f,
            ),
        )
    }

    @Test
    fun `water uses a colorless solid ring`() {
        assertEquals(
            HoverCursorSpec(4f, HoverRing.Solid, crosshair = true, ink = false),
            HoverCursorPolicy.resolve(
                PointerTool.STYLUS,
                ToolKind.Water(WaterParams(size = 8f)),
                eraser,
                canvasToScreenScale = 0.5f,
            ),
        )
    }

    @Test
    fun `eraser end takes precedence over the eyedropper glyph`() {
        assertEquals(
            HoverCursorSpec(20f, HoverRing.Dashed, crosshair = false, ink = false),
            HoverCursorPolicy.resolve(
                PointerTool.ERASER,
                ToolKind.Eyedropper(),
                eraser,
                canvasToScreenScale = 1f,
            ),
        )
    }

    @Test
    fun `only ink-laying cursors carry the brush colour`() {
        // A paint brush shows what it will lay down.
        assertTrue(
            HoverCursorPolicy.resolve(
                PointerTool.STYLUS,
                ToolKind.Brush(brush),
                eraser,
                canvasToScreenScale = 1f,
            )!!.ink,
        )
        // The eraser end, and an erase-mode preset, remove — no ink to show.
        assertFalse(
            HoverCursorPolicy.resolve(
                PointerTool.ERASER,
                ToolKind.Brush(brush),
                eraser,
                canvasToScreenScale = 1f,
            )!!.ink,
        )
        assertFalse(
            HoverCursorPolicy.resolve(
                PointerTool.STYLUS,
                ToolKind.Brush(eraser),
                eraser,
                canvasToScreenScale = 1f,
            )!!.ink,
        )
    }
}
