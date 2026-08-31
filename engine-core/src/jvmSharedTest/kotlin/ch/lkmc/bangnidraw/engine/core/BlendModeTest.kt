package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `docs/plan/05-layers.md` §4 is normative for the eight modes; `shaderId` is
 * what the GLSL `blendLayer` branches on.
 */
class BlendModeTest {

    @Test
    fun `no two modes share a shaderId`() {
        // The explicit ids exist so reordering the enum cannot silently swap
        // two modes. A duplicated id defeats that just as quietly, and
        // GlShaderContractTest cannot catch it — it checks that each declared
        // id appears in the shader, which a duplicate still does. This is the
        // only thing standing between a copy-paste typo and two blend modes
        // aliasing to one GLSL branch.
        assertEquals(
            BlendMode.entries.size,
            BlendMode.entries.map { it.shaderId }.toSet().size,
            "duplicate shaderId in ${BlendMode.entries.map { "${it.name}=${it.shaderId}" }}",
        )
    }

    @Test
    fun `every mode round-trips through its shaderId`() {
        for (mode in BlendMode.entries) {
            assertEquals(mode, BlendMode.fromShaderId(mode.shaderId), "${mode.name} did not round-trip")
        }
    }

    @Test
    fun `an unknown persisted name degrades to NORMAL rather than throwing`() {
        // 06 §3's rule. Worth pinning next to the uniqueness check, because
        // moving that check out of the class initializer is what keeps this
        // promise true — a failed <clinit> would make this throw forever.
        for (bad in listOf("", "normal", "SOFT_LIGHT", "🙂")) {
            assertEquals(BlendMode.NORMAL, BlendMode.fromNameOrNormal(bad), "\"$bad\"")
        }
        for (mode in BlendMode.entries) {
            assertEquals(mode, BlendMode.fromNameOrNormal(mode.name))
        }
    }
}
