package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.SliceHandle

/** Composites one source tile over one backdrop tile into a third pool slice. */
internal class TileCompositePass(
    private val program: GlProgram,
    private val state: GlState,
    private val pool: TilePool,
) {

    private val fbo = GlFbo()
    private val quad = FullRectQuad()

    fun draw(
        source: SliceHandle,
        backdrop: SliceHandle,
        target: SliceHandle,
        mode: BlendMode,
        opacity: Float,
    ): Boolean {
        require(target.page != source.page && target.page != backdrop.page) {
            "tile composite target must not share a sampled page"
        }
        if (!fbo.bindArrayLayer(pool.textureOf(target.page), target.slice)) return false

        state.useProgram(program)
        state.blendOff()
        state.scissorOff()
        state.viewport(0, 0, TILE_SIZE, TILE_SIZE)
        program.uniform1i("u_sourcePage", SOURCE_UNIT)
        program.uniform1i("u_backdropPage", BACKDROP_UNIT)
        program.uniform1f("u_sourceSlice", source.slice.toFloat())
        program.uniform1f("u_backdropSlice", backdrop.slice.toFloat())
        program.uniform1i("u_blend", mode.shaderId)
        program.uniform1f("u_opacity", opacity)

        bindPage(SOURCE_UNIT, source.page)
        bindPage(BACKDROP_UNIT, backdrop.page)
        quad.draw(TILE_SIZE.toFloat(), TILE_SIZE.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        return true
    }

    private fun bindPage(unit: Int, page: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, pool.textureOf(page))
    }

    fun release() {
        fbo.release()
        quad.release()
    }

    private companion object {
        const val SOURCE_UNIT = 0
        const val BACKDROP_UNIT = 1
    }
}
