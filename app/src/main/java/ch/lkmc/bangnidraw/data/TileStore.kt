package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.File
import java.io.IOException

/**
 * One layer's tiles on disk: `layers/<layerId>/<tx>_<ty>.tile`
 * (`docs/plan/06-document-and-persistence.md` §2, §4).
 *
 * Scoped to a single layer directory and built on a plain [File] so the JVM
 * suite can run it against a temp dir (`docs/plan/11-testing.md` §5); the
 * per-layer instances are cheap and stateless, so callers make one per layer
 * rather than passing layer ids into every method.
 *
 * The sparseness invariant: **an absent file is an empty tile.** Writing an
 * all-transparent tile deletes the file, so "tiles exist only where something
 * was painted" holds on disk as well as in memory, and erasing reclaims disk.
 */
class TileStore(private val layerDir: File) {

    sealed interface Read {
        data class Pixels(val pixels: ByteArray) : Read {
            override fun equals(other: Any?): Boolean =
                other is Pixels && pixels.contentEquals(other.pixels)

            override fun hashCode(): Int = pixels.contentHashCode()
        }

        /** No file — a tile that was never painted, or was erased to nothing. */
        data object Empty : Read

        /**
         * A file exists but fails [TileCodec] validation. The layer shows the
         * tile transparent and the caller counts it among the unreadable
         * (06 §4); the file is deliberately left on disk — never rewritten by
         * a loader — so a future reader with a fix could still recover it.
         */
        data object Corrupt : Read
    }

    /**
     * Writes one tile atomically, or deletes its file when [pixels] is all
     * zero. Throws [IOException] on a failed write — `TileFlusher` owns
     * turning that into the storage-full state (06 §6.3).
     */
    @Throws(IOException::class)
    fun write(key: TileKey, pixels: ByteArray) {
        val target = file(key)
        if (TileCodec.isAllZero(pixels)) {
            target.delete()
            return
        }
        if (!layerDir.isDirectory && !layerDir.mkdirs()) {
            throw IOException("could not create $layerDir")
        }
        AtomicFiles.write(target, TileCodec.encode(pixels))
    }

    /** Reads one tile; never throws for content reasons — see [Read]. */
    fun read(key: TileKey): Read {
        val target = file(key)
        if (!target.isFile) return Read.Empty
        val bytes = try {
            target.readBytes()
        } catch (_: IOException) {
            return Read.Corrupt
        }
        return when (val decoded = TileCodec.decode(bytes)) {
            is TileCodec.Decoded.Ok -> Read.Pixels(decoded.pixels)
            TileCodec.Decoded.Corrupt -> Read.Corrupt
        }
    }

    /**
     * The keys that exist on disk, from the directory listing alone — no
     * decoding, so a corrupt tile is still *listed* and only found corrupt
     * when read. A file whose name does not parse as `<tx>_<ty>.tile` is
     * ignored (06 §2); in-flight `.tmp` files are ignored the same way.
     */
    fun list(): List<TileKey> {
        val children = layerDir.listFiles() ?: return emptyList()
        return children.mapNotNull { parseName(it.name) }
    }

    private fun file(key: TileKey): File = File(layerDir, fileName(key))

    companion object {
        private const val SUFFIX = ".tile"

        /** `<tx>_<ty>.tile`, non-negative decimal coordinates (06 §2). */
        fun fileName(key: TileKey): String = "${key.tx}_${key.ty}$SUFFIX"

        /** The inverse of [fileName], or null for any name it never produces. */
        fun parseName(name: String): TileKey? {
            if (!name.endsWith(SUFFIX)) return null
            val stem = name.removeSuffix(SUFFIX)
            val sep = stem.indexOf('_')
            if (sep <= 0 || sep == stem.length - 1) return null
            val tx = parseCoordinate(stem.substring(0, sep)) ?: return null
            val ty = parseCoordinate(stem.substring(sep + 1)) ?: return null
            return TileKey(tx, ty)
        }

        /**
         * A strict non-negative decimal: no sign, no leading zeros past one
         * digit, within [TileKey]'s 16-bit coordinate range. Strictness is
         * not pedantry — `TileKey` wraps out-of-range coordinates, so "007"
         * and "7" naming the same key would let two files alias one tile.
         */
        private fun parseCoordinate(text: String): Int? {
            if (text.isEmpty() || text.length > 5) return null
            if (text.length > 1 && text[0] == '0') return null
            var value = 0
            for (c in text) {
                if (c !in '0'..'9') return null
                value = value * 10 + (c - '0')
            }
            return if (value <= 0xFFFF) value else null
        }
    }
}
