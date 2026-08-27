package ch.lkmc.bangnidraw.engine.mixbox

import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MixboxLutTest {

    @Test
    fun `vendored assets match upstream`() {
        assertEquals(GLSL_SHA256, sha256(asset(GLSL_PATH)))
        assertEquals(LUT_SHA256, sha256(asset(LUT_PATH)))
        assertEquals(LUT_BYTES, asset(LUT_PATH).length())
    }

    @Test
    fun `LUT is an RGBA 512 square PNG`() {
        DataInputStream(asset(LUT_PATH).inputStream()).use { input ->
            val signature = ByteArray(PNG_SIGNATURE.size)
            input.readFully(signature)
            assertContentEquals(PNG_SIGNATURE, signature)
            assertEquals(IHDR_BYTES, input.readInt())
            assertEquals(IHDR, input.readInt())
            assertEquals(LUT_EDGE, input.readInt())
            assertEquals(LUT_EDGE, input.readInt())
            assertEquals(BIT_DEPTH, input.readUnsignedByte())
            assertEquals(COLOR_TYPE_RGBA, input.readUnsignedByte())
        }
    }

    @Test
    fun `LUT corner texels match upstream`() {
        val image = requireNotNull(ImageIO.read(asset(LUT_PATH))) { "Mixbox LUT did not decode" }

        assertEquals(TOP_LEFT_ARGB, image.getRGB(0, 0))
        assertEquals(TOP_RIGHT_ARGB, image.getRGB(LUT_EDGE - 1, 0))
        assertEquals(BOTTOM_LEFT_ARGB, image.getRGB(0, LUT_EDGE - 1))
        assertEquals(BOTTOM_RIGHT_ARGB, image.getRGB(LUT_EDGE - 1, LUT_EDGE - 1))
    }

    private fun asset(path: String): File {
        val direct = File(path)
        if (direct.isFile) return direct

        return File("app", path).also { require(it.isFile) { "missing Mixbox asset $path" } }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val GLSL_PATH = "src/mixbox/assets/mixbox/mixbox.glsl"
        const val LUT_PATH = "src/mixbox/assets/mixbox/mixbox_lut.png"
        const val GLSL_SHA256 = "1ca60762c730405f8df18ef08ea0501d43606a67a6d309a610a077c8781cfce4"
        const val LUT_SHA256 = "b13d7532033d96d963c7e3a854ba2b4e98b8a44d324456386e9b34e0615552be"
        const val LUT_BYTES = 176_599L
        const val LUT_EDGE = 512
        const val IHDR_BYTES = 13
        const val IHDR = 0x49484452
        const val BIT_DEPTH = 8
        const val COLOR_TYPE_RGBA = 6
        const val HASH_BUFFER_BYTES = 8 * 1024
        const val TOP_LEFT_ARGB = 0xFF7C433F.toInt()
        const val TOP_RIGHT_ARGB = 0xFF0039BE.toInt()
        const val BOTTOM_LEFT_ARGB = 0xFF701000.toInt()
        const val BOTTOM_RIGHT_ARGB = 0xFF000000.toInt()
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
