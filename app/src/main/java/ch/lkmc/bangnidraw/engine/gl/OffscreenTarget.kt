package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES

/**
 * One viewport-sized RGBA8 texture that passes render into — `Accum` and
 * `Scratch` of `docs/plan/03-canvas-engine.md` §3.2.
 *
 * `Accum` exists because a fragment shader cannot read the framebuffer it
 * writes, and every blend mode but Normal needs the backdrop. So the
 * compositor builds the frame offscreen and presents it as a quad; `Scratch`
 * is the copy of `Accum` a non-normal layer samples as `u_backdrop`.
 *
 * Immutable storage (`glTexStorage2D`), reallocated only when the surface size
 * changes — about 16 MiB at 2560×1600, and two of them.
 */
class OffscreenTarget(val label: String) {

    private val ids = IntArray(1)

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    val texture: Int get() = ids[0]
    val isAllocated: Boolean get() = ids[0] != 0

    /**
     * Ensures a [width] × [height] texture exists, recreating it if the size
     * changed. Returns false if the allocation failed, so the caller can skip
     * the frame rather than draw into nothing.
     *
     * Recreated rather than resized because `glTexStorage2D` is immutable
     * storage: that is what makes it cheap to render into and what forbids
     * changing its size in place.
     */
    fun ensure(width: Int, height: Int, state: GlState): Boolean {
        require(width > 0 && height > 0) { "$label needs a positive size, was ${width}x$height" }
        if (isAllocated && width == this.width && height == this.height) return true
        release(state)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
        if (GlErrors.checkAllocation("$label glTexStorage2D ${width}x$height") != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, ids, 0)
            ids[0] = 0
            return false
        }
        // One level, so the default GL_NEAREST_MIPMAP_LINEAR min filter would
        // make this texture incomplete and sample black — the same trap
        // `TilePool.createPage` guards against, and worth guarding here too
        // because the present pass samples this every single frame.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE,
        )
        this.width = width
        this.height = height
        return true
    }

    fun release(state: GlState) {
        if (ids[0] != 0) {
            GLES30.glDeleteTextures(1, ids, 0)
            // Same reason TilePool.release takes a GlState: drivers recycle
            // ids, and a stale filter entry would make the next texture behind
            // this id skip its glTexParameteri and sample black.
            state.forgetTexture(ids[0])
            ids[0] = 0
        }
        width = 0
        height = 0
    }

    /** Bytes this target holds, for the memory readout of §14. */
    val bytes: Long get() = if (isAllocated) width.toLong() * height * 4 else 0L

    companion object {
        /** For the debug overlay: two of these against one pool page. */
        const val TILE_EQUIVALENT_BYTES = TILE_BYTES
    }
}
