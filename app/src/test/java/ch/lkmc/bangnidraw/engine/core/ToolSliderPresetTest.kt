package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ToolSliderPresetTest {

    @Test
    fun `a brush preset drives its own sliders`() {
        val preset = BrushPresets.INK_PEN

        assertSame(preset, ToolSliderPreset.forKind(ToolKind.Brush(preset)))
    }

    @Test
    fun `smudge sliders carry the params the stroke will use`() {
        val params = SmudgeParams(size = 60f, strength = 0.3f)

        val preset = ToolSliderPreset.forKind(ToolKind.Smudge(params))!!

        assertEquals(params.size, preset.size)
        assertEquals(params.sizeMin, preset.sizeMin)
        assertEquals(params.sizeMax, preset.sizeMax)
        // The opacity slider is the strength slider for an RMW tool.
        assertEquals(params.strength, preset.opacity)
        assertEquals(params.strength, preset.flow)
    }

    @Test
    fun `blur sliders carry the params the stroke will use`() {
        val params = BlurParams(size = 90f, strength = 0.25f)

        val preset = ToolSliderPreset.forKind(ToolKind.Blur(params))!!

        assertEquals(params.size, preset.size)
        assertEquals(params.sizeMin, preset.sizeMin)
        assertEquals(params.sizeMax, preset.sizeMax)
        assertEquals(params.strength, preset.opacity)
        assertEquals(params.strength, preset.flow)
    }

    @Test
    fun `fill and eyedropper get no sliders`() {
        assertNull(ToolSliderPreset.forKind(ToolKind.Fill(FillParams())))
        assertNull(ToolSliderPreset.forKind(ToolKind.Eyedropper(EyedropperParams())))
    }
}
