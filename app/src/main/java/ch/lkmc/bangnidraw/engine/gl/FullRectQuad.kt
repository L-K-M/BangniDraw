package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30

/**
 * One quad covering a whole rect, for the passes that are not per tile: the
 * `Accum` present of `docs/plan/03-canvas-engine.md` §3.2 step 3, and the
 * transparent-paper checkerboard of step 1.
 *
 * Separate from [CompositePass] because it shares nothing with it but the
 * vertex shader: no page batching, no per-tile uv, no streaming — the geometry
 * is four corners that change only when the target resizes. Folding it in
 * would mean a `drawFullRect` on a class whose whole design is "quads grouped
 * by texture-array page", taking a program that is not the one it was built
 * with.
 *
 * The vertex layout matches [Shaders.COMPOSITE_VERT]'s attributes, so any
 * program built on that shader can draw it.
 *
 * Reuse one instance where callers share a stable rect size. The geometry
 * cache remembers only the last size drawn, so alternating sizes re-upload on
 * every call.
 */
class FullRectQuad {

    private val vbo = IntArray(1)
    private val vao = IntArray(1)
    private var uploadedWidth = -1f
    private var uploadedHeight = -1f

    /**
     * The staging buffer, allocated once.
     *
     * A direct `ByteBuffer` is the expensive kind on Android — native backing
     * plus a Cleaner — and allocating one per upload puts that on the GL
     * thread. It is only six vertices, so one instance is reused for the life
     * of the quad.
     */
    private var staging: java.nio.FloatBuffer? = null

    private fun ensure() {
        if (vao[0] != 0) return
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            VERTICES * FLOATS_PER_VERTEX * 4,
            null,
            // DYNAMIC rather than STREAM: this is rewritten on a resize, not
            // per frame, so the driver should keep it where it is.
            GLES30.GL_DYNAMIC_DRAW,
        )
        GlErrors.checkAllocation("full-rect VBO")
        val stride = FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(Shaders.ATTR_POS)
        GLES30.glVertexAttribPointer(Shaders.ATTR_POS, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(Shaders.ATTR_UV)
        GLES30.glVertexAttribPointer(Shaders.ATTR_UV, 3, GLES30.GL_FLOAT, false, stride, 2 * 4)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Draws `(0,0)..(width,height)` with uv `0..1`, with whatever program is
     * currently in use.
     *
     * The geometry is re-uploaded only when the size changes — a resize, not a
     * frame — so the common path is a bind and a draw.
     */
    fun draw(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        ensure()
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        if (width != uploadedWidth || height != uploadedHeight) {
            val v = floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                width, 0f, 1f, 0f, 0f,
                width, height, 1f, 1f, 0f,
                0f, 0f, 0f, 0f, 0f,
                width, height, 1f, 1f, 0f,
                0f, height, 0f, 1f, 0f,
            )
            // Sized from `v` itself, not from the constants: `put(v)` and the
            // glBufferSubData below both measure `v`, so deriving the capacity
            // from anything else is a third source of truth that agrees only
            // by inspection. A row gaining a float would then overflow on the
            // GL thread.
            require(v.size == VERTICES * FLOATS_PER_VERTEX) {
                "the quad has ${v.size} floats but VERTICES x FLOATS_PER_VERTEX is " +
                    "${VERTICES * FLOATS_PER_VERTEX}; glDrawArrays would draw the wrong count"
            }
            val buffer = staging ?: java.nio.ByteBuffer
                .allocateDirect(v.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .also { staging = it }
            buffer.clear()
            buffer.put(v).flip()
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, v.size * 4, buffer)
            uploadedWidth = width
            uploadedHeight = height
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, VERTICES)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (vao[0] == 0) return
        GLES30.glDeleteBuffers(1, vbo, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        vbo[0] = 0
        vao[0] = 0
        uploadedWidth = -1f
        uploadedHeight = -1f
        staging = null
    }

    private companion object {
        const val VERTICES = 6

        /** x, y, u, v, slice — the slice is unused here and always 0. */
        const val FLOATS_PER_VERTEX = 5
    }
}
