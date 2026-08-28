package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.FilterPolicy
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.PreviewPlan
import ch.lkmc.bangnidraw.engine.core.ReferenceTransform
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.StrokeMode
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
    /**
     * §7.5's `preview.frag`, for the pass that draws the front-buffered frame.
     *
     * A second program rather than a second class, because §15's class map
     * gives `CompositePass` "tile quads by page, blend modes, filtering choice"
     * and §7.5 says in as many words that `CompositePass` batches the preview's
     * tiles — and because every rule the two share (which filter a zoom uses,
     * when hardware blending is on, orphan-before-upload) is a rule the preview
     * must not get wrong differently.
     *
     * Null for `SandwichCache`'s internal pass (§4), which composites the two
     * cached halves and has no stroke to preview. [drawPreview] fails loudly
     * rather than quietly drawing nothing if that pass is ever asked to.
     */
    private val previewProgram: GlProgram? = null,
    private val previewMixProgram: GlProgram? = null,
    private val mixboxLut: Int = 0,
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
        val error = GlErrors.checkAllocation("composite VBO grown to $tiles tiles") {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bytes, null, GLES30.GL_STREAM_DRAW)
        }
        if (error != GLES30.GL_NO_ERROR) {
            return false
        }
        vertexBuffer = allocate(tiles)
        capacityTiles = tiles
        return true
    }

    private fun ensureBuffers() {
        if (initialized) return
        // GL_STREAM_DRAW: rewritten every frame and read once, which is
        // exactly what the hint describes. A STATIC buffer here makes some
        // drivers migrate the allocation on every upload.
        GlErrors.checkAllocation("composite VBO") {
            GLES30.glGenBuffers(1, vbo, 0)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glBindVertexArray(vao[0])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                vertexBuffer.capacity() * 4,
                null,
                GLES30.GL_STREAM_DRAW,
            )
        }
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
    ): Int = drawTransformed(
        textures = textures,
        mode = mode,
        opacity = opacity,
        xx = screen.a,
        xy = -screen.b,
        yx = screen.b,
        yy = screen.a,
        tx = screen.tx,
        ty = screen.ty,
        effectiveScale = screen.effectiveScale,
        sourcePerTargetX = screen.canvasPerScreen,
        sourcePerTargetY = screen.canvasPerScreen,
        projection = projection,
        bufferTransform = bufferTransform,
        dirtyRect = dirtyRect,
        backdrop = backdrop,
    )

    /** Draws a reference after the canvas view without allocating a composed transform. */
    internal fun drawReferenceToScreen(
        textures: LayerTextures,
        opacity: Float,
        transform: ReferenceTransform,
        screen: ScreenTransform,
        projection: FloatArray,
        bufferTransform: FloatArray,
        dirtyRect: IntRect,
    ): Int = drawTransformed(
        textures = textures,
        mode = BlendMode.NORMAL,
        opacity = opacity,
        xx = screen.a * transform.xx - screen.b * transform.yx,
        xy = screen.a * transform.xy - screen.b * transform.yy,
        yx = screen.b * transform.xx + screen.a * transform.yx,
        yy = screen.b * transform.xy + screen.a * transform.yy,
        tx = screen.screenX(transform.tx, transform.ty),
        ty = screen.screenY(transform.tx, transform.ty),
        effectiveScale = transform.minimumScale * screen.effectiveScale,
        sourcePerTargetX = 1f / (transform.xScale * screen.effectiveScale),
        sourcePerTargetY = 1f / (transform.yScale * screen.effectiveScale),
        projection = projection,
        bufferTransform = bufferTransform,
        dirtyRect = dirtyRect,
        backdrop = 0,
    )

    /** Draws into one canvas-tile target without allocating a translated transform. */
    internal fun drawReferenceToTile(
        textures: LayerTextures,
        opacity: Float,
        transform: ReferenceTransform,
        tileLeft: Int,
        tileTop: Int,
        projection: FloatArray,
        bufferTransform: FloatArray,
        dirtyRect: IntRect,
    ): Int = drawTransformed(
        textures = textures,
        mode = BlendMode.NORMAL,
        opacity = opacity,
        xx = transform.xx,
        xy = transform.xy,
        yx = transform.yx,
        yy = transform.yy,
        tx = transform.tx - tileLeft,
        ty = transform.ty - tileTop,
        effectiveScale = transform.minimumScale,
        sourcePerTargetX = 1f / transform.xScale,
        sourcePerTargetY = 1f / transform.yScale,
        projection = projection,
        bufferTransform = bufferTransform,
        dirtyRect = dirtyRect,
        backdrop = 0,
    )

    private fun drawTransformed(
        textures: LayerTextures,
        mode: BlendMode,
        opacity: Float,
        xx: Float,
        xy: Float,
        yx: Float,
        yy: Float,
        tx: Float,
        ty: Float,
        effectiveScale: Float,
        sourcePerTargetX: Float,
        sourcePerTargetY: Float,
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
        program.uniform4f("u_screenBasis", xx, xy, yx, yy)
        program.uniform2f("u_screenTranslation", tx, ty)
        program.uniformMatrix4("u_projection", projection)
        program.uniformMatrix4("u_bufferTransform", bufferTransform)
        program.uniform1f("u_opacity", opacity)
        program.uniform1i("u_blend", mode.shaderId)
        val taps = FilterPolicy.taps(effectiveScale)
        program.uniform1i("u_taps", taps)
        program.uniform2f("u_canvasPerScreen", sourcePerTargetX, sourcePerTargetY)
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

        val filter = if (FilterPolicy.nearest(effectiveScale)) {
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
    // ------------------------------------------------- §7.5's truthful preview

    private var previewBuffer: FloatBuffer = allocatePreview(PREVIEW_TILES)
    private var previewCapacityTiles = PREVIEW_TILES
    private val previewVbo = IntArray(1)
    private val previewVao = IntArray(1)
    private var previewInitialized = false

    /** Kept keys and their three (page, slice) pairs, parallel and reused. */
    private var previewKeys = IntArray(0)
    private var previewLayerPage = IntArray(0)
    private var previewLayerSlice = IntArray(0)
    private var previewStrokePage = IntArray(0)
    private var previewStrokeSlice = IntArray(0)
    private var previewTailPage = IntArray(0)
    private var previewTailSlice = IntArray(0)
    private val runPages = IntArray(3)

    private fun ensurePreviewArrays(n: Int) {
        if (previewKeys.size >= n) return
        previewKeys = IntArray(n)
        previewLayerPage = IntArray(n)
        previewLayerSlice = IntArray(n)
        previewStrokePage = IntArray(n)
        previewStrokeSlice = IntArray(n)
        previewTailPage = IntArray(n)
        previewTailSlice = IntArray(n)
    }

    /**
     * Returns false when the initial store could not be allocated.
     *
     * Checked like [ensurePreviewCapacity]'s growth, and for the same reason:
     * committing `previewInitialized = true` after a failed `glBufferData` left
     * every later frame issuing `glBufferSubData` and `glDrawArrays` against an
     * unusable store, spamming GL errors forever. The names are deleted so a
     * retry does not leak them, and the flag stays false so the next frame is
     * a clean retry.
     */
    private fun ensurePreviewBuffers(): Boolean {
        if (previewInitialized) return true
        val error = GlErrors.checkAllocation("preview VBO ($previewCapacityTiles tiles)") {
            GLES30.glGenBuffers(1, previewVbo, 0)
            GLES30.glGenVertexArrays(1, previewVao, 0)
            GLES30.glBindVertexArray(previewVao[0])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, previewVbo[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                previewCapacityTiles * VERTICES_PER_TILE * PREVIEW_FLOATS_PER_VERTEX * 4,
                null,
                GLES30.GL_STREAM_DRAW,
            )
        }
        if (error != GLES30.GL_NO_ERROR) {
            GLES30.glBindVertexArray(0)
            GLES30.glDeleteBuffers(1, previewVbo, 0)
            GLES30.glDeleteVertexArrays(1, previewVao, 0)
            previewVbo[0] = 0
            previewVao[0] = 0
            return false
        }
        val stride = PREVIEW_FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(Shaders.ATTR_POS)
        GLES30.glVertexAttribPointer(Shaders.ATTR_POS, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(Shaders.ATTR_UV)
        GLES30.glVertexAttribPointer(Shaders.ATTR_UV, 3, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(Shaders.ATTR_STROKE_TAIL_SLICE)
        GLES30.glVertexAttribPointer(
            Shaders.ATTR_STROKE_TAIL_SLICE, 2, GLES30.GL_FLOAT, false, stride, 20,
        )
        GLES30.glBindVertexArray(0)
        previewInitialized = true
        return true
    }

    /**
     * §7.5's middle pass: the active layer drawn as
     * `mergeStroke(mergeStroke(L, S), T)` before its own blend mode and
     * opacity, so what the pen shows mid-stroke is what pen-up lands.
     *
     * **Candidates come from the grid, not from `visibleKeys`.** The keys drawn
     * are the *union* of three key sets, and `visibleKeys` returns one
     * texture's keys sorted by that texture's pages — an order that means
     * nothing to the other two, and a set that misses the common case of a
     * stroke on a blank tile. Row-major over the dirty rect is the order that
     * exists for all three; it costs nothing here because §11 makes this rect a
     * few tiles by construction, which is also why [PREVIEW_TILES] is small.
     *
     * Returns the number of tiles drawn.
     */
    fun drawPreview(
        layer: LayerTextures,
        stroke: StrokeBuffer,
        tail: StrokeBuffer?,
        spec: StrokeSpec,
        mode: BlendMode,
        opacity: Float,
        screen: ScreenTransform,
        projection: FloatArray,
        bufferTransform: FloatArray,
        dirtyRect: IntRect,
        backdrop: Int,
    ): Int {
        val plainPreview = checkNotNull(previewProgram) {
            "drawPreview on a CompositePass built without a preview program"
        }
        val usesPigment = spec.mode == StrokeMode.MIX && previewMixProgram != null && mixboxLut != 0
        val previewProgram = if (usesPigment) checkNotNull(previewMixProgram) else plainPreview
        if (opacity <= 0f || dirtyRect.isEmpty) return 0
        val grid = layer.grid
        if (keyScratch.size < grid.tileCount) keyScratch = IntArray(grid.tileCount)
        val candidates = grid.keysFor(dirtyRect, keyScratch)
        if (candidates == 0) return 0
        ensurePreviewArrays(candidates)

        var n = 0
        for (i in 0 until candidates) {
            val key = TileKey(keyScratch[i])
            val l = layer.slice(key)
            val s = stroke.slice(key)
            val t = tail?.slice(key) ?: SliceHandle.NONE
            // Any of the three: a stroke on blank canvas has no layer tile, and
            // dropping it would make the mark invisible exactly where there is
            // nothing under it.
            if (l.isNone && s.isNone && t.isNone) continue
            previewKeys[n] = keyScratch[i]
            previewLayerPage[n] = if (l.isNone) PreviewPlan.ABSENT else l.page
            previewLayerSlice[n] = if (l.isNone) PreviewPlan.ABSENT else l.slice
            previewStrokePage[n] = if (s.isNone) PreviewPlan.ABSENT else s.page
            previewStrokeSlice[n] = if (s.isNone) PreviewPlan.ABSENT else s.slice
            previewTailPage[n] = if (t.isNone) PreviewPlan.ABSENT else t.page
            previewTailSlice[n] = if (t.isNone) PreviewPlan.ABSENT else t.slice
            n++
        }
        if (n == 0) return 0
        if (!ensurePreviewBuffers()) return 0
        if (!ensurePreviewCapacity(n)) return 0

        state.useProgram(previewProgram)
        previewProgram.uniform4f("u_screen", screen.a, screen.b, screen.tx, screen.ty)
        previewProgram.uniformMatrix4("u_projection", projection)
        previewProgram.uniformMatrix4("u_bufferTransform", bufferTransform)
        previewProgram.uniform1f("u_opacity", opacity)
        previewProgram.uniform1i("u_blend", mode.shaderId)
        val taps = FilterPolicy.taps(screen.effectiveScale)
        previewProgram.uniform1i("u_taps", taps)
        val perScreen = screen.canvasPerScreen
        previewProgram.uniform2f("u_canvasPerScreen", perScreen, perScreen)
        previewProgram.uniform1i("u_tiles", TILE_UNIT)
        previewProgram.uniform1i("u_backdrop", BACKDROP_UNIT)
        previewProgram.uniform1i("u_strokePage", STROKE_UNIT)
        previewProgram.uniform1i("u_tailPage", TAIL_UNIT)
        if (usesPigment) {
            previewProgram.uniform1i("mixbox_lut", MIXBOX_LUT_UNIT)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + MIXBOX_LUT_UNIT)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mixboxLut)
        }
        // The same stroke constants the commit will merge with — the other half
        // of §7.5's promise. A preview that capped at a different opacity, or
        // ignored the alpha lock the merge honours, would disagree with the
        // pixels it is previewing.
        //
        // `ordinal`, and deliberately not `shaderId` like `u_blend` two lines
        // up. They are different enums answering different questions:
        // `BlendMode` is the layer's compositing mode and carries an explicit
        // `shaderId`, while `StrokeMode` (PAINT/ERASE/MIX) is what
        // `merge.glsl` switches on, and it switches on the ordinal — which
        // `StrokeShaderContractTest` pins, because nothing else in the codebase
        // would notice the enum being reordered. `MergePass` uploads it the
        // same way; changing one without the other is what would break §7.5.
        previewProgram.uniform1i("u_strokeMode", spec.mode.ordinal)
        previewProgram.uniform1f("u_strokeOpacity", spec.opacity)
        previewProgram.uniform1f("u_dilution", spec.dilution)
        previewProgram.uniform1i("u_alphaLock", if (spec.alphaLock) 1 else 0)

        if (mode == BlendMode.NORMAL) {
            state.blendSourceOver()
        } else {
            state.blendOff()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + BACKDROP_UNIT)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backdrop)
        }
        val filter = if (FilterPolicy.nearest(screen.effectiveScale)) {
            GLES30.GL_NEAREST
        } else {
            GLES30.GL_LINEAR
        }

        GLES30.glBindVertexArray(previewVao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, previewVbo[0])

        var drawn = 0
        var i = 0
        while (i < n) {
            val end = PreviewPlan.runEnd(i, n, previewLayerPage, previewStrokePage, previewTailPage)
            PreviewPlan.runPages(
                i, end, previewLayerPage, previewStrokePage, previewTailPage, runPages,
            )
            previewBuffer.clear()
            for (k in i until end) {
                appendPreviewQuad(grid, TileKey(previewKeys[k]), k)
            }
            val tiles = end - i
            previewBuffer.flip()
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER, previewBuffer.capacity() * 4, null, GLES30.GL_STREAM_DRAW,
            )
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER,
                0,
                tiles * VERTICES_PER_TILE * PREVIEW_FLOATS_PER_VERTEX * 4,
                previewBuffer,
            )
            // Every unit needs a complete texture bound even where the run has
            // no tile: `fetchTile` never samples a negative slice, but leaving a
            // sampler unbound is undefined behaviour in its own right, and the
            // driver decides that before the shader's branch does. At least one
            // of the three is real, because a key with none of them was never
            // kept — so there is always something valid to fill the others with.
            val fill = firstTexture(layer, stroke, tail)
            bindPage(TILE_UNIT, runPages[0], layer.pageTextureOrNull(runPages[0]) ?: fill, filter)
            bindPage(STROKE_UNIT, runPages[1], stroke.pageTextureOrNull(runPages[1]) ?: fill, filter)
            bindPage(TAIL_UNIT, runPages[2], tail?.pageTextureOrNull(runPages[2]) ?: fill, filter)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, tiles * VERTICES_PER_TILE)
            drawn += tiles
            i = end
        }
        GLES30.glBindVertexArray(0)
        GlErrors.checkGlDebug("compositePreview")
        return drawn
    }

    private fun firstTexture(layer: LayerTextures, stroke: StrokeBuffer, tail: StrokeBuffer?): Int {
        layer.pageTextureOrNull(runPages[0])?.let { return it }
        stroke.pageTextureOrNull(runPages[1])?.let { return it }
        tail?.pageTextureOrNull(runPages[2])?.let { return it }
        return 0
    }

    private fun bindPage(unit: Int, page: Int, texture: Int, filter: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texture)
        // Only a page that is actually sampled needs its filter set; a filler
        // binding must not disturb the filter of a texture another unit is
        // reading this same draw.
        if (page != PreviewPlan.ABSENT) {
            state.textureFilter(GLES30.GL_TEXTURE_2D_ARRAY, texture, filter)
        }
    }

    private fun ensurePreviewCapacity(tiles: Int): Boolean {
        if (tiles <= previewCapacityTiles) return true
        val error = GlErrors.checkAllocation("preview VBO ($tiles tiles)") {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, previewVbo[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                tiles * VERTICES_PER_TILE * PREVIEW_FLOATS_PER_VERTEX * 4,
                null,
                GLES30.GL_STREAM_DRAW,
            )
        }
        if (error != GLES30.GL_NO_ERROR) return false
        previewBuffer = allocatePreview(tiles)
        previewCapacityTiles = tiles
        return true
    }

    private fun appendPreviewQuad(grid: TileGrid, key: TileKey, at: Int) {
        val rect = grid.tileRect(key)
        val origin = grid.origin(key)
        val w = TILE_SIZE.toFloat()
        val x0 = rect.left.toFloat()
        val y0 = rect.top.toFloat()
        val x1 = rect.right.toFloat()
        val y1 = rect.bottom.toFloat()
        val u0 = (rect.left - origin.x) / w
        val v0 = (rect.top - origin.y) / w
        val u1 = (rect.right - origin.x) / w
        val v1 = (rect.bottom - origin.y) / w
        val ls = previewLayerSlice[at].toFloat()
        val ss = previewStrokeSlice[at].toFloat()
        val ts = previewTailSlice[at].toFloat()
        previewVertex(x0, y0, u0, v0, ls, ss, ts)
        previewVertex(x1, y0, u1, v0, ls, ss, ts)
        previewVertex(x1, y1, u1, v1, ls, ss, ts)
        previewVertex(x0, y0, u0, v0, ls, ss, ts)
        previewVertex(x1, y1, u1, v1, ls, ss, ts)
        previewVertex(x0, y1, u0, v1, ls, ss, ts)
    }

    private fun previewVertex(
        x: Float, y: Float, u: Float, v: Float, layerSlice: Float, strokeSlice: Float, tailSlice: Float,
    ) {
        previewBuffer.put(x).put(y).put(u).put(v).put(layerSlice).put(strokeSlice).put(tailSlice)
    }

    private fun allocatePreview(tiles: Int): FloatBuffer = ByteBuffer
        .allocateDirect(tiles * VERTICES_PER_TILE * PREVIEW_FLOATS_PER_VERTEX * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

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
        if (previewInitialized) {
            GLES30.glDeleteBuffers(1, previewVbo, 0)
            GLES30.glDeleteVertexArrays(1, previewVao, 0)
            previewVbo[0] = 0
            previewVao[0] = 0
            previewInitialized = false
            // The staging buffer goes with the capacity that describes it, for
            // the reason DabPass.release records: it is a direct buffer whose
            // off-heap bytes outlive every context-loss cycle otherwise.
            previewCapacityTiles = PREVIEW_TILES
            previewBuffer = allocatePreview(PREVIEW_TILES)
        }
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

        /** x, y, u, v, layerSlice, strokeSlice, tailSlice. */
        const val PREVIEW_FLOATS_PER_VERTEX = 7

        /**
         * The preview's staging size, small on purpose: §11 makes the front
         * frame's dirty rect a few tiles ("a batch of dabs at pen speed spans a
         * few hundred px"), and [ensurePreviewCapacity] grows it for the rare
         * large dab rather than reserving for one.
         */
        const val PREVIEW_TILES = 16

        const val TILE_UNIT = 0
        const val BACKDROP_UNIT = 1
        const val STROKE_UNIT = 2
        const val TAIL_UNIT = 3
    }
}
