package ch.lkmc.bangnidraw.engine.core

/**
 * The letterbox fit of the canvas inside a view, and the view↔UV mapping
 * both sides of the engine need: touch handling converts view points to
 * canvas coordinates with it, and the renderer positions the canvas quad
 * with it. One implementation so the two can never disagree.
 *
 * Ported from Meltorama2000's `engine/core/FitTransform` (public domain).
 */
data class FitTransform(
    val viewWidth: Float,
    val viewHeight: Float,
    val imageWidth: Float,
    val imageHeight: Float,
) {
    /** Uniform scale from canvas pixels to view pixels (fit-inside). */
    val scale: Float = minOf(viewWidth / imageWidth, viewHeight / imageHeight)

    /** Displayed canvas size in view pixels. */
    val fittedWidth: Float = imageWidth * scale
    val fittedHeight: Float = imageHeight * scale

    /** Top-left corner of the displayed canvas in view pixels (centered). */
    val offsetX: Float = (viewWidth - fittedWidth) / 2f
    val offsetY: Float = (viewHeight - fittedHeight) / 2f

    /** View point → canvas UV. May land outside [0,1] on the letterbox. */
    fun viewToUv(x: Float, y: Float): Pair<Float, Float> =
        Pair((x - offsetX) / fittedWidth, (y - offsetY) / fittedHeight)

    /** Canvas UV → view point. */
    fun uvToView(u: Float, v: Float): Pair<Float, Float> =
        Pair(offsetX + u * fittedWidth, offsetY + v * fittedHeight)

    /** True when the view point lands on the canvas, not the letterbox. */
    fun contains(x: Float, y: Float): Boolean {
        val (u, v) = viewToUv(x, y)
        return u in 0f..1f && v in 0f..1f
    }
}
