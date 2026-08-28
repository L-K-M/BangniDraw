package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.DabStamp
import ch.lkmc.bangnidraw.engine.core.WatercolorKernel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatercolorShaderContractTest {

    @Test
    fun `wet state uses bounded four-neighbor diffusion`() {
        val body = Shaders.WATERCOLOR_WET.fragment

        assertTrue("#define WATER_CELL_PX ${WatercolorKernel.CELL_SIZE}" in body)
        assertTrue("#define MAX_WATER_DIFFUSION ${WatercolorKernel.MAX_DIFFUSION}" in body)
        assertTrue("float suppliedWet(vec2 uv, vec2 canvas)" in body)
        assertTrue("suppliedWet(wetUv, canvas)" in body)
        assertTrue("suppliedWet(wetUv + vec2(u_wetTexel.x, 0.0)" in body)
        assertTrue("suppliedWet(wetUv - vec2(u_wetTexel.x, 0.0)" in body)
        assertTrue("suppliedWet(wetUv + vec2(0.0, u_wetTexel.y)" in body)
        assertTrue("suppliedWet(wetUv - vec2(0.0, u_wetTexel.y)" in body)
        assertTrue("clamp(center + diffusion * (average - center), 0.0, 1.0)" in body)
    }

    @Test
    fun `wet state carries a lazy age stamp`() {
        val body = Shaders.WATERCOLOR_WET.fragment

        assertTrue("uniform float u_nowTick;" in body)
        assertTrue("uniform bool u_ageOnly;" in body)
        assertTrue("uniform bool u_epochRollover;" in body)
        assertTrue("if (u_ageOnly) {" in body)
        assertTrue("u_epochRollover && updatedTick <= u_nowTick" in body)
        assertTrue("previous.r * age" in body)
        assertTrue("previous.a * age" in body)
        assertTrue("decodeTick" in body)
        assertTrue("encodeTick" in body)
        assertTrue("ageFactor" in body)
        assertTrue("${WatercolorKernel.TICK_MODULUS}" in body)
        assertTrue("${WatercolorKernel.DRY_TICKS}" in body)
    }

    @Test
    fun `wet state uses canvas paper for absorption and capacity`() {
        val body = Shaders.WATERCOLOR_WET.fragment

        assertTrue("precision highp int;" in body)
        assertTrue("#define PAPER_ABSORPTION_MIN ${WatercolorKernel.PAPER_ABSORPTION_MIN}" in body)
        assertTrue("#define PAPER_ABSORPTION_RANGE ${WatercolorKernel.PAPER_ABSORPTION_RANGE}" in body)
        assertTrue("#define PAPER_CAPACITY_MIN ${WatercolorKernel.PAPER_CAPACITY_MIN}" in body)
        assertTrue("#define PAPER_CAPACITY_RANGE ${WatercolorKernel.PAPER_CAPACITY_RANGE}" in body)
        assertTrue("float proceduralPaper(vec2 canvas)" in body)
        assertTrue("cell.x * ${DabStamp.GRAIN_HASH_X}u" in body)
        assertTrue("cell.y * ${DabStamp.GRAIN_HASH_Y}u" in body)
        assertTrue("h >> ${DabStamp.GRAIN_HASH_SHIFT}u" in body)
        assertTrue("h & ${DabStamp.GRAIN_HASH_MASK}u" in body)
        assertTrue("float paperPocket = 1.0 - proceduralPaper(canvas);" in body)
        assertTrue("WATER_ABSORPTION * paperAbsorption * paperCapacity" in body)
    }

    @Test
    fun `color flow stays premultiplied and has a pigment seam`() {
        val body = Shaders.WATERCOLOR_COLOR.fragment

        assertTrue("precision highp int;" in body)
        assertTrue("#define MIXLERP mix" in body)
        assertTrue("MIXLERP(cCenter, cAverage, clamp(t, 0.0, 1.0))" in body)
        assertTrue("rgb = min(rgb, vec3(alpha));" in body)
        assertTrue("uniform int u_depositMode;" in body)
        assertTrue("uniform bool u_alphaLock;" in body)
        assertTrue("proceduralPaper" in body)
    }

    @Test
    fun `clear water transports finished brush pixels without model state`() {
        val body = Shaders.WATERCOLOR_COLOR.fragment

        assertTrue("vec4 center = sampleColor(canvas);" in body)
        assertTrue("? depositPigment(flowed, deposit) : flowed;" in body)
        assertTrue("rgb = min(rgb, vec3(alpha));" in body)
        assertFalse("u_brushModel" in body)
        assertFalse("i_wetness" in body)
        assertFalse("i_bristleAlong" in body)
        assertFalse("i_bristleAcross" in body)
    }

    @Test
    fun `coarse wet cells conservatively cover small tips`() {
        val body = Shaders.WATERCOLOR_WET.fragment

        assertTrue("float wetCoverageMask(vec2 canvas, vec4 dab, vec2 tip)" in body)
        assertTrue("float halfCell = float(WATER_CELL_PX) * 0.5;" in body)
        assertTrue("halfCell * sqrt(2.0) / aspect" in body)
        assertTrue("waterMask(canvas, vec4(dab.xy, dab.z + cellReach, dab.w), tip)" in body)
    }

    @Test
    fun `flat watercolor tips use their angle and aspect`() {
        for (source in listOf(Shaders.WATERCOLOR_WET, Shaders.WATERCOLOR_COLOR)) {
            assertTrue(source.uniforms.any { it.name == "u_tip" })
            assertTrue("uniform vec2 u_tip;" in source.fragment)
        }
        assertTrue("wetCoverageMask(canvas, u_dab, u_tip)" in Shaders.WATERCOLOR_WET.fragment)
        assertTrue("waterMask(canvas, u_dab, u_tip)" in Shaders.WATERCOLOR_COLOR.fragment)
    }

    @Test
    fun `watercolor has a Mixbox shader variant`() {
        val source = "// ==========================================================\n" +
            "//  MIXBOX 2.0 (c) 2022 Secret Weapons. All rights reserved.\n" +
            "vec3 mixbox_lerp(vec3 a, vec3 b, float t) { return mix(a, b, t); }"
        val mixed = Shaders.watercolorColorMix(source)

        assertTrue("#define MIXLERP mixbox_lerp" in mixed.fragment)
        assertTrue(mixed.uniforms.any { it.name == "mixbox_lut" })
    }
}
