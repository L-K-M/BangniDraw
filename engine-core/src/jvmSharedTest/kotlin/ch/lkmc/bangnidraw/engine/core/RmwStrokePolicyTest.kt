package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RmwStrokePolicyTest {

    @Test
    fun `smudge fixes pickup storage and mixing at pen down`() {
        val params = SmudgeParams(sizeMax = 400.2f, pickupRate = 0.35f, mixing = true)

        val pigment = assertIs<RmwSpec.Smudge>(
            RmwStrokePolicy.spec(ToolKind.Smudge(params), pigmentMixer),
        )
        val rgb = assertIs<RmwSpec.Smudge>(
            RmwStrokePolicy.spec(ToolKind.Smudge(params), RgbMixer),
        )

        assertEquals(403, pigment.pickupEdge)
        assertEquals(0.35f, pigment.pickupRate)
        assertEquals(RmwMixing.Pigment, pigment.mixing)
        assertEquals(RmwMixing.Linear, rgb.mixing)
    }

    @Test
    fun `blur fixes its bounded kernel at pen down`() {
        val params = BlurParams(size = 100f, radiusFraction = 0.5f)

        val spec = assertIs<RmwSpec.Blur>(
            RmwStrokePolicy.spec(ToolKind.Blur(params), pigmentMixer),
        )

        assertEquals(BlurKernel.MAX_RADIUS, spec.radius)
    }

    @Test
    fun `watercolor brush freezes its medium and mixer at pen down`() {
        val behavior = WatercolorBehavior(
            waterLoad = 0.7f,
            spread = 0.6f,
            granulation = 0.3f,
            edgeDarkening = 0.4f,
        )
        val preset = BrushPreset(
            id = "test.watercolor",
            name = "Watercolor",
            mixing = true,
            bufferMode = BufferMode.Accumulate,
            watercolor = behavior,
        )

        val pigment = assertIs<RmwSpec.Watercolor>(
            RmwStrokePolicy.spec(ToolKind.Brush(preset), pigmentMixer),
        )
        val rgb = assertIs<RmwSpec.Watercolor>(
            RmwStrokePolicy.spec(ToolKind.Brush(preset), RgbMixer),
        )

        assertEquals(behavior, pigment.behavior)
        assertEquals(RmwMixing.Pigment, pigment.mixing)
        assertEquals(RmwMixing.Linear, rgb.mixing)
    }

    @Test
    fun `clear water freezes its medium and mixer at pen down`() {
        val params = WaterParams(
            waterLoad = 0.8f,
            spread = 0.55f,
            granulation = 0.2f,
            edgeDarkening = 0.25f,
        )

        val spec = assertIs<RmwSpec.Water>(
            RmwStrokePolicy.spec(ToolKind.Water(params), pigmentMixer),
        )

        assertEquals(params.behavior, spec.behavior)
        assertEquals(RmwMixing.Pigment, spec.mixing)
    }

    private val pigmentMixer = object : ColorMixer {
        override val isPigment: Boolean = true

        override fun mix(a: Int, b: Int, t: Float): Int = a
    }
}
