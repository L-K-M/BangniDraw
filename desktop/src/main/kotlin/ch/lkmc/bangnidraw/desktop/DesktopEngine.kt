package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Coverage
import ch.lkmc.bangnidraw.engine.core.DeviceMemory
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.FloodFill
import ch.lkmc.bangnidraw.engine.core.PixelCommitKind
import ch.lkmc.bangnidraw.engine.core.StrokeLayerDecision
import ch.lkmc.bangnidraw.engine.core.StrokeLayerPolicy
import ch.lkmc.bangnidraw.engine.core.StrokeMode
import ch.lkmc.bangnidraw.engine.core.IdSource
import ch.lkmc.bangnidraw.engine.core.LayerThumbnail
import ch.lkmc.bangnidraw.engine.core.Refusal
import ch.lkmc.bangnidraw.engine.core.StackEdit
import ch.lkmc.bangnidraw.engine.core.StackResult
import ch.lkmc.bangnidraw.engine.core.DabRing
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.PixelOp
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
import ch.lkmc.bangnidraw.engine.core.StrokeDriver
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TracingReference
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.gl.CanvasRenderer
import ch.lkmc.bangnidraw.engine.gl.platform.ClasspathEngineAssets
import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import ch.lkmc.bangnidraw.engine.gl.platform.GlLog
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
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()

            try {
                complete(DesktopPng.failureResult(failure))
            } finally {
                if (failure is Error) throw failure
            }
            return
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
 * thread owning the GL context and [CanvasRenderer], a task queue the UI
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
    private val host: DesktopGlHost,
    private val onFrame: (Frame) -> Unit,
    /**
     * Publishes the document model whenever the GL thread changes it. The
     * layer panel is a separate window reading Compose state, so the model
     * cannot simply be read back from here: it has to be pushed.
     */
    private val onStack: (LayerStack) -> Unit = {},
    /** Publishes the paper colour, which undo can move like any other edit. */
    private val onPaper: (Int) -> Unit = {},
    /**
     * Fires whenever the painting changes, so the window can offer to save.
     *
     * Deliberately a one-way flag rather than a save-point comparison:
     * undoing back past the last save still reports unsaved work. That
     * over-reports and never under-reports, which is the safe direction for
     * the question "close without saving?".
     */
    private val onEdited: () -> Unit = {},
    /**
     * The painting this document opens with, already decoded. Its pixels are
     * uploaded before the first frame, so the window never shows an empty
     * canvas that is about to be replaced.
     */
    private val initial: DesktopInitialContent? = null,
) : DesktopGlHost.Client {
    /** One rendered frame's pixels, RGBA8, row-major from the top. */
    class Frame(val width: Int, val height: Int, val pixels: ByteArray)

    private val budget = MemoryBudget.compute(memory, canvas)

    /** How many layers this canvas fits in the GPU pool (`10-performance.md` §4). */
    val layerCap: Int get() = budget.maxLayers

    /** The decoded RGBA8 a private image import may occupy (`10 §4`). */
    val transientImageBytes: Long get() = budget.transientImageBytes
    private val dabRing = DabRing()
    private val revisionCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val released = AtomicBoolean(false)
    private val exportExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, EXPORT_THREAD_NAME).apply { isDaemon = true }
    }

    // Its own worker, not the export one: a fill must not wait behind a PNG
    // encode, and an encode must not wait behind a full-canvas scan.
    private val fillExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, FILL_THREAD_NAME).apply { isDaemon = true }
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
    private var nextWetRefreshNs = 0L
    private var thumbnailSink: ((LayerId, LayerThumbnail?) -> Unit)? = null
    private var paperColor = initial?.paperArgb ?: DEFAULT_PAPER_ARGB
    private var fillGeneration = 0

    /**
     * The document model. Written only on the GL thread; `@Volatile` because
     * the input host reads the active layer while a gesture starts.
     */
    @Volatile
    var stack: LayerStack = initial?.stack ?: INITIAL_STACK
        private set

    private val undoHistory = DesktopHistory(
        maxSteps = budget.historyMaxSteps,
        maxBytes = budget.historyMaxBytes,
        sizeOf = DesktopUndoStep::bytes,
    )

    /** Fresh layer ids; `layer-<n>` keeps them legible in diagnostics. */
    private var nextLayerNumber = stack.layers.size + 1
    private val ids = IdSource {
        var candidate: LayerId
        do {
            candidate = LayerId("layer-" + nextLayerNumber++)
        } while (stack.layers.any { it.id == candidate })
        candidate
    }


    // ------------------------------------------------------------- control

    fun start() {
        host.attach(this)
    }

    /**
     * Closes this document: its workers stop, then the GL thread frees its
     * renderer. The shared thread itself outlives it — another document may
     * still be open — so nothing is joined here.
     */
    fun stopAndJoin() {
        if (released.getAndSet(true)) return

        fillGeneration++
        fillExecutor.shutdownNow()
        shutdownExports()
        host.detach(this)
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

    /**
     * Submits [block] to the shared GL thread. Dropped once this document is
     * closed: its renderer is gone, and a queued task would run against it.
     */
    fun post(block: () -> Unit) {
        if (!released.get()) host.post(block)
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

    /**
     * The paper colour, journaled. Android carries it on the document and
     * gives it its own `HistoryEntry`; here the step already describes the
     * whole document, so the colour rides along on the same journal rather
     * than needing a second one.
     */
    fun setPaperColor(argb: Int) = post {
        val renderer = renderer ?: return@post
        if (argb == paperColor) return@post

        val before = paperColor
        paperColor = argb
        renderer.setPaperColor(argb)
        undoHistory.record(
            DesktopUndoStep(
                stackBefore = stack,
                stackAfter = stack,
                pixelsBefore = emptyMap(),
                pixelsAfter = emptyMap(),
                paperBefore = before,
                paperAfter = argb,
            ),
        )
        onPaper(argb)
        onEdited()
        requestRepaintOnGl()
    }

    // ---------------------------------------------------- tracing reference

    /**
     * Publishes the tracing image's placement. Not journaled: `:app` keeps the
     * reference out of painting undo too — it is a private aid over the
     * paper, and an undo that walked back a nudge of it would be walking back
     * something the painting does not contain.
     */
    fun setTracingReference(reference: TracingReference?) = post {
        renderer?.setTracingReference(reference)
        requestRepaintOnGl()
    }

    /** One decoded batch of the reference's pixels; stale asset work is dropped. */
    fun uploadReferenceTiles(assetName: String, tiles: List<Pair<TileKey, ByteArray>>) = post {
        val renderer = renderer ?: return@post
        if (!renderer.isReady) return@post

        renderer.uploadReferenceTiles(assetName, tiles)
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
        val committed = commitMerged(renderer) { revision, onMerged ->
            renderer.endStroke(revision, opacityCeiling, onMerged)
        }
        if (committed) onCommitted()
        requestRepaintOnGl()
    }

    /**
     * Runs one pixel commit — a stroke merge or a fill — and journals what it
     * moved. [merge] gets the revision to commit under and the pre-merge
     * callback the renderer reports its touched keys through, and returns how
     * many tiles it merged; zero means nothing happened and nothing is
     * journaled.
     *
     * Shared, because a fill is a merge like any other: it uploads coverage
     * into the same stroke buffer and ends the same stroke. Two copies of this
     * bookkeeping would be two places to forget the readback drain, and the
     * mirror would silently lag the GPU for the *next* undo, not this one.
     */
    private inline fun commitMerged(
        renderer: CanvasRenderer,
        merge: (Int, (StrokeSpec, List<TileKey>) -> Unit) -> Int,
    ): Boolean {
        val revision = nextRevision()
        var layerId: LayerId? = null
        var keys: List<TileKey> = emptyList()
        var before: Map<TileKey, ByteArray?> = emptyMap()
        val merged = merge(revision) { spec, touched ->
            // Pre-merge state: everything but this commit's own tiles.
            val layerMirror = mirror[spec.layerId]
            val captured = HashMap<TileKey, ByteArray?>(touched.size)
            for (key in touched) captured[key] = layerMirror?.get(key)?.copyOf()
            layerId = spec.layerId
            keys = touched.toList()
            before = captured
        }
        if (merged <= 0) return false

        val committedLayer = checkNotNull(layerId) { "renderer merged without touched keys" }
        requireReadback(renderer)
        check(
            DesktopReadbackPolicy.delivery(
                keys = keys,
                expectedRevision = revision,
                revisionOf = { key -> readbackRevisions[committedLayer]?.get(key) },
            ) == ReadbackDelivery.Complete,
        ) { "GPU readback did not deliver every merged tile" }

        val layerMirror = mirror.getValue(committedLayer)
        val after = HashMap<TileKey, ByteArray?>(keys.size)
        for (key in keys) after[key] = layerMirror[key]?.copyOf()

        val stackBefore = stack
        val stackAfter = DesktopStrokeTiles.withCommitted(stackBefore, committedLayer, keys)
        if (stackAfter !== stackBefore) {
            stack = stackAfter
            renderer.setStack(stackAfter)
            publishStack()
        }
        undoHistory.record(
            DesktopUndoStep(
                stackBefore = stackBefore,
                stackAfter = stackAfter,
                pixelsBefore = mapOf(committedLayer to before),
                pixelsAfter = mapOf(committedLayer to after),
            ),
        )
        requestThumbnailRefresh()
        onEdited()
        return true
    }

    // ------------------------------------------------------- fill and pick

    /**
     * One eyedropper read. Every read is a synchronous `glReadPixels` — a full
     * pipeline sync — so the caller throttles with [EyedropperSampleGate]
     * rather than reading per sample. [onColor] runs on the GL thread.
     */
    fun sampleColor(x: Float, y: Float, params: EyedropperParams, onColor: (Int?) -> Unit) = post {
        onColor(renderer?.sampleColor(x, y, params))
    }

    /**
     * Floods from the canvas point ([x], [y]) and commits the coverage.
     *
     * Three hops: the reference snapshot is GPU work, the scan is CPU work on
     * its own worker (a 4096² scan is seconds, and the GL thread must keep
     * presenting), and the upload is GPU work again. [onDone] runs on the GL
     * thread when the whole sequence has settled, told whether anything landed.
     */
    fun startFill(x: Float, y: Float, params: FillParams, color: Int, onDone: (Boolean) -> Unit) = post {
        val renderer = renderer
        if (renderer == null) {
            onDone(false)
            return@post
        }
        // Lock refuses pixels; a hidden layer still accepts them (`05` §1).
        if (StrokeLayerPolicy.decide(
                visible = stack.active.props.visible,
                locked = stack.active.props.locked,
            ) == StrokeLayerDecision.REFUSE_LOCKED
        ) {
            onDone(false)
            return@post
        }
        val reference = renderer.fillReference(params.reference)
        if (reference == null) {
            onDone(false)
            return@post
        }

        val generation = ++fillGeneration
        val scan = FloodFill(canvas.width, canvas.height, reference, params)
        val seedX = kotlin.math.floor(x).toInt()
        val seedY = kotlin.math.floor(y).toInt()
        try {
            fillExecutor.execute {
                val coverage = scan.run(
                    seedX = seedX,
                    seedY = seedY,
                    progress = {},
                    isCancelled = { generation != fillGeneration },
                )
                post { finishFill(generation, coverage, params, color, onDone) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onDone(false)
        }
    }

    private fun finishFill(
        generation: Int,
        coverage: Coverage?,
        params: FillParams,
        color: Int,
        onDone: (Boolean) -> Unit,
    ) {
        val renderer = renderer
        if (generation != fillGeneration || renderer == null) {
            onDone(false)
            return
        }
        if (coverage == null || coverage.bounds.isEmpty) {
            onDone(false)
            return
        }

        // The active layer is re-read here, not captured before the scan: the
        // layer panel is a window of its own and the selection can have moved
        // while a full-canvas scan ran.
        val active = stack.active
        val spec = StrokeSpec(
            layerId = active.id,
            mode = StrokeMode.PAINT,
            opacity = params.opacity,
            alphaLock = active.props.alphaLock,
            commitKind = PixelCommitKind.Fill,
        )
        val applied = commitMerged(renderer) { revision, onMerged ->
            if (renderer.applyFill(spec, coverage, color, revision, onMerged)) 1 else 0
        }
        onDone(applied)
        requestRepaintOnGl()
    }

    /** Abandons a scan whose result nobody wants any more. */
    fun cancelFill() = post { fillGeneration++ }

    fun canUndo(): Boolean = undoHistory.canUndo
    fun canRedo(): Boolean = undoHistory.canRedo

    /**
     * Saves the painting as a PNG — to [target], or under
     * `~/Pictures/BangniDraw` when the document has no file yet — composed
     * from the readback mirror (the mirror is exact: every commit drains
     * its readback to completion first). [onComplete] is not UI-thread-bound;
     * callers must marshal UI state themselves.
     */
    fun savePng(target: java.io.File? = null, onComplete: (DesktopSaveResult) -> Unit) = post {
        val snapshot: DesktopExportSnapshot
        val file: java.io.File
        try {
            if (renderer == null) error("rendering is not ready")

            // Commits drain readback before updating history. Export captures
            // that last committed mirror without another blocking fence wait,
            // which could hold the GL owner long enough to exhaust DabRing.

            snapshot = DesktopPng.snapshot(
                width = canvas.width,
                height = canvas.height,
                paperArgb = paperColor,
                stack = stack,
                mirror = mirror,
            )
            file = target ?: java.io.File(
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

    /**
     * Saves the whole document — every layer, its props and the paper — as a
     * `.bangni` file. Composed from the same readback mirror the PNG export
     * uses, and written on the export worker for the same reason: a large
     * painting is megabytes of deflate, and the GL thread has to keep
     * presenting.
     */
    fun saveBangni(
        target: java.io.File,
        title: String,
        createdAt: Long,
        /** Read on the caller's thread: the reference lives on the shell state. */
        reference: TracingReference?,
        referencePng: ByteArray?,
        onComplete: (DesktopSaveResult) -> Unit,
    ) = post {
        val document: ch.lkmc.bangnidraw.data.shared.BangniDocument
        try {
            if (renderer == null) error("rendering is not ready")

            document = DesktopDocumentIo.snapshot(
                title = title,
                canvas = canvas,
                paperArgb = paperColor,
                stack = stack,
                mirror = mirror,
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis(),
                reference = reference,
                referencePng = referencePng,
            )
        } catch (failure: Exception) {
            onComplete(DesktopPng.failureResult(failure))
            return@post
        }

        val task = DesktopExportTask(
            export = { DesktopBangniWriter.write(document, target) },
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
        val step = undoHistory.move(direction) ?: return
        val target = step.stackFor(direction)
        val pixels = step.pixelsFor(direction)

        // `applyPixelOps`, not `restoreCancelledRmw`: the latter is the
        // CANCEL door. History has to go through the op path so
        // `WatercolorEditPolicy` sees an UndoRedo and dries the wet grid —
        // otherwise wetness from an undone watercolor stroke survives the
        // undo and bleeds into whatever is painted next.
        val ops = DesktopUndoOps.ops(stack, target, pixels)
        val applied = ops.isEmpty() || renderer.applyPixelOps(
            ops = ops,
            revision = nextRevision(),
            invalidation = SandwichPolicy.Op.UndoRedo,
        )
        if (!applied) {
            undoHistory.move(direction.opposite())
            return
        }

        stack = target
        renderer.setStack(target, SandwichPolicy.Op.UndoRedo)
        step.paperFor(direction)?.let { argb ->
            paperColor = argb
            renderer.setPaperColor(argb)
            onPaper(argb)
        }
        for ((layerId, tiles) in pixels) {
            val layerMirror = mirror.getOrPut(layerId) { HashMap() }
            DesktopTileMirror.apply(layerMirror, tiles)
            if (layerMirror.isEmpty()) mirror.remove(layerId)
        }
        pruneMirror(target)
        publishStack()
        requestThumbnailRefresh()
        onEdited()
        requestRepaintOnGl()
    }

    // ---------------------------------------------------------- document

    /**
     * Selection is a view concern (`05-layers.md` §3), never an edit: it
     * changes which layer the next stroke lands on and stales the sandwich,
     * but it is not journaled and cannot be undone.
     */
    fun selectLayer(index: Int) = post {
        val renderer = renderer ?: return@post
        val next = stack.select(index)
        if (next == stack) return@post

        stack = next
        renderer.setStack(next)
        publishStack()
        requestRepaintOnGl()
    }

    /**
     * Runs one pure [LayerStack] operation and commits whatever it produced.
     *
     * [edit] is evaluated on the GL thread against the live model, so a panel
     * that computed an index a frame ago cannot apply it to a stack that moved
     * underneath. [onResult] reports the [Refusal] the model gave — the panel
     * turns those into hints — or `null` on success.
     */
    fun editStack(onResult: (Refusal?) -> Unit = {}, edit: (LayerStack, IdSource) -> StackResult) = post {
        val renderer = renderer
        if (renderer == null) {
            onResult(Refusal.NOOP)
            return@post
        }
        when (val result = edit(stack, ids)) {
            is StackResult.Refused -> onResult(result.reason)
            is StackResult.Ok -> onResult(if (commitStackEdit(renderer, result.edit)) null else Refusal.NOOP)
        }
    }

    /**
     * Applies a [StackEdit]'s pixels, publishes its model, and journals the
     * step. The pixel payload is snapshotted from the mirror on both sides of
     * the edit, so undo and redo are exact inverses of each other rather than
     * replays of an operation the model would refuse a second time.
     */
    private fun commitStackEdit(renderer: CanvasRenderer, edit: StackEdit): Boolean {
        val before = stack
        val after = edit.stack
        val touched = DesktopStackEdits.touchedLayers(before, after, edit.pixels)
        val pixelsBefore = snapshotPixels(before, after, touched)
        val invalidation = DesktopStackEdits.invalidation(edit.entry, before)

        val op = edit.pixels
        if (op != null && !renderer.applyPixelOps(listOf(op), nextRevision(), invalidation)) {
            return false
        }

        stack = after
        renderer.setStack(after, invalidation)
        if (op != null) requireReadback(renderer)
        pruneMirror(after)
        undoHistory.record(
            DesktopUndoStep(
                stackBefore = before,
                stackAfter = after,
                pixelsBefore = pixelsBefore,
                pixelsAfter = snapshotPixels(before, after, touched),
            ),
        )
        publishStack()
        requestThumbnailRefresh()
        onEdited()
        requestRepaintOnGl()
        return true
    }

    /**
     * The mirror's current contents for every key either side of the edit
     * lists — `null` where there is no tile, which is exactly what
     * [PixelOp.Restore] means by a missing payload.
     */
    private fun snapshotPixels(
        before: LayerStack,
        after: LayerStack,
        touched: Set<LayerId>,
    ): Map<LayerId, Map<TileKey, ByteArray?>> {
        if (touched.isEmpty()) return emptyMap()
        val out = HashMap<LayerId, Map<TileKey, ByteArray?>>(touched.size)
        for (layerId in touched) {
            val keys = DesktopStackEdits.keysFor(before, after, layerId)
            if (keys.isEmpty()) continue
            val layerMirror = mirror[layerId]
            val tiles = HashMap<TileKey, ByteArray?>(keys.size)
            for (key in keys) tiles[key] = layerMirror?.get(key)
            out[layerId] = tiles
        }
        return out
    }

    /** Drops mirror rows for layers the model no longer carries. */
    private fun pruneMirror(current: LayerStack) {
        val live = current.layers.mapTo(HashSet()) { it.id }
        val gone = mirror.keys.filterNot { it in live }
        for (id in gone) {
            mirror.remove(id)
            readbackRevisions.remove(id)
        }
    }

    private fun publishStack() {
        val published = stack
        onStack(published)
    }

    // -------------------------------------------------------- thumbnails

    /**
     * Renders one isolated thumbnail per layer. The panel is a window of its
     * own, so it asks for these when it opens and after every edit; the
     * renderer's PBO pass answers on the GL thread and [onThumbnail] marshals
     * to the UI.
     */
    fun requestLayerThumbnails(onThumbnail: (LayerId, LayerThumbnail?) -> Unit) = post {
        val renderer = renderer ?: return@post
        thumbnailSink = onThumbnail
        renderer.requestLayerThumbnails(stack.layers.map { it.id }, onThumbnail)
    }

    /** Re-renders whatever the panel is showing, if it is showing anything. */
    private fun requestThumbnailRefresh() {
        val sink = thumbnailSink ?: return
        renderer?.requestLayerThumbnails(stack.layers.map { it.id }, sink)
    }

    fun stopLayerThumbnails() = post { thumbnailSink = null }

    private fun requireReadback(renderer: CanvasRenderer) {
        check(DesktopReadbackPolicy.drain(renderer::finishReadback) == ReadbackDrain.Complete) {
            "GPU readback timed out; the CPU mirror may be stale"
        }
    }

    private fun HistoryDirection.opposite(): HistoryDirection = when (this) {
        HistoryDirection.Undo -> HistoryDirection.Redo
        HistoryDirection.Redo -> HistoryDirection.Undo
    }

    // ------------------------------------------------------- the GL client

    override fun onGlReady() = initializeRenderer()

    /**
     * This document's turn of the shared loop: drain the thumbnail PBOs, age
     * the wet grid, and present a frame if anything asked for one.
     */
    override fun pumpGl(): Boolean {
        var worked = false
        if (thumbnailSink != null) renderer?.pollLayerThumbnails()
        if (pumpWetOverlay()) worked = true
        if (repaint.getAndSet(false)) {
            renderFrame()
            worked = true
        }
        return worked
    }

    private fun initializeRenderer() {
        val next = CanvasRenderer(canvas, budget, ClasspathEngineAssets()) { layerId, key, revision, pixels ->
            mirror.getOrPut(layerId) { HashMap() }[key] = pixels.copyOfBytes()
            readbackRevisions.getOrPut(layerId) { HashMap() }[key] = revision
        }
        check(next.onContextCreated(strict = true)) {
            DesktopGlDiagnostics.rendererRequirements
        }
        uploadInitialTiles(next)
        uploadInitialReference(next)
        next.setStack(stack)
        publishStack()
        next.setPaperColor(paperColor)
        next.setView(ViewTransform())
        renderer = next

        frameWidth = INITIAL_FRAME_WIDTH
        frameHeight = INITIAL_FRAME_HEIGHT
        allocateFrameTarget()
        next.onSurfaceChanged(frameWidth, frameHeight)

        // Publish frame one without depending on Compose's first layout callback.
        requestRepaintOnGl()
    }

    /**
     * Puts an opened painting's pixels onto its layers.
     *
     * `PixelOp.Restore` is the upload path undo already uses, so the tiles
     * land through the same transaction and the same readback sink that keeps
     * the mirror exact — which matters immediately, because the first Save
     * composes from that mirror.
     *
     * A failure here is not fatal: the document opens blank rather than not
     * at all, and the file on disk is untouched.
     */
    private fun uploadInitialTiles(renderer: CanvasRenderer) {
        val opened = initial ?: return
        val ops = opened.tiles.mapNotNull { (layerId, tiles) ->
            if (tiles.isEmpty()) null else PixelOp.Restore(layerId, tiles)
        }
        if (ops.isEmpty()) return

        // setStack first: `applyPixelOps` prepares against the published
        // model, and `targetFor` needs the layers to fill.
        renderer.setStack(stack)
        if (!renderer.applyPixelOps(ops, nextRevision(), SandwichPolicy.Op.UndoRedo)) {
            GlLog.w(LOG_TAG, "the opened painting's pixels could not be uploaded")
        }
    }

    /**
     * Places an opened painting's tracing image, if it had one.
     *
     * Here rather than posted from the caller: `renderer` is assigned at the
     * end of [initializeRenderer], so a task posted right after `start()`
     * finds it null and drops the upload without a sound — and a cold start,
     * where the GL host is still coming up, is exactly when that happens.
     * Applying it on this path makes the ordering structural instead.
     */
    private fun uploadInitialReference(renderer: CanvasRenderer) {
        val opened = initial ?: return
        val reference = opened.reference ?: return
        val tiles = opened.referenceTiles ?: return

        renderer.setTracingReference(reference)
        if (renderer.isReady) {
            renderer.uploadReferenceTiles(reference.assetName, tiles.toList())
        } else {
            GlLog.w(LOG_TAG, "the opened painting's tracing image could not be uploaded")
        }
    }

    /**
     * Wet paint expires on a clock, not on input. Without this tick a
     * watercolor stroke stays wet forever on an idle canvas — the shell only
     * ever redrew in response to something the user did. 100 ms is the
     * presentation refresh AGENTS.md pins for reclaiming expired pages, and
     * the loop's idle sleep gives it the granularity.
     */
    private fun pumpWetOverlay(): Boolean {
        val renderer = renderer ?: return false
        if (!renderer.hasWatercolorOverlay()) return false

        val now = System.nanoTime()
        if (now < nextWetRefreshNs) return false
        nextWetRefreshNs = now + WET_REFRESH_INTERVAL_NS

        if (renderer.refreshWatercolorOverlay().dirty.isEmpty) return false
        requestRepaintOnGl()
        return true
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

    /** This document's GL resources; the context belongs to the host. */
    override fun releaseGl() {
        renderer?.release()
        renderer = null
        releaseFrameTarget()
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
        private const val LOG_TAG = "BangniDraw"
        private const val EXPORT_THREAD_NAME = "BangniDraw-Export"
        private const val FILL_THREAD_NAME = "BangniDraw-Fill"
        private const val EXPORT_SHUTDOWN_TIMEOUT_MS = 5_000L
        private const val INITIAL_FRAME_WIDTH = 1280
        private const val INITIAL_FRAME_HEIGHT = 800
        private const val WET_REFRESH_INTERVAL_NS = 100_000_000L
        private const val RGBA_CHANNELS = 4
        private const val DEFAULT_PAPER_ARGB = 0xFFFFFFFF.toInt()

        /**
         * One empty layer, as a new painting opens (`05-layers.md` §1). Its
         * name follows the generated grammar rather than being literal
         * English, so [DesktopLayerNames] resolves it exactly as it resolves
         * every later "Layer N".
         */
        private val INITIAL_STACK = LayerStack(
            listOf(Layer(LayerProps(LayerId("layer-1"), LayerStack.defaultName(1)))),
            activeIndex = 0,
            nextName = 2,
        )

        private val IDENTITY_TRANSFORM = FloatArray(16).also {
            java.util.Arrays.fill(it, 0f)
            it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
        }
    }
}
