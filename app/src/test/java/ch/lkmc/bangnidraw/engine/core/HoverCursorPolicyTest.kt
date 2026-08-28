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
    fun `water uses a colorless solid ring sized to its bloom`() {
        // spreadPx = ceil(radius * spread * 0.5) = ceil(4 * 0.65 * 0.5) = 2
        assertEquals(
            HoverCursorSpec(6f, HoverRing.Solid, crosshair = false, ink = false),
            HoverCursorPolicy.resolve(
                PointerTool.STYLUS,
                ToolKind.Water(WaterParams(size = 8f, spread = 0.65f)),
                eraser,
                canvasToScreenScale = 0.5f,
            ),
        )
    }

    @Test
    fun `water ring grows with spread and stops at the bloom cap`() {
        val dry = HoverCursorPolicy.resolve(
            PointerTool.STYLUS,
            ToolKind.Water(WaterParams(size = 400f, spread = 0f)),
            eraser,
            canvasToScreenScale = 1f,
        )
        val wet = HoverCursorPolicy.resolve(
            PointerTool.STYLUS,
            ToolKind.Water(WaterParams(size = 400f, spread = 1f)),
            eraser,
            canvasToScreenScale = 1f,
        )

        assertEquals(400f, dry!!.diameterPx)
        // A 200 px radius wants 100 px of spread; the shared cap holds it at 32.
        assertEquals(464f, wet!!.diameterPx)
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
