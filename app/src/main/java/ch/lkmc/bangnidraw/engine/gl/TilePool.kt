package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import android.util.Log
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.PoolExhausted
import ch.lkmc.bangnidraw.engine.core.SliceAllocator
import ch.lkmc.bangnidraw.engine.core.SliceHandle

/**
 * Every tile on the GPU, in a small number of texture-array **pages**
 * (`docs/plan/03-canvas-engine.md` §2.1).
 *
 * A page is one `GL_TEXTURE_2D_ARRAY` created with
 * `glTexStorage3D(…, GL_RGBA8, 256, 256, slicesPerPage)`; each slice holds one
 * tile. Every tile of every layer, the two sandwich caches, the stroke buffer
 * and the RMW scratch slices come from this one pool.
 *
 * Pages are created lazily when the free list runs dry and **never destroyed
 * while the document is open** — freeing a page while the driver may still be
 * defragmenting is a known source of jank, so destruction happens on
 * [release] only.
 *
 * The bookkeeping lives in [SliceAllocator] (pure, tested); this class holds
 * the GL objects and does what §15 says a GL class does: call the pure twin
 * and issue GL calls.
 *
 * GL-thread-only.
 */
class TilePool(
    private val caps: GlCaps,
    private val budget: MemoryBudget.Result,
) {

    val slicesPerPage: Int = caps.slicesPerPage

    /**
     * The page cap comes from [MemoryBudget], not from the raw tile budget:
     * `poolArrayCount` is whole arrays only, which is exactly what this class
     * allocates. Sizing from `gpuTileBudgetBytes` instead would let the pool
     * over-commit by up to one page against the layer cap the New Canvas
     * dialog advertises — the disagreement `MemoryBudget`'s KDoc promises
     * cannot happen.
     *
     * `coerceAtLeast(1)` because a pool that can hold no page at all could not
     * open any document, and `MemoryBudget.compute` already `check`s that its
     * budget holds at least one array; this is the belt to that brace.
     */
    private val maxPages: Int = budget.poolArrayCount.coerceAtLeast(1)

    private val allocator = SliceAllocator(slicesPerPage, maxPages)

    /** GL texture id per page index, parallel to [allocator]'s pages. */
    private val textures = ArrayList<Int>()

    private val fbo = GlFbo()

    val pageCount: Int get() = allocator.pageCount
    val usedSlices: Int get() = allocator.usedCount
    val residentBytes: Long get() = allocator.residentBytes

    /** The GL texture of [page]. */
    fun textureOf(page: Int): Int = textures[page]

    /**
     * A slice, creating a page if every existing one is full.
     *
     * @throws PoolExhausted when the budget allows no further page. That is an
     * ordinary outcome, not a crash (§2.1): the caller refuses the operation
     * that needed the tile — a stroke is cancelled with a one-line notice, a
     * layer add is refused with the budget readout.
     */
    fun allocate(): SliceHandle {
        val existing = allocator.tryAllocate()
        if (!existing.isNone) return existing
        createPage()
        return allocator.tryAllocate()
    }

    /**
     * [allocate], but never from a page in [excluded]
     * (`allocateNotOn` in §2.1).
     *
     * ES 3.0 makes rendering undefined when a fragment shader samples the
     * texture object bound as the draw attachment, and the rule is per texture
     * *object and level*, not per slice — so rendering into slice 7 of a page
     * while sampling slice 3 of the same page is a feedback loop even though
     * the texels never overlap. Every read-modify pass that reads pool slices
     * (`MergePass`, the sandwich rebuild, `SmudgePass`) takes its target
     * through here, and a second page is created if only one exists: 64 MiB,
     * once, on the first stroke, paid from the same budget as every page.
     */
    fun allocateNotOn(excluded: IntArray): SliceHandle {
        val existing = allocator.tryAllocateNotOn(excluded)
        if (!existing.isNone) return existing
        createPage()
        // The page just created cannot be in `excluded` — it did not exist
        // when the caller built that list — so a plain retry is enough.
        return allocator.tryAllocateNotOn(excluded)
    }

    /**
     * [allocate] followed by a clear to transparent.
     *
     * A fresh slice's contents are whatever the previous tenant left, so
     * anything that will be *composited* before it is fully painted has to
     * come from here. A slice about to be overwritten in full — a readback
     * target, an upload destination — can take [allocate] and skip the clear.
     */
    fun allocateCleared(): SliceHandle {
        val handle = allocate()
        clear(handle)
        return handle
    }

    /**
     * Clears one slice to transparent black.
     *
     * Binds the shared FBO to that slice; the caller's framebuffer binding is
     * therefore **gone** afterwards. Every pass in this engine rebinds its own
     * target before drawing (§3.2 step 3 says so explicitly for the window
     * buffer), so this is stated rather than defended against.
     */
    fun clear(handle: SliceHandle) {
        require(allocator.isLive(handle)) { "cannot clear $handle: not allocated" }
        if (!fbo.bindArrayLayer(textures[handle.page], handle.slice)) return
        // No scissor: the whole slice is the target, and a stale scissor from
        // the compositor would leave most of the tile holding the previous
        // tenant's pixels.
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glViewport(0, 0, TILE_SIZE, TILE_SIZE)
        fbo.clear(0f, 0f, 0f, 0f)
    }

    /** Returns a slice. Its contents stay garbage until something clears them. */
    fun free(handle: SliceHandle) = allocator.free(handle)

    /** True while [handle] is one this pool handed out and has not taken back. */
    fun isLive(handle: SliceHandle): Boolean = allocator.isLive(handle)

    /**
     * Creates one page.
     *
     * `glTexStorage3D` is immutable storage — the size and format are fixed at
     * creation — which is what lets a slice be rendered into via
     * `glFramebufferTextureLayer` with no per-slice setup. `GL_OUT_OF_MEMORY`
     * here is converted into [PoolExhausted] and handled per §2.1 rather than
     * left to surface as a corrupt frame later.
     */
    private fun createPage() {
        if (!allocator.canAddPage) {
            throw PoolExhausted(allocator.pageCount, maxPages, slicesPerPage)
        }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ids[0])
        GLES30.glTexStorage3D(
            GLES30.GL_TEXTURE_2D_ARRAY,
            1, // no mip chain: §3.4 rejects mipmaps for tiles and supersamples instead
            GLES30.GL_RGBA8,
            TILE_SIZE,
            TILE_SIZE,
            slicesPerPage,
        )
        val error = GlErrors.checkAllocation("glTexStorage3D page ${allocator.pageCount}")
        if (error != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, ids, 0)
            throw PoolExhausted(allocator.pageCount, maxPages, slicesPerPage)
        }
        // CLAMP_TO_EDGE on both axes: a tile must never wrap into the opposite
        // edge of itself when the compositor samples half a texel past the
        // border (§3.4, "tile seams"). The filter is set per frame by
        // `GlState.textureFilter` from the zoom, so it is not set here.
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE,
        )
        // The allocator learns about the page only once the texture exists:
        // a recorded page with no texture behind it hands out handles into
        // nothing, and nothing downstream could tell.
        val page = allocator.addPage()
        textures += ids[0]
        Log.i(
            GL_TAG,
            "tile pool grew to ${page + 1} page(s), $residentBytes B of " +
                "${budget.poolCapacityBytes} B",
        )
    }

    /**
     * Deletes every page and forgets every handle — document close, or a
     * context that is gone (§12).
     *
     * After this, every [SliceHandle] anyone still holds is stale.
     * `LayerTextures.forgetAll` is what makes a layer safe to rebuild.
     */
    fun release(state: GlState? = null) {
        if (textures.isNotEmpty()) {
            val ids = textures.toIntArray()
            GLES30.glDeleteTextures(ids.size, ids, 0)
            state?.let { s -> ids.forEach(s::forgetTexture) }
            textures.clear()
        }
        fbo.release()
        allocator.reset()
    }

    /** One line for the debug overlay and for a `PoolExhausted` notice. */
    fun describe(): String =
        "$pageCount/$maxPages pages × $slicesPerPage slices, $usedSlices used, $residentBytes B"
}
