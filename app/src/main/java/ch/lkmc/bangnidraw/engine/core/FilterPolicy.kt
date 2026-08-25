package ch.lkmc.bangnidraw.engine.core

/**
 * How a tile is sampled at a given zoom (`docs/plan/03-canvas-engine.md`
 * §3.4's table), as pure arithmetic so the choice is testable without a GL
 * context — §15's rule for anything decision-shaped.
 *
 * | effectiveScale | Sampler | taps |
 * | --- | --- | --- |
 * | ≥ 4.0 | nearest | 1 |
 * | 0.5 – 4.0 | linear | 1 |
 * | 0.25 – 0.5 | linear | 2 |
 * | < 0.25 | linear | 4 |
 *
 * **No mipmaps**, which is what makes the tap counts necessary: `glGenerateMipmap`
 * works per texture, not per slice, so a mip chain on a pool page would have
 * to be regenerated for the whole page after every stroke. Supersampling costs
 * per *frame* and only on zoomed-out frames, which are rare (gestures), while
 * strokes are constant — and it is exact with respect to the sparse index,
 * which a mip of a tile cannot be because it cannot see its neighbours.
 */
object FilterPolicy {

    /** `GL_NEAREST` above this: pixels are ≥4 screen px wide and users are placing them. */
    const val NEAREST_ABOVE = 4f

    /** Below this, one bilinear tap per screen pixel starts to shimmer while panning. */
    const val TWO_TAPS_BELOW = 0.5f

    /** Below this, 2×2 is not enough either. */
    const val FOUR_TAPS_BELOW = 0.25f

    /**
     * True when the sampler should be `GL_NEAREST`.
     *
     * 400% is the conventional threshold in raster editors, and the reason is
     * not sharpness for its own sake: at that zoom a user is looking at and
     * placing individual pixels, and bilinear turns the pixel grid into mush
     * exactly when its structure is the thing being worked on.
     */
    fun nearest(effectiveScale: Float): Boolean =
        effectiveScale.isFinite() && effectiveScale >= NEAREST_ABOVE

    /**
     * The supersample grid edge: 1, 2 or 4 — never more, because §3.4 accepts
     * residual aliasing below 0.125 rather than going wider, and because the
     * shader clamps to `Shaders.MAX_TAPS` anyway.
     *
     * A non-finite scale answers 1. It cannot arise from a real
     * [ScreenTransform] — [ViewTransform.MIN_SCALE] and [FitTransform] both
     * bound it — but this is the value that reaches a uniform, and one bad
     * frame is better than a NaN division in the shader.
     */
    fun taps(effectiveScale: Float): Int = when {
        !effectiveScale.isFinite() || effectiveScale <= 0f -> 1
        effectiveScale < FOUR_TAPS_BELOW -> 4
        effectiveScale < TWO_TAPS_BELOW -> 2
        else -> 1
    }
}
