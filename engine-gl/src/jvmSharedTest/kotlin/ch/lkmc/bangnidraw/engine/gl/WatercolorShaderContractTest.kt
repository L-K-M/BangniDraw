package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.DabStamp
import ch.lkmc.bangnidraw.engine.core.WatercolorKernel
import ch.lkmc.bangnidraw.engine.core.WatercolorOverlayKernel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatercolorShaderContractTest {

    @Test
    fun `wet overlay decodes timestamps before sampling its cue`() {
        val source = Shaders.WATERCOLOR_OVERLAY
        val body = source.fragment

        assertTrue("texelFetch(u_tiles" in body)
        assertFalse("texture(u_tiles" in body)
        assertTrue("floor(state.gb * float(TICK_CHANNEL_MAX) + 0.5)" in body)
        assertTrue("(u_nowTick - updatedTick + TICK_MODULUS) % TICK_MODULUS" in body)
        assertTrue(
            "#define FULL_LOAD_DRY_TICKS ${WatercolorKernel.FULL_LOAD_DRY_TICKS}" in body,
        )
        assertTrue("#define OVERLAY_MAX_ALPHA ${WatercolorOverlayKernel.MAX_ALPHA}" in body)
        assertTrue("vec4(CUE_COLOR * alpha, alpha)" in body)
        assertTrue("if (outsideCanvas()) discard;" in body)
    }

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
        assertTrue("vec2 agedPrevious = ageWater(previous);" in body)
        assertTrue("decodeTick" in body)
        assertTrue("encodeTick" in body)
        assertTrue("ageWater" in body)
        assertTrue("${WatercolorKernel.TICK_MODULUS}" in body)
        assertTrue("${WatercolorKernel.FULL_LOAD_DRY_TICKS}" in body)
        assertTrue("${WatercolorKernel.MAX_DRY_TICKS}" in body)
    }

    @Test
    fun `wet shaders evaporate a fixed total amount`() {
        val wet = Shaders.WATERCOLOR_WET.fragment
        val overlay = Shaders.WATERCOLOR_OVERLAY.fragment

        assertTrue("vec2 ageWater(vec4 state)" in wet)
        assertTrue("water * (remaining / total)" in wet)
        assertFalse("previous.r * age" in wet)
        assertTrue("vec2 ageWater(vec4 state)" in overlay)
        assertTrue("water * (remaining / total)" in overlay)
        assertTrue("float total = water.x + water.y;" in wet)
        assertTrue("float total = water.x + water.y;" in overlay)
        assertTrue("if (total <= 0.0) return vec2(0.0);" in wet)
        assertTrue("if (total <= 0.0) return vec2(0.0);" in overlay)
        assertTrue("vec2 water = state.ra;" in wet)
        assertTrue("vec2 water = state.ra;" in overlay)
        assertTrue("total - age / float(FULL_LOAD_DRY_TICKS)" in wet)
        assertTrue("total - float(ageTicks) / float(FULL_LOAD_DRY_TICKS)" in overlay)
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
    fun `fresh pigment follows the same wet flow as existing pigment`() {
        val body = Shaders.WATERCOLOR_COLOR.fragment

        assertTrue("float pigmentDeposit(vec2 canvas)" in body)
        assertTrue("pigmentDeposit(canvas + vec2(1.0, 0.0))" in body)
        assertTrue("pigmentDeposit(canvas - vec2(1.0, 0.0))" in body)
        assertTrue("pigmentDeposit(canvas + vec2(0.0, 1.0))" in body)
        assertTrue("pigmentDeposit(canvas - vec2(0.0, 1.0))" in body)
        assertTrue("mix(pigmentDeposit(canvas), neighborDeposit, t)" in body)
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
        assertFalse("i_pathAngle" in body)
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
