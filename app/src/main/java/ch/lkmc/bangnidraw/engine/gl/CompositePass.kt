package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.FilterPolicy
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.engine.core.SliceHandle
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws a layer's visible tiles into the accumulation target
 * (`docs/plan/03-canvas-engine.md` §3.2).
 *
 * **Quads are batched by page.** The visible keys arrive already sorted by
 * page from `LayerTextures.visibleKeys`, so this walks them once, uploads one
 * `glBufferSubData` per page into a persistent streaming VBO, and issues one
 * `glDrawArrays` per page with that page's array texture bound. A layer whose
 * index has no slice in the dirty rect costs one CPU loop and no draw call —
 * which is why the compositor's cost is bounded by output pixels × layers and
 * never by canvas size, and an 8192² canvas with twenty tiles painted
 * composites as fast as a 512² one.
 *
 * GL-thread-only.
 */
class CompositePass(
    private val program: GlProgram,
    private val state: GlState,
) {

    /**
     * Vertices per tile: two triangles, no index buffer.
     *
     * An element buffer would save a third of the vertex data and cost a
     * second buffer to stream and keep in sync. At `TileGrid.MAX_TILES` the
     * whole batch is 120 KiB, uploaded once per page per layer per frame —
     * not the bottleneck, and `glDrawArrays` keeps the upload one contiguous
     * write with no index arithmetic to get wrong.
     */
    private var vertexBuffer: FloatBuffer = allocate(TileGrid.MAX_TILES)

    /** Tiles [vertexBuffer] and the VBO currently have room for. */
    private var capacityTiles = TileGrid.MAX_TILES

    private val vbo = IntArray(1)
    private val vao = IntArray(1)

    /** Reused across frames: `LayerTextures.visibleKeys` writes packed keys here. */
    private var keyScratch = IntArray(0)

    private var initialized = false

    /**
     * Grows the vertex buffer and the VBO to hold [tiles] quads.
     *
     * The buffer starts at `TileGrid.MAX_TILES`, which no grid can exceed
     * today — 8192 px per side is 32 tiles, so 1024 — and growing rather than
     * asserting that is deliberate. The alternatives are both bad: a `check`
     * turns a relaxed grid cap into an exception thrown from inside a render
     * callback, in the same PR that removed a `require` from
     * `OffscreenTarget.ensure` for exactly that reason; and an early `return 0`
     * silently drops the layer from every frame.
     *
     * Returns false when the GPU store could not grow — `GL_OUT_OF_MEMORY` is
     * the one way this fails, and an earlier version of this KDoc wrongly said
     * there was none. **The new capacity is committed only after the driver
     * confirms it**: assigning first meant one OOM left the pass believing it
     * had room it did not, so every later frame skipped the grow and uploaded
     * past the real store. The caller skips the layer for this frame instead,
     * and the next frame retries the growth.
     */
    private fun ensureCapacity(tiles: Int): Boolean {
        if (tiles <= capacityTiles) return true
        // The GPU store first, sized arithmetically — `glBufferData` takes a
        // size and a null pointer, so the CPU buffer is not needed to ask for
        // the growth. Allocating it first meant a sustained GL_OUT_OF_MEMORY
        // produced a direct ByteBuffer per frame (native memory plus a
        // Cleaner) that was discarded unread, exactly when the process is
        // least able to afford it. This way the failure path allocates
        // nothing, and it needs no retry cache to say so.
        val bytes = tiles * VERTICES_PER_TILE * FLOATS_PER_VERTEX * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bytes, null, GLES30.GL_STREAM_DRAW)
        if (GlErrors.checkAllocation("composite VBO grown to $tiles tiles") != GLES30.GL_NO_ERROR) {
            return false
        }
        vertexBuffer = allocate(tiles)
        capacityTiles = tiles
        return true
    }

    private fun ensureBuffers() {
        if (initialized) return
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        // GL_STREAM_DRAW: rewritten every frame and read once, which is
        // exactly what the hint describes. A STATIC buffer here makes some
        // drivers migrate the allocation on every upload.
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexBuffer.capacity() * 4,
            null,
            GLES30.GL_STREAM_DRAW,
        )
        GlErrors.checkAllocation("composite VBO")
        val stride = FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(Shaders.ATTR_POS)
        GLES30.glVertexAttribPointer(Shaders.ATTR_POS, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(Shaders.ATTR_UV)
        GLES30.glVertexAttribPointer(Shaders.ATTR_UV, 3, GLES30.GL_FLOAT, false, stride, 2 * 4)
        GLES30.glBindVertexArray(0)
        initialized = true
    }

    /**
     * Draws every tile of [textures] inside [dirtyRect] (canvas px), blended
     * with [mode] at [opacity].
     *
     * [backdrop] is the `Scratch` copy of `Accum` and is bound as `u_backdrop`
     * only for a non-normal [mode]; a Normal layer takes the hardware
     * source-over path and never reads the backdrop at all, which is the whole
     * reason §2.4 stores premultiplied.
     *
     * Returns the number of tiles drawn — 0 when the layer has nothing here.
     */
    fun draw(
        textures: LayerTextures,
        mode: BlendMode,
        opacity: Float,
        screen: ScreenTransform,
        projection: FloatArray,
        bufferTransform: FloatArray,
        dirtyRect: IntRect,
        backdrop: Int,
    ): Int {
        if (opacity <= 0f || dirtyRect.isEmpty || textures.tileCount == 0) return 0
        ensureBuffers()
        if (keyScratch.size < textures.grid.tileCount) keyScratch = IntArray(textures.grid.tileCount)
        val count = textures.visibleKeys(dirtyRect, keyScratch)
        if (count == 0) return 0
        if (!ensureCapacity(count)) return 0

        state.useProgram(program)
        program.uniform4f("u_screen", screen.a, screen.b, screen.tx, screen.ty)
        program.uniformMatrix4("u_projection", projection)
        program.uniformMatrix4("u_bufferTransform", bufferTransform)
        program.uniform1f("u_opacity", opacity)
        program.uniform1i("u_blend", mode.shaderId)
        val taps = FilterPolicy.taps(screen.effectiveScale)
        program.uniform1i("u_taps", taps)
        val perScreen = screen.canvasPerScreen
        program.uniform2f("u_canvasPerScreen", perScreen, perScreen)
        // Sampler units are constants, not state: the tiles are always unit 0
        // and the backdrop unit 1, so nothing has to track which is bound
        // where between passes.
        program.uniform1i("u_tiles", TILE_UNIT)
        program.uniform1i("u_backdrop", BACKDROP_UNIT)

        if (mode == BlendMode.NORMAL) {
            state.blendSourceOver()
        } else {
            // The shader writes the finished composite, so hardware blending
            // would apply the formula twice.
            state.blendOff()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + BACKDROP_UNIT)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backdrop)
        }

        val filter = if (FilterPolicy.nearest(screen.effectiveScale)) {
            GLES30.GL_NEAREST
        } else {
            GLES30.GL_LINEAR
        }

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])

        var drawn = 0
        var i = 0
        while (i < count) {
            // The keys are sorted by page, so one linear walk finds each page's
            // run without a grouping pass or a map.
            val page = textures.slice(TileKey(keyScratch[i])).page
            var end = i
            vertexBuffer.clear()
            while (end < count) {
                // One lookup per tile: the grouping condition and the quad both
                // need the handle, and this is the hottest loop in the
                // compositor — every visible tile of every layer, every frame.
                val key = TileKey(keyScratch[end])
                val handle = textures.slice(key)
                if (handle.page != page) break
                appendQuad(textures, key, handle)
                end++
            }
            val tiles = end - i
            vertexBuffer.flip()
            // Orphan the storage first. Writing into a range the previous
            // page's glDrawArrays is still reading makes a tiler driver stall
            // the CPU until the GPU catches up, instead of renaming the buffer
            // — the classic per-frame hitch, on exactly the multi-page,
            // multi-layer path this pass exists to make cheap.
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                vertexBuffer.capacity() * 4,
                null,
                GLES30.GL_STREAM_DRAW,
            )
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER,
                0,
                tiles * VERTICES_PER_TILE * FLOATS_PER_VERTEX * 4,
                vertexBuffer,
            )
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + TILE_UNIT)
            val texture = textures.pageTexture(page)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texture)
            state.textureFilter(GLES30.GL_TEXTURE_2D_ARRAY, texture, filter)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, tiles * VERTICES_PER_TILE)
            drawn += tiles
            i = end
        }
        GLES30.glBindVertexArray(0)
        GlErrors.checkGlDebug("compositePass")
        return drawn
    }

    /**
     * Six vertices for one tile: position in canvas px, uv inside the tile,
     * and the slice index as the array layer.
     *
     * The rect comes from [TileGrid.tileRect], which clips edge tiles to the
     * canvas — so the quad of a tile hanging off the right edge is only as
     * wide as the canvas, and its uv range shrinks to match. Drawing the full
     * 256 px would paint whatever the slice holds past the canvas boundary,
     * which for a tile created by a stroke near the edge is real paint.
     */
    private fun appendQuad(textures: LayerTextures, key: TileKey, handle: SliceHandle) {
        val rect = textures.grid.tileRect(key)
        val origin = textures.grid.origin(key)
        val w = TILE_SIZE.toFloat()
        val x0 = rect.left.toFloat()
        val y0 = rect.top.toFloat()
        val x1 = rect.right.toFloat()
        val y1 = rect.bottom.toFloat()
        val u0 = (rect.left - origin.x) / w
        val v0 = (rect.top - origin.y) / w
        val u1 = (rect.right - origin.x) / w
        val v1 = (rect.bottom - origin.y) / w
        val slice = handle.slice.toFloat()
        // Two triangles, counter-clockwise in a y-down space. Culling is off
        // throughout this engine, so winding is documentation rather than
        // enforcement — but consistent winding is what lets it be turned on.
        vertex(x0, y0, u0, v0, slice)
        vertex(x1, y0, u1, v0, slice)
        vertex(x1, y1, u1, v1, slice)
        vertex(x0, y0, u0, v0, slice)
        vertex(x1, y1, u1, v1, slice)
        vertex(x0, y1, u0, v1, slice)
    }

    private fun vertex(x: Float, y: Float, u: Float, v: Float, w: Float) {
        vertexBuffer.put(x).put(y).put(u).put(v).put(w)
    }

    fun release() {
        if (!initialized) return
        GLES30.glDeleteBuffers(1, vbo, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        vbo[0] = 0
        vao[0] = 0
        initialized = false
    }

    private fun allocate(tiles: Int): FloatBuffer = ByteBuffer
        .allocateDirect(tiles * VERTICES_PER_TILE * FLOATS_PER_VERTEX * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    companion object {
        const val VERTICES_PER_TILE = 6

        /** x, y, u, v, slice. */
        const val FLOATS_PER_VERTEX = 5

        const val TILE_UNIT = 0
        const val BACKDROP_UNIT = 1
    }
}
