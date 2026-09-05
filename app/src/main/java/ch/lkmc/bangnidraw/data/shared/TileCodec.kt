package ch.lkmc.bangnidraw.data.shared

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The one codec for `.tile` files, journal payloads and duplicates
 * (`docs/plan/06-document-and-persistence.md` §4).
 *
 * The payload is premultiplied RGBA8, row-major, top-left origin, no row
 * padding — exactly the bytes `glReadPixels` returns and `glTexSubImage3D`
 * consumes, so the codec never converts, only compresses.
 *
 * Layout, big-endian:
 *
 * | offset | size | field |
 * | --- | --- | --- |
 * | 0 | 4 | magic `"BNDT"` |
 * | 4 | 2 | format version, = 1 |
 * | 6 | 2 | width px |
 * | 8 | 2 | height px |
 * | 10 | 1 | compression: 0 none, 1 deflate |
 * | 11 | 1 | reserved, 0 |
 * | 12 | 4 | uncompressed length |
 * | 16 | … | payload |
 */
object TileCodec {

    const val HEADER_BYTES = 16
    const val FORMAT_VERSION = 1

    private const val MAGIC = 0x424E4454 // "BNDT"
    private const val COMPRESSION_NONE = 0
    private const val COMPRESSION_DEFLATE = 1

    sealed interface Decoded {
        /** [pixels] is a fresh array of exactly [TILE_BYTES] bytes. */
        data class Ok(val pixels: ByteArray) : Decoded {
            // An array field makes the generated equals identity-based; these
            // values are compared in tests, so compare the contents.
            override fun equals(other: Any?): Boolean =
                other is Ok && pixels.contentEquals(other.pixels)

            override fun hashCode(): Int = pixels.contentHashCode()
        }

        /**
         * The file failed validation — bad magic, a version from the future,
         * an inconsistent length, a truncated or broken deflate stream. Never
         * an exception: one bad tile must not fail an open (06 §4); the caller
         * treats the tile as empty, logs, and leaves the file on disk.
         */
        data object Corrupt : Decoded
    }

    /**
     * Encodes one tile of [TILE_BYTES] premultiplied RGBA bytes.
     *
     * Deflate at [Deflater.BEST_SPEED]: a tile is written on the IO thread
     * after every stroke, so speed matters more than the last 10 % of size
     * (06 §4; the level is a codec constant, not a file-format matter). If
     * deflate does not actually shrink the payload — noise-like content —
     * the raw bytes are stored with compression 0; readers handle both.
     */
    fun encode(pixels: ByteArray): ByteArray {
        require(pixels.size == TILE_BYTES) {
            "a tile is $TILE_BYTES bytes, got ${pixels.size}"
        }
        val deflated = deflate(pixels)
        val payload = if (deflated.size < pixels.size) deflated else pixels
        val compression = if (payload === deflated) COMPRESSION_DEFLATE else COMPRESSION_NONE
        val out = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
        out.putInt(MAGIC)
        out.putShort(FORMAT_VERSION.toShort())
        out.putShort(TILE_SIZE.toShort())
        out.putShort(TILE_SIZE.toShort())
        out.put(compression.toByte())
        out.put(0)
        out.putInt(TILE_BYTES)
        out.put(payload)
        return out.array()
    }

    /**
     * Decodes one `.tile` file's bytes, validating everything §4 lists:
     * magic, version ≤ current, `w·h·4 == uncompressed length`, and that
     * inflation produces exactly that many bytes. In v1 the tile size must
     * also be the pool's [TILE_SIZE] — the header field buys a future size
     * change, but a reader can only consume tiles of the size its pool
     * allocates, so any other value is unreadable *to this version*.
     */
    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size < HEADER_BYTES) return Decoded.Corrupt
        val header = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        if (header.int != MAGIC) return Decoded.Corrupt
        val version = header.short.toInt() and 0xFFFF
        if (version < 1 || version > FORMAT_VERSION) return Decoded.Corrupt
        val width = header.short.toInt() and 0xFFFF
        val height = header.short.toInt() and 0xFFFF
        val compression = header.get().toInt() and 0xFF
        header.get() // reserved
        val uncompressed = header.int
        if (width != TILE_SIZE || height != TILE_SIZE) return Decoded.Corrupt
        if (uncompressed != width * height * 4) return Decoded.Corrupt
        val payloadLength = bytes.size - HEADER_BYTES
        return when (compression) {
            COMPRESSION_NONE -> {
                if (payloadLength != uncompressed) return Decoded.Corrupt
                Decoded.Ok(bytes.copyOfRange(HEADER_BYTES, bytes.size))
            }
            COMPRESSION_DEFLATE -> {
                val pixels = inflate(bytes, HEADER_BYTES, payloadLength, uncompressed)
                    ?: return Decoded.Corrupt
                Decoded.Ok(pixels)
            }
            else -> Decoded.Corrupt
        }
    }

    /**
     * True when every byte is zero — a fully transparent tile, which is
     * stored as an *absent file* rather than written (06 §4: erasing an area
     * back to nothing reclaims disk). A `LongBuffer` scan, 32 K comparisons
     * per tile, negligible next to deflate.
     */
    fun isAllZero(pixels: ByteArray): Boolean {
        require(pixels.size == TILE_BYTES) {
            "a tile is $TILE_BYTES bytes, got ${pixels.size}"
        }
        val longs = ByteBuffer.wrap(pixels).asLongBuffer()
        for (i in 0 until longs.limit()) {
            if (longs.get(i) != 0L) return false
        }
        return true
    }

    private fun deflate(pixels: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(pixels)
            deflater.finish()
            val out = ByteArrayOutputStream(pixels.size / 4)
            val chunk = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Inflates to exactly [expected] bytes, or null when the stream disagrees. */
    private fun inflate(bytes: ByteArray, offset: Int, length: Int, expected: Int): ByteArray? {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, offset, length)
            val out = ByteArray(expected)
            var written = 0
            while (written < expected) {
                val n = inflater.inflate(out, written, expected - written)
                if (n == 0) {
                    // finished() with bytes missing, or needsInput() on a
                    // truncated stream: either way the payload cannot fill
                    // the tile it claims to hold.
                    return null
                }
                written += n
            }
            // The stream must also *end* here: trailing pixels beyond the
            // declared length mean the header lies about what this is.
            return if (inflater.finished() && written == expected) out else null
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}
