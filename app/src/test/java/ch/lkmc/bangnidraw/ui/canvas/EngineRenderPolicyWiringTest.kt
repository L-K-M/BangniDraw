package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class EngineRenderPolicyWiringTest {

    @Test
    fun `surface resize invalidates the preview before frame planning`() {
        val session = File(
            "src/main/java/ch/lkmc/bangnidraw/ui/canvas/EngineSession.kt",
        ).readText()
        val frontDraw = session.substringAfter("override fun onDrawFrontBufferedLayer(")
            .substringBefore("private fun drainPending(")
        val surface = frontDraw.indexOf("val surfaceChanged = renderer.onSurfaceChanged")
        val invalidate = frontDraw.indexOf("if (surfaceChanged) renderPolicy.requestRedraw()")
        val plan = frontDraw.indexOf("val framePlan = renderPolicy.frontFrame()")

        assertTrue(surface >= 0)
        assertTrue(surface < invalidate)
        assertTrue(invalidate < plan)
    }
}
