package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixboxShaderContractTest {
    private val vendored = asset().readText()
    private val mixing = listOf(
        Shaders.mergeMix(vendored),
        Shaders.previewMix(vendored),
        Shaders.smudgeDepositMix(vendored),
        Shaders.smudgeAbsorbMix(vendored),
    )

    @Test
    fun `mixing programs contain the vendored source verbatim`() {
        for (source in mixing) {
            assertTrue(vendored in source.fragment, source.name)
            assertTrue("#define BANGNI_MIXING 1" in source.fragment, source.name)
            assertTrue("#define MIXLERP mixbox_lerp" in source.fragment, source.name)
            assertTrue("#define MIXBOX_COLORSPACE_LINEAR" !in source.fragment, source.name)
        }
    }

    @Test
    fun `mixing programs declare one LUT sampler`() {
        for (source in mixing) {
            assertEquals(1, LUT_DECLARATION.findAll(source.fragment).count(), source.name)
            assertEquals(1, source.uniforms.count { it.name == "mixbox_lut" }, source.name)
            assertEquals(Shaders.VERSION_LINE, source.fragment.lineSequence().first(), source.name)
            assertTrue(
                source.fragment.indexOf("uniform sampler2D mixbox_lut;") <
                    source.fragment.indexOf("textureLod(mixbox_lut"),
                source.name,
            )
        }
    }

    @Test
    fun `plain programs have no Mixbox cost`() {
        for (source in listOf(
            Shaders.MERGE,
            Shaders.PREVIEW,
            Shaders.SMUDGE_DEPOSIT,
            Shaders.SMUDGE_ABSORB,
        )) {
            assertTrue("mixbox_lut" !in source.fragment, source.name)
            assertTrue("mixbox_lerp" !in source.fragment, source.name)
        }
    }

    private fun asset(): File {
        val direct = File(GLSL_PATH)
        if (direct.isFile) return direct

        return File("app", GLSL_PATH).also { require(it.isFile) { "missing Mixbox GLSL" } }
    }

    private companion object {
        const val GLSL_PATH = "src/mixbox/assets/mixbox/mixbox.glsl"
        val LUT_DECLARATION = Regex("uniform\\s+sampler2D\\s+mixbox_lut\\s*;")
    }
}
