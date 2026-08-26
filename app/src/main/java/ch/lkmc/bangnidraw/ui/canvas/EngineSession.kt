package ch.lkmc.bangnidraw.ui.canvas

import android.view.SurfaceView
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.DabRing
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.engine.gl.CanvasRenderer

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
 * through §8.3's `commit()`. Roadmap 2.5b adds §9's predicted tail on the same
 * path: a predicted batch is published exactly like a real one and the renderer
 * routes it by `DabBatch.predictedFrom`. What is still absent rather than
 * stubbed: `readTiles` and `flushReadbacks`, whose consumer is step 3's
 * `TileStore`.
 */
class EngineSession(
    surface: SurfaceView,
    canvas: CanvasSize,
    budget: MemoryBudget.Result,
    private val debugBuild: Boolean,
) : GLFrontBufferedRenderer.Callback<DabBatch> {

    val renderer = CanvasRenderer(canvas, budget)

    /**
     * `GLFrontBufferedRenderer` starts its GL thread inside its own
     * constructor, so a callback can fire before this line finishes assigning.
     *
     * That is safe only because [renderer] is declared *above* it and the
     * callbacks read [renderer] and the flags — never `frontBuffered` itself.
     * Reordering these two declarations would let a callback observe a null
     * `renderer`. (An earlier version of this KDoc said "null until the first
     * frame", which described a nullable field that never existed.)
     */
    private val frontBuffered = GLFrontBufferedRenderer(surface, this)

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

    private var contextReady = false

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
    override fun onDrawFrontBufferedLayer(
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
            // Bailing out silently would strand every one of them and their
            // ring slots: `acquireDabBatch` returning null for the rest of the
            // session, R-063's shape on the live path.
            drainPending(stamp = false)
            return
        }
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
        val dirty = drainPending(stamp = true)
        if (dirty.isEmpty) return
        renderer.drawStrokeFrame(
            frameBufferId = bufferInfo.frameBufferId,
            bufferWidth = bufferInfo.width,
            bufferHeight = bufferInfo.height,
            bufferTransform = transform,
            dirtyCanvas = dirty,
        )
    }

    /**
     * Consumes every published batch, returning the canvas rect they dirtied.
     *
     * GL thread only. [stamp] false releases without drawing — §4's cancelled
     * stroke, and the unsupported-device path, both of which must leave no
     * trace but must still return their slots.
     */
    private fun drainPending(stamp: Boolean): IntRect {
        var dirty = IntRect.EMPTY
        var stampedAny = false
        while (true) {
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
                }
                dirty = dirty.union(renderer.stampDabs(next))
            }
            dabRing.release(next)
        }
        return dirty
    }

    /**
     * The full viewport from committed state (§8.2) — and, per §5, every
     * non-stroke redraw: layer edits, undo, view changes, resize.
     *
     * [width] and [height] are the surface's; `bufferInfo.width/height` are the
     * **buffer's**, which may be swapped relative to them when the compositor
     * hands us a pre-rotated buffer. The present quad spans the buffer and goes
     * through [transform], which is why §3.2 step 3 forbids a blit here.
     */
    override fun onDrawMultiBufferedLayer(
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
        renderer.drawFrame(
            frameBufferId = bufferInfo.frameBufferId,
            bufferWidth = bufferInfo.width,
            bufferHeight = bufferInfo.height,
            bufferTransform = transform,
        )
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

    // ---------------------------------------------------------------- façade

    /**
     * Sets the view transform and redraws.
     *
     * The uniforms are written on the GL thread via [execute] rather than from
     * here: `renderer.view` is read inside `drawFrame`, and assigning it from
     * the main thread while a frame is in flight is the kind of race that
     * shows up as one torn frame every few hundred and never reproduces.
     */
    fun setView(view: ViewTransform) {
        frontBuffered.execute { renderer.setView(view) }
        redraw()
    }

    fun setStack(stack: LayerStack) {
        frontBuffered.execute { renderer.setStack(stack) }
        redraw()
    }

    fun setPaperColor(argb: Int) {
        frontBuffered.execute { renderer.setPaperColor(argb) }
        redraw()
    }

    /** Theme colours for the transparent-paper checkerboard, and the dp scale. */
    fun setCheckerboard(checkerPx: Float, colorA: Int, colorB: Int) {
        frontBuffered.execute {
            renderer.checkerPx = checkerPx
            renderer.checkerA = colorA
            renderer.checkerB = colorB
        }
        redraw()
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
        frontBuffered.execute { renderer.beginStroke(spec, mode, r, g, b) }
    }

    /**
     * The single-producer/single-consumer ring of `02-architecture.md` §3.2:
     * the main thread fills a slot, the GL thread consumes it and returns it.
     *
     * A ring rather than a copy per event, because a copy would allocate a
     * whole `DabBatch` — 8 KiB of `FloatArray`s at the default capacity — on
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

    // Slots are released from BOTH threads: the input thread through
    // [releaseDabBatch] and the empty-batch path, the GL thread from inside
    // [stampDabs]'s execute block. That is safe because DabRing's `acquire` and
    // `release` are `@Synchronized` — checked, not assumed. "Single-producer/
    // single-consumer" above describes the *dab flow*, not the release path,
    // and it would be a poor thing to leave a reader inferring lock-freedom
    // from.

    /**
     * Borrows a batch to fill, or null when the GL thread still holds every
     * slot.
     *
     * **A null means the caller drops that sample**, and the caller does. §3.5
     * describes a producer that keeps its samples and coalesces them into the
     * next batch; `CanvasScreen.onStrokeSample` does not do that yet — it
     * returns, and the sample's position, pressure and tilt are gone. The
     * driver resumes from its last accepted sample, so the path degrades rather
     * than breaks.
     *
     * Said plainly rather than promised, because a doc claiming §3.5's
     * "nothing is lost" while the only producer drops on the floor is worse
     * than no doc. Implementing the coalescing belongs with 2.5's ring-driven
     * front-buffered path, where a starved ring stops being hypothetical.
     */
    fun acquireDabBatch(): DabBatch? = dabRing.acquire()

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
        if (!frontBuffered.isValid()) {
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
        frontBuffered.renderFrontBufferedLayer(batch)
    }

    /**
     * Merges the stroke into its layer (§7.4).
     *
     * **§10.1's readback is not wired yet** — `readback = null` below, and
     * `revision = 0` with it. `Readback` exists and is tested, but its consumer
     * is `TileStore`, which arrives with step 3's persistence; enqueueing into
     * nothing would be a readback whose results are dropped on the GL thread.
     * Said here because a doc claiming the readback runs would send anyone
     * tracing §10.1 straight past the gap.
     */
    fun endStroke() {
        // §8.3's order, and it holds because the FIFO assumption §8.3 flags was
        // verified against graphics-core 1.0.4 (AGENTS.md): this block runs
        // before the multi-buffered draw `commit()` schedules, so the layer
        // already owns the stroke by the time the committed frame is composed.
        frontBuffered.execute {
            // §8.3's `dabPass.drain(untilStrokeEnd)`: any batch published but
            // not yet drawn is stamped now, before the merge, or its dabs would
            // be lost — and its slot would still be checked out when the replay
            // arrives, where nothing releases it any more.
            drainPending(stamp = true)
            renderer.endStroke(readback = null, revision = 0)
        }
        if (!frontBuffered.isValid()) return
        // commit(), not redraw(): the multi-buffered layer is redrawn AND the
        // front layer is hidden. A plain redraw would leave the front buffer's
        // last stroke frame on screen, doubling the stroke over the merged one.
        frontBuffered.commit()
    }

    /** §4/§8.4: a cancelled stroke leaves no trace. */
    fun cancelStroke() {
        frontBuffered.execute {
            // Released, not stamped: §4 says a cancelled stroke leaves no
            // trace, but the slots still have to come back.
            drainPending(stamp = false)
            renderer.cancelStroke()
        }
        if (!frontBuffered.isValid()) return
        // §8.4: cancel() drops the front-buffered content, and the
        // multi-buffered layer beneath is still showing the pre-stroke state,
        // so nothing else needs drawing.
        frontBuffered.cancel()
    }

    fun invalidate(op: SandwichPolicy.Op) {
        frontBuffered.execute { renderer.invalidate(op) }
        redraw()
    }

    /**
     * One full-viewport redraw through the multi-buffered layer.
     *
     * `renderMultiBufferedLayer(emptyList())` — verified present in the pinned
     * 1.0.4, which `03-canvas-engine.md` §8.6 left as an open question with an
     * `empty-param commit()` as the fallback. It is exposed, so the fallback
     * is not needed.
     */
    fun redraw() {
        if (!frontBuffered.isValid()) return
        frontBuffered.renderMultiBufferedLayer(emptyList())
    }

    /** Runs [block] on the GL thread. */
    fun execute(block: () -> Unit) = frontBuffered.execute(block)

    /**
     * Tears the session down.
     *
     * `cancelPending = true`: anything still queued draws into a surface that
     * is going away. The GL objects are deleted inside the release callback,
     * which is the last moment there is a context to delete them with.
     */
    fun release() {
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
        frontBuffered.release(true) {
            renderer.release()
        }
    }
}
