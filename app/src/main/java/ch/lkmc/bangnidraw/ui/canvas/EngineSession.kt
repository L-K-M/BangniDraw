package ch.lkmc.bangnidraw.ui.canvas

import android.view.SurfaceView
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.DabRing
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
 * **Roadmap 2.3b: the multi-buffered path only.** `beginStroke`, `renderBatch`,
 * `commitStroke`, `cancelStroke`, `readTiles` and `flushReadbacks` are the
 * stroke and readback surface of 2.4 and 2.5 and are deliberately absent
 * rather than stubbed — a method that exists and does nothing is worse than
 * one that is not there yet, because a caller cannot tell.
 * [onDrawFrontBufferedLayer] must still be implemented for the interface, and
 * says so.
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
     * Not this PR's path, and it must not silently draw the wrong thing.
     *
     * The front-buffered path needs `StrokeBuffer`, `DabPass` and `MergePass`
     * (roadmap 2.4) and the predicted tail (2.5). Until they exist nothing
     * calls `renderFrontBufferedLayer`, so this cannot fire — and if something
     * ever does call it, drawing a full committed frame into the *front*
     * buffer would be a visible correctness bug, not a graceful degradation.
     * Doing nothing leaves the multi-buffered layer beneath showing the
     * correct composite.
     */
    override fun onDrawFrontBufferedLayer(
        eglManager: EGLManager,
        width: Int,
        height: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        param: DabBatch,
    ) = Unit

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
        // §8.2 iterates `params` only to release the ring slots back to
        // `DabRing`. There is no ring in this PR — it arrives with the stroke
        // path in 2.4 — and `params` is empty on every non-stroke redraw,
        // which is all of them here. Releasing arrives with the ring that owns
        // the slots; a release written now would have nothing to release to.
        check(params.isEmpty()) {
            "the multi-buffered callback received ${params.size} batches, but nothing " +
                "publishes them until roadmap 2.4 — their ring slots would leak"
        }
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
     * Stamps a batch borrowed from [acquireDabBatch] and returns it to the ring
     * once the GL thread is done with it.
     *
     * The release happens **inside** the GL block, not after `execute`
     * returns: `execute` only queues, so releasing here would hand the slot
     * back while the GL thread was still reading it.
     */
    fun stampDabs(batch: DabBatch) {
        if (batch.count == 0) {
            dabRing.release(batch)
            return
        }
        frontBuffered.execute {
            renderer.stampDabs(batch)
            dabRing.release(batch)
        }
        redraw()
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
        frontBuffered.execute { renderer.endStroke(readback = null, revision = 0) }
        redraw()
    }

    /** §4: a cancelled stroke leaves no trace. */
    fun cancelStroke() {
        frontBuffered.execute { renderer.cancelStroke() }
        redraw()
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
        frontBuffered.release(true) {
            renderer.release()
        }
    }
}
