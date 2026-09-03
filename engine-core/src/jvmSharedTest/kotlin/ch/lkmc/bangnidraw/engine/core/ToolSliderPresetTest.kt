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
    fun `watercolor brush slider presents flow`() {
        val watercolor = BrushPresets.INK_PEN.copy(
            opacity = 1f,
            flow = 0.35f,
            mixing = true,
            watercolor = WatercolorBehavior(),
            bufferMode = BufferMode.Accumulate,
        )

        val presentation = ToolSliderPreset.forKind(ToolKind.Brush(watercolor))!!

        assertSame(watercolor, presentation)
        assertEquals(watercolor.flow, presentation.flow)
        assertEquals(1f, watercolor.opacity)
        assertEquals(watercolor.flow, ToolSliderPreset.secondaryValue(ToolKind.Brush(watercolor)))
        assertEquals(
            ToolSliderSecondary.FLOW,
            ToolSliderPreset.secondaryFor(ToolKind.Brush(watercolor)),
        )
    }

    @Test
    fun `the secondary slider writes back to the field it reads`() {
        val paint = BrushPresets.INK_PEN.copy(opacity = 1f, flow = 0.4f)
        val watercolor = BrushPresets.INK_PEN.copy(
            opacity = 1f,
            flow = 0.35f,
            mixing = true,
            watercolor = WatercolorBehavior(),
            bufferMode = BufferMode.Accumulate,
        )

        val tunedPaint = ToolSliderPreset.withSecondary(paint, 0.25f)
        val tunedWatercolor = ToolSliderPreset.withSecondary(watercolor, 0.25f)

        assertEquals(0.25f, tunedPaint.opacity)
        assertEquals(paint.flow, tunedPaint.flow)
        assertEquals(0.25f, ToolSliderPreset.secondaryValue(ToolKind.Brush(tunedPaint)))
        // A watercolor preset's opacity is an invariant, so the edit lands on flow.
        assertEquals(0.25f, tunedWatercolor.flow)
        assertEquals(1f, tunedWatercolor.opacity)
        assertEquals(0.25f, ToolSliderPreset.secondaryValue(ToolKind.Brush(tunedWatercolor)))
    }

    @Test
    fun `the secondary slider clamps and ignores a NaN`() {
        val paint = BrushPresets.INK_PEN.copy(opacity = 0.6f)
        val watercolor = BrushPresets.INK_PEN.copy(
            opacity = 1f,
            flow = 0.35f,
            mixing = true,
            watercolor = WatercolorBehavior(),
            bufferMode = BufferMode.Accumulate,
        )

        assertEquals(1f, ToolSliderPreset.withSecondary(paint, 4f).opacity)
        assertEquals(0f, ToolSliderPreset.withSecondary(paint, -1f).opacity)
        assertEquals(0.6f, ToolSliderPreset.withSecondary(paint, Float.NaN).opacity)
        assertEquals(1f, ToolSliderPreset.withSecondary(watercolor, 4f).flow)
        assertEquals(0.35f, ToolSliderPreset.withSecondary(watercolor, Float.NaN).flow)
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
    fun `water sliders show size and a secondary water load`() {
        val params = WaterParams(size = 80f, waterLoad = 0.65f)

        val preset = ToolSliderPreset.forKind(ToolKind.Water(params))!!

        assertEquals(params.size, preset.size)
        assertEquals(params.sizeMin, preset.sizeMin)
        assertEquals(params.sizeMax, preset.sizeMax)
        // Water load belongs to the secondary slider alone; stuffing it
        // into opacity would let a future reader misread one as the other.
        assertEquals(1f, preset.opacity)
        assertEquals(1f, preset.flow)
        assertEquals(
            params.waterLoad,
            ToolSliderPreset.secondaryValue(ToolKind.Water(params)),
        )
        assertEquals(
            ToolSliderSecondary.WATER,
            ToolSliderPreset.secondaryFor(ToolKind.Water(params)),
        )
        assertEquals(
            ToolSliderSecondary.OPACITY,
            ToolSliderPreset.secondaryFor(ToolKind.Brush(preset)),
        )
    }

    @Test
    fun `changing water load never leaks into the primary water preset`() {
        val low = ToolSliderPreset.forKind(ToolKind.Water(WaterParams(size = 80f, waterLoad = 0.2f)))!!
        val high = ToolSliderPreset.forKind(ToolKind.Water(WaterParams(size = 80f, waterLoad = 0.9f)))!!

        assertEquals(low.size, high.size)
        assertEquals(low.sizeMin, high.sizeMin)
        assertEquals(low.sizeMax, high.sizeMax)
        assertEquals(low.opacity, high.opacity)
        assertEquals(low.flow, high.flow)
    }

    @Test
    fun `fill and eyedropper get no sliders`() {
        assertNull(ToolSliderPreset.forKind(ToolKind.Fill(FillParams())))
        assertNull(ToolSliderPreset.forKind(ToolKind.Eyedropper(EyedropperParams())))
    }
}
