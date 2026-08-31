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
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
import ch.lkmc.bangnidraw.engine.gl.CanvasRenderer
import ch.lkmc.bangnidraw.engine.gl.platform.ClasspathEngineAssets
import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
class DesktopEngine(
    val canvas: CanvasSize,
    memory: DeviceMemory,
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
    }

    private val budget = MemoryBudget.compute(memory, canvas)
    private val dabRing = DabRing()
    private val revisionCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val started = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val glThread = Thread(::runGlLoop, "BangniDraw-GL").apply { isDaemon = true }
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val repaint = AtomicBoolean(false)

    /** The in-memory tile mirror the renderer's readback keeps current. */
    private val mirror = HashMap<LayerId, HashMap<TileKey, ByteArray>>()

    private var context: GlfwEsContext? = null
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
    private val undoJournal = ArrayDeque<UndoEntry>()
    private var undoCursor = 0

    val isStarted: Boolean get() = started.get()

    // ------------------------------------------------------------- control

    fun start() {
        if (!started.getAndSet(true)) glThread.start()
    }

    fun stop() {
        tasks.add {
            releaseGl()
        }
        glThread.interrupt()
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
        renderer?.cancelStroke()
        requestRepaintOnGl()
    }

    /**
     * Merges the stroke, then drains this stroke's readback to completion
     * before journaling — the serialization that makes the mirror (and
     * therefore the undo journal) exact on a desktop with one input
     * stream. [onCommitted] runs on the GL thread, not the caller.s.
     */
    fun endStroke(opacityCeiling: Float, onCommitted: () -> Unit) = post {
        val renderer = renderer ?: return@post
        var entry: UndoEntry? = null
        val merged = renderer.endStroke(
            revision = nextRevision(),
            opacityCeiling = opacityCeiling,
        ) { spec, keys ->
            // Pre-merge state: everything but this stroke's own tiles.
            val layerMirror = mirror[spec.layerId]
            val before = HashMap<TileKey, ByteArray?>(keys.size)
            for (key in keys) before[key] = layerMirror?.get(key)?.copyOf()
            entry = UndoEntry(spec.layerId, keys.toList(), before)
        }
        if (merged > 0) {
            renderer.finishReadback()
            entry?.let { committed ->
                val layerMirror = mirror.getValue(committed.layerId)
                val after = HashMap<TileKey, ByteArray?>(committed.keys.size)
                for (key in committed.keys) after[key] = layerMirror[key]?.copyOf()
                committed.after = after
                journal(committed)
            }
            onCommitted()
        }
        requestRepaintOnGl()
    }

    private fun journal(entry: UndoEntry) {
        while (undoJournal.size > undoCursor) undoJournal.removeLast()
        undoJournal.addLast(entry)
        if (undoJournal.size > MAX_UNDO) undoJournal.removeFirst()
        undoCursor = undoJournal.size
    }

    fun canUndo(): Boolean = undoCursor > 0
    fun canRedo(): Boolean = undoCursor < undoJournal.size

    /**
     * Saves the painting as a PNG under `~/Pictures/BangniDraw`, composed
     * from the readback mirror (the mirror is exact: every commit drains
     * its readback to completion first). [onSaved] reports the path on the
     * GL thread, not the caller's.
     */
    fun savePng(onSaved: (String) -> Unit) = post {
        val renderer = renderer ?: return@post
        renderer.finishReadback()

        val paper = DEFAULT_PAPER_ARGB
        val width = canvas.width
        val height = canvas.height
        val image = java.awt.image.BufferedImage(
            width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB,
        )

        val layerMirror = mirror[stack.layers[stack.activeIndex].id].orEmpty()
        for ((key, bytes) in layerMirror) {
            if (bytes.size != TILE_BYTES) continue
            val rect = ch.lkmc.bangnidraw.engine.core.TileGrid(width, height).tileRect(key)
            if (rect.isEmpty) continue

            for (row in 0 until rect.height) {
                for (column in 0 until rect.width) {
                    val o = (row * TILE_EDGE + column) * RGBA_CHANNELS
                    val r = bytes[o].toInt() and 0xFF
                    val g = bytes[o + 1].toInt() and 0xFF
                    val b = bytes[o + 2].toInt() and 0xFF
                    val a = bytes[o + 3].toInt() and 0xFF
                    image.setRGB(
                        rect.left + column, rect.top + row,
                        (a shl 24) or (r shl 16) or (g shl 8) or b,
                    )
                }
            }
        }

        // Source the paper under the premultiplied layer pixels.
        val composed = java.awt.image.BufferedImage(
            width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB,
        )
        for (y in 0 until height) {
            for (x in 0 until width) {
                composed.setRGB(x, y, sourceOver(image.getRGB(x, y), paper))
            }
        }

        val dir = DesktopPlatform.picturesDir()
        val file = java.io.File(dir, "BangniDraw-${System.currentTimeMillis()}.png")
        javax.imageio.ImageIO.write(composed, "png", file)
        onSaved(file.absolutePath)
    }

    private fun sourceOver(premultipliedTile: Int, opaquePaper: Int): Int {
        val a = (premultipliedTile ushr 24) and 0xFF
        if (a == 0xFF) return premultipliedTile
        if (a == 0) return opaquePaper

        // Mirror tiles are premultiplied RGBA; over an opaque paper the
        // result is straight-alpha, exactly what a PNG wants.
        val inv = 255 - a
        val r = (premultipliedTile ushr 16 and 0xFF) + (opaquePaper ushr 16 and 0xFF) * inv / 255
        val g = (premultipliedTile ushr 8 and 0xFF) + (opaquePaper ushr 8 and 0xFF) * inv / 255
        val b = (premultipliedTile and 0xFF) + (opaquePaper and 0xFF) * inv / 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun undo() = post { applyHistory(-1) }
    fun redo() = post { applyHistory(+1) }

    private fun applyHistory(direction: Int) {
        val renderer = renderer ?: return
        val next = undoCursor + direction
        if (next !in 0..undoJournal.size) return
        val entry = if (direction < 0) undoJournal[next - 1] else undoJournal[next - 1]
        val images = if (direction < 0) entry.before else entry.after
        val textures = renderer.textures(entry.layerId) ?: return

        for (key in entry.keys) {
            val pixels = images[key]
            if (pixels != null) {
                textures.upload(key, ByteBuffer.wrap(pixels))
            } else {
                textures.upload(key, ByteBuffer.wrap(ByteArray(TILE_BYTES)))
            }
        }
        undoCursor = next
        renderer.invalidate(SandwichPolicy.Op.UndoRedo)
        requestRepaintOnGl()
    }

    // ------------------------------------------------------------ the loop

    private fun runGlLoop() {
        val gl = GlfwEsContext.create(INITIAL_FRAME_WIDTH, INITIAL_FRAME_HEIGHT)
        if (gl == null) {
            failed.set(true)
            onFatal(
                "No OpenGL ES 3.0 context is available.\n" +
                    "Linux: install Mesa (libEGL/libGLESv2) or the vendor driver.\n" +
                    "macOS: place ANGLE's dylibs beside the app or on the library path\n" +
                    "(see the README's desktop section).",
            )
            return
        }
        context = gl

        val r = CanvasRenderer(canvas, budget, ClasspathEngineAssets()) { layerId, key, _, pixels ->
            // GL thread; the readback mirror this callback feeds is what
            // the undo journal snapshots from.
            mirror.getOrPut(layerId) { HashMap() }[key] = pixels.copyOfBytes()
        }
        val ready = r.onContextCreated(strict = true)
        if (!ready) {
            failed.set(true)
            onFatal("The GL context does not meet the engine's minimum (ES 3.0 with texture arrays).")
            return
        }
        r.setStack(stack)
        r.setPaperColor(DEFAULT_PAPER_ARGB)
        r.setView(ViewTransform())
        renderer = r

        frameWidth = INITIAL_FRAME_WIDTH
        frameHeight = INITIAL_FRAME_HEIGHT
        allocateFrameTarget()
        r.onSurfaceChanged(frameWidth, frameHeight)

        try {
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
        } catch (_: InterruptedException) {
            // stop() — fall through to release.
        }
        releaseGl()
    }

    private fun renderFrame() {
        val r = renderer ?: return
        val w = frameWidth
        val h = frameHeight
        if (w <= 0 || h <= 0) return

        r.drawFrame(frameFbo, w, h, IDENTITY_TRANSFORM)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameFbo)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        val pixels = framePixels ?: return
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(pixels))
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // glReadPixels is bottom-up; flip once into the published copy so
        // Compose receives top-down rows.
        val flipped = ByteArray(pixels.size)
        val rowBytes = w * RGBA_CHANNELS
        for (y in 0 until h) {
            val from = (h - 1 - y) * rowBytes
            val to = y * rowBytes
            System.arraycopy(pixels, from, flipped, to, rowBytes)
        }
        onFrame(Frame(w, h, flipped))
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

    private fun drainReadback(renderer: CanvasRenderer) {
        renderer.finishReadback()
    }

    private fun releaseGl() {
        renderer?.release()
        renderer = null
        releaseFrameTarget()
        context?.destroy()
        context = null
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
        private const val TAG = "DesktopEngine"
        private const val INITIAL_FRAME_WIDTH = 1280
        private const val INITIAL_FRAME_HEIGHT = 800
        private const val IDLE_SLEEP_MS = 4L
        private const val READBACK_POLL_MS = 2L
        private const val READBACK_TIMEOUT_MS = 2_000L
        private const val MAX_UNDO = 100
        private const val RGBA_CHANNELS = 4
        private const val TILE_EDGE = 256
        private const val TILE_BYTES = TILE_EDGE * TILE_EDGE * RGBA_CHANNELS
        private const val DEFAULT_PAPER_ARGB = 0xFFFFFFFF.toInt()

        private val IDENTITY_TRANSFORM = FloatArray(16).also {
            java.util.Arrays.fill(it, 0f)
            it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
        }
    }
}
