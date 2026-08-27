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

    private val pigmentMixer = object : ColorMixer {
        override val isPigment: Boolean = true

        override fun mix(a: Int, b: Int, t: Float): Int = a
    }
}
