package ch.lkmc.bangnidraw.engine.gl

/**
 * The two 4×4 matrices this engine builds, column-major as GL wants them.
 *
 * Small enough not to want a matrix library, and explicit enough that the
 * y-down convention of `docs/plan/03-canvas-engine.md` §3.1 is visible in one
 * place rather than implied by a helper's argument order.
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
     * This is the single place GL's y-up-ness is absorbed. Tiles are stored
     * with row 0 as the canvas's top row, exactly like the CPU copies and like
     * `glReadPixels` returns them, so nothing flips anywhere else; flipping the
     * sign here instead would put the canvas upside down and every other
     * y-handling in the engine would have to compensate.
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
}
