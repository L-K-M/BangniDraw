package ch.lkmc.bangnidraw.engine.gl

/**
 * The small set of 4×4 matrices this engine builds, column-major as GL wants
 * them.
 *
 * Small enough not to want a matrix library, and explicit enough that the
 * target-row conventions of `docs/plan/03-canvas-engine.md` §3.1 are visible
 * here rather than implied by a helper's argument order.
 */
object Mat4 {

    const val SIZE = 16

    /** The 4×4 identity — `u_bufferTransform` for every offscreen pass. */
    fun identity(out: FloatArray = FloatArray(SIZE)): FloatArray {
        require(out.size >= SIZE) { "a matrix needs $SIZE floats, was ${out.size}" }
        out.fill(0f)
        out[0] = 1f
        out[5] = 1f
        out[10] = 1f
        out[15] = 1f
        return out
    }

    /**
     * `ortho(0, width, height, 0)` — **y-down**, per §3.1's row convention.
     *
     * Viewport-sized offscreen targets absorb GL's y-up-ness here. Tiles are
     * stored with row 0 as the canvas's top row, exactly like the CPU copies
     * and like `glReadPixels` returns them. A draw into a tile slice must put
     * logical y = 0 in GL row zero and therefore uses [orthoYUp], as does the
     * SurfaceControl present.
     */
    fun orthoYDown(width: Float, height: Float, out: FloatArray = FloatArray(SIZE)): FloatArray {
        // Both guards at the contract boundary: writing indices 0..15 into a
        // shorter array fails at the store with a bare index exception, far
        // from whoever reused a mis-sized scratch buffer.
        require(out.size >= SIZE) { "a matrix needs $SIZE floats, was ${out.size}" }
        require(width > 0f && height > 0f) { "ortho needs a positive size, was ${width}x$height" }
        out.fill(0f)
        out[0] = 2f / width
        out[5] = -2f / height
        out[10] = -1f
        out[12] = -1f
        out[13] = 1f
        out[15] = 1f
        return out
    }

    /**
     * `ortho(0, width, 0, height)` — **y-up**, for a top-first target.
     *
     * Tile slices store logical top in GL row zero, and SurfaceControl consumes
     * GL row zero as the buffer's top row. Writing either target therefore
     * uses the same numeric y value as the GL framebuffer row. A tile draw
     * samples its source normally; the SurfaceControl present shader flips
     * only its source uv so it still samples viewport-oriented Accum.
     */
    fun orthoYUp(width: Float, height: Float, out: FloatArray = FloatArray(SIZE)): FloatArray {
        require(out.size >= SIZE) { "a matrix needs $SIZE floats, was ${out.size}" }
        require(width > 0f && height > 0f) { "ortho needs a positive size, was ${width}x$height" }
        out.fill(0f)
        out[0] = 2f / width
        out[5] = 2f / height
        out[10] = -1f
        out[12] = -1f
        out[13] = -1f
        out[15] = 1f
        return out
    }
}
