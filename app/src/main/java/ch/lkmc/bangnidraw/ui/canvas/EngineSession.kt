package ch.lkmc.bangnidraw.ui.canvas

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.GLRenderer
import androidx.graphics.opengl.egl.EGLManager
import androidx.graphics.surface.SurfaceControlCompat
import ch.lkmc.bangnidraw.engine.core.AttachmentCompletion
import ch.lkmc.bangnidraw.engine.core.AttachmentRenderPlan
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.BufferPresentationDecision
import ch.lkmc.bangnidraw.engine.core.BufferPresentationPolicy
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Coverage
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.DabRing
import ch.lkmc.bangnidraw.engine.core.EngineRenderPolicy
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.FillReference
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.LayerThumbnail
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.MultiDrawCompletion
import ch.lkmc.bangnidraw.engine.core.OverlayRedrawDecision
import ch.lkmc.bangnidraw.engine.core.PixelOp
import ch.lkmc.bangnidraw.engine.core.PendingBatchDrainScope
import ch.lkmc.bangnidraw.engine.core.PendingBatchDrainWindow
import ch.lkmc.bangnidraw.engine.core.ReadbackDrainResult
import ch.lkmc.bangnidraw.engine.core.ReadbackPolicy
import ch.lkmc.bangnidraw.engine.core.RedrawDecision
import ch.lkmc.bangnidraw.engine.core.RedrawCompletionTracker
import ch.lkmc.bangnidraw.engine.core.RenderAttachmentGate
import ch.lkmc.bangnidraw.engine.core.RenderDispatch
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
import ch.lkmc.bangnidraw.engine.core.StrokeCommitDecision
import ch.lkmc.bangnidraw.engine.core.StrokeFinish
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TiledPixelSource
import ch.lkmc.bangnidraw.engine.core.TracingReference
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.engine.core.WatercolorOverlayKernel
import ch.lkmc.bangnidraw.engine.gl.CanvasRenderer
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class LayerEditResult { APPLIED, REFUSED }
internal enum class StrokeCancelMode { BUFFERED, READ_MODIFY_WRITE }

/** Renderer chrome queued before bootstrap and reused for later theme changes. */
internal data class CanvasAppearance(
    val checkerPx: Float,
    val checkerA: Int,
    val checkerB: Int,
    val canvasVoid: Int,
)

/**
 * The per-canvas façade the ViewModel and tools talk to
 * (`docs/plan/02-architecture.md` §4.3).
 *
 * Lives in `ui/canvas` because its lifetime is the composable's — it needs the
 * `SurfaceView` — but it is not a composable. It is created in
 * `CanvasSurface`'s `AndroidView` factory and handed to the ViewModel through
 * `attachSession()`; the ViewModel outlives it, never the other way round.
 *
 * **Roadmap 2.5a: both paths.** The front-buffered path of §8 is live — dabs
 * are published with `renderFrontBufferedLayer`, [onDrawFrontBufferedLayer]
 * stamps and recomposites the dirty rect with §7.5's preview, and pen-up goes
 * through §8.3's `commit()`. Roadmap 3a wires §10.1's readback: [endStroke]
 * enqueues the merged tiles and [onTile] — `CanvasViewModel`'s hook into
 * `TileFlusher` — receives them as the fences signal.
 */
class EngineSession(
    surface: SurfaceView,
    canvas: CanvasSize,
    budget: MemoryBudget.Result,
    assets: AssetManager,
    private val debugBuild: Boolean,
    /**
     * §10.1's consumer, called on the GL thread once per merged tile; the
     * buffer is only valid for the duration of the call. Null leaves the
     * readback machinery entirely unbuilt — the placeholder-canvas case.
     */
    onTile: ((LayerId, TileKey, Int, ByteBuffer) -> Unit)? = null,
    /**
     * The commit-revision counter (§10.1's stale-chunk guard). Owned by the
     * caller and shared across sessions of one screen, because a session is
     * recreated on configuration change while the flusher's per-key
     * `latestRevision` map lives on: a counter restarting at zero would make
     * the flusher refuse every stroke of the new session as stale — silent
     * data loss on a rotation. The default exists for callers with no
     * persistence at all.
     */
    private val revisions: java.util.concurrent.atomic.AtomicInteger =
        java.util.concurrent.atomic.AtomicInteger(0),
) {

    private enum class DriverCreationMode {
        DEFERRED,
        SYNCHRONOUS_REDRAW,
    }

    private data class DriverAttachment(
        val generation: Long,
        val driver: GLFrontBufferedRenderer<DabBatch>,
    )

    val renderer = CanvasRenderer(canvas, budget, assets, onTile = onTile)
    private val renderPolicy = EngineRenderPolicy()
    private val attachmentGate = RenderAttachmentGate()
    private val frontResumeSignal = DabBatch(capacity = 1)
    private val pollHandler = Handler(Looper.getMainLooper())
    private val surfaceView = surface
    private val glRenderer = GLRenderer().apply { start() }
    private val redrawCompletionTracker = RedrawCompletionTracker()
    private val wetOverlayTickScheduled = AtomicBoolean(false)

    @Volatile
    private var released = false

    @Volatile
    private var frontBuffered: DriverAttachment? = null

    private fun completeMultiDraw(generation: Long): AttachmentCompletion {
        val completion = attachmentGate.multiDrawCompleted(generation)
        if (completion !is AttachmentCompletion.Accepted) return completion

        renderPolicy.onMultiDrawCompleted()
        var plan = completion.plan
        val resume = renderPolicy.resumeFront() == MultiDrawCompletion.RESUME_FRONT
        if (resume && plan.dispatch != RenderDispatch.FRONT) {
            val frontPlan = attachmentGate.requestFront()
            if (plan.dispatch == RenderDispatch.NONE) plan = frontPlan
        }
        dispatch(plan)
        return completion
    }

    var onRmwStarted: ((StrokeSpec) -> Unit)? = null
    var onRmwTilesTouched: ((StrokeSpec, IntArray, Int) -> Unit)? = null
    var onRmwCancelled: ((StrokeSpec, List<TileKey>) -> Unit)? = null

    private val surfaceCallback = object : SurfaceHolder.Callback2 {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (width <= 0 || height <= 0) {
                discardSurface()
                return
            }

            scheduleDriverCreation()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            discardSurface()
        }

        override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
            val completion = CountDownLatch(1)
            scheduleDriverCreation(
                redrawCompletion = Runnable { completion.countDown() },
                mode = DriverCreationMode.SYNCHRONOUS_REDRAW,
            )
            var interrupted = false
            while (true) {
                try {
                    completion.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }

            if (interrupted) Thread.currentThread().interrupt()
        }

        override fun surfaceRedrawNeededAsync(holder: SurfaceHolder, drawingFinished: Runnable) {
            scheduleDriverCreation(drawingFinished)
        }
    }

    init {
        renderer.onRmwFirstTouch = { spec, keys, count ->
            onRmwTilesTouched?.invoke(spec, keys, count)
        }
        // Registered before every graphics-core driver so redraw callbacks can
        // retire the old driver before its own callback sees the same event.
        surfaceView.holder.addCallback(surfaceCallback)
        if (
            surfaceView.holder.surface.isValid &&
            surfaceView.width > 0 &&
            surfaceView.height > 0
        ) {
            scheduleDriverCreation()
        }
    }

    /**
     * §11's budgets as measured, for the debug overlay (`10-performance.md`
     * §5.3).
     *
     * The renderer's own instance, not a copy: it is written on the GL thread
     * and read on the main thread through its `@Volatile` fields, which is the
     * whole design. Exposed here because `CanvasScreen` holds the session, not
     * the renderer.
     */
    val perf get() = renderer.perf

    /**
     * The pool and capability line for the overlay's last row.
     *
     * A function rather than a value because it builds a string: called four
     * times a second by the overlay's sampler, never on the render path — and
     * on the **main thread, outside any GL context**, so nothing it reaches may
     * issue a GL call. It does not today, checked rather than assumed:
     * `GlCaps.describe` formats values captured once at context creation ("read
     * here and never re-queried"), `TilePool.describe` formats counters, and
     * `accum.bytes` is a field. A `glGetString` added to any of them would be
     * invalid from here. Racy
     * by construction — it reads GL-thread state from the main thread — and
     * that is acceptable for a diagnostic line no decision is made from, which
     * is exactly why the *numbers* above it go through `@Volatile` fields
     * instead.
     */
    fun describeEngine(): String = renderer.describe()

    /**
     * True once the device has been probed and can run the engine (§13).
     *
     * `@Volatile` because it is written on the GL thread (from [ensureContext],
     * inside the draw callback) and read on the main thread by the UI that
     * decides whether to show the unsupported-device screen. Without it the JMM
     * permits the main thread to keep observing the initial `true`
     * indefinitely, so that screen would never appear — the user would get a
     * blank canvas and no explanation.
     */
    @Volatile
    var isSupported: Boolean = true
        private set

    /**
     * True once the GL context exists and the renderer can accept uploads —
     * what the reopen path's tile streaming waits for. Reads the renderer's
     * own `@Volatile` flag, so an IO coroutine polling this observes the GL
     * thread's write.
     */
    fun isEngineReady(): Boolean = renderer.isReady

    private var contextReady = false
    @Volatile
    private var activeStrokeRmw = false

    @Volatile
    private var activeStrokeSpec: StrokeSpec? = null

    /** Main-thread theme update retained until the live stroke owns no front frame. */
    private var pendingCanvasAppearance: CanvasAppearance? = null

    /** GL-thread damage retained until a front or scene frame presents it. */
    private var pendingWetOverlayDirty = IntRect.EMPTY
    private var wetOverlayPresentationRetries = 0

    /** Exactly one completion survives an apply/release race. */
    private val fillResult = AtomicReference<((Boolean) -> Unit)?>(null)

    /** Fallback for an end command dropped by surface release. */
    private val pendingStrokeFallback = AtomicReference<(() -> Unit)?>(null)

    // ------------------------------------------------------------- callbacks

    /**
     * §8.1: stamp this batch's dabs, then recomposite the rect they dirtied
     * with the stroke previewed on top of the active layer.
     *
     * **One frame, all dabs** (§11). graphics-core coalesces render requests,
     * so several batches may be outstanding when this runs; [pendingBatches]
     * holds the ones behind [param] and they are all stamped here, which is
     * what makes the callback count irrelevant to what gets drawn.
     *
     * Every batch consumed is released back to the ring *here*, on the GL
     * thread, after the renderer has read it — the same rule [stampDabs] used
     * to state, moved to the callback that now owns the read.
     */
    private fun onDrawFrontBufferedLayer(
        eglManager: EGLManager,
        width: Int,
        height: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        param: DabBatch,
    ) {
        ensureContext()
        if (!isSupported) {
            // Not just `return`. [stampDabs] does gate on `isSupported`, but the
            // flag starts **true** and only ever flips to false once
            // [ensureContext] has probed — so batches published before that
            // probe are queued normally and are sitting here when it fails.
            // Bailing out silently would strand every slot and backpressure
            // all later input for the rest of the session.
            drainPending(stamp = false, scope = PendingBatchDrainScope.EXHAUSTIVE)
            return
        }
        val framePlan = renderPolicy.frontFrame()

        renderer.onSurfaceChanged(width, height)
        // [param] is deliberately NOT consumed here: it is also in
        // [pendingBatches], which is the authoritative list, and stamping it
        // both ways would lay its dabs down twice and release its ring slot
        // twice. Draining the queue alone consumes each batch exactly once.
        //
        // An empty queue is normal rather than a bug: graphics-core coalesces
        // requests, so an earlier callback may already have drained everything
        // this one was scheduled for — and it recomposited when it did, so
        // there is nothing left to draw.
        val incrementalDirty = drainPending(
            stamp = true,
            scope = PendingBatchDrainScope.FRAME_SNAPSHOT,
        )
        val dirty = framePlan.dirty(
            incrementalDirty.union(pendingWetOverlayDirty),
            renderer.strokePreviewDirty,
        )
        if (dirty.present.isEmpty) return

        val presented = renderer.drawStrokeFrame(
            frameBufferId = bufferInfo.frameBufferId,
            bufferWidth = bufferInfo.width,
            bufferHeight = bufferInfo.height,
            bufferTransform = transform,
            compositeDirtyCanvas = dirty.composite,
            presentDirtyCanvas = dirty.present,
        )
        if (presented) {
            clearPendingWetOverlay()
            renderPolicy.frontFramePresented(framePlan)
        } else {
            retryWetOverlayPresentation()
        }
    }

    /**
     * Consumes every published batch, returning the canvas rect they dirtied.
     *
     * GL thread only. [stamp] false releases without drawing — §4's cancelled
     * stroke, and the unsupported-device path, both of which must leave no
     * trace but must still return their slots.
     */
    private val pendingDrainWindow = PendingBatchDrainWindow()

    private fun drainPending(stamp: Boolean, scope: PendingBatchDrainScope): IntRect {
        var dirty = IntRect.EMPTY
        var stampedAny = false
        // Returning a slot lets the input thread refill the queue immediately.
        // A live frame consumes only its entry snapshot so it reaches present;
        // pen-up and cancel use EXHAUSTIVE because no producer remains.
        pendingDrainWindow.begin(scope, pendingBatches.size)
        while (pendingDrainWindow.canPoll()) {
            val next = pendingBatches.poll() ?: break
            if (stamp) {
                if (!stampedAny) {
                    stampedAny = true
                    // §8.1 step 3's "previous predicted tail's rect", folded in
                    // once per frame before anything is stamped. Whatever this
                    // frame draws supersedes the last frame's guess: a real
                    // batch because the pen has actually arrived, a predicted
                    // one because it is the newer guess. Redrawing the rect is
                    // what erases the old tail — there is no undo pass (§9).
                    //
                    // Inside the loop rather than above it, so a callback that
                    // finds the queue already drained — routine, since
                    // graphics-core coalesces requests — leaves the tail alone
                    // instead of wiping a guess that is still the best there is.
                    dirty = renderer.clearTail()
                    // Same "once per stamping frame" moment: what the stamps
                    // below cost belongs to this frame and no other.
                    renderer.beginFrame()
                }
                dirty = dirty.union(renderer.stampDabs(next))
            }
            dabRing.release(next)
        }
        if (stampedAny && renderer.hasWatercolorOverlay()) pumpWetOverlay()
        return dirty
    }

    /**
     * The full viewport from committed state (§8.2) — and, per §5, every
     * non-stroke redraw: layer edits, undo, view changes, resize.
     *
     * [width] and [height] are the surface's; `bufferInfo.width/height` are the
     * **buffer's**, which may be swapped relative to them when the compositor
     * hands us a pre-rotated buffer. The logical present quad goes through
     * [transform], which is why §3.2 step 3 forbids a blit here.
     */
    private fun onDrawMultiBufferedLayer(
        eglManager: EGLManager,
        width: Int,
        height: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        params: Collection<DabBatch>,
    ) {
        ensureContext()
        if (!isSupported) return
        renderer.onSurfaceChanged(width, height)
        val presented = renderer.drawFrame(
            frameBufferId = bufferInfo.frameBufferId,
            bufferWidth = bufferInfo.width,
            bufferHeight = bufferInfo.height,
            bufferTransform = transform,
        )
        if (presented) {
            clearPendingWetOverlay()
        } else {
            retryWetOverlayPresentation()
        }
        // §8.2 says `params` is iterated only to release ring slots. **This
        // implementation must not**, and the difference is a crash.
        //
        // graphics-core replays the SAME objects: `commit()` runs
        // `mSegments.add(mActiveSegment.release())` and this callback polls that
        // collection, so every batch here has already been through
        // [onDrawFrontBufferedLayer]'s drain, which released it. `DabRing.release`
        // is deliberately not idempotent — `require(!free[i])` — so a second
        // release throws out of a GL callback on the first pen-up.
        //
        // [pendingBatches] is therefore the single owner of a published batch,
        // and every exit drains it: this callback, `endStroke`, `cancelStroke`
        // and `release`. `endStroke` drains inside its `execute` block, which
        // §8.3's verified FIFO ordering puts before this callback — so the queue
        // is always empty by the time the replay arrives.
        //
        // The dabs are not restamped either: the merge already happened.
    }

    private fun ensureContext() {
        if (contextReady) return
        contextReady = true
        isSupported = renderer.onContextCreated(strict = debugBuild)
    }

    private fun scheduleDriverCreation(
        redrawCompletion: Runnable? = null,
        mode: DriverCreationMode = DriverCreationMode.DEFERRED,
    ) {
        val generation = attachmentGate.surfaceChanged()
        runRedrawCompletions(redrawCompletionTracker.beginGeneration(generation))
        redrawCompletion?.let { queueRedrawCompletion(generation, it) }
        if (released) return

        discardDriver()
        deferActiveFrontRecovery()
        // SurfaceView dispatches a callback snapshot. Inline sync creation is
        // excluded from that pass; deferred creation preserves startup order.
        when (mode) {
            DriverCreationMode.DEFERRED -> pollHandler.post { createDriver(generation) }
            DriverCreationMode.SYNCHRONOUS_REDRAW -> createDriver(generation, mode)
        }
    }

    private fun createDriver(
        generation: Long,
        mode: DriverCreationMode = DriverCreationMode.DEFERRED,
    ) {
        if (!attachmentGate.isCurrentGeneration(generation)) return
        if (!surfaceView.holder.surface.isValid) {
            runRedrawCompletions(redrawCompletionTracker.abandon(generation))
            return
        }
        if (surfaceView.width <= 0 || surfaceView.height <= 0) {
            runRedrawCompletions(redrawCompletionTracker.abandon(generation))
            return
        }

        val driver = GLFrontBufferedRenderer(
            surfaceView,
            GenerationCallback(generation),
            glRenderer = glRenderer,
        )
        if (!attachmentGate.isCurrentGeneration(generation)) {
            driver.release(true)
            return
        }

        frontBuffered = DriverAttachment(generation, driver)
        // Driver target attachment and prior scene commands share this FIFO.
        glRenderer.execute {
            when (mode) {
                DriverCreationMode.DEFERRED -> pollHandler.post {
                    dispatch(attachmentGate.surfaceReady(generation))
                }
                DriverCreationMode.SYNCHRONOUS_REDRAW -> {
                    dispatch(attachmentGate.surfaceReady(generation))
                }
            }
        }
    }

    private fun discardSurface() {
        attachmentGate.surfaceDestroyed()
        discardDriver()
        deferActiveFrontRecovery()
        runRedrawCompletions(redrawCompletionTracker.destroy())
    }

    private fun discardDriver() {
        val attachment = frontBuffered ?: return

        frontBuffered = null
        attachment.driver.release(true)
    }

    private fun deferActiveFrontRecovery() {
        if (renderPolicy.onFrontInvalidated() != MultiDrawCompletion.RESUME_FRONT) return

        attachmentGate.requestFront()
    }

    private fun queueRedrawCompletion(generation: Long, completion: Runnable) {
        runRedrawCompletions(redrawCompletionTracker.queue(generation, completion))
    }

    private fun finishRedrawCompletions(generation: Long) {
        runRedrawCompletions(redrawCompletionTracker.finish(generation))
    }

    private fun abandonRedrawCompletions() {
        runRedrawCompletions(redrawCompletionTracker.release())
    }

    private fun runRedrawCompletions(completions: List<Runnable>) {
        completions.forEach(Runnable::run)
    }

    private fun isLive(): Boolean = !released && glRenderer.isRunning()

    private inner class GenerationCallback(
        private val generation: Long,
    ) : GLFrontBufferedRenderer.Callback<DabBatch> {

        private var halfTurnNeutralized = false

        override fun onDrawFrontBufferedLayer(
            eglManager: EGLManager,
            width: Int,
            height: Int,
            bufferInfo: BufferInfo,
            transform: FloatArray,
            param: DabBatch,
        ) {
            if (!attachmentGate.acceptsDriverCallback(generation)) return

            captureHalfTurn(width, height, bufferInfo, transform)

            this@EngineSession.onDrawFrontBufferedLayer(
                eglManager, width, height, bufferInfo, transform, param,
            )
        }

        override fun onDrawMultiBufferedLayer(
            eglManager: EGLManager,
            width: Int,
            height: Int,
            bufferInfo: BufferInfo,
            transform: FloatArray,
            params: Collection<DabBatch>,
        ) {
            if (!attachmentGate.acceptsDriverCallback(generation)) return

            captureHalfTurn(width, height, bufferInfo, transform)

            this@EngineSession.onDrawMultiBufferedLayer(
                eglManager, width, height, bufferInfo, transform, params,
            )
        }

        override fun onFrontBufferedLayerRenderComplete(
            frontBufferedLayerSurfaceControl: SurfaceControlCompat,
            transaction: SurfaceControlCompat.Transaction,
        ) {
            if (!attachmentGate.acceptsDriverCallback(generation)) return
            if (halfTurnNeutralized) {
                transaction.setBufferTransform(
                    frontBufferedLayerSurfaceControl,
                    SurfaceControlCompat.BUFFER_TRANSFORM_IDENTITY,
                )
            }

            // graphics-core still owns its parameter lock in this callback.
            // Re-enter after it commits so input never blocks behind GL work.
            glRenderer.execute {
                val completion = attachmentGate.frontDrawCompleted(generation)
                if (completion !is AttachmentCompletion.Accepted) return@execute

                dispatch(completion.plan)
            }
        }

        override fun onMultiBufferedLayerRenderComplete(
            frontBufferedLayerSurfaceControl: SurfaceControlCompat,
            multiBufferedLayerSurfaceControl: SurfaceControlCompat,
            transaction: SurfaceControlCompat.Transaction,
        ) {
            if (!attachmentGate.acceptsDriverCallback(generation)) return
            if (halfTurnNeutralized) {
                transaction.setBufferTransform(
                    multiBufferedLayerSurfaceControl,
                    SurfaceControlCompat.BUFFER_TRANSFORM_IDENTITY,
                )
            }

            // graphics-core commits the transaction after this callback
            // returns. The next GL command therefore observes that commit.
            glRenderer.execute {
                val completion = completeMultiDraw(generation)
                if (completion is AttachmentCompletion.Accepted) {
                    finishRedrawCompletions(generation)
                }
            }
        }

        private fun captureHalfTurn(
            logicalWidth: Int,
            logicalHeight: Int,
            bufferInfo: BufferInfo,
            transform: FloatArray,
        ) {
            halfTurnNeutralized = BufferPresentationPolicy.decide(
                transform = transform,
                logicalWidth = logicalWidth,
                logicalHeight = logicalHeight,
                bufferWidth = bufferInfo.width,
                bufferHeight = bufferInfo.height,
            ) == BufferPresentationDecision.NEUTRALIZE_HALF_TURN
        }
    }

    private fun dispatch(
        plan: AttachmentRenderPlan,
        frontParam: DabBatch = frontResumeSignal,
    ) {
        val attachment = frontBuffered ?: return
        if (attachment.generation != plan.generation) return

        val driver = attachment.driver

        if (!driver.isValid()) return

        when (plan.dispatch) {
            RenderDispatch.NONE -> Unit
            RenderDispatch.BOOTSTRAP -> driver.renderMultiBufferedLayer(emptyList())
            RenderDispatch.COMMIT -> driver.commit()
            RenderDispatch.FRONT -> driver.renderFrontBufferedLayer(frontParam)
        }
    }

    // ---------------------------------------------------------------- façade

    /** Applies initial document state before scheduling one scene redraw. */
    internal fun configure(
        stack: LayerStack,
        paperColor: Int,
        appearance: CanvasAppearance,
        view: ViewTransform,
        tracingReference: TracingReference?,
    ) {
        // A full configure supersedes any deferred appearance; drop it so a
        // later stroke cannot re-apply stale colors over this newer set.
        pendingCanvasAppearance = null
        glRenderer.execute {
            renderer.setStack(stack)
            renderer.setPaperColor(paperColor)
            applyCanvasAppearance(appearance)
            renderer.setView(view)
            renderer.setTracingReference(tracingReference)
        }
        redraw()
    }

    /**
     * Sets the view transform and redraws.
     *
     * The uniforms are written on the GL thread via [execute] rather than from
     * here: `renderer.view` is read inside `drawFrame`, and assigning it from
     * the main thread while a frame is in flight is the kind of race that
     * shows up as one torn frame every few hundred and never reproduces.
     */
    fun setView(view: ViewTransform) {
        glRenderer.execute { renderer.setView(view) }
        redraw()
    }

    fun setStack(stack: LayerStack) {
        glRenderer.execute { renderer.setStack(stack) }
        redraw()
    }

    fun setTracingReference(reference: TracingReference?) {
        glRenderer.execute { renderer.setTracingReference(reference) }
        redraw()
    }

    /** Applies one journaled layer transition without exposing GL to the UI. */
    internal fun applyLayerEdit(
        stack: LayerStack,
        pixelOps: List<PixelOp>,
        invalidation: SandwichPolicy.Op,
        beforeCommit: () -> Boolean,
        onResult: (LayerEditResult) -> Unit,
    ) {
        if (!isLive()) {
            onResult(LayerEditResult.REFUSED)
            return
        }
        glRenderer.execute {
            if (!renderer.isReady) {
                pollHandler.post { onResult(LayerEditResult.REFUSED) }
                return@execute
            }
            val pending = renderer.finishReadback()
            if (ReadbackPolicy.drainResult(pending) == ReadbackDrainResult.PENDING) {
                pendingMirror = pending
                pumpReadback()
                pollHandler.post { onResult(LayerEditResult.REFUSED) }
                return@execute
            }
            val revision = revisions.incrementAndGet()
            val applied = renderer.applyPixelOps(
                pixelOps,
                revision,
                invalidation,
                beforeCommit,
            )
            if (applied) {
                renderer.setStack(stack, invalidation)
                pendingMirror = renderer.readbackPending
                if (pendingMirror > 0) pumpReadback()
            }
            pollHandler.post {
                val result = if (applied) LayerEditResult.APPLIED else LayerEditResult.REFUSED
                if (applied) redraw()
                onResult(result)
            }
        }
    }

    fun setPaperColor(argb: Int) {
        glRenderer.execute { renderer.setPaperColor(argb) }
        redraw()
    }

    /** Theme colours for the canvas surround and transparent-paper checkerboard. */
    fun setCanvasAppearance(
        checkerPx: Float,
        colorA: Int,
        colorB: Int,
        canvasVoid: Int,
    ) {
        val appearance = CanvasAppearance(
            checkerPx = checkerPx,
            checkerA = colorA,
            checkerB = colorB,
            canvasVoid = canvasVoid,
        )
        if (activeStrokeSpec != null) {
            pendingCanvasAppearance = appearance
            redraw()
            return
        }

        // A refused stroke may leave a deferred value; this newer choice wins.
        pendingCanvasAppearance = null
        glRenderer.execute { applyCanvasAppearance(appearance) }
        redraw()
    }

    /** GL-thread mutation paired with an initial or full scene frame. */
    private fun applyCanvasAppearance(appearance: CanvasAppearance) {
        renderer.checkerPx = appearance.checkerPx
        renderer.checkerA = appearance.checkerA
        renderer.checkerB = appearance.checkerB
        renderer.canvasVoid = appearance.canvasVoid
    }

    private fun takePendingCanvasAppearance(): CanvasAppearance? {
        val appearance = pendingCanvasAppearance
        pendingCanvasAppearance = null

        return appearance
    }

    fun sampleColor(
        x: Float,
        y: Float,
        params: EyedropperParams,
        onColor: (Int?) -> Unit,
    ) {
        if (!isLive()) {
            onColor(null)
            return
        }
        glRenderer.execute {
            val color = renderer.sampleColor(x, y, params)
            pollHandler.post { onColor(color) }
        }
    }

    /** Captures one immutable, paper-free fill reference on the GL thread. */
    fun requestFillReference(
        reference: FillReference,
        onReference: (TiledPixelSource?) -> Unit,
    ) {
        if (!isLive()) {
            onReference(null)
            return
        }
        glRenderer.execute {
            val source = renderer.fillReference(reference)
            pollHandler.post { onReference(source) }
        }
    }

    /** Commits CPU fill coverage through the renderer's stroke merge. */
    fun applyFill(
        spec: StrokeSpec,
        coverage: Coverage,
        color: Int,
        onResult: (Boolean) -> Unit,
    ) {
        if (!fillResult.compareAndSet(null, onResult)) {
            onResult(false)
            return
        }
        if (!isLive()) {
            completeFill(false)
            return
        }
        val mergedListener = onStrokeMerged
        glRenderer.execute {
            val pending = renderer.finishReadback()
            if (ReadbackPolicy.strokeCommit(pending) == StrokeCommitDecision.CANCEL) {
                pendingMirror = pending
                pumpReadback()
                pollHandler.post { completeFill(false) }
                return@execute
            }

            val revision = revisions.incrementAndGet()
            val applied = renderer.applyFill(spec, coverage, color, revision) { merged, keys ->
                mergedListener?.invoke(merged, keys, revision)
            }
            pendingMirror = renderer.readbackPending
            if (pendingMirror > 0) pumpReadback()
            pollHandler.post {
                if (applied) redraw()
                completeFill(applied)
            }
        }
    }

    private fun completeFill(applied: Boolean) {
        fillResult.getAndSet(null)?.invoke(applied)
    }

    // ------------------------------------------------------- the stroke (§7)

    /**
     * Opens a stroke on the GL thread (§7.1).
     *
     * Fire-and-forget, like every other command here: the answer
     * `CanvasRenderer.beginStroke` returns cannot come back synchronously
     * without blocking the input thread on the GL thread, which is what
     * `02-architecture.md` §3.3 forbids. A refused stroke — an RMW tool, a
     * context still coming up — simply produces no dabs, and every later call
     * for it is a no-op, so refusing late costs nothing.
     */
    fun beginStroke(spec: StrokeSpec, mode: BufferMode, r: Float, g: Float, b: Float) {
        renderPolicy.beginStroke()
        activeStrokeRmw = spec.rmw != null
        activeStrokeSpec = spec
        glRenderer.execute {
            if (spec.rmw != null) {
                val pending = renderer.finishReadback()
                if (ReadbackPolicy.strokeCommit(pending) == StrokeCommitDecision.CANCEL) {
                    pendingMirror = pending
                    pumpReadback()
                    activeStrokeRmw = false
                    activeStrokeSpec = null
                    onRmwCancelled?.invoke(spec, emptyList())
                    return@execute
                }
                onRmwStarted?.invoke(spec)
            }

            val opened = renderer.beginStroke(spec, mode, r, g, b)
            if (!opened && spec.rmw != null) {
                activeStrokeRmw = false
                activeStrokeSpec = null
                onRmwCancelled?.invoke(spec, emptyList())
            }
        }
    }

    /**
     * The single-producer/single-consumer ring of `02-architecture.md` §3.2:
     * the main thread fills a slot, the GL thread consumes it and returns it.
     *
     * A ring rather than a copy per event, because a copy would allocate a
     * whole `DabBatch` — 44 KiB of `FloatArray`s at the default capacity — on
     * every `ACTION_MOVE`, which is precisely the per-sample allocation
     * `10-performance.md` §2.4 exists to forbid. Handing the caller's own
     * scratch over instead would race the input path's refill against the GL
     * thread's read.
     */
    private val dabRing = DabRing()

    /**
     * Batches published to the front layer but not yet consumed by the
     * callback.
     *
     * graphics-core takes one `param` per `renderFrontBufferedLayer` call and
     * coalesces the requests, so with unbuffered dispatch the input thread can
     * publish several between two callbacks. §8.1 step 1 says the callback
     * consumes `param` "and any batch already published behind it"; this is
     * that queue. Concurrent because the input thread adds and the GL thread
     * drains.
     */
    private val pendingBatches = java.util.concurrent.ConcurrentLinkedQueue<DabBatch>()

    // Slots return from both threads: input through [releaseDabBatch] and early
    // exits, GL through [drainPending]. DabRing synchronizes its pool;
    // "single-producer/single-consumer" describes dab flow, not release.

    /** Borrows a bounded ring slot, or null while the GL thread holds all slots. */
    fun acquireDabBatch(): DabBatch? = dabRing.acquire()

    /**
     * Borrows for replaceable prediction without consuming real input's final
     * slot.
     */
    fun acquirePredictionDabBatch(): DabBatch? = dabRing.acquirePrediction()

    /**
     * Hands a borrowed batch back unused — the generator emitted nothing for
     * this sample, which the stabilizer's leash makes routine. Without this the
     * slot would stay checked out and the ring would starve after eight quiet
     * samples.
     */
    fun releaseDabBatch(batch: DabBatch) = dabRing.release(batch)

    /**
     * Publishes a batch borrowed from [acquireDabBatch] to the front layer
     * (§8.1); [onDrawFrontBufferedLayer] stamps it and returns its slot.
     *
     * The release happens on the **GL thread**, after the renderer has read
     * the batch — never here, because publishing only queues, and returning
     * the slot now would hand it back while the GL thread was still reading
     * it.
     */
    fun stampDabs(batch: DabBatch) {
        if (batch.count == 0) {
            dabRing.release(batch)
            return
        }
        if (!isSupported) {
            // Safe to read here, and safe to act on: `isSupported` starts
            // **true** and only ever goes true → false, when [ensureContext]'s
            // probe fails on the GL thread. So this cannot fire on a supported
            // device — not even before the first frame, where the initial
            // `true` is the value read — and it never discards a batch that
            // would have been drawn. `@Volatile` supplies the happens-before
            // edge for the one transition there is.
            //
            // The gate is an optimisation, not the correctness path: without it
            // the batch would be queued and then released undrawn by
            // [onDrawFrontBufferedLayer]'s drain. It just saves the round trip.
            dabRing.release(batch)
            return
        }
        if (!isLive()) {
            // The renderer is gone; the block would be dropped with the slot
            // still checked out. `execute` after release logs and returns
            // rather than throwing (AGENTS.md), so nothing else would say so.
            dabRing.release(batch)
            return
        }
        // §8.1's path, not `execute` + a full redraw: this is what puts the
        // mark under the pen instead of at pen-up.
        //
        // The batch is queued FIRST and then published. The reverse order
        // races: the callback can run and drain before the add lands, leaving a
        // batch in the queue with no request outstanding — drawn a batch late
        // at best, and its slot held until the next sample at worst. Queue then
        // publish means every request finds its own batch already there.
        pendingBatches.add(batch)
        dispatch(attachmentGate.requestFront(), batch)
    }

    /** The next commit revision — the restore path's share of the counter. */
    fun bumpRevision(): Int = revisions.incrementAndGet()

    /**
     * §10.2's capture hook: runs on the GL thread inside the commit, after
     * the merge and after every *previous* stroke's readback has been mapped,
     * but before this stroke's own results can land — the one moment "the
     * mirror plus disk" is exactly the pre-stroke state. The receiver
     * captures (`TileFlusher.captureMirror` copies under its lock) and
     * enqueues the entry job; it must not block.
     */
    var onStrokeMerged: ((StrokeSpec, List<TileKey>, revision: Int) -> Unit)? = null

    /** Reports an empty, refused, failed, or released commit on Main, once. */
    var onStrokeNotMerged: (() -> Unit)? = null

    /**
     * Merges the stroke into its layer (§7.4) and enqueues §10.1's readback of
     * the merged tiles. The fences usually signal a frame or two later, so
     * [pumpReadback] keeps polling after the commit until everything in flight
     * has been mapped and handed to the tile sink. [onCommitted] returns on
     * Main only when the merge changed at least one tile.
     */
    fun endStroke(opacityCeiling: Float, onCommitted: () -> Unit) {
        renderPolicy.finishStroke(StrokeFinish.COMMIT)
        activeStrokeRmw = false
        activeStrokeSpec = null
        val deferredAppearance = takePendingCanvasAppearance()
        val thisRevision = revisions.incrementAndGet()
        val mergedListener = onStrokeMerged
        val notMergedListener = onStrokeNotMerged
        val fallback: () -> Unit = {
            if (notMergedListener != null) notMergedListener()
        }
        if (!pendingStrokeFallback.compareAndSet(null, fallback)) {
            pendingCanvasAppearance = deferredAppearance
            fallback()
            return
        }
        if (!isLive()) {
            // Dead GL cannot apply the palette; keep it for recreation.
            pendingCanvasAppearance = deferredAppearance
            completeStrokeWithoutMerge(fallback)
            return
        }
        // §8.3's order, and it holds because the FIFO assumption §8.3 flags was
        // verified against graphics-core 1.0.4 (AGENTS.md): this block runs
        // before the multi-buffered draw `commit()` schedules, so the layer
        // already owns the stroke by the time the committed frame is composed.
        glRenderer.execute {
            deferredAppearance?.let(::applyCanvasAppearance)
            // §10.1's ordering rule, enforced where §10.2 says it must be:
            // stroke N+1's capture must not run until stroke N's readback has
            // been mapped into the mirror, or undoing N+1 would also revert N.
            // Normally a no-op — N's fences signal a frame or two after N's
            // pen-up — and bounded by the fence timeout when the GPU is
            // wedged. This stroke's own readback is not enqueued yet, so it
            // cannot be swept in.
            val pending = renderer.finishReadback()
            if (ReadbackPolicy.strokeCommit(pending) == StrokeCommitDecision.CANCEL) {
                pendingMirror = pending
                pumpReadback()
                drainPending(stamp = false, scope = PendingBatchDrainScope.EXHAUSTIVE)
                renderer.cancelStroke()
                completeStrokeWithoutMerge(fallback)
                return@execute
            }
            // §8.3's `dabPass.drain(untilStrokeEnd)`: any batch published but
            // not yet drawn is stamped now, before the merge, or its dabs would
            // be lost — and its slot would still be checked out when the replay
            // arrives, where nothing releases it any more.
            drainPending(stamp = true, scope = PendingBatchDrainScope.EXHAUSTIVE)
            val merged = renderer.endStroke(
                revision = thisRevision,
                opacityCeiling = opacityCeiling,
            ) { spec, keys ->
                if (pendingStrokeFallback.compareAndSet(fallback, null)) {
                    mergedListener?.invoke(spec, keys, thisRevision)
                    if (keys.isNotEmpty()) {
                        pollHandler.post(onCommitted)
                    }
                }
            }
            if (merged == 0) completeStrokeWithoutMerge(fallback)
        }
        // A scene commit hides the front layer. If the surface is detached,
        // the gate latches it until graphics-core has rebuilt its targets.
        dispatch(attachmentGate.endStroke())
        pumpReadback()
    }

    private fun completeStrokeWithoutMerge(fallback: () -> Unit) {
        if (!pendingStrokeFallback.compareAndSet(fallback, null)) return
        pollHandler.post(fallback)
    }

    /**
     * §10.1's between-frame poll: while PBOs are in flight the main thread
     * keeps posting `execute { poll() }`, because after pen-up no further
     * frame may arrive to do it — a painter who lifts the pen and waits would
     * otherwise leave the last stroke's tiles unmapped until the next stroke.
     *
     * [pendingMirror] carries the count from the GL thread to the main-thread
     * scheduling decision; the handler reposts while it is non-zero. The chain
     * dies on its own when the count reaches zero, and [release] removes any
     * scheduled tick.
     */
    @Volatile
    private var pendingMirror = 0

    @Volatile
    private var pendingThumbnails = 0

    private val pollTick = Runnable {
        if (!isLive()) return@Runnable
        glRenderer.execute {
            renderer.pollReadback()
            renderer.pollLayerThumbnails()
            pendingMirror = renderer.readbackPending
            pendingThumbnails = renderer.thumbnailPending
            if (pendingMirror > 0 || pendingThumbnails > 0) pumpReadback()
        }
    }

    private fun pumpReadback() {
        // Posted unconditionally on pen-up (the enqueue itself happens on the
        // GL thread, so the main thread cannot see its pending count yet) and
        // re-posted from the GL thread while chunks remain. postDelayed on a
        // Handler is thread-safe from both.
        pollHandler.removeCallbacks(pollTick)
        pollHandler.postDelayed(pollTick, READBACK_POLL_MS)
    }

    /** Redraws the fading cue; monotonic wet timestamps remain authoritative. */
    private val wetOverlayTick = Runnable {
        wetOverlayTickScheduled.set(false)
        if (!isLive()) return@Runnable

        glRenderer.execute {
            val refresh = renderer.refreshWatercolorOverlay()
            pendingWetOverlayDirty = pendingWetOverlayDirty.union(refresh.dirty)
            if (!refresh.dirty.isEmpty) {
                wetOverlayPresentationRetries = WET_OVERLAY_PRESENT_RETRIES
            }
            val presentationRefresh = refresh.copy(dirty = pendingWetOverlayDirty)

            pollHandler.post { applyWetOverlayRefresh(presentationRefresh) }
        }
    }

    private fun applyWetOverlayRefresh(
        refresh: WatercolorOverlayKernel.RefreshResult,
    ) {
        if (!isLive()) return

        if (!refresh.dirty.isEmpty) requestWetOverlayRedraw()
        if (refresh.action == WatercolorOverlayKernel.Refresh.REDRAW_AND_CONTINUE) {
            pumpWetOverlay()
        }
    }

    private fun requestWetOverlayRedraw() {
        when (renderPolicy.requestOverlayRedraw()) {
            OverlayRedrawDecision.SCENE -> redrawNow()
            OverlayRedrawDecision.FRONT -> dispatch(attachmentGate.requestFront())
            OverlayRedrawDecision.DEFER,
            OverlayRedrawDecision.COVERED,
            -> Unit
        }
    }

    /** Retries one failed wet presentation, then leaves damage for the next frame. */
    private fun retryWetOverlayPresentation() {
        if (pendingWetOverlayDirty.isEmpty) return
        if (wetOverlayPresentationRetries <= 0) return
        if (wetOverlayTickScheduled.get()) return

        wetOverlayPresentationRetries -= 1
        pumpWetOverlay()
    }

    private fun clearPendingWetOverlay() {
        pendingWetOverlayDirty = IntRect.EMPTY
        wetOverlayPresentationRetries = 0
    }

    private fun pumpWetOverlay() {
        if (!wetOverlayTickScheduled.compareAndSet(false, true)) return
        pollHandler.postDelayed(
            wetOverlayTick,
            WatercolorOverlayKernel.REFRESH_MILLIS,
        )
    }

    /** Renders panel thumbnails on the GL thread and returns them on main. */
    internal fun requestLayerThumbnails(
        layers: Collection<LayerId>,
        onThumbnail: (LayerId, LayerThumbnail?) -> Unit,
    ) {
        if (layers.isEmpty()) return
        if (!isLive()) {
            layers.forEach { onThumbnail(it, null) }
            return
        }

        glRenderer.execute {
            renderer.requestLayerThumbnails(layers) { layer, thumbnail ->
                pollHandler.post { onThumbnail(layer, thumbnail) }
            }
            pendingThumbnails = renderer.thumbnailPending
            if (pendingThumbnails > 0) pumpReadback()
        }
        pumpReadback()
    }

    /**
     * Uploads decoded tiles into a layer's textures — §5.7's reopen path. One
     * `execute {}` per call, so the caller chunks: a whole 4096² painting in
     * one block would hold the GL thread for the entire upload.
     *
     * Each buffer must stay untouched until the block has run; the ViewModel
     * hands over freshly decoded arrays and never reuses them.
     */
    fun uploadTiles(layerId: LayerId, tiles: List<Pair<TileKey, ByteArray>>, last: Boolean) {
        if (tiles.isEmpty() && !last) return
        glRenderer.execute {
            if (renderer.isReady) {
                val textures = renderer.textures(layerId)
                if (textures != null) {
                    for ((key, pixels) in tiles) {
                        textures.upload(key, ByteBuffer.wrap(pixels))
                    }
                }
                // Restored pixels stale the caches exactly as undo's uploads
                // do (§10.3) — per batch, not once at the end, or the interim
                // redraws would composite from a stale sandwich.
                renderer.invalidate(SandwichPolicy.Op.UndoRedo)
            }
        }
        redraw()
    }

    fun uploadReferenceTiles(
        assetName: String,
        tiles: List<Pair<TileKey, ByteArray>>,
    ) {
        if (tiles.isEmpty()) return
        glRenderer.execute {
            if (renderer.isReady) renderer.uploadReferenceTiles(assetName, tiles)
        }
        redraw()
    }

    /** Restores the GPU-only partial pixels of a cancelled RMW stroke. */
    fun restoreCancelledRmw(
        layer: LayerId,
        tiles: Map<TileKey, ByteArray?>,
        onDone: (Boolean) -> Unit,
    ) {
        if (!isLive()) {
            renderPolicy.completeRmwCancel()
            onDone(false)
            return
        }
        glRenderer.execute {
            val restored = renderer.restoreCancelledRmw(layer, tiles)
            pollHandler.post {
                val deferredRedraw = renderPolicy.completeRmwCancel()
                if (restored || deferredRedraw == RedrawDecision.DRAW) redraw()
                onDone(restored)
            }
        }
    }

    /** Completes an RMW cancellation that touched no tiles. */
    internal fun completeCancelledRmwRestore() {
        if (renderPolicy.completeRmwCancel() == RedrawDecision.DRAW) redraw()
    }

    /**
     * Reports whether bounded fence waits delivered every in-flight tile.
     * A pending result keeps callers from persisting stale CPU pixels.
     */
    internal fun finishReadback(onDone: (ReadbackDrainResult) -> Unit) {
        if (!isLive()) {
            onDone(ReadbackDrainResult.COMPLETE)
            return
        }
        glRenderer.execute {
            val pending = renderer.finishReadback()
            pendingMirror = pending
            if (pending > 0) pumpReadback()
            onDone(ReadbackPolicy.drainResult(pending))
        }
    }

    /** §4/§8.4: a cancelled stroke leaves no trace. */
    internal fun cancelStroke(
        beforeCancel: (StrokeCancelMode) -> Unit = {},
    ): StrokeCancelMode {
        val mode = if (activeStrokeRmw) {
            StrokeCancelMode.READ_MODIFY_WRITE
        } else {
            StrokeCancelMode.BUFFERED
        }
        val finish = when (mode) {
            StrokeCancelMode.BUFFERED -> StrokeFinish.CANCEL_BUFFERED
            StrokeCancelMode.READ_MODIFY_WRITE -> StrokeFinish.CANCEL_READ_MODIFY_WRITE
        }
        val deferredRedraw = renderPolicy.finishStroke(finish)
        attachmentGate.cancelFront()
        val cancelledSpec = activeStrokeSpec
        activeStrokeRmw = false
        activeStrokeSpec = null
        val deferredAppearance = takePendingCanvasAppearance()
        // Install the document-action barrier before an invalid surface can
        // synchronously deliver the restore callback.
        beforeCancel(mode)

        if (!isLive()) {
            if (cancelledSpec?.rmw != null) onRmwCancelled?.invoke(cancelledSpec, emptyList())
            // Dead GL cannot apply the palette; keep it for recreation.
            pendingCanvasAppearance = deferredAppearance
            return mode
        }
        glRenderer.execute {
            deferredAppearance?.let(::applyCanvasAppearance)
            // Released, not stamped: §4 says a cancelled stroke leaves no
            // trace, but the slots still have to come back.
            drainPending(stamp = false, scope = PendingBatchDrainScope.EXHAUSTIVE)
            renderer.cancelStroke { spec, keys -> onRmwCancelled?.invoke(spec, keys) }
        }
        // §8.4: cancel() drops the front-buffered content, and the
        // multi-buffered layer beneath is still showing the pre-stroke state,
        // so nothing else needs drawing.
        frontBuffered?.driver?.cancel()
        if (deferredRedraw == RedrawDecision.DRAW) redraw()
        return mode
    }

    fun invalidate(op: SandwichPolicy.Op) {
        glRenderer.execute { renderer.invalidate(op) }
        redraw()
    }

    /** One full-viewport redraw through the multi-buffered layer. */
    fun redraw() {
        if (renderPolicy.requestRedraw() != RedrawDecision.DRAW) return
        redrawNow()
    }

    private fun redrawNow() {
        dispatch(attachmentGate.requestScene())
    }

    /** Runs [block] on the GL thread. */
    fun execute(block: () -> Unit) = glRenderer.execute(block)

    /**
     * Tears the session down.
     *
     * The driver is discarded first. Canvas GL objects are then deleted on the
     * shared context before its thread stops.
     */
    fun release() {
        if (released) return

        released = true
        // The pump has nothing left to poll — the renderer's own release path
        // maps what is still in flight, on the GL thread, with a live context.
        renderPolicy.release()
        attachmentGate.release()
        surfaceView.holder.removeCallback(surfaceCallback)
        discardDriver()
        abandonRedrawCompletions()
        pollHandler.removeCallbacks(pollTick)
        pollHandler.removeCallbacks(wetOverlayTick)
        wetOverlayTickScheduled.set(false)
        // Anything published but never drawn: `cancelPending = true` below
        // means those callbacks will not run, so their slots would stay checked
        // out. Harmless for a session that is going away — except that the ring
        // is the session's, and a leak here reads in a profile exactly like the
        // one R-063 was about, so it is closed rather than left to be
        // rediscovered.
        while (true) {
            val pending = pendingBatches.poll() ?: break
            dabRing.release(pending)
        }
        glRenderer.execute {
            renderer.release()
            val droppedStroke = pendingStrokeFallback.getAndSet(null)
            pollHandler.post {
                droppedStroke?.invoke()
                completeFill(false)
            }
        }
        glRenderer.stop(false)
    }

    private companion object {
        /**
         * Two 120 Hz frames. The fence for a normal stroke signals within a
         * frame or two of pen-up (§10.1), so the first poll usually lands it;
         * anything slower is a wedged GPU, where polling faster buys nothing.
         */
        const val READBACK_POLL_MS = 17L
        const val WET_OVERLAY_PRESENT_RETRIES = 1
    }
}
