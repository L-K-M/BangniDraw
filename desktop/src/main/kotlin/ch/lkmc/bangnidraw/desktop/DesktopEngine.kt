package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DeviceMemory
import ch.lkmc.bangnidraw.engine.core.DabRing
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.StrokeDriver
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.gl.CanvasRenderer
import ch.lkmc.bangnidraw.engine.gl.platform.ClasspathEngineAssets
import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** One export whose completion is delivered at most once, including cancellation. */
internal class DesktopExportTask(
    private val export: () -> DesktopSaveResult,
    private val onComplete: (DesktopSaveResult) -> Unit,
) : Runnable {
    private val completed = AtomicBoolean(false)

    override fun run() {
        val result = try {
            export()
        } catch (failure: Exception) {
            DesktopPng.failureResult(failure)
        }
        complete(result)
    }

    fun cancel() {
        complete(DesktopSaveResult.Failed(EXPORT_CANCELLED_MESSAGE))
    }

    fun fail(failure: Exception) {
        complete(DesktopPng.failureResult(failure))
    }

    private fun complete(result: DesktopSaveResult) {
        if (completed.compareAndSet(false, true)) onComplete(result)
    }

    private companion object {
        const val EXPORT_CANCELLED_MESSAGE = "export cancelled while the app was closing"
    }
}

/**
 * The desktop EngineSession equivalent (DESKTOP.md Phase 2, M4): one GL
 * thread owning the GLFW context and [CanvasRenderer], a task queue the UI
 * thread submits to, and the offscreen-FBO → readback → [Frame] handoff
 * that carries pixels to Compose.
 *
 * Deliberately minimal next to the Android session: no surface
 * attachments, no front-buffered layers, no wet overlay, no persistence —
 * a plain render loop (`swap interval 0` posture per DESKTOP.md's latency
 * section) with the same renderer, the same stroke pipeline
 * (StrokeDriver → DabRing → renderer), and an in-memory undo journal fed
 * by the renderer's readback mirror.
 */
internal class DesktopEngine(
    val canvas: CanvasSize,
    memory: DeviceMemory,
    private val context: GlfwEsContext,
    private val onFrame: (Frame) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    /** One rendered frame's pixels, RGBA8, row-major from the top. */
    class Frame(val width: Int, val height: Int, val pixels: ByteArray)

    /** One undoable stroke: per-tile before/after images from the mirror. */
    private class UndoEntry(
        val layerId: LayerId,
        val keys: List<TileKey>,
        val before: Map<TileKey, ByteArray?>,
    ) {
        lateinit var after: Map<TileKey, ByteArray?>

        val bytes: Long
            get() = before.pixelBytes() + after.pixelBytes()

        private fun Map<TileKey, ByteArray?>.pixelBytes(): Long =
            values.sumOf { pixels -> pixels?.size?.toLong() ?: 0L }
    }

    private val budget = MemoryBudget.compute(memory, canvas)
    private val dabRing = DabRing()
    private val revisionCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val started = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val glThread = Thread(::runGlLoop, "BangniDraw-GL").apply { isDaemon = true }
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val exportExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, EXPORT_THREAD_NAME).apply { isDaemon = true }
    }
    private val repaint = AtomicBoolean(false)

    /** The in-memory tile mirror the renderer's readback keeps current. */
    private val mirror = HashMap<LayerId, HashMap<TileKey, ByteArray>>()
    private val readbackRevisions = HashMap<LayerId, HashMap<TileKey, Int>>()

    private var renderer: CanvasRenderer? = null
    private var frameFbo = 0
    private var frameTexture = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var framePixels: ByteArray? = null

    val stack = LayerStack(
        listOf(Layer(LayerProps(LayerId("layer-1"), "Layer 1"))),
        activeIndex = 0,
        nextName = 2,
    )
    private val undoHistory = DesktopHistory(
        maxSteps = budget.historyMaxSteps,
        maxBytes = budget.historyMaxBytes,
        sizeOf = UndoEntry::bytes,
    )


    // ------------------------------------------------------------- control

    fun start() {
        if (!started.getAndSet(true)) glThread.start()
    }

    fun stopAndJoin() {
        shutdownExports()
        if (!started.get()) return

        glThread.interrupt()
        if (Thread.currentThread() === glThread) return

        try {
            glThread.join(GL_SHUTDOWN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            context.abandonAfterOwnerTimeout()
            Thread.currentThread().interrupt()
            return
        }

        // Never destroy a context that may still be current in native code.
        if (glThread.isAlive) context.abandonAfterOwnerTimeout()
    }

    private fun shutdownExports() {
        exportExecutor.shutdown()
        try {
            val stopped = exportExecutor.awaitTermination(
                EXPORT_SHUTDOWN_TIMEOUT_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            if (!stopped) cancelQueuedExports(exportExecutor.shutdownNow())
        } catch (_: InterruptedException) {
            cancelQueuedExports(exportExecutor.shutdownNow())
            Thread.currentThread().interrupt()
        }
    }

    private fun cancelQueuedExports(tasks: List<Runnable>) {
        for (runnable in tasks) {
            val task = runnable as? DesktopExportTask ?: continue
            task.cancel()
        }
    }

    /** Submits [block] to the GL thread; dropped after a fatal failure. */
    fun post(block: () -> Unit) {
        if (!failed.get()) tasks.add(block)
    }

    fun requestRepaint() {
        repaint.set(true)
    }

    fun setViewportSize(width: Int, height: Int) = post {
        if (width > 0 && height > 0 && (width != frameWidth || height != frameHeight)) {
            frameWidth = width
            frameHeight = height
            allocateFrameTarget()
            renderer?.onSurfaceChanged(width, height)
            requestRepaintOnGl()
        }
    }

    fun setView(view: ViewTransform) = post {
        renderer?.setView(view)
        requestRepaintOnGl()
    }

    fun setPaperColor(argb: Int) = post {
        renderer?.setPaperColor(argb)
        requestRepaintOnGl()
    }

    // ------------------------------------------------------ stroke pipeline

    /** Borrows a bounded ring slot for one input batch, or null if the GL thread holds all. */
    fun acquireDabBatch(): DabBatch? = dabRing.acquire()

    /** Hands a borrowed batch back unused (the driver emitted nothing). */
    fun releaseDabBatch(batch: DabBatch) = dabRing.release(batch)

    fun beginStroke(spec: StrokeSpec, mode: BufferMode, r: Float, g: Float, b: Float) = post {
        val renderer = renderer ?: return@post
        renderer.beginStroke(spec, mode, r, g, b)
        requestRepaintOnGl()
    }

    fun stampDabs(batch: DabBatch) {
        if (batch.count == 0) {
            dabRing.release(batch)
            return
        }
        post {
            val renderer = renderer ?: return@post
            renderer.stampDabs(batch)
            // The ring slot returns on the GL thread, after the renderer has
            // read the batch — never on the caller's thread.
            dabRing.release(batch)
            requestRepaintOnGl()
        }
    }

    fun cancelStroke() = post {
        val renderer = renderer ?: return@post
        var cancelledRmw: Pair<StrokeSpec, List<TileKey>>? = null
        renderer.cancelStroke { spec, keys ->
            cancelledRmw = spec to keys
        }

        cancelledRmw?.let { (spec, keys) ->
            val images = DesktopTileMirror.snapshot(
                source = mirror[spec.layerId],
                keys = keys,
            )
            check(renderer.restoreCancelledRmw(spec.layerId, images)) {
                "cancelled RMW pixels could not be restored"
            }
        }
        requestRepaintOnGl()
    }

    /**
     * Merges the stroke, then drains this stroke's readback to completion
     * before journaling — the serialization that makes the mirror (and
     * therefore the undo journal) exact on a desktop with one input
     * stream. [onCommitted] runs on the GL thread, not the caller's.
     */
    fun endStroke(opacityCeiling: Float, onCommitted: () -> Unit) = post {
        val renderer = renderer ?: return@post
        val revision = nextRevision()
        var entry: UndoEntry? = null
        val merged = renderer.endStroke(
            revision = revision,
            opacityCeiling = opacityCeiling,
        ) { spec, keys ->
            // Pre-merge state: everything but this stroke's own tiles.
            val layerMirror = mirror[spec.layerId]
            val before = HashMap<TileKey, ByteArray?>(keys.size)
            for (key in keys) before[key] = layerMirror?.get(key)?.copyOf()
            entry = UndoEntry(spec.layerId, keys.toList(), before)
        }
        if (merged > 0) {
            val committed = checkNotNull(entry) { "renderer merged without touched keys" }
            requireReadback(renderer)
            check(
                DesktopReadbackPolicy.delivery(
                    keys = committed.keys,
                    expectedRevision = revision,
                    revisionOf = { key -> readbackRevisions[committed.layerId]?.get(key) },
                ) == ReadbackDelivery.Complete,
            ) { "GPU readback did not deliver every merged tile" }

            val layerMirror = mirror.getValue(committed.layerId)
            val after = HashMap<TileKey, ByteArray?>(committed.keys.size)
            for (key in committed.keys) after[key] = layerMirror[key]?.copyOf()
            committed.after = after
            journal(committed)
            onCommitted()
        }
        requestRepaintOnGl()
    }

    private fun journal(entry: UndoEntry) = undoHistory.record(entry)

    fun canUndo(): Boolean = undoHistory.canUndo
    fun canRedo(): Boolean = undoHistory.canRedo

    /**
     * Saves the painting as a PNG under `~/Pictures/BangniDraw`, composed
     * from the readback mirror (the mirror is exact: every commit drains
     * its readback to completion first). [onComplete] is not UI-thread-bound;
     * callers must marshal UI state themselves.
     */
    fun savePng(onComplete: (DesktopSaveResult) -> Unit) = post {
        val snapshot: DesktopExportSnapshot
        val file: java.io.File
        try {
            if (renderer == null) error("rendering is not ready")

            // Commits drain readback before updating history. Export captures
            // that last committed mirror without another blocking fence wait,
            // which could hold the GL owner long enough to exhaust DabRing.

            val layerMirror = mirror[stack.layers[stack.activeIndex].id].orEmpty()
            snapshot = DesktopPng.snapshot(
                width = canvas.width,
                height = canvas.height,
                paperArgb = DEFAULT_PAPER_ARGB,
                tiles = layerMirror,
            )
            file = java.io.File(
                DesktopPlatform.picturesDir(),
                DesktopBrand.exportFileStem(DesktopBrand.displayName) + "-" +
                    System.currentTimeMillis() + ".png",
            )
        } catch (failure: Exception) {
            onComplete(DesktopPng.failureResult(failure))
            return@post
        }

        // Composition and ImageIO are CPU/disk work; never block the GL owner.
        val task = DesktopExportTask(
            export = { DesktopPng.export(snapshot, file) },
            onComplete = onComplete,
        )
        try {
            exportExecutor.execute(task)
        } catch (failure: Exception) {
            task.fail(failure)
        }
    }

    fun undo() = post { applyHistory(HistoryDirection.Undo) }
    fun redo() = post { applyHistory(HistoryDirection.Redo) }

    private fun applyHistory(direction: HistoryDirection) {
        val renderer = renderer ?: return
        val entry = undoHistory.move(direction) ?: return
        val images = if (direction == HistoryDirection.Undo) entry.before else entry.after

        if (!renderer.restoreCancelledRmw(entry.layerId, images)) {
            undoHistory.move(direction.opposite())
            return
        }

        val layerMirror = mirror.getOrPut(entry.layerId) { HashMap() }
        DesktopTileMirror.apply(layerMirror, images)
        if (layerMirror.isEmpty()) mirror.remove(entry.layerId)
        requestRepaintOnGl()
    }

    private fun requireReadback(renderer: CanvasRenderer) {
        check(DesktopReadbackPolicy.drain(renderer::finishReadback) == ReadbackDrain.Complete) {
            "GPU readback timed out; the CPU mirror may be stale"
        }
    }

    private fun HistoryDirection.opposite(): HistoryDirection = when (this) {
        HistoryDirection.Undo -> HistoryDirection.Redo
        HistoryDirection.Redo -> HistoryDirection.Undo
    }

    // ------------------------------------------------------------ the loop

    private fun runGlLoop() {
        try {
            context.activate()
            initializeRenderer()
            runTasksAndFrames()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            failed.set(true)
            val detail = failure.message ?: failure::class.simpleName ?: "unknown failure"
            onFatal("Desktop rendering stopped: $detail")
        } finally {
            releaseGl()
        }
    }

    private fun initializeRenderer() {
        val next = CanvasRenderer(canvas, budget, ClasspathEngineAssets()) { layerId, key, revision, pixels ->
            mirror.getOrPut(layerId) { HashMap() }[key] = pixels.copyOfBytes()
            readbackRevisions.getOrPut(layerId) { HashMap() }[key] = revision
        }
        check(next.onContextCreated(strict = true)) {
            DesktopGlDiagnostics.rendererRequirements
        }
        next.setStack(stack)
        next.setPaperColor(DEFAULT_PAPER_ARGB)
        next.setView(ViewTransform())
        renderer = next

        frameWidth = INITIAL_FRAME_WIDTH
        frameHeight = INITIAL_FRAME_HEIGHT
        allocateFrameTarget()
        next.onSurfaceChanged(frameWidth, frameHeight)

        // Publish frame one without depending on Compose's first layout callback.
        requestRepaintOnGl()
    }

    private fun runTasksAndFrames() {
        while (!Thread.currentThread().isInterrupted) {
            var worked = false
            while (true) {
                val task = tasks.poll() ?: break
                task()
                worked = true
            }
            if (repaint.getAndSet(false)) {
                renderFrame()
                worked = true
            }
            if (!worked) Thread.sleep(IDLE_SLEEP_MS)
        }
    }

    private fun renderFrame() {
        val r = renderer ?: return
        val w = frameWidth
        val h = frameHeight
        if (w <= 0 || h <= 0) return

        check(r.drawFrame(frameFbo, w, h, IDENTITY_TRANSFORM)) {
            "the engine could not render the desktop frame"
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameFbo)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        val pixels = framePixels ?: return
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(pixels))
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // CanvasRenderer already defines row zero as the canvas top.
        onFrame(Frame(w, h, DesktopFramePixels.copyForCompose(pixels)))
    }

    private fun allocateFrameTarget() {
        releaseFrameTarget()
        val w = frameWidth
        val h = frameHeight
        val names = IntArray(1)

        GLES30.glGenTextures(1, names, 0)
        frameTexture = names[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )

        GLES30.glGenFramebuffers(1, names, 0)
        frameFbo = names[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, frameTexture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "desktop frame buffer is incomplete: 0x${status.toString(16)}"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        framePixels = ByteArray(w * h * RGBA_CHANNELS)
    }

    private fun releaseFrameTarget() {
        if (frameFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(frameFbo), 0)
            frameFbo = 0
        }
        if (frameTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(frameTexture), 0)
            frameTexture = 0
        }
    }

    private fun releaseGl() {
        try {
            renderer?.release()
            renderer = null
            releaseFrameTarget()
        } finally {
            context.deactivate()
        }
    }

    private fun nextRevision(): Int = revisionCounter.incrementAndGet()

    private fun requestRepaintOnGl() {
        repaint.set(true)
    }

    private fun ByteBuffer.copyOfBytes(): ByteArray {
        val copy = ByteArray(remaining())
        get(copy)
        return copy
    }

    companion object {
        private const val EXPORT_THREAD_NAME = "BangniDraw-Export"
        private const val EXPORT_SHUTDOWN_TIMEOUT_MS = 5_000L
        private const val GL_SHUTDOWN_TIMEOUT_MS = 5_000L
        private const val INITIAL_FRAME_WIDTH = 1280
        private const val INITIAL_FRAME_HEIGHT = 800
        private const val IDLE_SLEEP_MS = 4L
        private const val RGBA_CHANNELS = 4
        private const val DEFAULT_PAPER_ARGB = 0xFFFFFFFF.toInt()

        private val IDENTITY_TRANSFORM = FloatArray(16).also {
            java.util.Arrays.fill(it, 0f)
            it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
        }
    }
}
