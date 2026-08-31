package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.FitTransform
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerThumbnail
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import java.nio.ByteBuffer

/** Renders isolated layers into one small target and reads them through two PBOs. */
internal class LayerThumbnailPass(
    canvas: CanvasSize,
    private val state: GlState,
    private val composite: CompositePass,
) {
    enum class EnqueueResult {
        STARTED,
        BUSY,
        FAILED,
    }

    private class Chunk {
        val pbo = IntArray(1)
        var fence = 0L
        var layer: LayerId? = null
        var callback: ((LayerId, LayerThumbnail?) -> Unit)? = null

        val inFlight: Boolean get() = fence != 0L
    }

    private val target = OffscreenTarget("LayerThumbnail")
    private val fbo = GlFbo()
    private val chunks = Array(PBO_COUNT) { Chunk() }
    private val projection = FloatArray(Mat4.SIZE)
    private val identity = Mat4.identity()
    private val canvasRect = IntRect(0, 0, canvas.width, canvas.height)
    private val width: Int
    private val height: Int
    private val screen: ScreenTransform
    private var built = false

    init {
        val size = LayerThumbnail.size(canvas)
        width = size.first
        height = size.second
        val fit = FitTransform(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            imageWidth = canvas.width.toFloat(),
            imageHeight = canvas.height.toFloat(),
        )
        screen = ScreenTransform.of(fit, ViewTransform())
        Mat4.orthoYDown(width.toFloat(), height.toFloat(), projection)
    }

    val pending: Int get() = chunks.count(Chunk::inFlight)

    fun enqueue(
        layer: LayerId,
        textures: LayerTextures,
        opacity: Float,
        callback: (LayerId, LayerThumbnail?) -> Unit,
    ): EnqueueResult {
        val chunk = chunks.firstOrNull { !it.inFlight } ?: return EnqueueResult.BUSY
        if (!ensureBuilt() || !target.ensure(width, height, state) ||
            !fbo.bindTexture2d(target.texture)
        ) {
            callback(layer, null)
            return EnqueueResult.FAILED
        }

        state.viewport(0, 0, width, height)
        state.scissorOff()
        fbo.clear(0f, 0f, 0f, 0f)
        composite.draw(
            textures = textures,
            mode = BlendMode.NORMAL,
            opacity = opacity,
            screen = screen,
            projection = projection,
            bufferTransform = identity,
            dirtyRect = canvasRect,
            backdrop = 0,
        )

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, chunk.pbo[0])
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(
            0, 0, width, height,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0,
        )
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (fence == 0L) {
            callback(layer, null)
            return EnqueueResult.FAILED
        }

        chunk.layer = layer
        chunk.callback = callback
        chunk.fence = fence
        return EnqueueResult.STARTED
    }

    fun poll() {
        for (chunk in chunks) {
            if (!chunk.inFlight) continue

            val status = GLES30.glClientWaitSync(chunk.fence, 0, 0L)
            when (status) {
                GLES30.GL_TIMEOUT_EXPIRED -> continue
                GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> finish(chunk)
                else -> fail(chunk)
            }
        }
    }

    private fun finish(chunk: Chunk) {
        val layer = chunk.layer ?: return fail(chunk)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, chunk.pbo[0])
        val bytes = width * height * CHANNELS
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER,
            0,
            bytes,
            GLES30.GL_MAP_READ_BIT,
        ) as? ByteBuffer
        val thumbnail = mapped?.let { LayerThumbnail.fromBottomUpRgba(width, height, it) }
        if (mapped != null) GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        val callback = chunk.callback
        clear(chunk)
        callback?.invoke(layer, thumbnail)
    }

    private fun fail(chunk: Chunk) {
        val layer = chunk.layer
        val callback = chunk.callback
        clear(chunk)
        if (layer != null) callback?.invoke(layer, null)
    }

    private fun clear(chunk: Chunk) {
        if (chunk.fence != 0L) GLES30.glDeleteSync(chunk.fence)
        chunk.fence = 0L
        chunk.layer = null
        chunk.callback = null
    }

    private fun ensureBuilt(): Boolean {
        if (built) return true
        val bytes = width * height * CHANNELS
        for (chunk in chunks) {
            val error = GlErrors.checkAllocation("layer thumbnail PBO") {
                GLES30.glGenBuffers(1, chunk.pbo, 0)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, chunk.pbo[0])
                GLES30.glBufferData(
                    GLES30.GL_PIXEL_PACK_BUFFER,
                    bytes,
                    null,
                    GLES30.GL_STREAM_READ,
                )
            }
            if (error != GLES30.GL_NO_ERROR) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                releaseBuffers()
                return false
            }
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        built = true
        return true
    }

    fun forgetAll() {
        for (chunk in chunks) {
            val layer = chunk.layer
            val callback = chunk.callback
            chunk.fence = 0L
            chunk.pbo[0] = 0
            chunk.layer = null
            chunk.callback = null
            if (layer != null) callback?.invoke(layer, null)
        }
        built = false
    }

    fun release() {
        for (chunk in chunks) {
            if (chunk.inFlight) fail(chunk)
        }
        releaseBuffers()
        target.release(state)
        fbo.release()
    }

    private fun releaseBuffers() {
        for (chunk in chunks) {
            if (chunk.pbo[0] == 0) continue
            GLES30.glDeleteBuffers(1, chunk.pbo, 0)
            chunk.pbo[0] = 0
        }
        built = false
    }

    private companion object {
        const val PBO_COUNT = 2
        const val CHANNELS = 4
    }
}
