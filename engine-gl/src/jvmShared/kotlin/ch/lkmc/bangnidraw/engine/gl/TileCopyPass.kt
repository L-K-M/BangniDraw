package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.SliceHandle

/** Copies one tile exactly, without running stored bytes through blend math. */
internal class TileCopyPass(private val pool: TilePool) {

    private val sourceFbo = GlFbo()
    private val targetFbo = GlFbo()

    fun copy(source: SliceHandle, target: SliceHandle): Boolean {
        require(source.page != target.page) {
            "tile copy target must not share its source page"
        }
        val sourceReady = sourceFbo.bindArrayLayer(
            pool.textureOf(source.page),
            source.slice,
            GLES30.GL_READ_FRAMEBUFFER,
        )
        if (!sourceReady) return false

        try {
            val targetReady = targetFbo.bindArrayLayer(
                pool.textureOf(target.page),
                target.slice,
                GLES30.GL_DRAW_FRAMEBUFFER,
            )
            if (!targetReady) return false

            GLES30.glBlitFramebuffer(
                0,
                0,
                TILE_SIZE,
                TILE_SIZE,
                0,
                0,
                TILE_SIZE,
                TILE_SIZE,
                GLES30.GL_COLOR_BUFFER_BIT,
                GLES30.GL_NEAREST,
            )
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        }
        return true
    }

    fun release() {
        sourceFbo.release()
        targetFbo.release()
    }
}
