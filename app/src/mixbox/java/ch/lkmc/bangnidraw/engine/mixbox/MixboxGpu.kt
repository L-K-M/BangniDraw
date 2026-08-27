package ch.lkmc.bangnidraw.engine.mixbox

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.gl.GlErrors
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Loads the unmodified licensed shader from the Mixbox-only source set. */
object MixboxShaderSource {
    fun load(assets: AssetManager): String =
        assets.open(GLSL_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private const val GLSL_ASSET = "mixbox/mixbox.glsl"
}

/** Uploads Mixbox's latent-data LUT without color or alpha conversion. */
object MixboxLut {
    fun upload(assets: AssetManager): Int {
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = assets.open(LUT_ASSET).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input, null, options)) { "Mixbox LUT did not decode" }
        }

        try {
            require(bitmap.width == LUT_EDGE && bitmap.height == LUT_EDGE) {
                "Mixbox LUT must be ${LUT_EDGE}x$LUT_EDGE, was ${bitmap.width}x${bitmap.height}"
            }
            require(!bitmap.isPremultiplied) { "Mixbox LUT alpha is data and must not be premultiplied" }
            require(bitmap.getPixel(0, 0) == PROBE_ARGB) { "Mixbox LUT probe pixel is corrupt" }
            require(bitmap.rowBytes == LUT_ROW_BYTES) { "Mixbox LUT rows must be tightly packed" }

            return upload(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun upload(bitmap: Bitmap): Int {
        val pixels = ByteBuffer.allocateDirect(LUT_BYTES).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(pixels)
        pixels.rewind()

        val names = IntArray(1)
        val allocationError = GlErrors.checkAllocation("Mixbox LUT") {
            GLES30.glGenTextures(1, names, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, names[0])
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE,
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, 0)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA8,
                LUT_EDGE,
                LUT_EDGE,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                pixels,
            )
        }
        val texture = names[0]
        require(texture != 0) { "Mixbox LUT texture allocation returned 0" }
        if (allocationError == GLES30.GL_NO_ERROR) return texture

        GLES30.glDeleteTextures(1, names, 0)
        error("Mixbox LUT upload failed")
    }

    private const val LUT_ASSET = "mixbox/mixbox_lut.png"
    private const val LUT_EDGE = 512
    private const val CHANNELS = 4
    private const val LUT_ROW_BYTES = LUT_EDGE * CHANNELS
    private const val LUT_BYTES = LUT_EDGE * LUT_ROW_BYTES
    private const val PROBE_ARGB = 0xFF7C433F.toInt()
}
