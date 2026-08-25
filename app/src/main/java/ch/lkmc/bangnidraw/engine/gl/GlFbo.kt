package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30

/**
 * One reusable framebuffer object, re-attached rather than re-created
 * (`docs/plan/03-canvas-engine.md` §2.1, §3.2).
 *
 * Every render-to-texture in this engine goes through a single FBO whose
 * colour attachment moves: to a slice of a pool page via
 * `glFramebufferTextureLayer`, or to `Accum`/`Scratch` via
 * `glFramebufferTexture2D`. Creating an FBO per target would put a driver
 * object allocation on the stroke path for something whose only state is one
 * attachment.
 *
 * The last attachment is remembered so re-binding the same target is free —
 * a stroke clears and draws into the same slice many times in a row.
 */
class GlFbo {

    private val ids = IntArray(1)

    /** 0 until [ensure] has run. */
    val id: Int get() = ids[0]

    private var attachedTexture = 0
    private var attachedLayer = -1

    private fun ensure() {
        if (ids[0] == 0) {
            GLES30.glGenFramebuffers(1, ids, 0)
            GlErrors.checkAllocation("glGenFramebuffers")
        }
    }

    /**
     * Binds this FBO with [layer] of the 2D-array texture [texture] as colour
     * attachment 0.
     *
     * Returns false when the driver reports the combination incomplete, so the
     * caller can skip the pass rather than draw into nothing — the one
     * outcome that is a device condition rather than a bug (a slice count the
     * driver over-reported, most plausibly).
     */
    fun bindArrayLayer(texture: Int, layer: Int): Boolean {
        ensure()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0])
        if (texture != attachedTexture || layer != attachedLayer) {
            GLES30.glFramebufferTextureLayer(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                texture,
                0,
                layer,
            )
            attachedTexture = texture
            attachedLayer = layer
        }
        return isComplete("array layer $texture:$layer")
    }

    /** Binds this FBO with the 2D texture [texture] as colour attachment 0. */
    fun bindTexture2d(texture: Int): Boolean {
        ensure()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0])
        if (texture != attachedTexture || attachedLayer != -1) {
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                texture,
                0,
            )
            attachedTexture = texture
            attachedLayer = -1
        }
        return isComplete("texture $texture")
    }

    private fun isComplete(what: String): Boolean {
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status == GLES30.GL_FRAMEBUFFER_COMPLETE) return true
        android.util.Log.w(GL_TAG, "framebuffer incomplete for $what: 0x${Integer.toHexString(status)}")
        return false
    }

    /**
     * Clears the currently bound attachment to a **premultiplied** colour.
     *
     * Premultiplied because everything in this engine is (§2.4); passing a
     * straight-alpha colour here paints a halo at every transparent edge, and
     * it is the sort of mistake that looks right at alpha 1.
     */
    fun clear(r: Float, g: Float, b: Float, a: Float) {
        // Scissor and colour mask both gate glClear. The compositor scissors
        // deliberately, so this does not touch GL_SCISSOR_TEST — but a stale
        // colour mask would silently drop channels, and nothing else resets it.
        GLES30.glColorMask(true, true, true, true)
        GLES30.glClearColor(r, g, b, a)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Releases the FBO. The textures it pointed at are not touched — they
     * belong to `TilePool` and to the renderer's `Accum`/`Scratch`.
     */
    fun release() {
        if (ids[0] != 0) {
            GLES30.glDeleteFramebuffers(1, ids, 0)
            ids[0] = 0
        }
        attachedTexture = 0
        attachedLayer = -1
    }
}
