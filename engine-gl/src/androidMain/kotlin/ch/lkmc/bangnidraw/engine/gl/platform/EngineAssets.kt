package ch.lkmc.bangnidraw.engine.gl.platform

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The Android actual: the app's merged assets, exactly as before the seam
 * existed. The Bitmap-backed [DecodedPng] preserves the previous decode
 * options verbatim — `inPremultiplied = false`, `inScaled = false`,
 * `ARGB_8888` — including `copyPixelsToBuffer`'s native row order, which
 * modern Skia stores R,G,B,A exactly like GL (AGENTS.md,
 * `BitmapLayoutProbe`).
 */
class AssetManagerEngineAssets(
    private val assets: AssetManager,
) : EngineAssets {

    override fun readText(path: String): String? = try {
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: IOException) {
        null
    }

    override fun decodeRgbaPng(path: String): DecodedPng? {
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try {
            assets.open(path).use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (_: IOException) {
            null
        } ?: return null

        return BitmapPng(bitmap)
    }

    private class BitmapPng(private val bitmap: Bitmap) : DecodedPng {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
        override val rowBytes: Int get() = bitmap.rowBytes
        override val premultiplied: Boolean get() = bitmap.isPremultiplied

        override fun argbAt(x: Int, y: Int): Int = bitmap.getPixel(x, y)

        override fun copyRgbaInto(out: ByteBuffer) {
            bitmap.copyPixelsToBuffer(out)
        }
    }
}
