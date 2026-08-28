package ch.lkmc.bangnidraw.engine.core

/**
 * The other view anchor (`docs/plan/08-ui-and-layout.md` §3.1's reset pill's
 * long-press): **actual size**, one canvas pixel per view pixel, next to
 * reset-to-fit. Pixel work on a 4096² canvas needs both ends — fit to see the
 * whole drawing, 100 % to see the pixels.
 *
 * The view transform composes over the fit (`screen = view ∘ fit`, see
 * [ScreenTransform]), so actual size is `view.scale = 1 / fit.scale`, not 1.
 * The canvas point under the view's centre stays under the centre — the user
 * zoomed into something, and the anchor is what they were looking at.
 * Rotation zeroes: 100 % is for inspecting pixels, and pixels are
 * axis-aligned.
 */
internal object ActualSizePolicy {

    fun transform(fit: FitTransform, view: ViewTransform): ViewTransform {
        val scale = (1f / fit.scale).coerceIn(ViewTransform.MIN_SCALE, ViewTransform.MAX_SCALE)
        val centerX = fit.viewWidth / 2f
        val centerY = fit.viewHeight / 2f
        val canvasX = view.invertX(centerX, centerY)
        val canvasY = view.invertY(centerX, centerY)
        return ViewTransform(
            scale = scale,
            rotation = 0f,
            tx = centerX - scale * canvasX,
            ty = centerY - scale * canvasY,
        )
    }
}
