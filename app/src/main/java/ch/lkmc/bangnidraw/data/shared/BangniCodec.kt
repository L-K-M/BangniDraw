package ch.lkmc.bangnidraw.data.shared

import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * The `.bangni` document format: one painting, with its layers, in one file.
 *
 * A PNG holds one layer, so a painting saved as one is flattened and cannot be
 * taken apart again. This is the format that keeps the stack — the one a phone
 * writes and a laptop opens.
 *
 * ### The container
 *
 * A ZIP, because both platforms already have `java.util.zip`, the entries are
 * self-describing, and a damaged file still yields the entries before the
 * damage. Entries:
 *
 * | entry | method | contents |
 * | --- | --- | --- |
 * | `manifest.json` | deflated | [BangniManifest] |
 * | `layers/<id>/<tx>_<ty>.tile` | stored | [TileCodec] bytes |
 * | `reference.png` | stored | the tracing image, if any |
 *
 * Tiles are **stored**, not deflated: [TileCodec] has already deflated them,
 * and deflating a deflate stream costs time to make it slightly larger.
 *
 * ### Reading a file someone else wrote
 *
 * Everything in a `.bangni` came from outside this program, so the reader
 * treats it that way:
 *
 * - **Entry names are parsed, never joined onto a path.** Tiles are decoded
 *   into memory and the layer id is matched against [LayerId]'s own rule, so
 *   an entry called `layers/../../etc/passwd/0_0.tile` matches nothing and is
 *   reported as unknown. Nothing here builds a `File` from an entry name,
 *   which is what makes zip-slip inapplicable rather than merely guarded.
 * - **The expansion is bounded, at decoded size.** A file may not carry more
 *   than [MAX_ENTRIES] entries, expand past [Limits.maxTotalBytes] in total,
 *   or past [MAX_ENTRY_BYTES] in any one entry — and a tile is charged the
 *   [TILE_BYTES] it becomes, not the compressed bytes it arrives as, since
 *   those differ by three orders of magnitude for an empty one. So a zip bomb
 *   fails the open instead of the process. The total is the *reader's* bound,
 *   [Limits.forHeap], not a fixed number: this codec is shared, and a ceiling
 *   sized for a phone would refuse a painting on a laptop that can hold it.
 * - **A tile outside the canvas grid, or one that fails [TileCodec], is
 *   skipped with a warning.** One bad tile must not fail an open
 *   (`06-document-and-persistence.md` §4).
 */
object BangniCodec {

    const val EXTENSION = "bangni"
    const val MANIFEST_ENTRY = "manifest.json"
    const val REFERENCE_ENTRY = "reference.png"
    const val LAYERS_PREFIX = "layers/"
    const val TILE_SUFFIX = ".tile"

    const val MAX_ENTRIES = 200_000

    /**
     * The format's own ceiling on decoded pixels: [PerfConstants.MAX_LAYERS]
     * layers of [TileGrid.MAX_TILES] tiles, 4 GiB.
     *
     * This is a *correctness* bound, not a memory one. It exists so a crafted
     * file cannot claim more than the format can express — 200,000 entries
     * decode to 50 GB, which this refuses — and it is deliberately loose
     * enough that no document either writer can produce is ever refused. A
     * reader that cannot hold 4 GiB narrows it through [Limits.forHeap]; a
     * mobile-derived constant here would refuse a painting on a laptop that
     * has the memory for it.
     */
    const val MAX_TOTAL_BYTES: Long =
        PerfConstants.MAX_LAYERS.toLong() * TileGrid.MAX_TILES * TILE_BYTES

    /**
     * The ceiling on one entry. Without it the reader accumulates up to the
     * whole remaining budget in one buffer before refusing, so the guard
     * against a deflate bomb is itself the thing that exhausts the heap.
     */
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** A crafted file must not turn 200,000 junk entries into 200,000 strings. */
    const val MAX_WARNINGS = 64

    /**
     * What a read may expand to, **charged at decoded size** — tiles are
     * *stored* rather than deflated, because `TileCodec` already compressed
     * them, so an empty one arrives as a few hundred bytes and becomes
     * [TILE_BYTES]. A budget counting arrival bytes bounds nothing.
     *
     * A parameter rather than constants read in place for two reasons: a test
     * can drive the accounting at four tiles instead of sixteen thousand, and
     * a reader can bound it by the memory it actually has.
     */
    data class Limits(
        val maxEntries: Int = MAX_ENTRIES,
        val maxTotalBytes: Long = MAX_TOTAL_BYTES,
        val maxEntryBytes: Long = MAX_ENTRY_BYTES,
        val maxWarnings: Int = MAX_WARNINGS,
    ) {
        companion object {
            /**
             * Bounded by what this JVM can hold rather than by a constant
             * someone guessed. The whole painting is built in memory before
             * `read` returns, so the ceiling is a property of the heap: half
             * of it, clamped to the format's own ceiling above.
             *
             * Half, not all, because the decoded tiles are not the only thing
             * live at that moment — the compressed entry, the map, and
             * whatever the caller already holds share the heap with them.
             *
             * **No floor.** An earlier version raised the result to 256 MiB
             * when half the heap fell short, which inverted the whole point:
             * on a 192 MiB heap that is a 256 MiB budget — 133 % of the heap —
             * and the floor bound *only* below 512 MiB, which is to say only
             * on the devices least able to honour it. A heap too small for a
             * painting should refuse it with a message, which is the reason
             * this budget exists at all.
             */
            fun forHeap(maxMemoryBytes: Long): Limits = Limits(
                maxTotalBytes = (maxMemoryBytes / 2).coerceIn(0L, MAX_TOTAL_BYTES),
            )

            /**
             * The default every `read` without an explicit budget gets, so
             * forgetting to pass one is safe rather than merely undefined.
             */
            val DEFAULT = forHeap(Runtime.getRuntime().maxMemory())
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // `<tx>_<ty>.tile`, non-negative and without leading zeros beyond "0".
    private val TILE_NAME = Regex("""^(0|[1-9]\d{0,4})_(0|[1-9]\d{0,4})\.tile$""")

    fun write(out: OutputStream, document: BangniDocument) {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
        zip.write(json.encodeToString(BangniManifest.serializer(), document.manifest).toByteArray())
        zip.closeEntry()

        for ((layerId, tiles) in document.tiles) {
            for ((key, pixels) in tiles) {
                if (pixels.size != TILE_BYTES) continue

                stored(zip, "$LAYERS_PREFIX${layerId.value}/${key.tx}_${key.ty}$TILE_SUFFIX", TileCodec.encode(pixels))
            }
        }
        document.referencePng?.let { stored(zip, REFERENCE_ENTRY, it) }
        zip.finish()
        zip.flush()
    }

    /**
     * Reads [input] to the end. Never throws for a bad file: a caller opening
     * whatever the user picked gets a [BangniReadResult.Failed] instead.
     */
    fun read(input: InputStream, limits: Limits = Limits.DEFAULT): BangniReadResult = try {
        readOrThrow(input, limits)
    } catch (failure: java.io.IOException) {
        BangniReadResult.Failed(failure.message ?: "the file could not be read")
    } catch (failure: RuntimeException) {
        // A malformed zip surfaces as IllegalArgumentException or
        // ZipException depending on where it breaks, and the JSON parser
        // throws its own; none of them may take the app down.
        BangniReadResult.Failed(failure.message ?: "the file is not a readable ${EXTENSION} document")
    }

    private fun readOrThrow(input: InputStream, limits: Limits): BangniReadResult {
        var manifest: BangniManifest? = null
        val tiles = HashMap<LayerId, HashMap<TileKey, ByteArray>>()
        var referencePng: ByteArray? = null
        val warnings = BoundedWarnings(limits.maxWarnings)
        var entries = 0
        var totalBytes = 0L

        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            entries += 1
            if (entries > limits.maxEntries) {
                return BangniReadResult.Failed("the file carries more than ${limits.maxEntries} entries")
            }
            if (entry.isDirectory) continue

            val remaining = limits.maxTotalBytes - totalBytes
            val bytes = zip.readBoundedBytes(minOf(remaining, limits.maxEntryBytes))
                ?: return BangniReadResult.Failed(
                    // Which bound tripped: the reader stops at whichever is
                    // nearer, and naming the wrong one sends the next person
                    // looking for a total budget problem that is not there.
                    if (remaining <= limits.maxEntryBytes) {
                        "the file expands past ${limits.maxTotalBytes / (1024 * 1024)} MB"
                    } else {
                        "an entry expands past ${limits.maxEntryBytes / (1024 * 1024)} MB"
                    },
                )

            when {
                entry.name == MANIFEST_ENTRY ->
                    manifest = json.decodeFromString(BangniManifest.serializer(), bytes.decodeToString())
                // Kept, so charged what it occupies. A tile is charged its
                // decoded size in its own branch instead.
                entry.name == REFERENCE_ENTRY -> {
                    totalBytes += bytes.size
                    referencePng = bytes
                }
                entry.name.startsWith(LAYERS_PREFIX) && entry.name.endsWith(TILE_SUFFIX) -> {
                    // Charged before the decode, at the size the decode will
                    // reach and *only* that: the compressed bytes are about to
                    // be discarded, so charging them too would quietly shrink
                    // the budget below what it says it is.
                    totalBytes += TILE_BYTES
                    if (totalBytes > limits.maxTotalBytes) {
                        return BangniReadResult.Failed(
                            "the file expands past ${limits.maxTotalBytes / (1024 * 1024)} MB",
                        )
                    }
                    readTile(entry.name, bytes, tiles, warnings)
                }
                // Forward compatibility: a newer writer's extra entry is not a
                // reason to refuse a file whose painting this build can show.
                else -> warnings += "ignored an unknown entry: ${entry.name}"
            }
        }

        val header = manifest
            ?: return BangniReadResult.Failed("the file has no $MANIFEST_ENTRY; it is not a $EXTENSION document")
        if (header.formatVersion > BangniManifest.FORMAT_VERSION) {
            return BangniReadResult.Failed(
                "the file was written by a newer version of the app " +
                    "(format ${header.formatVersion}, this build reads ${BangniManifest.FORMAT_VERSION})",
            )
        }
        val grid = try {
            TileGrid(header.width, header.height)
        } catch (_: IllegalArgumentException) {
            return BangniReadResult.Failed("the file's canvas is ${header.width}×${header.height}")
        }
        if (header.layers.isEmpty()) {
            return BangniReadResult.Failed("the file lists no layers")
        }

        // Tiles for a layer the manifest never mentions, or outside the grid,
        // would allocate GPU slices nothing draws.
        val known = header.layers.mapNotNullTo(HashSet()) { it.toPropsOrNull()?.id }
        val kept = HashMap<LayerId, Map<TileKey, ByteArray>>(known.size)
        for ((layerId, layerTiles) in tiles) {
            if (layerId !in known) {
                warnings += "ignored tiles for a layer the file does not list: ${layerId.value}"
                continue
            }
            val inside = layerTiles.filterKeys(grid::contains)
            if (inside.size != layerTiles.size) {
                warnings += "ignored ${layerTiles.size - inside.size} tile(s) outside the canvas"
            }
            if (inside.isNotEmpty()) kept[layerId] = inside
        }

        return BangniReadResult.Ok(
            BangniDocument(header, kept, referencePng.takeIf { header.tracingReference != null }),
            warnings,
        )
    }

    private fun readTile(
        name: String,
        bytes: ByteArray,
        tiles: HashMap<LayerId, HashMap<TileKey, ByteArray>>,
        warnings: MutableList<String>,
    ) {
        // Exactly `layers/<id>/<tx>_<ty>.tile`: a name with another separator
        // in it, or a `..` segment, matches nothing here.
        val rest = name.removePrefix(LAYERS_PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0 || rest.indexOf('/', slash + 1) >= 0) {
            warnings += "ignored an unknown entry: $name"
            return
        }
        val layerId = try {
            LayerId(rest.substring(0, slash))
        } catch (_: IllegalArgumentException) {
            warnings += "ignored an entry with an unusable layer id: $name"
            return
        }
        val match = TILE_NAME.matchEntire(rest.substring(slash + 1))
        if (match == null) {
            warnings += "ignored an unknown entry: $name"
            return
        }

        when (val decoded = TileCodec.decode(bytes)) {
            is TileCodec.Decoded.Corrupt -> warnings += "skipped a damaged tile: $name"
            is TileCodec.Decoded.Ok -> {
                val key = TileKey(match.groupValues[1].toInt(), match.groupValues[2].toInt())
                tiles.getOrPut(layerId) { HashMap() }[key] = decoded.pixels
            }
        }
    }

    private fun stored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * The entry's bytes, or null once it would pass [limit].
     *
     * `ZipEntry.size` is the *declared* size and a hostile file may lie about
     * it, so the budget is enforced against what actually arrives.
     */
    private fun InputStream.readBoundedBytes(limit: Long): ByteArray? {
        if (limit <= 0) return null

        val out = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break

            total += read
            if (total > limit) return null

            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private const val COPY_BUFFER = 64 * 1024

    /**
     * A warning list that stops growing. One malformed entry earns one
     * string, and [MAX_ENTRIES] of them would otherwise be their own
     * exhaustion path through the very list that reports the problem.
     */
    private class BoundedWarnings(private val limit: Int) : AbstractMutableList<String>() {
        private val items = ArrayList<String>()

        override val size: Int get() = items.size
        override fun get(index: Int): String = items[index]
        override fun set(index: Int, element: String): String = items.set(index, element)
        override fun removeAt(index: Int): String = items.removeAt(index)

        override fun add(index: Int, element: String) {
            if (items.size >= limit) return
            items.add(index, element)
        }
    }
}
