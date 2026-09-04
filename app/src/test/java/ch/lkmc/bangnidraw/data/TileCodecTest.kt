package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.data.shared.TileCodec
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `docs/plan/06-document-and-persistence.md` §4's codec, on the JVM. */
class TileCodecTest {

    private fun randomTile(seed: Int = 7): ByteArray =
        Random(seed).nextBytes(TILE_BYTES)

    /** Compressible content, so the deflate path (not the raw fallback) is exercised. */
    private fun flatTile(value: Byte = 0x42): ByteArray =
        ByteArray(TILE_BYTES) { value }

    @Test
    fun `a compressible tile round-trips through deflate`() {
        val pixels = flatTile()
        val encoded = TileCodec.encode(pixels)
        assertTrue(encoded.size < TILE_BYTES / 2, "flat content should deflate hard")
        assertEquals(1, encoded[10].toInt(), "compression byte should say deflate")
        val decoded = assertIs<TileCodec.Decoded.Ok>(TileCodec.decode(encoded))
        assertTrue(decoded.pixels.contentEquals(pixels))
    }

    @Test
    fun `random content round-trips byte-equal`() {
        // Random bytes barely compress; whichever branch encode picks, the
        // decode must return exactly the premultiplied bytes it was given —
        // "the codec never converts" is the §4 claim under test.
        val pixels = randomTile()
        val decoded = assertIs<TileCodec.Decoded.Ok>(TileCodec.decode(TileCodec.encode(pixels)))
        assertTrue(decoded.pixels.contentEquals(pixels))
    }

    @Test
    fun `a wrong-sized buffer is refused at encode`() {
        assertFailsWith<IllegalArgumentException> { TileCodec.encode(ByteArray(TILE_BYTES - 1)) }
    }

    @Test
    fun `truncation, bad magic, a future version and a lying length all decode as Corrupt`() {
        val good = TileCodec.encode(flatTile())

        assertIs<TileCodec.Decoded.Corrupt>(TileCodec.decode(ByteArray(4)), "shorter than a header")
        assertIs<TileCodec.Decoded.Corrupt>(
            TileCodec.decode(good.copyOfRange(0, good.size - 1)),
            "truncated deflate stream",
        )

        val badMagic = good.copyOf().also { it[0] = 'X'.code.toByte() }
        assertIs<TileCodec.Decoded.Corrupt>(TileCodec.decode(badMagic))

        val futureVersion = good.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putShort(4, 2)
        }
        assertIs<TileCodec.Decoded.Corrupt>(TileCodec.decode(futureVersion))

        val lyingLength = good.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(12, TILE_BYTES - 4)
        }
        assertIs<TileCodec.Decoded.Corrupt>(TileCodec.decode(lyingLength), "w·h·4 != length")

        val wrongSize = good.copyOf().also {
            // 128×128 would be internally consistent at 65 536 bytes, but v1's
            // pool holds 256² slices; the header field buys a future change,
            // not this reader.
            val bb = ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN)
            bb.putShort(6, 128)
            bb.putShort(8, 128)
            bb.putInt(12, 128 * 128 * 4)
        }
        assertIs<TileCodec.Decoded.Corrupt>(TileCodec.decode(wrongSize))
    }

    @Test
    fun `isAllZero detects the empty tile and one lone byte defeats it`() {
        val zeros = ByteArray(TILE_BYTES)
        assertTrue(TileCodec.isAllZero(zeros))
        zeros[TILE_BYTES - 1] = 1
        assertTrue(!TileCodec.isAllZero(zeros))
    }
}
