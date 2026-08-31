package ch.lkmc.bangnidraw.engine.mixbox

import ch.lkmc.bangnidraw.engine.gl.GlErrors
import ch.lkmc.bangnidraw.engine.gl.platform.EngineAssets
import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Loads the unmodified licensed shader from the Mixbox-only source set. */
object MixboxShaderSource {
    fun load(assets: EngineAssets): String =
        assets.readText(GLSL_ASSET) ?: error("Mixbox shader asset missing: $GLSL_ASSET")

    private const val GLSL_ASSET = "mixbox/mixbox.glsl"
}

/** Uploads Mixbox's latent-data LUT without color or alpha conversion. */
object MixboxLut {
    fun upload(assets: EngineAssets): Int {
        val png = assets.decodeRgbaPng(LUT_ASSET) ?: error("Mixbox LUT asset missing: $LUT_ASSET")

        require(png.width == LUT_EDGE && png.height == LUT_EDGE) {
            "Mixbox LUT must be ${LUT_EDGE}x$LUT_EDGE, was ${png.width}x${png.height}"
        }
        require(!png.premultiplied) { "Mixbox LUT alpha is data and must not be premultiplied" }
        require(png.rowBytes == LUT_ROW_BYTES) { "Mixbox LUT rows must be tightly packed" }
        require(png.argbAt(0, 0) == PROBE_ARGB) { "Mixbox LUT probe pixel is corrupt" }

        return upload(png)
    }

    private fun upload(png: ch.lkmc.bangnidraw.engine.gl.platform.DecodedPng): Int {
        val pixels = ByteBuffer.allocateDirect(LUT_BYTES).order(ByteOrder.nativeOrder())
        png.copyRgbaInto(pixels)
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
