package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import ch.lkmc.bangnidraw.engine.core.ColorSample
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.engine.core.TileGrid
import java.nio.ByteBuffer
import kotlin.math.floor

/** Synchronous, single-pixel GL readback for the eyedropper. GL-thread-only. */
class PixelReadback(
    private val grid: TileGrid,
    private val fbo: GlFbo,
) {

    private val pixel = ByteBuffer.allocateDirect(CHANNELS)

    fun sampleComposite(
        target: OffscreenTarget,
        screen: ScreenTransform,
        canvasX: Float,
        canvasY: Float,
        radius: Int,
    ): Int? {
        if (!target.isAllocated || !fbo.bindTexture2d(target.texture)) return null

        val totals = Totals()
        if (radius == 0) {
            readCompositePoint(target, screen, canvasX, canvasY, totals)
            return totals.color()
        }

        forEachCanvasPixel(canvasX, canvasY, radius) { x, y ->
            readCompositePoint(target, screen, x + PIXEL_CENTER, y + PIXEL_CENTER, totals)
        }
        return totals.color()
    }

    fun sampleLayer(
        textures: LayerTextures?,
        canvasX: Float,
        canvasY: Float,
        radius: Int,
    ): Int? {
        val totals = Totals()
        forEachCanvasPixel(canvasX, canvasY, radius) { x, y ->
            val layer = textures
            if (layer == null) {
                return@forEachCanvasPixel
            }
            val key = grid.keyAt(x, y)
            val handle = layer.slice(key)
            if (handle.isNone) {
                return@forEachCanvasPixel
            }
            if (!fbo.bindArrayLayer(layer.pageTexture(handle.page), handle.slice)) {
                return@forEachCanvasPixel
            }

            read(x % TILE_SIZE, y % TILE_SIZE, totals)
        }
        return totals.color()
    }

    private fun readCompositePoint(
        target: OffscreenTarget,
        screen: ScreenTransform,
        canvasX: Float,
        canvasY: Float,
        totals: Totals,
    ) {
        val sx = floor(screen.screenX(canvasX, canvasY)).toInt()
        val sy = floor(screen.screenY(canvasX, canvasY)).toInt()
        if (sx !in 0 until target.width || sy !in 0 until target.height) return

        read(sx, target.height - 1 - sy, totals)
    }

    private inline fun forEachCanvasPixel(
        canvasX: Float,
        canvasY: Float,
        radius: Int,
        block: (Int, Int) -> Unit,
    ) {
        val centerX = floor(canvasX).toInt()
        val centerY = floor(canvasY).toInt()
        for (y in centerY - radius..centerY + radius) {
            if (y !in 0 until grid.height) continue
            for (x in centerX - radius..centerX + radius) {
                if (x !in 0 until grid.width) continue
                block(x, y)
            }
        }
    }

    private fun read(x: Int, y: Int, totals: Totals) {
        pixel.clear()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(x, y, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixel)
        totals.red += pixel.get(0).toUByte().toLong()
        totals.green += pixel.get(1).toUByte().toLong()
        totals.blue += pixel.get(2).toUByte().toLong()
        totals.alpha += pixel.get(3).toUByte().toLong()
    }

    private class Totals {
        var red = 0L
        var green = 0L
        var blue = 0L
        var alpha = 0L
        fun color(): Int? = ColorSample.opaqueArgb(red, green, blue, alpha)
    }

    private companion object {
        const val CHANNELS = 4
        const val PIXEL_CENTER = 0.5f
    }
}
