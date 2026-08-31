package ch.lkmc.bangnidraw.engine.core

/** Whether the canvas-only composition overlay is drawn. */
enum class CompositionGuideVisibility {
    HIDDEN,
    VISIBLE,
    ;

    fun toggled(): CompositionGuideVisibility = when (this) {
        HIDDEN -> VISIBLE
        VISIBLE -> HIDDEN
    }
}

/** Rule-of-thirds geometry in canvas px. */
object CompositionGuide {

    data class Segment(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    data class Point(val x: Float, val y: Float)

    /** The four third-lines spanning the paper: two vertical, two horizontal. */
    fun thirds(canvas: CanvasSize): List<Segment> {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        return listOf(
            Segment(w / THIRDS, 0f, w / THIRDS, h),
            Segment(w * 2f / THIRDS, 0f, w * 2f / THIRDS, h),
            Segment(0f, h / THIRDS, w, h / THIRDS),
            Segment(0f, h * 2f / THIRDS, w, h * 2f / THIRDS),
        )
    }

    fun center(canvas: CanvasSize): Point =
        Point(canvas.width / 2f, canvas.height / 2f)

    private const val THIRDS = 3f
}
