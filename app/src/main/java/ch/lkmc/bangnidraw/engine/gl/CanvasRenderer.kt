package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import android.util.Log
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.BufferScissor
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.FitTransform
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.PerfConstants.SANDWICH_MARGIN_PX
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.ViewTransform

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
 * 1. **Paper** — clear `Accum` to the premultiplied paper colour, or draw the
 *    checkerboard when the paper is transparent. When the sandwich is in use
 *    the paper is already baked into `Below`, so this clears to transparent
 *    instead and `Below` is drawn Normal over it.
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
) {

    private val grid = TileGrid(canvas.width, canvas.height)
    private val state = GlState()

    private val accum = OffscreenTarget("Accum")
    private val scratch = OffscreenTarget("Scratch")
    private val fbo = GlFbo()

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

    private val projection = FloatArray(Mat4.SIZE)
    private val identity = Mat4.identity()

    var caps: GlCaps? = null
        private set

    private var pool: TilePool? = null
    private var composite: GlProgram? = null
    private var present: GlProgram? = null
    private var checker: GlProgram? = null
    private var compositePass: CompositePass? = null
    private var dab: GlProgram? = null
    private var merge: GlProgram? = null
    private var dabPass: DabPass? = null
    private var mergePass: MergePass? = null
    private var preview: GlProgram? = null
    private var strokeBuffer: StrokeBuffer? = null

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

    /** Reused across strokes: the merge walks it and the readback reads it. */
    private val mergedKeys = ArrayList<TileKey>()
    private val mergeQuad = FullRectQuad()
    /**
     * One per pass, not one shared.
     *
     * `FullRectQuad` caches the last size it uploaded, and these two draw at
     * different sizes: the present quad spans the window buffer, the
     * checkerboard spans `Accum`. Those differ whenever the buffer is
     * pre-rotated, so one shared instance would re-upload its geometry twice a
     * frame, for every frame, on a transparent-paper canvas.
     */
    private val presentQuad = FullRectQuad()
    private val checkerQuad = FullRectQuad()
    private var sandwich: SandwichCache? = null

    private val layers = LinkedHashMap<LayerId, LayerTextures>()

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

    /** True once [onContextCreated] has run and the device can render at all. */
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
        val linked = ArrayList<GlProgram>(6)
        var compositeProgram: GlProgram? = null
        var presentProgram: GlProgram? = null
        var checkerProgram: GlProgram? = null
        var dabProgram: GlProgram? = null
        var mergeProgram: GlProgram? = null
        var previewProgram: GlProgram? = null
        try {
            compositeProgram = GlProgram.link(Shaders.COMPOSITE).also { linked += it }
            presentProgram = GlProgram.link(Shaders.PRESENT).also { linked += it }
            checkerProgram = GlProgram.link(Shaders.CHECKER).also { linked += it }
            dabProgram = GlProgram.link(Shaders.DAB).also { linked += it }
            mergeProgram = GlProgram.link(Shaders.MERGE).also { linked += it }
            previewProgram = GlProgram.link(Shaders.PREVIEW).also { linked += it }
        } catch (e: GlProgramException) {
            linked.forEach(GlProgram::release)
            Log.e(GL_TAG, "shader link failed on ${probed.describe()}", e)
            isReady = false
            return false
        }
        // Every one of them is non-null here: the try either assigned all six
        // or returned from the catch.
        checkNotNull(compositeProgram)
        checkNotNull(presentProgram)
        checkNotNull(checkerProgram)
        checkNotNull(dabProgram)
        checkNotNull(mergeProgram)
        checkNotNull(previewProgram)
        composite = compositeProgram
        present = presentProgram
        checker = checkerProgram
        preview = previewProgram
        compositePass = CompositePass(compositeProgram, state, previewProgram)
        val tiles = TilePool(probed, budget)
        pool = tiles
        sandwich = SandwichCache(grid, tiles, compositeProgram, state)
        dab = dabProgram
        merge = mergeProgram
        dabPass = DabPass(dabProgram, state)
        mergePass = MergePass(mergeProgram, state, tiles, mergeQuad)
        strokeBuffer = StrokeBuffer(grid, tiles)
        isReady = true
        return true
    }

    // ------------------------------------------------- the stroke (§7)

    /**
     * Opens a stroke (`docs/plan/03-canvas-engine.md` §7.1).
     *
     * Returns false and opens nothing when the engine is not ready or the
     * stroke is a read-modify-write one: §7.6's smudge and blur write the layer
     * directly, dab by dab, and `SmudgePass` does not exist yet. Refusing here
     * is what keeps a tool that has no path from silently painting through the
     * wrong one.
     */
    fun beginStroke(spec: StrokeSpec, mode: BufferMode, colorR: Float, colorG: Float, colorB: Float): Boolean {
        if (!isReady) return false
        if (!spec.usesStrokeBuffer) return false
        val buffer = strokeBuffer ?: return false
        // A stroke already open means the previous one never ended — a pen-up
        // lost to a cancelled gesture. Drop its buffer rather than merging it:
        // §4 says a cancelled stroke leaves no trace.
        if (stroke != null) buffer.reset()
        stroke = spec
        strokeBufferMode = mode
        strokeR = colorR
        strokeG = colorG
        strokeB = colorB
        return true
    }

    /**
     * Stamps a batch into the open stroke's buffer and returns the canvas rect
     * it dirtied, for the caller to redraw. Empty when no stroke is open.
     */
    fun stampDabs(batch: DabBatch): IntRect {
        if (stroke == null) return IntRect.EMPTY
        val pass = dabPass ?: return IntRect.EMPTY
        val buffer = strokeBuffer ?: return IntRect.EMPTY
        return pass.stamp(batch, buffer, strokeBufferMode, strokeR, strokeG, strokeB)
    }

    /**
     * Merges the open stroke into its layer and hands the touched keys to
     * [readback] (§7.4, §10.1). Returns the tiles merged.
     *
     * The buffer is reset **after** the readback is enqueued, not before: the
     * merge has already consumed it by then, but resetting first would free
     * slices the enqueued reads have not been issued against yet.
     */
    fun endStroke(readback: Readback?, revision: Int): Int {
        val spec = stroke ?: return 0
        val buffer = strokeBuffer ?: return 0
        val pass = mergePass ?: return 0
        val textures = textures(spec.layerId)
        stroke = null
        if (textures == null) {
            buffer.reset()
            return 0
        }
        val merged = pass.merge(textures, buffer, spec, mergedKeys)
        if (merged > 0) {
            readback?.enqueue(spec.layerId, textures, mergedKeys, revision)
            // SandwichPolicy's own name for this case: a stroke merged into
            // the active layer. The active layer is in neither cached half, so
            // it decides which halves this actually stales.
            invalidate(SandwichPolicy.Op.StrokeCommit)
        }
        buffer.reset()
        return merged
    }

    /**
     * Abandons the open stroke. §4: a cancelled stroke leaves **no trace** — no
     * history entry, no pixels — which is exactly what the buffer makes cheap.
     */
    fun cancelStroke() {
        stroke = null
        strokeBuffer?.reset()
    }

    /** The rect the open stroke has dirtied so far, for the caller's redraw. */
    val strokeDirty: IntRect get() = strokeBuffer?.dirty ?: IntRect.EMPTY

    /** A new surface, or a resize: `Accum` and `Scratch` are the only casualties. */
    fun onSurfaceChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val previous = fit
        viewportWidth = width
        viewportHeight = height
        val next = FitTransform(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            imageWidth = canvas.width.toFloat(),
            imageHeight = canvas.height.toFloat(),
        )
        // Keeps the canvas point under the viewport centre across rotation,
        // fold and multi-window (§8.6), rather than leaving a stale pixel pan.
        if (previous != null) view = view.rebase(previous, next)
        fit = next
        state.invalidate()
        accum.ensure(width, height, state)
        scratch.ensure(width, height, state)
        Mat4.orthoYDown(width.toFloat(), height.toFloat(), projection)
    }

    // ------------------------------------------------------------- document

    fun setStack(next: LayerStack) {
        val previous = stack
        stack = next
        // A `return` inside the getOrPut lambda is a NON-LOCAL return: with a
        // null pool it abandoned setStack after `stack` was already assigned,
        // skipping the stale-layer release, `observe` and the invalidate below.
        // Benign only because pool and sandwich are created together today.
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
        sandwich?.observe(next)
        if (previous == null || previous.activeIndex != next.activeIndex) {
            sandwich?.invalidate(SandwichPolicy.Op.Select(next.activeIndex), next.activeIndex)
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
     * only ever binds on the present quad.
     */
    fun drawFrame(
        frameBufferId: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        bufferTransform: FloatArray,
    ) {
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

        presentToWindow(frameBufferId, bufferWidth, bufferHeight, bufferTransform, null)
        GlErrors.checkGlDebug("drawFrame")
    }

    /**
     * §8.1's front-buffered frame: the same composite as [drawFrame], over the
     * stroke's dirty rect, with the active layer previewed through
     * `preview.frag`.
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
     */
    fun drawStrokeFrame(
        frameBufferId: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        bufferTransform: FloatArray,
        dirtyCanvas: IntRect,
    ) {
        val current = stack
        val screenTransform = screen
        val pass = compositePass
        val spec = stroke
        if (current == null || screenTransform == null || pass == null || spec == null ||
            !isReady || !accum.isAllocated || dirtyCanvas.isEmpty
        ) {
            // Nothing is drawn and nothing is cleared: the front layer keeps
            // whatever it held, and the multi-buffered layer beneath is still
            // showing a correct pre-stroke composite. Clearing here would flash
            // the stroke away instead.
            return
        }
        val windowRect = screenTransform.screenBoundsOf(dirtyCanvas, viewportWidth, viewportHeight)
        if (windowRect.isEmpty) return
        val bufferRect = BufferScissor.bounds(windowRect, bufferTransform, bufferWidth, bufferHeight)
        if (bufferRect.isEmpty) return

        state.invalidate()
        if (!compositeIntoAccum(current, screenTransform, pass, dirtyCanvas, windowRect, spec)) return
        presentToWindow(frameBufferId, bufferWidth, bufferHeight, bufferTransform, bufferRect)
        GlErrors.checkGlDebug("drawStrokeFrame")
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
        rebuildSandwichIfNeeded(current, screenTransform)

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

        val cache = sandwich
        // Both halves: they become unavailable independently — `above` on
        // associativity, `below` on the missing ping-pong — and using the
        // sandwich with one of them unusable composites a half that was never
        // built.
        val useSandwich = cache != null && cache.aboveAvailable && cache.belowAvailable

        drawPaper(bakedIntoBelow = useSandwich)

        if (useSandwich && cache != null) {
            pass.draw(
                cache.below, BlendMode.NORMAL, 1f, screenTransform, projection, identity,
                rect, scratch.texture,
            )
            drawLayer(pass, current.activeIndex, current, screenTransform, rect, previewSpec)
            pass.draw(
                cache.above, BlendMode.NORMAL, 1f, screenTransform, projection, identity,
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
        if (!props.visible || props.opacity <= 0f) return
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
        val buffer = strokeBuffer
        if (previewSpec != null && buffer != null &&
            index == current.activeIndex && previewSpec.layerId == layer.id
        ) {
            pass.drawPreview(
                layer = textures,
                stroke = buffer,
                tail = null, // roadmap 2.5b
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

    private fun drawPaper(bakedIntoBelow: Boolean) {
        val transparent = (paperColor ushr 24) == 0
        if (bakedIntoBelow || transparent) {
            // Below already carries the paper, so Accum starts empty and Below
            // is drawn Normal over it. A transparent paper gets the
            // checkerboard instead, in SCREEN space: canvas-space squares
            // would shrink to noise zoomed out and become slabs zoomed in.
            fbo.clear(0f, 0f, 0f, 0f)
            if (transparent) drawChecker()
            return
        }
        val a = ((paperColor ushr 24) and 0xFF) / 255f
        fbo.clear(
            (((paperColor ushr 16) and 0xFF) / 255f) * a,
            (((paperColor ushr 8) and 0xFF) / 255f) * a,
            ((paperColor and 0xFF) / 255f) * a,
            a,
        )
    }

    private fun drawChecker() {
        val program = checker ?: return
        state.useProgram(program)
        // Identity screen transform: the checkerboard is a screen-space
        // pattern over the whole target, so the quad IS the target rect and
        // the view must not move it.
        program.uniform4f("u_screen", 1f, 0f, 0f, 0f)
        program.uniformMatrix4("u_projection", projection)
        program.uniformMatrix4("u_bufferTransform", identity)
        program.uniform1f("u_checkerPx", checkerPx)
        setColorUniform(program, "u_checkerA", checkerA)
        setColorUniform(program, "u_checkerB", checkerB)
        state.blendOff()
        checkerQuad.draw(accum.width.toFloat(), accum.height.toFloat())
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
    ) {
        val program = present
        if (program == null || bufferWidth <= 0 || bufferHeight <= 0) {
            // `compositeIntoAccum` may have left an Accum-sized scissor enabled,
            // and these two exits are the only paths that skip the reset at the
            // end. A leaked scissor is not a dropped frame: it is applied, in
            // Accum coordinates, to whatever graphics-core binds next.
            state.scissorOff()
            return
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
            BufferScissor.toGlScissor(scissor, bufferHeight, scissorScratch)
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
        // The quad spans the BUFFER, not Accum: when graphics-core hands us a
        // pre-rotated buffer its width and height are swapped relative to the
        // viewport, and u_bufferTransform is what maps one onto the other.
        presentQuad.draw(bufferWidth.toFloat(), bufferHeight.toFloat())
        // The scissor is per-frame state on a target the next callback reuses;
        // leaving it set would clip whatever graphics-core draws next to this
        // stroke's dirty rect.
        state.scissorOff()
    }

    private fun rebuildSandwichIfNeeded(current: LayerStack, screenTransform: ScreenTransform) {
        val cache = sandwich ?: return
        // onContextLost() nulls the pool while leaving the cache in place, so
        // this window is reachable — and `!!` there would throw from inside a
        // render callback on surface recreation.
        val tiles = pool ?: return
        // Viewport-first, plus a margin so a small pan does not stall on a
        // rebuild (`docs/plan/10-performance.md` §2.6).
        val visible = visibleCanvasRect(screenTransform)
        cache.rebuild(visible, current, paperColor) { index ->
            textures(current.layers[index].id) ?: LayerTextures(grid, tiles)
        }
    }

    /**
     * The canvas-space rect the viewport can see, inflated by
     * [SANDWICH_MARGIN_PX].
     *
     * The inverse image of the viewport's four corners, bounding-boxed — the
     * same "not two corners" reasoning as `ScreenTransform.screenBoundsOf`,
     * in the other direction.
     */
    private fun visibleCanvasRect(screenTransform: ScreenTransform): IntRect {
        val corners = listOf(
            screenTransform.invert(0f, 0f),
            screenTransform.invert(viewportWidth.toFloat(), 0f),
            screenTransform.invert(viewportWidth.toFloat(), viewportHeight.toFloat()),
            screenTransform.invert(0f, viewportHeight.toFloat()),
        )
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for ((x, y) in corners) {
            if (!x.isFinite() || !y.isFinite()) return IntRect(0, 0, canvas.width, canvas.height)
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        val margin = SANDWICH_MARGIN_PX
        return IntRect(
            (minX.toInt() - margin).coerceIn(0, canvas.width),
            (minY.toInt() - margin).coerceIn(0, canvas.height),
            (maxX.toInt() + margin).coerceIn(0, canvas.width),
            (maxY.toInt() + margin).coerceIn(0, canvas.height),
        )
    }

    // -------------------------------------------------------------- teardown

    /** The context is gone; the textures went with it (§12). Nothing is freed. */
    fun onContextLost() {
        for (textures in layers.values) textures.forgetAll()
        sandwich?.forgetAll()
        // A stroke in progress cannot survive: its buffer's slices went with
        // the context. Forgetting rather than resetting, because resetting
        // would free handles into a pool that no longer exists — §12's rule
        // that nothing is freed on context loss.
        stroke = null
        strokeBuffer = null
        dabPass = null
        mergePass = null
        // EVERY program reference — six now that preview.frag exists. The ids
        // died with the context, and `release()` calls `release()` on each of
        // them; if a recreated context has reused one of those names, that
        // glDeleteProgram deletes a live program belonging to the new context.
        // This list has been wrong twice: once when it covered none of the five,
        // and once when 2.5a added a sixth and left it out. Adding a program
        // means adding it here and in `release()`.
        composite = null
        present = null
        checker = null
        dab = null
        merge = null
        preview = null
        compositePass = null
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
    fun release() {
        for (textures in layers.values) textures.release()
        layers.clear()
        sandwich?.release()
        compositePass?.release()
        dabPass?.release()
        mergePass?.release()
        strokeBuffer?.reset()
        presentQuad.release()
        checkerQuad.release()
        mergeQuad.release()
        composite?.release()
        present?.release()
        checker?.release()
        dab?.release()
        merge?.release()
        preview?.release()
        accum.release(state)
        scratch.release(state)
        fbo.release()
        readFbo.release()
        pool?.release(state)
        pool = null
        stroke = null
        strokeBuffer = null
        dabPass = null
        mergePass = null
        isReady = false
    }

    /** For the About screen and the debug overlay of 2.5. */
    fun describe(): String = buildString {
        append(caps?.describe() ?: "no GL context")
        append(" | pool ").append(pool?.describe() ?: "none")
        append(" | accum ").append(accum.bytes).append(" B")
    }
}
