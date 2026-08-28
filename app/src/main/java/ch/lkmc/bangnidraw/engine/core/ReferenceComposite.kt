package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE

/**
 * The CPU twin of `CompositePass`'s reference draw — the pixels a gallery
 * copy gets when it includes the tracing image (AGENTS.md's gallery-variant
 * rule). Renders one canvas tile of the transformed reference as
 * **premultiplied ARGB**, transparent outside the image footprint.
 *
 * It walks the GL draw's arithmetic in the same order so the two stay
 * comparable, without being bit-exact against any one GPU:
 *
 * 1. the canvas pixel centre maps through the inverse [ReferenceTransform]
 *    into continuous image coordinates — the quad covers exactly
 *    `[0, width] × [0, height]`, so anything outside is transparent;
 * 2. the filter is `FilterPolicy.nearest`/linear at the same
 *    `minimumScale` the renderer passes `drawReferenceToTile`;
 * 3. taps are premultiplied **before** the bilinear weights — the GPU tiles
 *    hold premultiplied RGBA and GL filters the texel values as stored;
 * 4. opacity is *not* applied here: the caller composites this tile as a
 *    source-over layer at the reference's opacity, the same
 *    [Composite.tile] path every paint layer takes.
 *
 * One deliberate divergence from the shader: no supersampling when the
 * reference is heavily minified (`FilterPolicy.taps`). The canvas pays that
 * cost per frame because shimmering is visible while panning; this runs once
 * per gallery sync on IO, where residual aliasing is accepted.
 */
object ReferenceComposite {

    /** Straight (non-premultiplied) ARGB access to the decoded asset. */
    fun interface Source {
        fun pixel(x: Int, y: Int): Int
    }

    /**
     * Whether the reference's transformed footprint intersects [tile] —
     * the same `sourceBoundsOf` test the renderer uses to decide whether a
     * tile pass draws the reference at all.
     */
    fun coversTile(reference: TracingReference, tile: IntRect): Boolean {
        if (!includes(reference)) return false
        return !reference.transform.sourceBoundsOf(
            destination = tile,
            sourceWidth = reference.imageWidth,
            sourceHeight = reference.imageHeight,
        ).isEmpty
    }

    /**
     * One `TILE_SIZE × TILE_SIZE` tile of premultiplied ARGB whose origin
     * sits at (`tileLeft`, `tileTop`) in canvas pixels. Rows past the canvas
     * edge are zero; `Composite`'s writer clips them away.
     */
    fun tile(
        reference: TracingReference,
        source: Source,
        tileLeft: Int,
        tileTop: Int,
    ): IntArray {
        val out = IntArray(TILE_SIZE * TILE_SIZE)
        if (!includes(reference)) return out

        val transform = reference.transform
        val width = reference.imageWidth
        val height = reference.imageHeight
        val maxX = width.toFloat()
        val maxY = height.toFloat()
        val nearest = FilterPolicy.nearest(transform.minimumScale)
        var i = 0
        for (y in 0 until TILE_SIZE) {
            val canvasY = tileTop + y + 0.5f
            for (x in 0 until TILE_SIZE) {
                val canvasX = tileLeft + x + 0.5f
                val u = transform.inverseX(canvasX, canvasY)
                val v = transform.inverseY(canvasX, canvasY)

                // The quad covers [0, width] × [0, height] in image space.
                if (u >= 0f && u <= maxX && v >= 0f && v <= maxY) {
                    out[i] = if (nearest) {
                        sampleNearest(source, u, v, width, height)
                    } else {
                        sampleBilinear(source, u, v, width, height)
                    }
                }
                i++
            }
        }
        return out
    }

    /** The one visibility/opacity gate the renderer's draw also applies. */
    private fun includes(reference: TracingReference): Boolean =
        reference.visibility == ReferenceVisibility.VISIBLE && reference.opacity > 0f

    /**
     * `GL_NEAREST` with clamp-to-edge: texel index `floor(coord + 0.5)` on
     * the coordinate already clamped to `[0, size - 1]`.
     */
    private fun sampleNearest(source: Source, u: Float, v: Float, width: Int, height: Int): Int {
        val x = nearestIndex(u - 0.5f, width)
        val y = nearestIndex(v - 0.5f, height)
        return Composite.premultiply(source.pixel(x, y))
    }

    /** `floor(clamp(coord) + 0.5)`, capped — the spec's `GL_NEAREST` index. */
    private fun nearestIndex(coordinate: Float, size: Int): Int {
        if (size <= 1) return 0
        val clamped = coordinate.coerceIn(0f, (size - 1).toFloat())
        return (clamped + 0.5f).toInt().coerceIn(0, size - 1)
    }

    /**
     * `GL_LINEAR` with clamp-to-edge, over premultiplied taps: the texel
     * pair around `coord - 0.5`, the off-edge end pinned to the border
     * texel, straight channels lerped per premultiplied channel.
     */
    private fun sampleBilinear(source: Source, u: Float, v: Float, width: Int, height: Int): Int {
        val x0 = clampIndex(u - 0.5f, width)
        val y0 = clampIndex(v - 0.5f, height)
        val x1 = if (x0 + 1 <= width - 1) x0 + 1 else x0
        val y1 = if (y0 + 1 <= height - 1) y0 + 1 else y0
        val fx = (u - 0.5f).coerceIn(0f, (width - 1).toFloat()) - x0
        val fy = (v - 0.5f).coerceIn(0f, (height - 1).toFloat()) - y0

        val c00 = Composite.premultiply(source.pixel(x0, y0))
        val c10 = Composite.premultiply(source.pixel(x1, y0))
        val c01 = Composite.premultiply(source.pixel(x0, y1))
        val c11 = Composite.premultiply(source.pixel(x1, y1))
        return lerp(lerp(c00, c10, fx), lerp(c01, c11, fx), fy)
    }

    /** `floor` of the clamped coordinate — the bilinear tap's lower corner. */
    private fun clampIndex(coordinate: Float, size: Int): Int {
        if (size <= 1) return 0
        val clamped = coordinate.coerceIn(0f, (size - 1).toFloat())
        return clamped.toInt().coerceIn(0, size - 1)
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val inv = 1f - t
        return Composite.argb(
            Math.round(Composite.alpha(a) * inv + Composite.alpha(b) * t),
            Math.round(Composite.red(a) * inv + Composite.red(b) * t),
            Math.round(Composite.green(a) * inv + Composite.green(b) * t),
            Math.round(Composite.blue(a) * inv + Composite.blue(b) * t),
        )
    }
}
