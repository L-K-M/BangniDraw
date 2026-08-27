package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CanvasInputLifecycleWiringTest {

    private val surface = File(
        "src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasSurface.kt",
    ).readText()
    private val handler = File(
        "src/main/java/ch/lkmc/bangnidraw/input/CanvasTouchHandler.kt",
    ).readText()

    @Test
    fun `listener replacement detaches the previous handler first`() {
        val update = surface.substringAfter("update = { surface ->")
            .substringBefore("updateGestureExclusion(")
        val detach = update.indexOf(".detach()")
        val attach = update.indexOf("surface.setOnTouchListener(touchHandler)")

        assertTrue(detach >= 0)
        assertTrue(detach < attach)
    }

    @Test
    fun `detach stops stroke and frame callbacks`() {
        assertTrue(handler.contains("internal fun detach()"))
        val detach = handler.substringAfter("internal fun detach()")
            .substringBefore("// ------------------------------------------------")

        assertTrue(detach.contains("handleCancel"))
        assertTrue(detach.contains("stopPredicting()"))
        assertTrue(detach.contains("removeFrameCallback(hoverFrameCallback)"))
    }

    @Test
    fun `retroactive platform cancellation reaches the cancel path`() {
        val touch = handler.substringAfter("override fun onTouch")
            .substringBefore("override fun onGenericMotion")

        assertTrue(touch.contains("MotionEvent.FLAG_CANCELED"))
        assertTrue(touch.contains("handleCancel(timeNs)"))
    }
}
