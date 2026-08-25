package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
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

    /**
     * One pass for every tile of every rebuild, not one per tile.
     *
     * A `CompositePass` owns a ~120 KiB direct vertex buffer plus a VBO and a
     * VAO. Constructing one per tile would allocate and destroy all of that up
     * to `TileGrid.MAX_TILES` times per rebuild — on the layer-switch path
     * that §4 budgets tens of milliseconds for, and with GL object churn the
     * driver has no reason to make cheap.
     */
    private val pass = CompositePass(program, state)
    private val identity = Mat4.identity()
    private val tileProjection = Mat4.orthoYDown(TILE_SIZE.toFloat(), TILE_SIZE.toFloat())

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
    var aboveAvailable: Boolean = true
        private set

    /**
     * Recomputes [aboveAvailable] for [stack]. Cheap, and called whenever the
     * stack or the active layer changes.
     */
    fun observe(stack: LayerStack) {
        val activeIndex = stack.activeIndex
        aboveAvailable = SandwichPolicy.aboveIsCacheable(
            stack.layers.subList((activeIndex + 1).coerceAtMost(stack.layers.size), stack.layers.size),
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
        val keys = grid.keysFor(rect)
        for (k in keys) {
            if (below) belowBuilt.remove(k.packed)
            if (above) aboveBuilt.remove(k.packed)
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
    fun rebuild(rect: IntRect, stack: LayerStack, paper: Int, layerTextures: (Int) -> LayerTextures) {
        if (!belowStale && !aboveStale) return
        val activeIndex = stack.activeIndex
        val keys = grid.keysFor(rect)
        for (key in keys) {
            if (belowStale && belowBuilt.add(key.packed)) {
                buildTile(
                    key,
                    target = below,
                    // The paper is the backdrop of the below stack, baked in.
                    paper = paper,
                    indices = 0 until activeIndex,
                    stack = stack,
                    layerTextures = layerTextures,
                )
            }
            if (aboveStale && aboveAvailable && aboveBuilt.add(key.packed)) {
                buildTile(
                    key,
                    target = above,
                    // Above composites over TRANSPARENT: it is drawn on top of
                    // the active layer, so anything opaque here would hide it.
                    paper = 0,
                    indices = (activeIndex + 1) until stack.layers.size,
                    stack = stack,
                    layerTextures = layerTextures,
                )
            }
        }
        if (belowBuilt.size >= grid.tileCount) belowStale = false
        if (aboveBuilt.size >= grid.tileCount) aboveStale = false
    }

    /**
     * Composites one tile of a half, in canvas space.
     *
     * Ping-pong between two scratch slices, one pass per contributing layer,
     * with the last pass writing straight into the cache's own slice. Both
     * scratch slices are taken with `allocateNotOn` against each other and
     * against the pages being sampled — §2.1's rule that a pass may not render
     * into a slice of a page it samples, which is per texture *object*, not
     * per slice.
     */
    private fun buildTile(
        key: TileKey,
        target: LayerTextures,
        paper: Int,
        indices: IntRange,
        stack: LayerStack,
        layerTextures: (Int) -> LayerTextures,
    ) {
        val destination = target.sliceForWrite(key)
        if (!fbo.bindArrayLayer(target.pageTexture(destination.page), destination.slice)) return
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glViewport(0, 0, TILE_SIZE, TILE_SIZE)
        fbo.clear(
            premultiplied(paper, 16), premultiplied(paper, 8), premultiplied(paper, 0),
            ((paper ushr 24) and 0xFF) / 255f,
        )
        // One 256x256 quad per contributing layer, identity screen transform:
        // the cache is in CANVAS space, so there is no view to apply and the
        // tile maps one-to-one onto the target.
        val origin = grid.origin(key)
        val tileScreen = ScreenTransform(1f, 0f, -origin.x.toFloat(), -origin.y.toFloat())
        val tileRect = grid.tileRect(key)
        for (i in indices) {
            val props = stack.layers[i].props
            if (!props.visible || props.opacity <= 0f) continue
            // A non-normal layer inside a cache half would need the half's own
            // partial result as a backdrop, which is the ping-pong §4
            // describes. `below` is built bottom-up so the backdrop is always
            // what has been written so far — and for `above`, availability
            // already guaranteed every mode here is Normal.
            pass.draw(
                textures = layerTextures(i),
                mode = if (target === above) BlendMode.NORMAL else props.blendMode,
                opacity = props.opacity,
                screen = tileScreen,
                projection = tileProjection,
                bufferTransform = identity,
                dirtyRect = tileRect,
                backdrop = 0,
            )
        }
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
    }

    /** The context died: the slices do not exist to be freed (§12). */
    fun forgetAll() {
        below.forgetAll()
        above.forgetAll()
        belowBuilt.clear()
        aboveBuilt.clear()
        belowStale = true
        aboveStale = true
    }
}
