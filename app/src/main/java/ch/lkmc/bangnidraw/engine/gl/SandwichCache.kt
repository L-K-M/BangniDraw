package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.PoolExhausted
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey

/**
 * The two cached composites either side of the active layer
 * (`docs/plan/03-canvas-engine.md` §4).
 *
 * - **Below** = the paper ⊕ layers `0 .. k-1`, in canvas space. The paper is
 *   **baked in**, not drawn under the cache: with §3.3's formula a non-Normal
 *   layer over a transparent backdrop degenerates to plain source-over
 *   (`b.a = 0 ⇒ co = cs`), so a paper-less Below drawn over the paper would
 *   render a Multiply layer 0 as Normal and diverge from both the direct
 *   composite and from flatten.
 * - **Above** = layers `k+1 .. N-1` over transparent, **only if every visible
 *   one of them is Normal** ([SandwichPolicy.aboveIsCacheable]). Otherwise it
 *   is unavailable and those layers are composited individually per frame.
 *
 * A frame while a stroke is live is then `Below → (active ⊕ stroke) → Above`:
 * **three layer passes whatever N is**. That is the entire point — the live
 * path's cost must not grow with the layer count, and layer count is what
 * large paintings have.
 *
 * Staleness is per **side**, and rebuilds are lazy and per tile: a stale half
 * costs at most one extra composite of the visible tiles, on actions that
 * already expect a beat (switching layers), never during a stroke.
 *
 * GL-thread-only.
 */
class SandwichCache(
    private val grid: TileGrid,
    private val pool: TilePool,
    private val program: GlProgram,
    private val state: GlState,
) {

    /** The two halves, each shaped like a layer. */
    val below = LayerTextures(grid, pool)
    val above = LayerTextures(grid, pool)

    private val fbo = GlFbo()
    private val pass = TileCompositePass(program, state, pool)
    private val excludedPages = IntArray(2)

    /**
     * Reused tile-key scratch for [rebuild]. Separate from [invalidateScratch]
     * so a path reachable from [buildTile] that re-enters [invalidateTiles]
     * cannot corrupt the in-flight loop below.
     */
    private var keyScratch = IntArray(0)

    /** Reused tile-key scratch for [invalidateTiles]; never touches [keyScratch]. */
    private var invalidateScratch = IntArray(0)

    /**
     * Grows [keyScratch] to full grid capacity and fills it with the keys
     * intersecting [rect]; returns how many were written. Contract:
     * [TileGrid.keysFor] never emits more keys than [TileGrid.tileCount] for any
     * rect, so the buffer is never over-run.
     */
    private fun fillKeys(rect: IntRect): Int {
        if (keyScratch.size < grid.tileCount) keyScratch = IntArray(grid.tileCount)
        return grid.keysFor(rect, keyScratch)
    }

    /** A whole half needs rebuilding; individual tiles are rebuilt as they are drawn. */
    private var belowStale = true
    private var aboveStale = true

    /** Which tiles of a stale half have already been brought up to date. */
    private val belowBuilt = HashSet<Int>()
    private val aboveBuilt = HashSet<Int>()

    /**
     * False when a non-normal layer sits above the active one, so `Above`
     * cannot be cached at all and the renderer must take the per-layer path.
     *
     * Distinct from stale on purpose: stale means "rebuild it", unavailable
     * means "there is no correct thing to build". Source-over is associative,
     * so `above OVER (active BLEND below)` is exact; Multiply and the rest are
     * not associative with respect to the backdrop.
     */
    var aboveAvailable: Boolean = UNAVAILABLE_UNTIL_OBSERVED
        private set

    /** Below can cache every blend mode because [buildTile] ping-pongs. */
    var belowAvailable: Boolean = UNAVAILABLE_UNTIL_OBSERVED
        private set

    /**
     * Recomputes [aboveAvailable] **and** [belowAvailable] for [stack]. Cheap,
     * and called whenever the stack or the active layer changes.
     *
     * Until it first runs both halves report *unavailable*, so a cache is
     * never built from an unexamined stack — see [UNAVAILABLE_UNTIL_OBSERVED].
     */
    fun observe(stack: LayerStack) {
        val activeIndex = stack.activeIndex
        val size = stack.layers.size
        aboveAvailable = SandwichPolicy.aboveIsCacheable(
            stack.layers.subList((activeIndex + 1).coerceIn(0, size), size),
        )
        belowAvailable = SandwichPolicy.belowIsCacheable(
            stack.layers.subList(0, activeIndex.coerceIn(0, size)),
        )
    }

    /** Applies an edit's invalidation per `05-layers.md` §8. */
    fun invalidate(op: SandwichPolicy.Op, activeIndex: Int) {
        val stale = SandwichPolicy.stale(op, activeIndex)
        if (stale.below) markBelowStale()
        if (stale.above) markAboveStale()
    }

    /**
     * Marks only the tiles of [rect] — an undo restoring tiles, a fill — as
     * needing a rebuild, rather than the whole half.
     *
     * §4's table distinguishes "rebuild affected tiles" from "rebuild all",
     * and the distinction is worth keeping: an undo of a stroke on a layer
     * below touches a handful of tiles, and staling the half would rebuild
     * every visible one.
     */
    fun invalidateTiles(rect: IntRect, below: Boolean, above: Boolean) {
        if (invalidateScratch.size < grid.tileCount) invalidateScratch = IntArray(grid.tileCount)
        val count = grid.keysFor(rect, invalidateScratch)
        for (i in 0 until count) {
            if (below) belowBuilt.remove(invalidateScratch[i])
            if (above) aboveBuilt.remove(invalidateScratch[i])
        }
        if (below) belowStale = true
        if (above) aboveStale = true
    }

    private fun markBelowStale() {
        belowStale = true
        belowBuilt.clear()
    }

    private fun markAboveStale() {
        aboveStale = true
        aboveBuilt.clear()
    }

    /**
     * Brings every tile of [rect] up to date in whichever half is stale, so
     * the compositor can then draw both as ordinary layers.
     *
     * Viewport-first by construction: the caller passes the visible rect (plus
     * `SANDWICH_MARGIN_PX`), so a "rebuild all" costs one composite of what is
     * on screen and the rest arrives as it is scrolled into view.
     */
    fun rebuild(
        rect: IntRect,
        stack: LayerStack,
        paper: Int,
        layerTextures: (Int) -> LayerTextures,
        drawBelowBase: (TileKey) -> Unit,
    ) {
        // An unavailable half never fills its built set, so its stale flag can
        // never clear — without the availability terms this early return never
        // fires again and every frame pays a keysFor allocation and a walk for
        // zero work, in the standing configuration where a non-Normal layer
        // sits next to the active one.
        val belowPending = belowStale && belowAvailable
        val abovePending = aboveStale && aboveAvailable
        if (!belowPending && !abovePending) return
        val activeIndex = stack.activeIndex
        val count = fillKeys(rect)
        for (i in 0 until count) {
            val key = TileKey(keyScratch[i])
            if (belowPending && key.packed !in belowBuilt) {
                val built = buildTile(
                    key,
                    target = below,
                    // The paper is the backdrop of the below stack, baked in.
                    paper = paper,
                    indices = 0 until activeIndex,
                    stack = stack,
                    layerTextures = layerTextures,
                    drawBase = drawBelowBase,
                )
                if (built) belowBuilt.add(key.packed)
            }
            if (abovePending && key.packed !in aboveBuilt) {
                val built = buildTile(
                    key,
                    target = above,
                    // Above composites over TRANSPARENT: it is drawn on top of
                    // the active layer, so anything opaque here would hide it.
                    paper = 0,
                    indices = (activeIndex + 1) until stack.layers.size,
                    stack = stack,
                    layerTextures = layerTextures,
                    drawBase = null,
                )
                if (built) aboveBuilt.add(key.packed)
            }
        }
        if (belowBuilt.size >= grid.tileCount) belowStale = false
        if (aboveBuilt.size >= grid.tileCount) aboveStale = false
    }

    /**
     * Composites one tile of a half, in canvas space.
     *
     * Each contributing layer reads the partial composite and its own tile,
     * then writes a fresh slice excluded from both sampled pages. The cache
     * adopts the final slice only after every pass succeeds, so a refusal keeps
     * the previous cached tile intact for a later retry.
     */
    private fun buildTile(
        key: TileKey,
        target: LayerTextures,
        paper: Int,
        indices: IntRange,
        stack: LayerStack,
        layerTextures: (Int) -> LayerTextures,
        drawBase: ((TileKey) -> Unit)?,
    ): Boolean {
        var current = try {
            pool.allocate()
        } catch (_: PoolExhausted) {
            return false
        }
        if (!fbo.bindArrayLayer(pool.textureOf(current.page), current.slice)) {
            pool.free(current)
            return false
        }
        state.scissorOff()
        state.viewport(0, 0, TILE_SIZE, TILE_SIZE)
        fbo.clear(
            premultiplied(paper, 16), premultiplied(paper, 8), premultiplied(paper, 0),
            ((paper ushr 24) and 0xFF) / 255f,
        )
        drawBase?.invoke(key)

        for (i in indices) {
            val props = stack.layers[i].props
            if (!props.visible || props.opacity <= 0f) continue
            val source = layerTextures(i).slice(key)
            if (source.isNone) continue

            excludedPages[0] = current.page
            excludedPages[1] = source.page
            val next = try {
                pool.allocateNotOn(excludedPages)
            } catch (_: PoolExhausted) {
                pool.free(current)
                return false
            }
            if (!pass.draw(source, current, next, props.blendMode, props.opacity)) {
                pool.free(next)
                pool.free(current)
                return false
            }

            pool.free(current)
            current = next
        }
        target.swap(key, current)
        return true
    }

    private fun premultiplied(argb: Int, shift: Int): Float {
        val a = ((argb ushr 24) and 0xFF) / 255f
        return (((argb ushr shift) and 0xFF) / 255f) * a
    }

    /** Both halves are gone — document closed, or the context died (§12). */
    fun release() {
        below.release()
        above.release()
        pass.release()
        fbo.release()
        belowBuilt.clear()
        aboveBuilt.clear()
        belowStale = true
        aboveStale = true
        // Derived from a stack this cache has not seen since the context went
        // away, so back to refusing until re-observed.
        aboveAvailable = UNAVAILABLE_UNTIL_OBSERVED
        belowAvailable = UNAVAILABLE_UNTIL_OBSERVED
    }

    private companion object {
        /**
         * Both availability flags start **false**.
         *
         * `true` would mean "cacheable" for the window between construction and
         * the first [observe], and a [rebuild] landing there would force
         * `BlendMode.NORMAL` over layers that are actually Multiply or Screen,
         * cache that composite, and show it — no crash, no log, just wrong
         * blending. Today the renderer cannot reach that window (a non-null
         * stack implies [observe] ran), but the ordering is a convention, not a
         * type, and a fresh cache after a context loss starts here again.
         *
         * Defaulting to unavailable costs a frame or two on the always-correct
         * per-layer path and cannot be wrong — the same trade
         * `OffscreenTarget.ensure` makes when it returns false instead of
         * throwing: degrade to the safe path, never to a wrong result.
         */
        const val UNAVAILABLE_UNTIL_OBSERVED = false
    }

    /** The context died: the slices do not exist to be freed (§12). */
    fun forgetAll() {
        below.forgetAll()
        above.forgetAll()
        belowBuilt.clear()
        aboveBuilt.clear()
        belowStale = true
        aboveStale = true
        // Derived from a stack this cache has not seen since the context went
        // away, so back to refusing until re-observed.
        aboveAvailable = UNAVAILABLE_UNTIL_OBSERVED
        belowAvailable = UNAVAILABLE_UNTIL_OBSERVED
    }
}
