package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import android.util.Log
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
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
     * [MemoryBudget] runs before a GL context exists and therefore assumes
     * 256-slice pages. Recompute the page count from the probed page size;
     * retaining the assumed count gives a 64-slice driver one quarter of the
     * budgeted capacity.
     */
    private val bytesPerPage = slicesPerPage.toLong() * TILE_BYTES
    private val maxPages: Int =
        (budget.gpuTileBudgetBytes / bytesPerPage)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .also { check(it > 0) { "tile budget cannot hold one $slicesPerPage-slice page" } }

    private val allocator = SliceAllocator(slicesPerPage, maxPages)

    /** GL texture id per page index, parallel to [allocator]'s pages. */
    private val textures = ArrayList<Int>()

    private val fbo = GlFbo()

    /**
     * The clear-bind warning fires once, for the same reason
     * `GlFbo.loggedIncomplete` does — and it is the same underlying failure.
     * A driver that persistently reports the FBO incomplete fails this bind on
     * every fresh-slice clear, and §12's streaming refill allocates tiles
     * continuously, so an unguarded log would put back exactly the per-frame
     * spam `GlFbo` suppresses one call down the stack.
     */
    private var loggedClearBindFailure = false

    val pageCount: Int get() = allocator.pageCount
    val usedSlices: Int get() = allocator.usedCount

    /**
     * Every slice this pool can ever hold — the denominator the debug overlay
     * shows [usedSlices] against.
     *
     * From [maxPages], after the context's page size has replaced the
     * pre-context assumption in [MemoryBudget].
     */
    val sliceCapacity: Int get() = slicesPerPage * maxPages
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
        val handle = allocator.tryAllocate()
        // createPage() either added slicesPerPage free slices or threw, so this
        // cannot fail — which is exactly why it is worth saying out loud: a
        // NONE escaping here reaches `textures[handle.page]` and either throws
        // out of bounds or skips a draw, with nothing naming the pool.
        check(!handle.isNone) { "no slice after creating page ${allocator.pageCount - 1}" }
        return handle
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
        // when the caller built that list — so a plain retry is enough. Unless
        // a caller passes an over-wide or stale list, which is the case this
        // check names rather than letting a NONE reach the render path.
        val handle = allocator.tryAllocateNotOn(excluded)
        check(!handle.isNone) {
            "no slice after creating page ${allocator.pageCount - 1}; " +
                "stale exclusion list ${excluded.contentToString()}?"
        }
        return handle
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
        if (!fbo.bindArrayLayer(textures[handle.page], handle.slice)) {
            // Silent would be the worst outcome in this file: `allocateCleared`
            // would hand out a slice still holding the previous tenant's pixels
            // while every caller believes it is transparent, and nothing in the
            // log would point at the cause. Once, though — the messages differ
            // only by handle, so the second adds noise rather than information.
            if (!loggedClearBindFailure) {
                loggedClearBindFailure = true
                Log.w(
                    GL_TAG,
                    "clear($handle): FBO bind failed, the slice keeps stale contents " +
                        "(further clear bind failures this session are suppressed)",
                )
            }
            return
        }
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
        val error = GlErrors.checkAllocation("glTexStorage3D page ${allocator.pageCount}") {
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
        }
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
        // Incomplete-texture insurance. GL's default min filter is
        // GL_NEAREST_MIPMAP_LINEAR, and this page has one level (§3.4 rejects
        // mipmaps for tiles), so a page sampled before `GlState.textureFilter`
        // has set a filter is *incomplete* and reads back black — with no GL
        // error. The per-frame filter still overrides this; what it removes is
        // the whole failure class for any future path that binds a page
        // directly (a readback helper, a debug pass).
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR,
        )
        // The allocator learns about the page only once the texture exists:
        // a recorded page with no texture behind it hands out handles into
        // nothing, and nothing downstream could tell.
        val page = allocator.addPage()
        textures += ids[0]
        Log.i(
            GL_TAG,
            "tile pool grew to ${page + 1} page(s), $residentBytes B of " +
                "${budget.gpuTileBudgetBytes} B",
        )
    }

    /**
     * Deletes every page and forgets every handle — document close, or a
     * context that is gone (§12).
     *
     * After this, every [SliceHandle] anyone still holds is stale.
     * `LayerTextures.forgetAll` is what makes a layer safe to rebuild.
     */
    fun release(state: GlState) {
        if (textures.isNotEmpty()) {
            val ids = textures.toIntArray()
            GLES30.glDeleteTextures(ids.size, ids, 0)
            // Not optional, and it used to default to null. Drivers recycle
            // texture ids: leaving GlState's filter cache holding a deleted id
            // means the next page to inherit that id is believed to already
            // have its filter set, `textureFilter` skips the glTexParameteri,
            // and the fresh page samples black at its default min filter.
            ids.forEach(state::forgetTexture)
            textures.clear()
        }
        fbo.release()
        allocator.reset()
        loggedClearBindFailure = false
    }

    /** One line for the debug overlay and for a `PoolExhausted` notice. */
    fun describe(): String =
        "$pageCount/$maxPages pages × $slicesPerPage slices, $usedSlices used, $residentBytes B"
}
