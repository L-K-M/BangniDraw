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
    fun `finger deadlines use one scheduled callback and detach removes it`() {
        assertTrue(handler.contains("private val gestureTick = Runnable"))
        assertTrue(handler.contains("postDelayed(gestureTick"))
        assertTrue(handler.contains("removeCallbacks(gestureTick)"))

        val detach = handler.substringAfter("internal fun detach()")
            .substringBefore("// ------------------------------------------------")
        assertTrue(detach.contains("cancelGestureTick()"))
    }

    @Test
    fun `middle mouse navigation publishes activity for its changed span`() {
        val move = handler.substringAfter("private fun moveMiddleDrag(")
            .substringBefore("private fun endMiddleDrag()")
        val end = handler.substringAfter("private fun endMiddleDrag()")
            .substringBefore("private fun publishMouseView()")

        assertTrue(move.contains("beginNavigationActivity()"))
        assertTrue(end.contains("endNavigationActivity()"))
    }

    @Test
    fun `retroactive platform cancellation reaches the cancel path`() {
        val touch = handler.substringAfter("override fun onTouch")
            .substringBefore("override fun onGenericMotion")

        assertTrue(touch.contains("MotionEvent.FLAG_CANCELED"))
        assertTrue(touch.contains("handleCancel(timeNs)"))
    }
}
