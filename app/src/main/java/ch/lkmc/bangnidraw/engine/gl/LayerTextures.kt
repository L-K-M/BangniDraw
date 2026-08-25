package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.SliceHandle
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileIndex
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.nio.ByteBuffer

/**
 * One layer's tiles on the GPU (`docs/plan/03-canvas-engine.md` §2.2, §15).
 *
 * The index — which key holds which slice, and which keys a dirty rect makes
 * visible — is [TileIndex], pure and tested. This class owns what only GL can
 * do: create a tile the first time something touches its key, upload pixels
 * into a slice, swap a slice in on merge, and give it all back.
 *
 * A tile is created (and cleared) the first time a dab batch, merge, fill or
 * upload touches its key, and is **never freed while the layer exists** — a
 * fully erased tile stays allocated, because proving it empty needs a readback
 * and post-v1 residency work is where that belongs.
 *
 * GL-thread-only.
 */
class LayerTextures(
    val grid: TileGrid,
    private val pool: TilePool,
) {

    private val index = TileIndex(grid)

    /**
     * Scratch for [visibleKeys], sized once at the grid's tile count.
     *
     * Owned here rather than by the compositor because it is per layer: two
     * layers' visible sets are gathered before either is drawn, so one shared
     * buffer would have the second overwrite the first. `TileGrid.MAX_TILES`
     * caps this at 1024, so it is 8 KiB and 4 KiB per layer.
     */
    private val sortScratch = LongArray(grid.tileCount)

    /** Tiles this layer has a slice for. */
    val tileCount: Int get() = index.presentCount

    /** The slice holding [k], or [SliceHandle.NONE]. Never allocates. */
    fun slice(k: TileKey): SliceHandle = index[k]

    /**
     * The slice for [k], creating and clearing one if this is the first time
     * anything touched that key.
     *
     * @throws ch.lkmc.bangnidraw.engine.core.PoolExhausted when the pool is
     * full. The caller refuses the operation rather than crashing (§2.1).
     */
    fun sliceForWrite(k: TileKey): SliceHandle {
        val existing = index[k]
        if (!existing.isNone) return existing
        val fresh = pool.allocateCleared()
        // Only after the allocation succeeded: an index entry pointing at a
        // slice the pool refused would be a handle into nothing, and
        // `PoolExhausted` is a normal outcome, so this path runs.
        index.put(k, fresh)
        return fresh
    }

    /**
     * Points [k] at [handle] and frees whatever it held — the swap-on-merge of
     * §7.4, where a pass renders into a fresh slice and the layer adopts it.
     *
     * Freeing the old slice here rather than at the call site is deliberate:
     * the caller has no other reason to hold the previous handle, and a merge
     * that forgot to free would leak one slice per stroke.
     */
    fun swap(k: TileKey, handle: SliceHandle) {
        require(pool.isLive(handle)) { "cannot map $k to $handle: not allocated" }
        val previous = index.put(k, handle)
        if (previous.packed != handle.packed) pool.free(previous)
    }

    /**
     * Fills [out] with the packed keys of this layer that fall inside [rect]
     * and have a slice, sorted by page, and returns how many.
     *
     * Page order is what lets `CompositePass` issue one `glBindTexture` and
     * one `glDrawArrays` per page (§3.2). A layer whose index has no slice in
     * the rect answers 0 and costs one CPU loop and no draw call — which is
     * why the compositor's cost is bounded by output pixels × layers and never
     * by canvas size.
     *
     * [out] is the caller's, sized at [TileGrid.tileCount] and reused: this
     * runs per layer per frame, where `docs/plan/10-performance.md` §2.4
     * allows no allocation.
     */
    fun visibleKeys(rect: IntRect, out: IntArray): Int = index.visibleKeys(rect, out, sortScratch)

    /** Appends every key with content to [out], row-major. Cold paths only — [TileKey] boxes. */
    fun allKeys(out: MutableList<TileKey>) = index.allKeys(out)

    /**
     * Uploads one tile's pixels into its slice, creating the slice if needed.
     *
     * [pixels] is `TILE_SIZE × TILE_SIZE` **premultiplied** RGBA8, row 0 the
     * tile's top row — the same convention as the CPU copies and as
     * `glReadPixels` returns (§2.4, §3.1), so nothing flips anywhere.
     *
     * Edge tiles are uploaded whole, including the part that falls outside the
     * canvas: the slice is a fixed 256², the compositor clips the quad to the
     * canvas rect, and a partial `glTexSubImage3D` would cost a second call
     * per edge tile to save nothing.
     */
    fun upload(k: TileKey, pixels: ByteBuffer) {
        val expected = TILE_SIZE * TILE_SIZE * 4
        require(pixels.remaining() == expected) {
            "a tile upload needs $expected bytes, got ${pixels.remaining()}"
        }
        val handle = sliceForWrite(k)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, pool.textureOf(handle.page))
        // Row alignment 1: the default of 4 happens to be right for RGBA8 at
        // 256 px, but it is right by coincidence, and the coincidence breaks
        // the day anything uploads a sub-rect or a different format.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage3D(
            GLES30.GL_TEXTURE_2D_ARRAY,
            0,
            0,
            0,
            handle.slice,
            TILE_SIZE,
            TILE_SIZE,
            1,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            pixels,
        )
        GlErrors.checkAllocation("upload $k")
    }

    /** Frees every slice this layer holds — the layer is being deleted. */
    fun release() = index.clear(pool::free)

    /**
     * Drops every handle **without freeing** — the context died and the
     * textures went with it (§12).
     *
     * Distinct from [release] because after a context loss there is nothing to
     * return: handing stale indices to a pool that has itself been reset would
     * push them onto the free list of pages that no longer exist. The layer is
     * empty afterwards and `TileStore` refills it, tile by tile, as the IO
     * arrives.
     */
    fun forgetAll() = index.forgetAll()
}
