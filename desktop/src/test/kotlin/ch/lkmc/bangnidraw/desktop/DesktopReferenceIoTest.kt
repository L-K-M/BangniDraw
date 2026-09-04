package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.ReferenceTransform
import ch.lkmc.bangnidraw.engine.core.TracingReference
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `.bangni` reference path takes its pixels from a document someone
 * handed the user, so the size it will allocate has to be settled from the
 * header rather than discovered after `ImageIO` has already allocated it.
 */
class DesktopReferenceIoTest {

    @Test
    fun `a stored reference of the recorded size decodes to tiles`() {
        val png = png(320, 288)

        val tiles = DesktopReferenceIo.tiles(png, reference(320, 288))

        assertNotNull(tiles)
        // 320x288 spans a 2x2 grid of 256 px tiles; the opaque ones are kept.
        assertEquals(4, tiles.size)
    }

    @Test
    fun `a stored reference whose pixels disagree with the record is refused`() {
        assertNull(DesktopReferenceIo.tiles(png(320, 288), reference(288, 320)))
    }

    @Test
    fun `a declared size outside the tile grid's range is refused`() {
        assertNull(
            DesktopReferenceIo.tiles(pngHeaderOnly(60_000, 60_000), reference(60_000, 60_000)),
        )
    }

    /**
     * That the size is settled *before* the raster is allocated, which no
     * return value can show: `ImageIO.read` sizes its buffer from the
     * declared dimensions, so a few hundred bytes of header asking for
     * 60000x60000 is a 14 GB allocation — and a guard that ran afterwards
     * would return exactly the same null, having already thrown
     * `OutOfMemoryError` on the way. Pinned as source order, the way this
     * repo pins its other invisible orderings.
     */
    @Test
    fun `the header is read before the raster is decoded`() {
        val body = section(
            "desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopReferenceIo.kt",
            "fun tiles(png: ByteArray",
            "private fun headerSize(",
        )

        val header = body.indexOf("headerSize(png)")
        val decode = body.indexOf("ImageIO.read")
        if (header < 0) fail("tiles() no longer asks for the header size")
        if (decode < 0) fail("tiles() no longer decodes the raster")
        assertTrue(header < decode, "the raster is decoded before the header bounds it")
    }

    private fun section(path: String, start: String, end: String): String {
        val source = java.io.File(repositoryRoot(), path).readText()
        val from = source.indexOf(start)
        if (from < 0) fail("missing source marker: $start")

        val to = source.indexOf(end, from)
        if (to <= from) fail("missing source marker: $end")

        return source.substring(from, to)
    }

    private fun repositoryRoot(): java.io.File {
        val start = java.io.File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(start) { it.parentFile }
            .firstOrNull { java.io.File(it, "settings.gradle.kts").isFile }
            ?: fail("repository root not found above $start")
    }

    @Test
    fun `a reference smaller than a tile grid allows is refused, not thrown`() {
        assertNull(DesktopReferenceIo.tiles(png(64, 64), reference(64, 64)))
    }

    @Test
    fun `bytes that are not an image at all are refused`() {
        assertNull(DesktopReferenceIo.tiles("not a png".toByteArray(), reference(320, 288)))
    }

    private fun reference(width: Int, height: Int) = TracingReference(
        assetName = "reference.png",
        imageWidth = width,
        imageHeight = height,
        transform = ReferenceTransform.IDENTITY,
    )

    /** A real opaque PNG, so the success cases decode through ImageIO. */
    private fun png(width: Int, height: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                raw.write(byteArrayOf((x and 0xFF).toByte(), (y and 0xFF).toByte(), 0x40, 0xFF.toByte()))
            }
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()

        return signature() + chunk("IHDR", ihdr(width, height)) +
            chunk("IDAT", compressed.toByteArray()) + chunk("IEND", ByteArray(0))
    }

    /** A structurally valid header with no raster behind it. */
    private fun pngHeaderOnly(width: Int, height: Int): ByteArray =
        signature() + chunk("IHDR", ihdr(width, height)) + chunk("IEND", ByteArray(0))

    private fun signature() =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun ihdr(width: Int, height: Int): ByteArray =
        be(width) + be(height) + byteArrayOf(8, 6, 0, 0, 0)

    private fun chunk(tag: String, data: ByteArray): ByteArray {
        val body = tag.toByteArray(Charsets.US_ASCII) + data
        val crc = CRC32().apply { update(body) }.value.toInt()
        return be(data.size) + body + be(crc)
    }

    private fun be(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
