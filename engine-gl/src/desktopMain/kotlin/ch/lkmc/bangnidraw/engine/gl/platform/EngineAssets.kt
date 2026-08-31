package ch.lkmc.bangnidraw.engine.gl.platform

import java.awt.image.BufferedImage
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/**
 * The desktop actual: classpath resources, read straight from the module's
 * resources root (which is also where `src/mixbox/assets` is wired, so
 * the vendored files have exactly one copy in the repository).
 *
 * `ImageIO`'s `TYPE_INT_ARGB` raster is straight-alpha RGBA — the exact
 * memory contract `inPremultiplied = false` + `ARGB_8888` buys on Android
 * — so [DecodedPng.copyRgbaInto] writes GL-canonical R,G,B,A bytes, the
 * row order every modern Android device's `copyPixelsToBuffer` produces.
 */
class ClasspathEngineAssets(
    private val classLoader: ClassLoader = ClasspathEngineAssets::class.java.classLoader,
) : EngineAssets {

    override fun readText(path: String): String? = try {
        classLoader.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    } catch (_: Exception) {
        null
    }

    override fun decodeRgbaPng(path: String): DecodedPng? {
        val image = try {
            classLoader.getResourceAsStream(path)?.use { ImageIO.read(it) }
        } catch (_: Exception) {
            null
        } ?: return null

        // Palette or 16-bit PNGs decode into other types; normalize so the
        // ARGB extraction below is uniform.
        val rgba = if (image.type == BufferedImage.TYPE_INT_ARGB) {
            image
        } else {
            val converted = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            val g = converted.createGraphics()
            try {
                g.drawImage(image, 0, 0, null)
            } finally {
                g.dispose()
            }
            converted
        }
        return BufferedImagePng(rgba)
    }

    private class BufferedImagePng(private val image: BufferedImage) : DecodedPng {
        override val width: Int get() = image.width
        override val height: Int get() = image.height

        // ImageIO never pads rows; the getter exists for the shared
        // tight-row assertion to ask the same question of both actuals.
        override val rowBytes: Int get() = width * RGBA_CHANNELS
        override val premultiplied: Boolean get() = false

        override fun argbAt(x: Int, y: Int): Int = image.getRGB(x, y)

        override fun copyRgbaInto(out: ByteBuffer) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val argb = image.getRGB(x, y)
                    out.put(((argb ushr RED_SHIFT) and BYTE_MASK).toByte())
                    out.put(((argb ushr GREEN_SHIFT) and BYTE_MASK).toByte())
                    out.put(((argb ushr BLUE_SHIFT) and BYTE_MASK).toByte())
                    out.put((argb and BYTE_MASK).toByte())
                }
            }
        }
    }

    private companion object {
        const val RGBA_CHANNELS = 4
        const val BYTE_MASK = 0xFF
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val BLUE_SHIFT = 0
    }
}
