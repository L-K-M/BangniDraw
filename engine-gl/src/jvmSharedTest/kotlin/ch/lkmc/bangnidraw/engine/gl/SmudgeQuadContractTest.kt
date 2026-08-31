package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.gl.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `FullRectQuad` caches only its last geometry, so one instance drawn at
 * alternating sizes re-uploads on every call — a fresh vertex array on the
 * GL thread and a `glBufferSubData` into a buffer the previous draw may
 * still be reading. AGENTS.md records this rule for the present quad
 * ("sharing the viewport present quad alternates dimensions and uploads
 * geometry twice per transparent frame"); smudge's pickup/work/tile
 * alternation is the same trap per dab.
 */
class SmudgeQuadContractTest {

    @Test
    fun `each smudge draw size owns its quad`() {
        // Whitespace-STRIPPED, not collapsed: every pin here is space-free,
        // so removal makes them immune to interior line wraps too — a wrap
        // after an opening paren leaves ' ' under collapse and would
        // false-fail the pin.
        val pass = ContractTestSources.read(SMUDGE_PASS_PATH).replace(WHITESPACE, "")

        assertTrue("pickupQuad.draw(spec.pickupEdge.toFloat()" in pass)
        assertTrue("workQuad.draw(work.width.toFloat()" in pass)
        assertTrue("tileQuad.draw(TILE_SIZE.toFloat()" in pass)
        // Structural, not spelled: exactly one construction per draw size,
        // so a reintroduced shared instance (however written) shifts the
        // count and fails.
        val quadConstructions = QUAD_CONSTRUCTION.findAll(pass).count()
        assertTrue(
            quadConstructions == 3,
            "SmudgePass must own one FullRectQuad per draw size, found $quadConstructions",
        )
        for (quad in listOf("pickupQuad", "workQuad", "tileQuad")) {
            assertTrue("$quad.release()" in pass, "$quad must be released with the pass")
        }
    }

    private companion object {
        const val SMUDGE_PASS_PATH = "engine-gl/src/jvmShared/kotlin/ch/lkmc/bangnidraw/engine/gl/SmudgePass.kt"
        val QUAD_CONSTRUCTION = Regex("=FullRectQuad\\(\\)")
        val WHITESPACE = Regex("\\s+")
    }
}
