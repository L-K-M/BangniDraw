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
        val frontDraw = session.substringAfter("private fun onDrawFrontBufferedLayer(")
            .substringBefore("private fun drainPending(")
        val surface = frontDraw.indexOf("val surfaceChanged = renderer.onSurfaceChanged")
        val redraw = frontDraw.indexOf("renderPolicy.requestRedraw()")
        val invalidate = frontDraw.indexOf("renderPolicy.sceneChanged()")
        val plan = frontDraw.indexOf("val framePlan = renderPolicy.frontFrame()")

        assertTrue(surface >= 0)
        assertTrue(surface < redraw)
        assertTrue(redraw < invalidate)
        assertTrue(invalidate < plan)
    }
}
