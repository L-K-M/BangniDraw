package ch.lkmc.bangnidraw.ui.canvas

import android.view.SurfaceView
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
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
