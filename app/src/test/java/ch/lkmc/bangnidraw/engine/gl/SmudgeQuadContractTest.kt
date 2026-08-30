package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
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
        val pass = ContractTestSources.read(SMUDGE_PASS_PATH)

        assertTrue("pickupQuad.draw(spec.pickupEdge.toFloat()" in pass)
        assertTrue("workQuad.draw(work.width.toFloat()" in pass)
        assertTrue("tileQuad.draw(TILE_SIZE.toFloat()" in pass)
        // A lone shared instance is the regression this pins against.
        assertTrue(
            "private val quad = FullRectQuad()" !in pass,
            "SmudgePass must not share one FullRectQuad across its three draw sizes",
        )
        for (quad in listOf("pickupQuad", "workQuad", "tileQuad")) {
            assertTrue("$quad.release()" in pass, "$quad must be released with the pass")
        }
    }

    private companion object {
        const val SMUDGE_PASS_PATH = "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/SmudgePass.kt"
    }
}
