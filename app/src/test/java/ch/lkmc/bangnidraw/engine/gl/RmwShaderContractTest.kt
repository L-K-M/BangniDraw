package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.BlurKernel
import ch.lkmc.bangnidraw.engine.core.SmudgeKernel
import kotlin.test.Test
import kotlin.test.assertTrue

class RmwShaderContractTest {

    @Test
    fun `smudge deposit matches the CPU alpha and pigment share`() {
        val body = Shaders.SMUDGE_DEPOSIT.fragment

        assertTrue("mix(D.a, P.a, w)" in body)
        assertTrue("w * P.a / a" in body)
        assertTrue("a < ${SmudgeKernel.ALPHA_EPSILON}" in body)
        assertTrue("MIXLERP(cD, cP, clamp(t, 0.0, 1.0))" in body)
    }

    @Test
    fun `smudge absorb samples the pre-deposit layer`() {
        val body = Shaders.SMUDGE_ABSORB.fragment

        assertTrue("uniform sampler2D u_before;" in body)
        assertTrue("mix(P.a, L.a, w)" in body)
        assertTrue("w * L.a / a" in body)
        assertTrue("MIXLERP(cP, cL, clamp(t, 0.0, 1.0))" in body)
    }

    @Test
    fun `blur is bounded and separable`() {
        val horizontal = Shaders.BLUR_HORIZONTAL.fragment
        val vertical = Shaders.BLUR_VERTICAL.fragment

        assertTrue("#define MAX_BLUR_RADIUS ${BlurKernel.MAX_RADIUS}" in horizontal)
        assertTrue("if (abs(i) > u_radius) continue;" in horizontal)
        assertTrue("vec2(float(i) * u_texel.x, 0.0)" in horizontal)
        assertTrue("vec2(0.0, float(i) * u_texel.y)" in vertical)
        assertTrue("mix(original, blurred, w)" in vertical)
        assertTrue("MIXLERP" !in horizontal && "MIXLERP" !in vertical)
    }
}
