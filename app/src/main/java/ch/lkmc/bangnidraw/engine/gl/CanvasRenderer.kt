package ch.lkmc.bangnidraw.engine.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.BufferScissor
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Clock
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.BufferPresentationDecision
import ch.lkmc.bangnidraw.engine.core.BufferPresentationPolicy
import ch.lkmc.bangnidraw.engine.core.CanvasVoidColorPolicy
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.FitTransform
import ch.lkmc.bangnidraw.engine.core.FillReference
import ch.lkmc.bangnidraw.engine.core.Coverage
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.LayerThumbnail
import ch.lkmc.bangnidraw.engine.core.LayerVisibilityPolicy
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.MutableIntRect
import ch.lkmc.bangnidraw.engine.core.PixelOp
import ch.lkmc.bangnidraw.engine.core.PoolExhausted
import ch.lkmc.bangnidraw.engine.core.PerfStats
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.SANDWICH_MARGIN_PX
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
import ch.lkmc.bangnidraw.engine.core.SampleSource
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.engine.core.TileCapacityPolicy
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.RmwTouchTracker
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.engine.core.TiledPixelSource
import ch.lkmc.bangnidraw.engine.core.ThemeTone
import ch.lkmc.bangnidraw.engine.mixbox.MixboxLut
import ch.lkmc.bangnidraw.engine.mixbox.MixboxShaderSource

/**
 * Frame orchestration: the GL-thread half of a canvas
 * (`docs/plan/03-canvas-engine.md` §3.2, §15).
 *
 * **This is the multi-buffered path only** (roadmap 2.3b). It draws the full
 * viewport from committed state, which §8.2 says also serves every non-stroke
 * redraw — layer edits, undo, view changes, resize. The front-buffered path
 * (`onDrawFrontBufferedLayer`, the stroke buffer, the predicted tail) is
 * roadmap 2.5, and `DabPass`/`MergePass` are 2.4; until they land there is no
 * live stroke to draw, so the one callback here is the whole renderer.
 *
 * Per frame, in §3.2's order, all into `Accum` and then presented:
 *
 * 1. **Canvas void + paper** — clear `Accum` to the themed surround, then draw
 *    the canvas-sized paper (or checkerboard) through the same screen transform
 *    as the layer tiles. When the sandwich is in use opaque paper is already
 *    baked into `Below`, so only the surround is needed before `Below`.
 * 2. **Layers bottom to top** — `Below → active → Above`, or every layer
 *    individually when a cache half is unavailable.
 * 3. **Present** — `Accum` into the window buffer as a textured quad through
 *    `u_bufferTransform`, never a blit: a blit cannot rotate, and graphics-core
 *    may hand us a pre-rotated buffer (§3.2 step 3, §8.5).
 *
 * GL-thread-only. Every entry point assumes a current context.
 */
class CanvasRenderer(
    private val canvas: CanvasSize,
    private val budget: MemoryBudget.Result,
    private val assets: AssetManager,
    /**
     * §11's budgets, measured (`10-performance.md` §5.3). Written here on the
     * GL thread and read by the debug overlay on the main thread; the default
     * is a live instance rather than null so no call site needs a null check on
     * the render path.
     */
    val perf: PerfStats = PerfStats(),
    /** Injected so a test can hand it values; `System.nanoTime` in production. */
    private val clock: Clock = Clock.SYSTEM,
    /**
     * §10.1's consumer: receives each merged tile's pixels on the GL thread,
     * copies them out (the buffer dies when the call returns — [Readback]'s
     * contract) and hands them to `TileFlusher`. Null in tests and in any
     * context without persistence; then no [Readback] exists and a merge
     * leaves the CPU mirror alone.
     */
    private val onTile: ((LayerId, TileKey, Int, java.nio.ByteBuffer) -> Unit)? = null,
) {

    /**
     * Owned here rather than by the session so it lives and dies with the GL
     * objects: [release] must map what is still in flight before the PBOs go,
     * and [onContextLost] must forget them without touching dead fences.
     */
    private val readback: Readback? = onTile?.let { Readback(it) }

    /** In-flight readback chunks, mirrored for the session's poll pump. */
    val readbackPending: Int get() = readback?.pending ?: 0

    /** §10.1's poll, for the `Choreographer`-driven pump between frames. */
    fun pollReadback() {
        readback?.poll()
    }

    /**
     * Blocks the GL thread until everything in flight has been handed over —
     * the checkpoint's "the last stroke's pixels are on the CPU" barrier.
     * Bounded by [Readback]'s fence timeout, never forever.
     */
    fun finishReadback(): Int {
        readback?.finish()
        return readbackPending
    }

    private val grid = TileGrid(canvas.width, canvas.height)
    private val state = GlState()

    private val accum = OffscreenTarget("Accum")
    private val scratch = OffscreenTarget("Scratch")
    private val fbo = GlFbo()
    private val pixelReadback = PixelReadback(grid, fbo)

    /**
     * A second FBO purely so `Accum → Scratch` can bind a source and a
     * destination at once.
     *
     * `glBindFramebuffer(GL_FRAMEBUFFER, …)` sets the read AND draw bindings,
     * so blitting through one FBO object makes the destination its own source
     * and copies a texture onto itself — silently, with no GL error.
     */
    private val readFbo = GlFbo()

    /** The backdrop-copy warning fires once, like the others: it recurs per frame. */
    private var loggedBackdropFailure = false

    /** `(x, y, width, height)` for `glScissor`, reused — §2.4 allows no per-frame allocation. */
    private val scissorScratch = IntArray(4)

    /**
     * The whole canvas, built once rather than per frame.
     *
     * `IntRect` is a `data class`, not a value class, so the obvious
     * `IntRect(0, 0, canvas.width, canvas.height)` inside `drawFrame` allocated
     * on the render thread every committed frame — next to the comment above
     * citing the rule against exactly that. `canvas` is fixed for the life of
     * the renderer (a size change builds a new session, `key(canvas)`), so
     * there is nothing to invalidate.
     */
    private val fullCanvasRect = IntRect(0, 0, canvas.width, canvas.height)

    /** Reused by the sandwich's viewport query on every frame. */
    private val visibleCanvasBounds = MutableIntRect()

    private val projection = FloatArray(Mat4.SIZE)
    private val identity = Mat4.identity()

    var caps: GlCaps? = null
        private set

    private var pool: TilePool? = null
    private var composite: GlProgram? = null
    private var present: GlProgram? = null
    private var checker: GlProgram? = null
    private var tileComposite: GlProgram? = null
    private var compositePass: CompositePass? = null
    private var thumbnailPass: LayerThumbnailPass? = null
    private var layerPixelPass: LayerPixelPass? = null
    private var dab: GlProgram? = null
    private var smudgeDeposit: GlProgram? = null
    private var smudgeAbsorb: GlProgram? = null
    private var blurHorizontal: GlProgram? = null
    private var blurVertical: GlProgram? = null
    private var smudgeDepositMix: GlProgram? = null
    private var smudgeAbsorbMix: GlProgram? = null
    private var merge: GlProgram? = null
    private var mergeMix: GlProgram? = null
    private var dabPass: DabPass? = null
    private var smudgePass: SmudgePass? = null
    private var mergePass: MergePass? = null
    private var preview: GlProgram? = null
    private var previewMix: GlProgram? = null
    private var mixboxLut = 0
    private var strokeBuffer: StrokeBuffer? = null

    /**
     * §9's `TailBuffer`: the predicted tail's tiles, cleared every frame.
     *
     * A second [StrokeBuffer] rather than a class of its own, because that is
     * literally what §9 specifies — "allocated like a stroke buffer, ≤ 4 keys,
     * cleared every frame" — and the two differ only in lifetime. A `TailBuffer`
     * type would be the same tile index, the same lazy cleared allocation and
     * the same dirty rect under a second name, with the pool-ordering rules of
     * §2.1 duplicated into it.
     *
     * **It is not in `MemoryBudget`'s reservation, and does not need to be.**
     * §7.1 reserves a full layer's worth for the stroke buffer because a wild
     * stroke can touch every key; a tail covers one frame of pen travel, which
     * is the ≤ 4 keys §9 names. If the pool is full anyway, `DabPass` skips the
     * key it could not allocate — and a gap in a *predicted* tail is the
     * cheapest pixel in the engine to lose, replaced wholesale on the next
     * frame.
     */
    private var tailBuffer: StrokeBuffer? = null

    /**
     * The canvas rect the tail drawn last frame occupies, or empty.
     *
     * §8.1 step 3's "previous predicted tail's rect": the composite redraws
     * complete pixels from committed content plus the *real* stroke buffer, so
     * including this rect in the next frame's dirty region is the entire
     * mechanism that erases the old tail. There is no undo-the-tail pass, and
     * this one field is the whole of the bookkeeping (§9).
     *
     * Kept here rather than read back from [tailBuffer] because the buffer is
     * reset before the new tail is stamped, and the rect has to outlive that.
     */
    private var previousTailRect: IntRect = IntRect.EMPTY

    /**
     * The stroke in progress, or null between strokes. Set at pen-down and
     * cleared at merge or cancel — the one place that says whether a stroke
     * exists, so [strokeBuffer] never has to be interrogated for it.
     */
    private var stroke: StrokeSpec? = null
    private var strokeR = 0f
    private var strokeG = 0f
    private var strokeB = 0f
    private var strokeBufferMode = BufferMode.Accumulate
    private var rmwDirty = IntRect.EMPTY
    private val rmwTouches = RmwTouchTracker(grid)
    private val rmwKeyScratch = IntArray(grid.tileCount)

    /** Captures direct-write before-images immediately before their first write. */
    var onRmwFirstTouch: ((StrokeSpec, IntArray, Int) -> Unit)? = null

    /** Reused across strokes: the merge walks it and the readback reads it. */
    private val mergedKeys = ArrayList<TileKey>()
    private val mergeQuad = FullRectQuad()
    /**
     * Canvas-sized paper/checker geometry; stable even when the viewport is a
     * different shape.
     */
    private val paperQuad = FullRectQuad()
    /** The `Accum` present uses the viewport's logical full-screen quad. */
    private val screenQuad = FullRectQuad()
    private var sandwich: SandwichCache? = null

    private val layers = LinkedHashMap<LayerId, LayerTextures>()
    private val sandwichLayers: (LayerId) -> LayerTextures? = { layers[it] }
    private val transparentTile = java.nio.ByteBuffer.allocate(TILE_BYTES)

    private data class ThumbnailRequest(
        val layer: LayerId,
        val callback: (LayerId, LayerThumbnail?) -> Unit,
    )

    private val thumbnailRequests = ArrayDeque<ThumbnailRequest>()

    val thumbnailPending: Int
        get() = thumbnailRequests.size + (thumbnailPass?.pending ?: 0)

    /** Set by the session; the renderer never reads the document itself. */
    var stack: LayerStack? = null
        private set

    /** Premultiplied-ready ARGB; alpha 0 means the transparent-paper checkerboard. */
    var paperColor: Int = 0xFFFFFFFF.toInt()
        private set

    var view: ViewTransform = ViewTransform()
        private set

    private var fit: FitTransform? = null
    private var viewportWidth = 0
    private var viewportHeight = 0

    /** 8 dp of checkerboard in px; the caller owns the display density. */
    var checkerPx: Float = 24f

    /** Theme colours for the transparent-paper checkerboard (`ui/theme/Color.kt` owns them). */
    var checkerA: Int = 0xFFFFFFFF.toInt()
    var checkerB: Int = 0xFFE0E0E0.toInt()

    /** Theme colour outside the transformed paper (`08-ui-and-layout.md` §5.1). */
    var canvasVoid: Int = CanvasVoidColorPolicy.argb(ThemeTone.LIGHT)

    /**
     * True once [onContextCreated] has run and the device can render at all.
     *
     * `@Volatile` because the reopen path polls it from an IO coroutine to
     * know when tile uploads may begin (roadmap 3a); it is written on the GL
     * thread, and without the fence the poller could read `false` forever.
     */
    @Volatile
    var isReady: Boolean = false
        private set

    /** The composed `view ∘ fit`, or null before the first surface size arrives. */
    val screen: ScreenTransform?
        get() = fit?.let { ScreenTransform.of(it, view) }

    // ------------------------------------------------------------ lifecycle

    /**
     * Probes the device, compiles the shaders and creates the pool — §12's
     * cold path, run once per context before anything is allocated.
     *
     * Returns false on a device that cannot run this engine, which §13 says
     * shows the "unsupported device" screen: the Studio still works, the
     * Canvas refuses to open. Not expected to fire on any API-29 hardware.
     */
    fun onContextCreated(strict: Boolean): Boolean {
        // Process-wide: with more than one GL context the last caller wins for
        // all of them. Fine while the app has a single canvas; revisit before a
        // second GL surface (a thumbnail renderer) exists.
        GlErrors.strict = strict
        GlErrors.reset()
        state.invalidate()
        state.forgetAllTextures()
        loggedBackdropFailure = false
        val probed = GlCaps.probe()
        caps = probed
        Log.i(GL_TAG, "GL context: ${probed.describe()}")
        if (!probed.isSupported) {
            Log.w(GL_TAG, "unsupported device: ${probed.describe()}")
            isReady = false
            return false
        }
        // §13 calls a link failure "fatal for the Canvas ... a crash-report-worthy
        // bug, not a device condition". Fatal for the Canvas is what returning
        // false does — the Studio still works and the Canvas refuses to open —
        // and it is strictly better than letting the exception escape a GL
        // callback, which crashes the app and reports the same information.
        // Accumulated so the ones that DID link can be released: the fields are
        // assigned only after the try, so on a failure the earlier programs
        // would simply go out of scope and leak their GL ids until the context
        // is destroyed — once per retry, on a path that is retryable.
        // Captured as they link rather than pulled back out by index. The
        // list still exists so the failure path can release whatever got as far
        // as linking; what it must not be is the way programs are *identified*,
        // because nothing distinguishes one GlProgram from another and
        // inserting a program above PREVIEW would silently hand the preview
        // pass someone else's shaders. The `onContextLost` comment records that
        // this family of lists has already been wrong twice.
        val linked = ArrayList<GlProgram>(15)
        var compositeProgram: GlProgram? = null
        var presentProgram: GlProgram? = null
        var checkerProgram: GlProgram? = null
        var tileCompositeProgram: GlProgram? = null
        var dabProgram: GlProgram? = null
        var smudgeDepositProgram: GlProgram? = null
        var smudgeAbsorbProgram: GlProgram? = null
        var blurHorizontalProgram: GlProgram? = null
        var blurVerticalProgram: GlProgram? = null
        var smudgeDepositMixProgram: GlProgram? = null
        var smudgeAbsorbMixProgram: GlProgram? = null
        var mergeProgram: GlProgram? = null
        var mergeMixProgram: GlProgram? = null
        var previewProgram: GlProgram? = null
        var previewMixProgram: GlProgram? = null
        var lutTexture = 0
        try {
            compositeProgram = GlProgram.link(Shaders.COMPOSITE).also { linked += it }
            presentProgram = GlProgram.link(Shaders.PRESENT).also { linked += it }
            checkerProgram = GlProgram.link(Shaders.CHECKER).also { linked += it }
            tileCompositeProgram = GlProgram.link(Shaders.TILE_COMPOSITE).also { linked += it }
            dabProgram = GlProgram.link(Shaders.DAB).also { linked += it }
            smudgeDepositProgram = GlProgram.link(Shaders.SMUDGE_DEPOSIT).also { linked += it }
            smudgeAbsorbProgram = GlProgram.link(Shaders.SMUDGE_ABSORB).also { linked += it }
            blurHorizontalProgram = GlProgram.link(Shaders.BLUR_HORIZONTAL).also { linked += it }
            blurVerticalProgram = GlProgram.link(Shaders.BLUR_VERTICAL).also { linked += it }
            mergeProgram = GlProgram.link(Shaders.MERGE).also { linked += it }
            previewProgram = GlProgram.link(Shaders.PREVIEW).also { linked += it }
            val mixboxSource = MixboxShaderSource.load(assets)
            if (mixboxSource.isNotEmpty()) {
                mergeMixProgram = GlProgram.link(Shaders.mergeMix(mixboxSource)).also { linked += it }
                previewMixProgram = GlProgram.link(Shaders.previewMix(mixboxSource)).also { linked += it }
                smudgeDepositMixProgram = GlProgram
                    .link(Shaders.smudgeDepositMix(mixboxSource)).also { linked += it }
                smudgeAbsorbMixProgram = GlProgram
                    .link(Shaders.smudgeAbsorbMix(mixboxSource)).also { linked += it }
                lutTexture = MixboxLut.upload(assets)
            }
        } catch (e: Exception) {
            linked.forEach(GlProgram::release)
            Log.e(GL_TAG, "engine startup failed on ${probed.describe()}", e)
            isReady = false
            return false
        }
        // Plain programs are mandatory; pigment variants are optional.
        checkNotNull(compositeProgram)
        checkNotNull(presentProgram)
        checkNotNull(checkerProgram)
        checkNotNull(tileCompositeProgram)
        checkNotNull(dabProgram)
        checkNotNull(smudgeDepositProgram)
        checkNotNull(smudgeAbsorbProgram)
        checkNotNull(blurHorizontalProgram)
        checkNotNull(blurVerticalProgram)
        checkNotNull(mergeProgram)
        checkNotNull(previewProgram)
        composite = compositeProgram
        present = presentProgram
        checker = checkerProgram
        tileComposite = tileCompositeProgram
        preview = previewProgram
        val canvasCompositePass = CompositePass(
            compositeProgram,
            state,
            previewProgram,
            previewMixProgram,
            lutTexture,
        )
        compositePass = canvasCompositePass
        thumbnailPass = LayerThumbnailPass(canvas, state, canvasCompositePass)
        val tiles = TilePool(probed, budget)
        pool = tiles
        sandwich = null
        syncSandwichCache(stack)
        layerPixelPass = LayerPixelPass(tiles, tileCompositeProgram, state)
        dab = dabProgram
        smudgeDeposit = smudgeDepositProgram
        smudgeAbsorb = smudgeAbsorbProgram
        blurHorizontal = blurHorizontalProgram
        blurVertical = blurVerticalProgram
        smudgeDepositMix = smudgeDepositMixProgram
        smudgeAbsorbMix = smudgeAbsorbMixProgram
        merge = mergeProgram
        mergeMix = mergeMixProgram
        previewMix = previewMixProgram
        mixboxLut = lutTexture
        dabPass = DabPass(dabProgram, state)
        smudgePass = SmudgePass(
            state,
            smudgeDepositProgram,
            smudgeAbsorbProgram,
            blurHorizontalProgram,
            blurVerticalProgram,
            smudgeDepositMixProgram,
            smudgeAbsorbMixProgram,
            lutTexture,
        )
        mergePass = MergePass(mergeProgram, state, tiles, mergeQuad, mergeMixProgram, lutTexture)
        strokeBuffer = StrokeBuffer(grid, tiles)
        tailBuffer = StrokeBuffer(grid, tiles)
        isReady = true
        return true
    }

    // ------------------------------------------------- the stroke (§7)

    /**
     * Opens a stroke (`docs/plan/03-canvas-engine.md` §7.1).
     *
     * Returns false and opens nothing when the engine is not ready or another
     * stroke is still open.
     */
    fun beginStroke(spec: StrokeSpec, mode: BufferMode, colorR: Float, colorG: Float, colorB: Float): Boolean {
        if (!isReady) return false
        if (stroke != null) return false
        val rmw = spec.rmw
        if (rmw == null && strokeBuffer == null) return false
        if (rmw != null && smudgePass?.begin(rmw) != true) return false

        rmwTouches.reset()
        rmwDirty = IntRect.EMPTY
        // Unconditionally, not only on that branch: a tail whose slices were
        // never returned would hold pool pages for the whole of the next
        // stroke. After an ordinary pen-up this is already empty and costs a
        // null check — [endStroke] and [cancelStroke] have both cleared it and
        // both had their pixels taken away with the front layer, so there is no
        // rect owed to anyone here either.
        clearTail()
        stroke = spec
        perf.resetPeaks()
        strokeBufferMode = mode
        strokeR = colorR
        strokeG = colorG
        strokeB = colorB
        return true
    }

    /**
     * Stamps a batch into the open stroke's buffers and returns the canvas rect
     * it dirtied, for the caller to redraw. Empty when no stroke is open.
     *
     * **Two destinations, one batch.** `batch.predictedFrom` splits it: the
     * committed dabs go to the stroke buffer, which merges into the layer at
     * pen-up, and the predicted ones go to [tailBuffer], which never does (§9:
     * "nothing predicted ever reaches the stroke buffer or the layer"). The
     * split is done here, from the header the generator wrote, rather than by
     * asking the caller to make two calls — the header is the single place that
     * says which dabs were a guess, and a caller that routed them by hand could
     * disagree with it.
     *
     * **No producer mixes the two in one batch today**, and this does not rely
     * on that: `CanvasScreen` takes a fresh ring slot for the tail, so a real
     * batch has `predictedFrom == -1` and a tail has 0. The general split is
     * what `DabBatch`'s header has always described (`02-architecture.md`
     * §3.2's `predictedFrom` is an *index*, and `DabRingTest` pins the mixed
     * case), and it is two comparisons — cheaper than a `require` that would
     * make the routing depend on a producer's habit.
     */
    fun stampDabs(batch: DabBatch): IntRect {
        val spec = stroke ?: return IntRect.EMPTY
        val startNs = clock.nowNanos()
        val committed = batch.committedCount
        val rmw = spec.rmw
        if (rmw != null) {
            val pass = smudgePass ?: return IntRect.EMPTY
            val textures = textures(spec.layerId) ?: return IntRect.EMPTY
            val dirty = pass.stamp(batch, textures, rmw, rmwTouches) { keys, count ->
                onRmwFirstTouch?.invoke(spec, keys, count)
            }
            rmwDirty = rmwDirty.union(dirty)
            pendingStampNs += clock.nowNanos() - startNs
            pendingDabs += committed
            return dirty
        }

        val pass = dabPass ?: return IntRect.EMPTY
        val buffer = strokeBuffer ?: return IntRect.EMPTY
        var dirty = IntRect.EMPTY
        if (committed > 0) {
            dirty = pass.stamp(
                batch, buffer, strokeBufferMode, spec.grainMode,
                strokeR, strokeG, strokeB,
                from = 0, until = committed,
            )
        }
        if (committed < batch.count) {
            val tail = tailBuffer
            if (tail != null) {
                val tailRect = pass.stamp(
                    batch, tail, strokeBufferMode, spec.grainMode,
                    strokeR, strokeG, strokeB,
                    from = committed, until = batch.count,
                )
                previousTailRect = previousTailRect.union(tailRect)
                dirty = dirty.union(tailRect)
            }
        }
        // Accumulated rather than assigned: §8.1's drain stamps every batch
        // that arrived since the last callback, so one *frame* is several
        // `stampDabs` calls and §11's "≤ 1 ms for a typical batch" is about the
        // frame's total. [beginFrame] opens the window and [publishFrame]
        // consumes it.
        pendingStampNs += clock.nowNanos() - startNs
        pendingDabs += batch.count
        return dirty
    }

    /** Stamp time and dab count accumulated across one frame's drain (§8.1 step 1). */
    private var pendingStampNs = 0L
    private var pendingDabs = 0

    /**
     * Opens one front-buffered frame's measurement, discarding whatever the
     * previous attempt accumulated.
     *
     * Called where the drain begins, beside [clearTail], because resetting on
     * the *publish* path is not enough: `drawStrokeFrame` returns early on an
     * empty window rect, an empty buffer rect and a failed `Accum` bind, and
     * `EngineSession` does not call it at all when the drain dirtied nothing —
     * which a pool-exhausted stamp does, having spent real time first. Every
     * one of those paths would roll its milliseconds into the next frame that
     * *did* publish, and the overlay would report a 4 ms stamp for a frame that
     * took 0.4. A number that is wrong only on the rare path is worse than no
     * number, because nothing marks it as the rare path.
     */
    fun beginFrame() {
        // graphics-core owns the context between callbacks; stamps must not
        // trust the previous callback's blend, scissor, viewport, or program.
        state.invalidate()
        pendingStampNs = 0L
        pendingDabs = 0
    }

    /**
     * Drops the tail and returns the canvas rect it occupied — §8.1 step 3's
     * "previous predicted tail's rect", which the caller folds into the frame's
     * dirty region so that redrawing it erases the tail (§9).
     *
     * Called once per front-buffered frame that stamps anything, before the
     * stamping: a real batch supersedes the tail it was predicting, and a
     * predicted batch replaces it outright. A frame that stamps nothing must
     * *not* call this, or a callback graphics-core coalesced into an earlier
     * one would wipe a tail that is still the best guess available.
     */
    fun clearTail(): IntRect {
        val previous = previousTailRect
        previousTailRect = IntRect.EMPTY
        tailBuffer?.reset()
        return previous
    }

    /**
     * Merges the open stroke into its layer and hands the touched keys to
     * [readback] (§7.4, §10.1). Returns the tiles merged. [revision] is the
     * commit sequence the readback stamps on each tile, so the flusher can
     * refuse a stale one (chunks can complete out of order across two PBOs).
     *
     * The buffer is reset **after** the readback is enqueued, not before: the
     * merge has already consumed it by then, but resetting first would free
     * slices the enqueued reads have not been issued against yet.
     */
    fun endStroke(
        revision: Int,
        opacityCeiling: Float,
        onMerged: ((StrokeSpec, List<TileKey>) -> Unit)? = null,
    ): Int {
        val startNs = clock.nowNanos()
        val spec = stroke?.withOpacityCeiling(opacityCeiling) ?: return 0
        if (spec.rmw != null) return endRmwStroke(spec, revision, onMerged, startNs)

        val buffer = strokeBuffer ?: return 0
        val pass = mergePass ?: return 0
        // Before the merge and before any early return: the tail is front-layer
        // only and the front layer is about to be hidden by `commit()`, so its
        // slices would otherwise stay checked out of the pool until the next
        // stroke reset them — a whole stroke's worth of leak per pen-up.
        //
        // **The returned rect is dropped on purpose, and only here and in
        // [cancelStroke] is that safe.** Everywhere else it is the erase: the
        // caller folds it into a dirty rect and redrawing those pixels is what
        // removes the old tail. On these two paths nothing needs redrawing,
        // because the front layer itself goes away — `EngineSession.endStroke`
        // follows this with `commit()`, which hides the front layer and repaints
        // the multi-buffered one from committed state across the whole viewport,
        // and `cancelStroke` follows with `cancel()`, which drops the
        // front-buffered content outright (§8.3, §8.4). A tail tip reaching past
        // the last committed dab therefore cannot survive either, even though
        // the stroke buffer's own merge rect does not cover it.
        clearTail()
        val textures = textures(spec.layerId)
        stroke = null
        if (textures == null) {
            buffer.reset()
            return 0
        }
        val merged = pass.merge(textures, buffer, spec, mergedKeys)
        if (merged > 0) {
            // Before the enqueue's own results can land anywhere: the journal
            // capture reads the CPU mirror, and §10.2's whole point is that it
            // sees the state as of the *previous* commit. Inside this block
            // nothing polls, so the callback runs against exactly that state.
            onMerged?.invoke(spec, ArrayList(mergedKeys))
            readback?.enqueue(spec.layerId, textures, mergedKeys, revision)
            // SandwichPolicy's own name for this case: a stroke merged into
            // the active layer. The active layer is in neither cached half, so
            // it decides which halves this actually stales.
            invalidate(SandwichPolicy.Op.StrokeCommit)
        }
        buffer.reset()
        perf.commitMs = (clock.nowNanos() - startNs) / NANOS_PER_MS
        return merged
    }

    private fun endRmwStroke(
        spec: StrokeSpec,
        revision: Int,
        onMerged: ((StrokeSpec, List<TileKey>) -> Unit)?,
        startNs: Long,
    ): Int {
        clearTail()
        stroke = null
        val textures = layers[spec.layerId]
        val count = rmwTouches.all(rmwKeyScratch)
        mergedKeys.clear()
        for (index in 0 until count) mergedKeys += TileKey(rmwKeyScratch[index])
        onMerged?.invoke(spec, ArrayList(mergedKeys))

        if (textures != null && mergedKeys.isNotEmpty()) {
            readback?.enqueue(spec.layerId, textures, mergedKeys, revision)
            invalidate(SandwichPolicy.Op.StrokeCommit)
        }
        rmwTouches.reset()
        rmwDirty = IntRect.EMPTY
        perf.commitMs = (clock.nowNanos() - startNs) / NANOS_PER_MS
        return mergedKeys.size
    }

    /**
     * Abandons the open stroke. §4: a cancelled stroke leaves **no trace** — no
     * history entry, no pixels — which is exactly what the buffer makes cheap.
     */
    fun cancelStroke(onRmwCancelled: ((StrokeSpec, List<TileKey>) -> Unit)? = null) {
        val spec = stroke
        if (spec?.rmw != null) {
            val count = rmwTouches.all(rmwKeyScratch)
            val keys = ArrayList<TileKey>(count)
            for (index in 0 until count) keys += TileKey(rmwKeyScratch[index])
            onRmwCancelled?.invoke(spec, keys)
            rmwTouches.reset()
            rmwDirty = IntRect.EMPTY
        }
        stroke = null
        strokeBuffer?.reset()
        // Rect dropped, for the reason [endStroke] gives: `cancel()` drops the
        // front-buffered content, so there is nothing left to redraw the tail
        // out of.
        clearTail()
    }

    /** The rect the open stroke has dirtied so far, for the caller's redraw. */
    val strokeDirty: IntRect
        get() = if (stroke?.rmw != null) rmwDirty else strokeBuffer?.dirty ?: IntRect.EMPTY

    /** Cumulative committed and predicted pixels needed after a front-layer reset. */
    internal val strokePreviewDirty: IntRect
        get() = strokeDirty.union(previousTailRect)

    /** A new surface, or a resize: `Accum` and `Scratch` are the only casualties. */
    fun onSurfaceChanged(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false

        val changed = width != viewportWidth || height != viewportHeight
        viewportWidth = width
        viewportHeight = height
        val next = FitTransform(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            imageWidth = canvas.width.toFloat(),
            imageHeight = canvas.height.toFloat(),
        )
        // Input owns resize rebasing; duplicating it here applies one resize
        // twice when Compose publishes the rebased view before this callback.
        fit = next
        state.invalidate()
        accum.ensure(width, height, state)
        scratch.ensure(width, height, state)
        Mat4.orthoYDown(width.toFloat(), height.toFloat(), projection)
        return changed
    }

    // ------------------------------------------------------------- document

    fun setStack(next: LayerStack, invalidation: SandwichPolicy.Op? = null) {
        val previous = stack
        stack = next
        // Keep lifecycle work below running when the context has no pool or a
        // legacy stack intentionally has no sandwich cache.
        val tiles = pool
        if (tiles != null) {
            for (layer in next.layers) {
                layers.getOrPut(layer.id) { LayerTextures(grid, tiles) }
            }
        }
        // A layer that left the stack takes its slices with it; leaving them
        // allocated is how a pool runs dry over a long editing session.
        val live = next.layers.map { it.id }.toSet()
        val gone = layers.keys.filterNot { it in live }
        for (id in gone) layers.remove(id)?.release()
        syncSandwichCache(next)
        when {
            previous == null -> sandwich?.invalidate(SandwichPolicy.Op.Select(next.activeIndex), next.activeIndex)
            invalidation != null -> sandwich?.invalidate(invalidation, previous.activeIndex)
            previous.active.id != next.active.id ->
                sandwich?.invalidate(SandwichPolicy.Op.Select(next.activeIndex), previous.activeIndex)
        }
    }

    /** Legacy stacks over today's cap use the exact direct path until they shrink. */
    private fun syncSandwichCache(current: LayerStack?) {
        val tiles = pool
        val hasReserve = current == null || tiles == null || TileCapacityPolicy.hasTransientReserve(
            layerCount = current.size,
            canvas = canvas,
            poolSliceCapacity = tiles.sliceCapacity.toLong(),
        )
        if (!hasReserve) {
            sandwich?.release()
            sandwich = null
            return
        }

        val cache = sandwich ?: run {
            tiles ?: return
            val program = tileComposite ?: return
            SandwichCache(grid, tiles, program, state).also { sandwich = it }
        }
        if (current != null) cache.observe(current)
    }

    /** Queues isolated layer renders; [pollLayerThumbnails] drains their PBOs. */
    internal fun requestLayerThumbnails(
        layerIds: Collection<LayerId>,
        callback: (LayerId, LayerThumbnail?) -> Unit,
    ) {
        if (!isReady) {
            layerIds.forEach { callback(it, null) }
            return
        }

        layerIds.forEach { thumbnailRequests += ThumbnailRequest(it, callback) }
        pumpLayerThumbnails()
    }

    fun pollLayerThumbnails() {
        thumbnailPass?.poll()
        pumpLayerThumbnails()
    }

    private fun pumpLayerThumbnails() {
        if (stroke != null) return
        val pass = thumbnailPass ?: return
        val current = stack ?: return
        while (thumbnailRequests.isNotEmpty()) {
            val request = thumbnailRequests.first()
            val layer = current.layers.firstOrNull { it.id == request.layer }
            val textures = layers[request.layer]
            if (layer == null || textures == null) {
                thumbnailRequests.removeFirst()
                request.callback(request.layer, null)
                continue
            }

            when (
                pass.enqueue(
                    layer = request.layer,
                    textures = textures,
                    opacity = layer.props.opacity,
                    callback = request.callback,
                )
            ) {
                LayerThumbnailPass.EnqueueResult.STARTED -> thumbnailRequests.removeFirst()
                LayerThumbnailPass.EnqueueResult.FAILED -> thumbnailRequests.removeFirst()
                LayerThumbnailPass.EnqueueResult.BUSY -> return
            }
        }
    }

    /** Prepared output remains detached until its history entry is queued. */
    private sealed interface PreparedPixelOp {
        data class LayerDelete(val layer: LayerId, val keys: Set<TileKey>)

        data class Composite(
            val transaction: LayerPixelPass.Transaction,
            val layer: LayerId,
            val target: LayerTextures,
            val keys: Set<TileKey>,
            val deleteAfter: List<LayerDelete> = emptyList(),
            val createdTarget: LayerId? = null,
        ) : PreparedPixelOp

        data class Clear(val layer: LayerId, val keys: Set<TileKey>) : PreparedPixelOp
        data class Delete(val layer: LayerId, val keys: Set<TileKey>) : PreparedPixelOp
        data class Restore(
            val transaction: LayerPixelPass.Transaction,
            val layer: LayerId,
            val tiles: Map<TileKey, ByteArray?>,
            val createdTarget: LayerId? = null,
        ) : PreparedPixelOp
    }

    private data class PixelTarget(
        val textures: LayerTextures,
        val created: Boolean,
    )

    /** Applies structural pixel work before [setStack] publishes its model. */
    fun applyPixelOps(
        ops: List<PixelOp>,
        revision: Int,
        beforeCommit: () -> Boolean = { true },
    ): Boolean {
        val prepared = ArrayList<PreparedPixelOp>(ops.size)
        for (op in ops) {
            val next = when (op) {
                is PixelOp.Copy -> layerPixelPass?.let { prepareCopy(it, op) }
                is PixelOp.Merge -> layerPixelPass?.let { prepareMerge(it, op) }
                is PixelOp.Clear -> prepareClear(op)
                is PixelOp.Delete -> prepareDelete(op)
                is PixelOp.Flatten -> layerPixelPass?.let { prepareFlatten(it, op) }
                is PixelOp.Restore -> layerPixelPass?.let { prepareRestore(it, op) }
            }
            if (next == null) {
                abort(prepared)
                return false
            }
            prepared += next
        }
        if (!beforeCommit()) {
            abort(prepared)
            return false
        }

        prepared.forEach { commit(it, revision) }
        return true
    }

    private fun prepareCopy(pass: LayerPixelPass, op: PixelOp.Copy): PreparedPixelOp.Composite? {
        val current = stack ?: return null
        val sourceLayer = current.layers.firstOrNull { it.id == op.src } ?: return null
        if (sourceLayer.tiles != op.keys || current.layers.any { it.id == op.dst }) return null

        val source = layers[op.src] ?: return null
        val target = targetFor(op.dst) ?: return null
        val transaction = pass.copy(source, target.textures, op.keys)
        if (transaction == null) {
            releaseCreatedTarget(op.dst, target)
            return null
        }
        return PreparedPixelOp.Composite(
            transaction,
            op.dst,
            target.textures,
            op.keys,
            createdTarget = op.dst.takeIf { target.created },
        )
    }

    private fun prepareMerge(pass: LayerPixelPass, op: PixelOp.Merge): PreparedPixelOp.Composite? {
        val current = stack ?: return null
        val topLayer = current.layers.firstOrNull { it.id == op.top } ?: return null
        val bottomLayer = current.layers.firstOrNull { it.id == op.bottom } ?: return null
        if (topLayer.props != op.topProps || bottomLayer.props != op.bottomProps) return null
        val topIndex = current.indexOf(op.top)
        if (topIndex <= 0 || current.layers[topIndex - 1].id != op.bottom) return null
        val expectedKeys = if (bottomLayer.props.opacity != 1f) {
            bottomLayer.tiles + topLayer.tiles
        } else {
            topLayer.tiles
        }
        if (op.keys != expectedKeys) return null

        val top = layers[op.top] ?: return null
        val bottom = layers[op.bottom] ?: return null
        val topSource = top.asSource(topLayer)
        val bottomSource = bottom.asSource(bottomLayer)
        val transaction = pass.merge(bottomSource, topSource, bottom, op.keys) ?: return null
        return PreparedPixelOp.Composite(
            transaction,
            op.bottom,
            bottom,
            op.keys,
            deleteAfter = listOf(PreparedPixelOp.LayerDelete(op.top, topLayer.tiles)),
        )
    }

    private fun prepareFlatten(pass: LayerPixelPass, op: PixelOp.Flatten): PreparedPixelOp.Composite? {
        val current = stack ?: return null
        val visible = current.layers.filter { it.props.visible }
        if (op.order != visible.map { it.props }) return null
        if (current.layers.any { it.id == op.result }) return null

        val sources = ArrayList<LayerPixelPass.Source>(op.order.size)
        val keys = LinkedHashSet<TileKey>()
        for (layer in visible) {
            val props = layer.props
            val source = layers[props.id] ?: return null
            sources += source.asSource(layer)
            keys += layer.tiles
        }
        val target = targetFor(op.result) ?: return null
        val transaction = pass.flatten(sources, target.textures, keys)
        if (transaction == null) {
            releaseCreatedTarget(op.result, target)
            return null
        }
        val oldLayers = current.layers.map { PreparedPixelOp.LayerDelete(it.id, it.tiles) }
        return PreparedPixelOp.Composite(
            transaction,
            op.result,
            target.textures,
            keys,
            deleteAfter = oldLayers,
            createdTarget = op.result.takeIf { target.created },
        )
    }

    private fun prepareClear(op: PixelOp.Clear): PreparedPixelOp.Clear? {
        val layer = stack?.layers?.firstOrNull { it.id == op.layer } ?: return null
        if (op.layer !in layers) return null
        return PreparedPixelOp.Clear(op.layer, layer.tiles)
    }

    private fun prepareDelete(op: PixelOp.Delete): PreparedPixelOp.Delete? {
        val layer = stack?.layers?.firstOrNull { it.id == op.layer } ?: return null
        if (op.layer !in layers) return null
        return PreparedPixelOp.Delete(op.layer, layer.tiles)
    }

    private fun prepareRestore(pass: LayerPixelPass, op: PixelOp.Restore): PreparedPixelOp.Restore? {
        if (op.tiles.keys.any { !grid.contains(it) }) return null
        val target = targetFor(op.layer) ?: return null
        val transaction = pass.restore(target.textures, op.tiles)
        if (transaction == null) {
            releaseCreatedTarget(op.layer, target)
            return null
        }
        return PreparedPixelOp.Restore(
            transaction,
            op.layer,
            op.tiles,
            createdTarget = op.layer.takeIf { target.created },
        )
    }

    private fun targetFor(id: LayerId): PixelTarget? {
        val existing = layers[id]
        if (existing != null) return PixelTarget(existing, created = false)
        val created = textures(id) ?: return null
        return PixelTarget(created, created = true)
    }

    private fun releaseCreatedTarget(id: LayerId, target: PixelTarget) {
        if (!target.created) return
        layers.remove(id)?.release()
    }

    private fun abort(prepared: List<PreparedPixelOp>) {
        for (op in prepared) {
            val (transaction, id) = when (op) {
                is PreparedPixelOp.Composite -> op.transaction to op.createdTarget
                is PreparedPixelOp.Restore -> op.transaction to op.createdTarget
                else -> continue
            }
            transaction.abort()
            if (id == null) continue
            layers.remove(id)?.release()
        }
    }

    private fun commit(prepared: PreparedPixelOp, revision: Int) {
        when (prepared) {
            is PreparedPixelOp.Composite -> {
                prepared.transaction.commit()
                enqueueReadback(prepared.layer, prepared.target, prepared.keys, revision)
                prepared.deleteAfter.forEach { deleteLayer(it.layer, it.keys, revision) }
            }
            is PreparedPixelOp.Clear -> clearLayer(prepared.layer, prepared.keys, revision)
            is PreparedPixelOp.Delete -> deleteLayer(prepared.layer, prepared.keys, revision)
            is PreparedPixelOp.Restore -> {
                prepared.transaction.commit()
                emitRestored(prepared.layer, prepared.tiles, revision)
            }
        }
    }

    private fun clearLayer(id: LayerId, keys: Set<TileKey>, revision: Int): Boolean {
        val target = layers[id] ?: return false
        emitEmpty(id, keys, revision)
        target.release()
        return true
    }

    private fun deleteLayer(id: LayerId, keys: Set<TileKey>, revision: Int): Boolean {
        val target = layers.remove(id) ?: return false
        emitEmpty(id, keys, revision)
        target.release()
        return true
    }

    private fun LayerTextures.asSource(layer: Layer) =
        LayerPixelPass.Source(
            this,
            layer.tiles,
            layer.props.blendMode,
            layer.props.opacity,
            layer.props.visible,
        )

    private fun enqueueReadback(
        layer: LayerId,
        target: LayerTextures,
        keys: Collection<TileKey>,
        revision: Int,
    ) {
        readback?.enqueue(layer, target, keys.toList(), revision)
    }

    private fun emitEmpty(layer: LayerId, keys: Set<TileKey>, revision: Int) {
        val sink = onTile ?: return
        for (key in keys) {
            transparentTile.clear()
            sink(layer, key, revision, transparentTile)
        }
    }

    private fun emitRestored(
        layer: LayerId,
        tiles: Map<TileKey, ByteArray?>,
        revision: Int,
    ) {
        val sink = onTile ?: return
        for ((key, pixels) in tiles) {
            val buffer = if (pixels == null) {
                transparentTile.clear()
                transparentTile
            } else {
                java.nio.ByteBuffer.wrap(pixels)
            }
            sink(layer, key, revision, buffer)
        }
    }

    fun setPaperColor(argb: Int) {
        if (argb == paperColor) return
        paperColor = argb
        stack?.let { sandwich?.invalidate(SandwichPolicy.Op.PaperColor, it.activeIndex) }
    }

    fun setView(next: ViewTransform) {
        // Pan/zoom/rotate never stale anything: the caches are in canvas space.
        view = next
    }

    fun sampleColor(x: Float, y: Float, params: EyedropperParams): Int? {
        val currentScreen = screen ?: return null
        return when (params.source) {
            SampleSource.Composite ->
                pixelReadback.sampleComposite(accum, currentScreen, x, y, params.radius)
            SampleSource.CurrentLayer -> {
                val active = stack?.active ?: return null
                pixelReadback.sampleLayer(layers[active.id], x, y, params.radius)
            }
        }
    }

    /** Captures the current GPU state used as a paper-free fill reference. */
    fun fillReference(reference: FillReference): TiledPixelSource? {
        if (!isReady) return null
        val current = stack ?: return null
        val pass = layerPixelPass ?: return null
        val sources = ArrayList<LayerPixelPass.Source>()
        val keys = LinkedHashSet<TileKey>()

        when (reference) {
            FillReference.CurrentLayer -> {
                val textures = layers[current.active.id] ?: return TiledPixelSource(grid, emptyMap())
                val layerKeys = ArrayList<TileKey>()
                textures.allKeys(layerKeys)
                keys.addAll(layerKeys)
                sources += LayerPixelPass.Source(
                    textures = textures,
                    keys = keys,
                    mode = BlendMode.NORMAL,
                    opacity = 1f,
                    visible = true,
                )
            }
            FillReference.Composite -> {
                for (layer in current.layers) {
                    if (!layer.props.visible || layer.props.opacity <= 0f) continue
                    val textures = layers[layer.id] ?: continue
                    val actualKeys = ArrayList<TileKey>()
                    textures.allKeys(actualKeys)
                    val layerKeys = actualKeys.toSet()
                    keys.addAll(layerKeys)
                    sources += LayerPixelPass.Source(
                        textures = textures,
                        keys = layerKeys,
                        mode = layer.props.blendMode,
                        opacity = layer.props.opacity,
                        visible = true,
                    )
                }
            }
        }
        return pass.snapshot(grid, sources, keys)
    }

    /** Uploads fill coverage and commits it through the ordinary merge path. */
    fun applyFill(
        spec: StrokeSpec,
        coverage: Coverage,
        color: Int,
        revision: Int,
        onMerged: ((StrokeSpec, List<TileKey>) -> Unit)? = null,
    ): Boolean {
        if (coverage.bounds.isEmpty) return false
        val red = ((color ushr 16) and CHANNEL_MASK) / CHANNEL_MAX
        val green = ((color ushr 8) and CHANNEL_MASK) / CHANNEL_MAX
        val blue = (color and CHANNEL_MASK) / CHANNEL_MAX
        if (!beginStroke(spec, BufferMode.Accumulate, red, green, blue)) return false

        val buffer = strokeBuffer ?: return false
        val uploaded = try {
            buffer.uploadFill(coverage, color, spec.opacity)
        } catch (_: PoolExhausted) {
            cancelStroke()
            return false
        }
        if (uploaded == 0) {
            cancelStroke()
            return false
        }
        // Fill opacity already scaled every coverage value, including AA.
        return endStroke(revision, FULL_OPACITY, onMerged) > 0
    }

    /** Restores a cancelled direct-write stroke without changing the CPU mirror. */
    fun restoreCancelledRmw(layer: LayerId, tiles: Map<TileKey, ByteArray?>): Boolean {
        val target = layers[layer] ?: return false
        try {
            for ((key, pixels) in tiles) {
                if (pixels == null) {
                    target.remove(key)
                    continue
                }
                target.upload(key, java.nio.ByteBuffer.wrap(pixels))
            }
        } catch (_: PoolExhausted) {
            return false
        }
        invalidate(SandwichPolicy.Op.UndoRedo)
        return true
    }

    /** The tiles of [id], creating the holder on first use. */
    fun textures(id: LayerId): LayerTextures? {
        val tiles = pool ?: return null
        return layers.getOrPut(id) { LayerTextures(grid, tiles) }
    }

    fun invalidate(op: SandwichPolicy.Op) {
        val active = stack?.activeIndex ?: return
        sandwich?.invalidate(op, active)
    }

    // ---------------------------------------------------------------- frame

    /**
     * `onDrawMultiDoubleBufferedLayer` (§8.2): the full viewport from
     * committed state, into [frameBufferId] at [bufferWidth] × [bufferHeight].
     *
     * [bufferTransform] is graphics-core's pre-rotation matrix, applied in
     * **buffer pixel space before the projection** (§3.1's `projection ×
     * transform × pixelPos`). It is identity for every offscreen pass here and
     * only ever binds on the present quad. The canonical half-turn takes the
     * device fallback documented in §8.5.
     */
    fun drawFrame(
        frameBufferId: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        bufferTransform: FloatArray,
    ) {
        // §10.1: every GL-thread entry maps whatever readback has finished.
        readback?.poll()
        val current = stack
        val screenTransform = screen
        val pass = compositePass
        if (current == null || screenTransform == null || pass == null ||
            !isReady || !accum.isAllocated
        ) {
            // graphics-core presents this buffer as-is once the callback
            // returns, and does not promise it was cleared. Returning without
            // touching it shows whatever it held — undefined content, or the
            // frame from two buffers ago — for the frames between surface
            // creation and the first fully valid state.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameBufferId)
            GLES30.glColorMask(true, true, true, true)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            state.invalidate()
            return
        }

        // graphics-core runs this with a framebuffer already bound and may have
        // touched state between frames, so the shadow starts each frame empty.
        // A cache that silently went stale would skip the one call that
        // mattered, which is worse than the redundancy it saves.
        state.invalidate()

        if (!compositeIntoAccum(current, screenTransform, pass, fullCanvasRect, null, null)) return

        val effectiveBufferTransform = effectiveBufferTransform(
            bufferTransform,
            bufferWidth,
            bufferHeight,
        )
        presentToWindow(frameBufferId, bufferWidth, bufferHeight, effectiveBufferTransform, null)
        GlErrors.checkGlDebug("drawFrame")
    }

    /**
     * §8.1's front-buffered frame: update [compositeDirtyCanvas] in `Accum`,
     * then copy [presentDirtyCanvas] to the front layer. The two differ while
     * guarding against AndroidX clearing that layer after a multi-buffer draw.
     *
     * **It goes through the same [compositeIntoAccum] as the committed frame,
     * and that is the point rather than a convenience.** §7.5 promises that
     * what the pen shows mid-stroke is what pen-up lands. Two composition paths
     * — one for the front layer, one for the multi-buffered one — would make
     * that promise a coincidence maintained by hand: the sandwich decision, the
     * per-layer fallback, the backdrop copy and the paper all have to agree,
     * and nothing on the JVM could check that they do. One path cannot
     * disagree with itself.
     *
     * The scissor is built in two steps because the buffer may be pre-rotated:
     * `screenBoundsOf` gives window px (inflated and viewport-clipped), and
     * [BufferScissor] carries that into buffer px. `Accum` is
     * viewport-oriented, so it takes the *window* rect; only the present quad,
     * which writes the real buffer, takes the transformed one.
     * Returns true only after the front target was presented.
     */
    fun drawStrokeFrame(
        frameBufferId: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        bufferTransform: FloatArray,
        compositeDirtyCanvas: IntRect,
        presentDirtyCanvas: IntRect,
    ): Boolean {
        readback?.poll()
        val current = stack
        val screenTransform = screen
        val pass = compositePass
        val spec = stroke
        if (current == null || screenTransform == null || pass == null || spec == null ||
            !isReady || !accum.isAllocated || presentDirtyCanvas.isEmpty
        ) {
            // Nothing is drawn and nothing is cleared: the front layer keeps
            // whatever it held, and the multi-buffered layer beneath is still
            // showing a correct pre-stroke composite. Clearing here would flash
            // the stroke away instead.
            return false
        }
        val presentWindowRect = screenTransform.screenBoundsOf(
            presentDirtyCanvas,
            viewportWidth,
            viewportHeight,
        )
        if (presentWindowRect.isEmpty) return false
        val effectiveBufferTransform = effectiveBufferTransform(
            bufferTransform,
            bufferWidth,
            bufferHeight,
        )
        val bufferRect = BufferScissor.bounds(
            presentWindowRect,
            effectiveBufferTransform,
            bufferWidth,
            bufferHeight,
        )
        if (bufferRect.isEmpty) return false

        val startNs = clock.nowNanos()
        state.invalidate()
        if (!compositeDirtyCanvas.isEmpty) {
            val compositeWindowRect = screenTransform.screenBoundsOf(
                compositeDirtyCanvas,
                viewportWidth,
                viewportHeight,
            )
            if (compositeWindowRect.isEmpty) return false

            // The inflated window AABB can clear across a tile boundary. Draw
            // every tile under it so the margin cannot cut white grid lines.
            val compositeCanvasRect = screenTransform.canvasBoundsOf(
                compositeWindowRect,
                canvas.width,
                canvas.height,
            )
            if (compositeCanvasRect.isEmpty) return false
            val composited = compositeIntoAccum(
                current,
                screenTransform,
                pass,
                compositeCanvasRect,
                compositeWindowRect,
                spec,
            )
            if (!composited) return false
        }
        val presented = presentToWindow(
            frameBufferId,
            bufferWidth,
            bufferHeight,
            effectiveBufferTransform,
            bufferRect,
        )
        if (!presented) return false
        // The clock stops HERE, before the error check. `checkGlDebug` drains
        // `glGetError` in a loop — a driver round-trip, and a synchronisation
        // point on some drivers — and it is a cost only debug builds pay. Since
        // debug builds are also the only ones that show the overlay, leaving it
        // inside the span would measure §11's "≤ 2 ms" against a number release
        // never pays, making the budget read as harder to meet than it is.
        val compositeNs = clock.nowNanos() - startNs
        GlErrors.checkGlDebug("drawStrokeFrame")
        publishFrame(compositeNs)
        return true
    }

    /**
     * Hands one front-buffered frame's timings to [perf] and **consumes** the
     * drain's accumulation.
     *
     * **CPU time, and the overlay says so.** These are wall-clock spans around
     * GL *calls*, which return as soon as the driver has queued the work — so
     * this measures the cost of building the frame, not of the GPU drawing it.
     * That is still the number §11's budgets are about (they bound what the
     * render thread spends per frame, and a stroke stutters when the thread is
     * late, not when the GPU is), but reading these as GPU time would flatter
     * the engine. Real GPU timing needs `GL_EXT_disjoint_timer_query`, which is
     * not in §13's probe and is not worth adding before a device exists to run
     * it on.
     *
     * A failed composite returns before this, deliberately: a frame that was
     * abandoned half-built has a duration but not a meaning, and averaging it
     * in would make a broken frame look like a fast one.
     */
    private fun publishFrame(compositeNs: Long) {
        perf.frame(
            stampMs = pendingStampNs / NANOS_PER_MS,
            compositeMs = compositeNs / NANOS_PER_MS,
            dabs = pendingDabs,
        )
        // Consumed, not merely read. [beginFrame] resets too, and both are
        // needed for different failures: `beginFrame` covers the frame that
        // accumulated and never published, this covers a second publish between
        // two `beginFrame`s — which would otherwise re-report the whole drain
        // as another frame, inflating the stamp time, the dab count and the
        // frame counter at once. Today's single caller does neither, so this is
        // the cheap half of not depending on that.
        pendingStampNs = 0L
        pendingDabs = 0
        perf.tilesResident = pool?.usedSlices ?: 0
        perf.tilesBudget = pool?.sliceCapacity ?: 0
    }

    /**
     * Builds the frame in `Accum`: paper, then the stack, over [rect] only.
     *
     * [accumScissor] restricts the work to the front frame's dirty region.
     * `Accum` is viewport-oriented, so this is the **window-px** rect, not the
     * buffer-px one. Null composites the whole target.
     *
     * [previewSpec] non-null draws the active layer through §7.5's preview
     * instead of plainly — the one and only difference between the front frame
     * and the committed one.
     *
     * Returns false when `Accum` could not be bound, which is the caller's cue
     * to leave the target alone rather than present a half-built frame.
     */
    private fun compositeIntoAccum(
        current: LayerStack,
        screenTransform: ScreenTransform,
        pass: CompositePass,
        rect: IntRect,
        accumScissor: IntRect?,
        previewSpec: StrokeSpec?,
    ): Boolean {
        val readyCache = readySandwichForFrame(current, screenTransform, rect)

        if (!fbo.bindTexture2d(accum.texture)) return false
        state.viewport(0, 0, accum.width, accum.height)
        if (accumScissor == null) {
            state.scissorOff()
        } else {
            // Accum is viewport-oriented and y-down like every rect here; the
            // flip to GL's bottom-left origin is the same one BufferScissor
            // documents, done against Accum's own height.
            state.scissor(
                accumScissor.left,
                accum.height - accumScissor.bottom,
                accumScissor.right - accumScissor.left,
                accumScissor.bottom - accumScissor.top,
            )
        }

        val useSandwich = readyCache != null

        drawPaper(screenTransform, bakedIntoBelow = useSandwich)

        if (readyCache != null) {
            pass.draw(
                readyCache.below, BlendMode.NORMAL, 1f, screenTransform, projection, identity,
                rect, scratch.texture,
            )
            drawLayer(pass, current.activeIndex, current, screenTransform, rect, previewSpec)
            pass.draw(
                readyCache.above, BlendMode.NORMAL, 1f, screenTransform, projection, identity,
                rect, scratch.texture,
            )
        } else {
            // The always-correct path §12 step 3 falls back to: every visible
            // layer, bottom to top, with its own mode. Costs N passes instead
            // of three, which is exactly what the sandwich exists to avoid —
            // and exactly what makes the sandwich an optimization rather than
            // a correctness requirement.
            for (i in current.layers.indices) {
                drawLayer(pass, i, current, screenTransform, rect, previewSpec)
            }
        }
        return true
    }

    private fun drawLayer(
        pass: CompositePass,
        index: Int,
        current: LayerStack,
        screenTransform: ScreenTransform,
        rect: IntRect,
        previewSpec: StrokeSpec? = null,
    ) {
        val layer = current.layers.getOrNull(index) ?: return
        val props = layer.props
        val buffer = strokeBuffer
        val previewsStroke = previewSpec != null &&
            index == current.activeIndex && previewSpec.layerId == layer.id
        if (!LayerVisibilityPolicy.shouldDraw(props.visible, props.opacity, previewsStroke)) return
        val textures = textures(layer.id) ?: return
        if (props.blendMode != BlendMode.NORMAL) {
            // A non-normal layer needs the backdrop, and a shader cannot read
            // the target it writes — so Accum is blitted into Scratch and
            // sampled from there. Both are viewport-oriented, which is the one
            // case §3.2 allows a blit for.
            // A failed backdrop copy is not something to draw through: the
            // layer would blend against whatever Scratch held from an earlier
            // frame, which is the "subtly wrong picture" the size guard below
            // exists to prevent, reached by another door. Every path that
            // returns false is a GPU allocation that already failed, so the
            // frame is compromised either way — skipping leaves a log line
            // instead of a plausible-looking composite.
            if (!copyAccumToScratch()) {
                if (!loggedBackdropFailure) {
                    loggedBackdropFailure = true
                    Log.w(
                        GL_TAG,
                        "no backdrop for ${props.blendMode} on ${layer.id.value}; " +
                            "skipping the layer (further backdrop failures are suppressed)",
                    )
                }
                return
            }
            // BEFORE the draw, not after. The blit leaves Scratch as the draw
            // target, so drawing here without rebinding composites the layer
            // into its own backdrop copy — and samples Scratch at the same
            // time, which is a feedback loop on top of drawing into the wrong
            // texture. Accum would simply never receive the layer.
            if (!fbo.bindTexture2d(accum.texture)) return
        }
        // §7.5: only the ACTIVE layer is previewed, and only while a stroke is
        // open on it. `spec.layerId` is checked rather than assumed equal to
        // the active layer's — a stroke that began before a layer switch would
        // otherwise be previewed onto whichever layer is active now, showing
        // the mark somewhere it will never land.
        if (previewSpec?.usesStrokeBuffer == true && buffer != null && previewsStroke) {
            pass.drawPreview(
                layer = textures,
                stroke = buffer,
                // Null when the tail is empty, not merely when it is absent:
                // `drawPreview` binds a page per texture, and handing it a
                // buffer with no tiles would cost a bind and three page lookups
                // per draw for a texture every fetch returns transparent from.
                tail = tailBuffer?.takeIf { !it.isEmpty },
                spec = previewSpec,
                mode = props.blendMode,
                opacity = props.opacity,
                screen = screenTransform,
                projection = projection,
                bufferTransform = identity,
                dirtyRect = rect,
                backdrop = scratch.texture,
            )
            return
        }
        pass.draw(
            textures, props.blendMode, props.opacity, screenTransform, projection, identity,
            rect, scratch.texture,
        )
    }

    /**
     * `Accum → Scratch`, so a non-normal layer has a backdrop to sample.
     *
     * The one place §3.2 allows `glBlitFramebuffer`: both sides are
     * viewport-oriented and the same size, so there is nothing to rotate. (The
     * present step cannot use one for exactly that reason — a blit cannot
     * rotate, and the window buffer may be pre-rotated.)
     *
     * **A blit obeys the scissor**, so on the front-buffered path this copies
     * only the dirty rect and leaves the rest of `Scratch` stale. That is
     * correct rather than tolerated: the same scissor stops any fragment
     * outside the rect from being written, and the backdrop is read with
     * `texelFetch` at `gl_FragCoord`, so no fragment that survives ever samples
     * the stale part.
     */
    private fun copyAccumToScratch(): Boolean {
        if (!scratch.isAllocated) return false
        // Same size, or the blit silently rescales the backdrop and every
        // non-normal layer blends against a stretched copy — no GL error, and
        // a picture that is subtly wrong rather than obviously broken. They are
        // allocated together in onSurfaceChanged, so this only fires if one
        // allocation failed.
        if (accum.width != scratch.width || accum.height != scratch.height) return false
        if (!readFbo.bindTexture2d(accum.texture, GLES30.GL_READ_FRAMEBUFFER)) return false
        try {
            if (!fbo.bindTexture2d(scratch.texture, GLES30.GL_DRAW_FRAMEBUFFER)) return false
            GLES30.glBlitFramebuffer(
                0, 0, accum.width, accum.height,
                0, 0, scratch.width, scratch.height,
                GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST,
            )
        } finally {
            // On EVERY path once the read side is bound. The binding is ours
            // and must not outlive the blit: the next pass binds
            // GL_FRAMEBUFFER, which leaves this FBO as the read source of
            // whatever runs after it — including the failure path, where the
            // invariant is hardest to notice.
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        }
        return true
    }

    private fun drawPaper(screenTransform: ScreenTransform, bakedIntoBelow: Boolean) {
        // `Accum` is viewport-sized, while the paper is not. The void clear is
        // deliberately first and obeys the active front-buffer scissor, so a
        // dirty stroke frame replaces complete pixels without repainting the
        // whole viewport. The paper quad below is clipped by that same scissor.
        clearColor(canvasVoid)

        val transparent = (paperColor ushr 24) == 0
        // Opaque Below already covers every visible canvas tile with paper.
        // Transparent Below does not: its checkerboard is a display backdrop,
        // not document pixels, so it must still be drawn here inside the
        // transformed canvas boundary.
        if (bakedIntoBelow && !transparent) return

        val colorA = if (transparent) checkerA else paperColor
        val colorB = if (transparent) checkerB else paperColor
        drawPaperQuad(screenTransform, colorA, colorB)
    }

    private fun drawPaperQuad(screenTransform: ScreenTransform, colorA: Int, colorB: Int) {
        val program = checker ?: return
        state.useProgram(program)
        program.uniform4f(
            "u_screen",
            screenTransform.a,
            screenTransform.b,
            screenTransform.tx,
            screenTransform.ty,
        )
        program.uniformMatrix4("u_projection", projection)
        program.uniformMatrix4("u_bufferTransform", identity)
        program.uniform1f("u_checkerPx", checkerPx)
        setColorUniform(program, "u_checkerA", colorA)
        setColorUniform(program, "u_checkerB", colorB)
        state.blendOff()
        paperQuad.draw(canvas.width.toFloat(), canvas.height.toFloat())
    }

    private fun clearColor(argb: Int) {
        val a = ((argb ushr 24) and 0xFF) / 255f
        fbo.clear(
            (((argb ushr 16) and 0xFF) / 255f) * a,
            (((argb ushr 8) and 0xFF) / 255f) * a,
            ((argb and 0xFF) / 255f) * a,
            a,
        )
    }

    private fun setColorUniform(program: GlProgram, name: String, argb: Int) {
        val a = ((argb ushr 24) and 0xFF) / 255f
        program.uniform4f(
            name,
            (((argb ushr 16) and 0xFF) / 255f) * a,
            (((argb ushr 8) and 0xFF) / 255f) * a,
            ((argb and 0xFF) / 255f) * a,
            a,
        )
    }

    private fun effectiveBufferTransform(
        bufferTransform: FloatArray,
        bufferWidth: Int,
        bufferHeight: Int,
    ): FloatArray {
        val decision = BufferPresentationPolicy.decide(
            transform = bufferTransform,
            logicalWidth = accum.width,
            logicalHeight = accum.height,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
        )
        return when (decision) {
            BufferPresentationDecision.USE_LIBRARY_TRANSFORM -> bufferTransform
            BufferPresentationDecision.NEUTRALIZE_HALF_TURN -> identity
        }
    }

    /**
     * §3.2 step 3: `Accum` into the window buffer as a textured quad.
     *
     * `bufferInfo.frameBufferId` is the callback's own target, which our
     * per-tile FBO binds have replaced by now — the library's KDoc calls it
     * "useful for re-binding to the original target after rendering to
     * intermediate frame buffer objects", which is exactly this.
     */
    private fun presentToWindow(
        frameBufferId: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        bufferTransform: FloatArray,
        scissor: IntRect?,
    ): Boolean {
        val program = present
        if (program == null || bufferWidth <= 0 || bufferHeight <= 0) {
            // `compositeIntoAccum` may have left an Accum-sized scissor enabled,
            // and these two exits are the only paths that skip the reset at the
            // end. A leaked scissor is not a dropped frame: it is applied, in
            // Accum coordinates, to whatever graphics-core binds next.
            state.scissorOff()
            return false
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameBufferId)
        state.viewport(0, 0, bufferWidth, bufferHeight)
        if (scissor == null) {
            state.scissorOff()
        } else {
            // §8.1 step 4: the front buffer receives COMPLETE pixels inside the
            // rect and is untouched outside it. That is what makes the stale
            // content of a front buffer irrelevant — nothing here is drawn
            // incrementally onto what was there before.
            BufferScissor.toHardwareBufferScissor(scissor, bufferHeight, scissorScratch)
            state.scissor(
                scissorScratch[0], scissorScratch[1], scissorScratch[2], scissorScratch[3],
            )
        }
        state.blendOff()
        state.useProgram(program)
        // Bound directly rather than through `state`: GlState caches sampler
        // FILTERS per texture id, not bindings, so there is no binding cache to
        // keep in sync here. If it ever gains one, this is a call site to route
        // through it.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, accum.texture)
        program.uniform1i("u_source", 0)
        // The projection is over the BUFFER, which may be rotated relative to
        // the viewport, so it is rebuilt here rather than reusing the frame's.
        val bufferProjection = Mat4.orthoYDown(bufferWidth.toFloat(), bufferHeight.toFloat())
        program.uniform4f("u_screen", 1f, 0f, 0f, 0f)
        program.uniformMatrix4("u_projection", bufferProjection)
        program.uniformMatrix4("u_bufferTransform", bufferTransform)
        // The transform maps logical Accum coordinates into the pre-rotated
        // buffer. Starting with buffer dimensions clips one side after a 90°.
        screenQuad.draw(accum.width.toFloat(), accum.height.toFloat())
        // The scissor is per-frame state on a target the next callback reuses;
        // leaving it set would clip whatever graphics-core draws next to this
        // stroke's dirty rect.
        state.scissorOff()
        return true
    }

    private fun readySandwichForFrame(
        current: LayerStack,
        screenTransform: ScreenTransform,
        requested: IntRect,
    ): SandwichCache? {
        val cache = sandwich ?: return null
        val rebuildPending = cache.hasPendingRebuild()
        val fullFrame = requested == fullCanvasRect

        if (rebuildPending || fullFrame) {
            updateVisibleCanvasBounds(screenTransform)
        }
        if (rebuildPending) {
            cache.rebuild(visibleCanvasBounds, current, paperColor, sandwichLayers)
        }

        val ready = if (fullFrame) {
            cache.isReady(visibleCanvasBounds)
        } else {
            cache.isReady(requested)
        }
        return if (ready) cache else null
    }

    /**
     * The canvas-space rect the viewport can see, inflated by
     * [SANDWICH_MARGIN_PX].
     *
     * The inverse image of the viewport's four corners, bounding-boxed — the
     * same "not two corners" reasoning as `ScreenTransform.screenBoundsOf`,
     * in the other direction.
     *
     * The corners are derived from [viewportWidth]/[viewportHeight] here
     * rather than cached in a parallel array: two int reads and two `toFloat`
     * calls cost the same (nothing) as a `FloatArray` read, and a second
     * copy of the one viewport fact is exactly the kind of cache a future
     * resize path forgets to update.
     *
     * [visibleCanvasBounds] is caller-owned, so neither inverse mapping nor the
     * cache lookup allocates on the frame path.
     */
    private fun updateVisibleCanvasBounds(screenTransform: ScreenTransform) {
        screenTransform.canvasBoundsOfViewport(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            canvasWidth = canvas.width,
            canvasHeight = canvas.height,
            margin = SANDWICH_MARGIN_PX,
            out = visibleCanvasBounds,
        )
    }

    // -------------------------------------------------------------- teardown

    /** The context is gone; the textures went with it (§12). Nothing is freed. */
    fun onContextLost() {
        // The fences and PBOs died with the context; waiting on them would
        // hang, so whatever was in flight is dropped and the CPU mirror
        // simply stays stale for those keys (§12).
        readback?.forgetAll()
        thumbnailPass?.forgetAll()
        failQueuedThumbnails()
        for (textures in layers.values) textures.forgetAll()
        sandwich?.forgetAll()
        // A stroke in progress cannot survive: its buffer's slices went with
        // the context. Forgetting rather than resetting, because resetting
        // would free handles into a pool that no longer exists — §12's rule
        // that nothing is freed on context loss.
        stroke = null
        strokeBuffer = null
        tailBuffer = null
        rmwTouches.reset()
        rmwDirty = IntRect.EMPTY
        previousTailRect = IntRect.EMPTY
        dabPass = null
        smudgePass = null
        mergePass = null
        layerPixelPass = null
        // EVERY program reference, including the optional pigment variants.
        // The ids died with the context, and `release()` releases each of them;
        // if a recreated context has reused one of those names, that
        // glDeleteProgram deletes a live program belonging to the new context.
        // This list has been wrong twice; adding a program means adding it here
        // and in `release()`.
        composite = null
        present = null
        checker = null
        tileComposite = null
        dab = null
        smudgeDeposit = null
        smudgeAbsorb = null
        blurHorizontal = null
        blurVertical = null
        smudgeDepositMix = null
        smudgeAbsorbMix = null
        merge = null
        mergeMix = null
        preview = null
        previewMix = null
        mixboxLut = 0
        compositePass = null
        thumbnailPass = null
        sandwich = null
        pool = null
        isReady = false
        // Per context, like GlFbo.loggedIncomplete and
        // TilePool.loggedClearBindFailure: a genuinely new failure under a new
        // context must still be reported, or the flag silences the diagnostics
        // it exists to provide.
        loggedBackdropFailure = false
        state.forgetAllTextures()
        state.invalidate()
        GlErrors.reset()
    }

    /** Ordinary teardown, with a live context: everything is deleted. */
    fun release(): Int {
        // First, before anything is torn down: map what is still in flight and
        // hand it over — these are the last stroke's tiles, and dropping them
        // here would lose exactly the pixels a leave-checkpoint is about to
        // save. Then delete the PBOs with the rest of the GL objects.
        readback?.finish()
        val pendingReadback = readbackPending
        readback?.release()
        for (textures in layers.values) textures.release()
        layers.clear()
        sandwich?.release()
        thumbnailPass?.release()
        failQueuedThumbnails()
        compositePass?.release()
        dabPass?.release()
        smudgePass?.release()
        mergePass?.release()
        layerPixelPass?.release()
        strokeBuffer?.reset()
        tailBuffer?.reset()
        paperQuad.release()
        screenQuad.release()
        mergeQuad.release()
        composite?.release()
        present?.release()
        checker?.release()
        tileComposite?.release()
        dab?.release()
        smudgeDeposit?.release()
        smudgeAbsorb?.release()
        blurHorizontal?.release()
        blurVertical?.release()
        smudgeDepositMix?.release()
        smudgeAbsorbMix?.release()
        merge?.release()
        mergeMix?.release()
        preview?.release()
        previewMix?.release()
        if (mixboxLut != 0) {
            val texture = intArrayOf(mixboxLut)
            GLES30.glDeleteTextures(1, texture, 0)
            state.forgetTexture(mixboxLut)
            mixboxLut = 0
        }
        accum.release(state)
        scratch.release(state)
        fbo.release()
        readFbo.release()
        pool?.release(state)
        pool = null
        stroke = null
        strokeBuffer = null
        tailBuffer = null
        rmwTouches.reset()
        rmwDirty = IntRect.EMPTY
        previousTailRect = IntRect.EMPTY
        dabPass = null
        smudgePass = null
        mergePass = null
        layerPixelPass = null
        thumbnailPass = null
        isReady = false
        return pendingReadback
    }

    private fun failQueuedThumbnails() {
        while (thumbnailRequests.isNotEmpty()) {
            val request = thumbnailRequests.removeFirst()
            request.callback(request.layer, null)
        }
    }

    /** For the About screen and the debug overlay of 2.5. */
    fun describe(): String = buildString {
        append(caps?.describe() ?: "no GL context")
        append(" | pool ").append(pool?.describe() ?: "none")
        append(" | accum ").append(accum.bytes).append(" B")
    }

    private companion object {
        /** Nanos to millis, as a float divisor so the overlay gets sub-ms resolution. */
        const val NANOS_PER_MS = 1_000_000f
        const val CHANNEL_MASK = 0xFF
        const val CHANNEL_MAX = 255f
        const val FULL_OPACITY = 1f
    }
}
