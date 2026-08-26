package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerRecord
import ch.lkmc.bangnidraw.engine.core.TileKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The `<seq>.entry` / `<seq>.redo` header line and its mapping to
 * [HistoryEntry] (`docs/plan/06-document-and-persistence.md` §5.3).
 *
 * The header is one line of UTF-8 JSON — inspectable with `cat` — followed by
 * raw payload bytes, each an entire tile file in [TileCodec]'s format. `off`
 * is relative to the first byte after the newline; a payload of `len 0`
 * records "this tile was empty before", so restoring it deletes the tile.
 *
 * Field placement follows §5.3's example: `layerId` sits at the top level for
 * the kinds that have exactly one subject layer, and every other
 * kind-specific field lives under `data`. Payloads carry their own layer id
 * because `LayerMerge` and `Flatten` hold tiles of several layers in one
 * entry.
 */
internal object HistoryCodec {

    const val FORMAT_VERSION = 1

    @Serializable
    data class PayloadRef(
        val layer: String,
        val tx: Int,
        val ty: Int,
        val off: Long,
        val len: Int,
    )

    @Serializable
    data class EntryHeader(
        val v: Int = FORMAT_VERSION,
        val seq: Long,
        val kind: String,
        val ts: Long = 0L,
        val activeBefore: String = "",
        val activeAfter: String = "",
        val layerId: String? = null,
        val payloads: List<PayloadRef> = emptyList(),
        val data: JsonObject = JsonObject(emptyMap()),
    )

    /**
     * `ignoreUnknownKeys` is what lets this reader open an entry written by a
     * later minor revision of the same format on the fields it knows —
     * exactly `project.json`'s §13 posture. `allowSpecialFloatingPointValues`
     * for the same reason as the loader's `Json` (REVIEW.md R-020): a record
     * inside an entry must degrade at *apply* time, not kill the decode.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        // Defaults are written out — §5.3's example shows "v":1 in the line,
        // and a header a person can `cat` should say its version rather than
        // imply it.
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    /** The keys of [entry]'s payloads, in the order they must be written. */
    fun payloadKeys(entry: HistoryEntry): List<Pair<LayerId, TileKey>> = when (entry) {
        is HistoryEntry.Stroke -> entry.tiles.map { entry.layerId to it }
        is HistoryEntry.Fill -> entry.tiles.map { entry.layerId to it }
        is HistoryEntry.LayerDelete ->
            entry.tiles.map { LayerId(entry.layer.id) to it }
        is HistoryEntry.LayerClear -> entry.tiles.map { entry.layerId to it }
        is HistoryEntry.LayerMerge ->
            entry.upperTiles.map { LayerId(entry.upper.id) to it } +
                entry.lowerTiles.map { LayerId(entry.lower.id) to it }
        is HistoryEntry.Flatten ->
            entry.tilesPerLayer.flatMap { (layer, keys) -> keys.map { layer to it } }
        is HistoryEntry.LayerAdd,
        is HistoryEntry.LayerReorder,
        is HistoryEntry.LayerProps,
        is HistoryEntry.LayerDuplicate,
        is HistoryEntry.PaperColor,
        -> emptyList()
    }

    /**
     * True when *redoing* [entry] needs pixel payloads — the kinds whose
     * `.redo` sidecar exists at all (§5.4). Everything else redoes from its
     * header, or from tiles that are unchanged by truncation's guarantee.
     */
    fun redoNeedsPixels(entry: HistoryEntry): Boolean = when (entry) {
        is HistoryEntry.Stroke,
        is HistoryEntry.Fill,
        is HistoryEntry.LayerMerge,
        is HistoryEntry.Flatten,
        -> true
        else -> false
    }

    /** The header for [entry], with [payloads] already laid out. */
    fun headerOf(entry: HistoryEntry, seq: Long, ts: Long, payloads: List<PayloadRef>): EntryHeader {
        val kind = kindOf(entry)
        val data = buildJsonObject {
            when (entry) {
                is HistoryEntry.Stroke, is HistoryEntry.Fill, is HistoryEntry.LayerClear -> Unit
                is HistoryEntry.LayerAdd -> {
                    put("layer", json.encodeToJsonElement(entry.layer))
                    put("index", entry.index)
                }
                is HistoryEntry.LayerDelete -> {
                    put("layer", json.encodeToJsonElement(entry.layer))
                    put("index", entry.index)
                }
                is HistoryEntry.LayerReorder -> {
                    put("fromIndex", entry.fromIndex)
                    put("toIndex", entry.toIndex)
                }
                is HistoryEntry.LayerProps -> {
                    put("before", json.encodeToJsonElement(entry.before))
                    put("after", json.encodeToJsonElement(entry.after))
                }
                is HistoryEntry.LayerMerge -> {
                    put("upper", json.encodeToJsonElement(entry.upper))
                    put("upperIndex", entry.upperIndex)
                    put("lower", json.encodeToJsonElement(entry.lower))
                }
                is HistoryEntry.LayerDuplicate -> {
                    put("sourceId", entry.sourceId.value)
                    put("copy", json.encodeToJsonElement(entry.copy))
                    put("index", entry.index)
                }
                is HistoryEntry.Flatten -> {
                    put("layers", json.encodeToJsonElement(entry.layers))
                    put("result", json.encodeToJsonElement(entry.result))
                }
                is HistoryEntry.PaperColor -> {
                    put("before", entry.before)
                    put("after", entry.after)
                }
            }
        }
        val layerId = when (entry) {
            is HistoryEntry.Stroke -> entry.layerId.value
            is HistoryEntry.Fill -> entry.layerId.value
            is HistoryEntry.LayerClear -> entry.layerId.value
            is HistoryEntry.LayerReorder -> entry.layerId.value
            is HistoryEntry.LayerProps -> entry.layerId.value
            else -> null
        }
        return EntryHeader(
            v = FORMAT_VERSION,
            seq = seq,
            kind = kind,
            ts = ts,
            activeBefore = entry.activeBefore.value,
            activeAfter = entry.activeAfter.value,
            layerId = layerId,
            payloads = payloads,
            data = data,
        )
    }

    /**
     * The inverse of [headerOf]: an **unstamped** entry (the caller stamps it
     * with the real on-disk byte count), or null when the header does not
     * describe a well-formed entry of a known kind — a malformed id included,
     * because a hand-edited file must not hand a traversal to a path join
     * (the `HistoryEntry` KDoc's trust boundary). Null means "this entry and
     * every later one are discarded" (§5.6: a prefix or it is lies).
     */
    fun entryOf(header: EntryHeader): HistoryEntry? {
        return try {
            val activeBefore = LayerId(header.activeBefore)
            val activeAfter = LayerId(header.activeAfter)
            val tiles = header.payloads.map { TileKey(it.tx, it.ty) }
            val one = header.layerId?.let { LayerId(it) }
            when (header.kind) {
                "Stroke" -> HistoryEntry.Stroke(
                    activeBefore = activeBefore, activeAfter = activeAfter,
                    layerId = one ?: return null, tiles = tiles,
                )
                "Fill" -> HistoryEntry.Fill(
                    activeBefore = activeBefore, activeAfter = activeAfter,
                    layerId = one ?: return null, tiles = tiles,
                )
                "LayerAdd" -> HistoryEntry.LayerAdd(
                    activeBefore = activeBefore, activeAfter = activeAfter,
                    layer = record(header, "layer") ?: return null,
                    index = int(header, "index") ?: return null,
                )
                "LayerDelete" -> HistoryEntry.LayerDelete(
                    activeBefore = activeBefore, activeAfter = activeAfter,
                    layer = record(header, "layer") ?: return null,
                    index = int(header, "index") ?: return null,
                    tiles = tiles,
                )
                "LayerReorder" -> HistoryEntry.LayerReorder(
                    activeBefore = activeBefore, activeAfter = activeAfter,
                    layerId = one ?: return null,
                    fromIndex = int(header, "fromIndex") ?: return null,
                    toIndex = int(header, "toIndex") ?: return null,
                )
                "LayerProps" -> HistoryEntry.LayerProps(
                    activeBefore = activeBefore, activeAfter = activeAfter,
                    layerId = one ?: return null,
                    before = record(header, "before") ?: return null,
                    after = record(header, "after") ?: return null,
                )
                "LayerMerge" -> {
                    val upper = record(header, "upper") ?: return null
                    val lower = record(header, "lower") ?: return null
                    // Ids are validated here because the partition below
                    // joins on them; a payload naming neither layer is a
                    // header that lies about its own contents.
                    val upperId = LayerId(upper.id)
                    val lowerId = LayerId(lower.id)
                    val upperTiles = ArrayList<TileKey>()
                    val lowerTiles = ArrayList<TileKey>()
                    for (ref in header.payloads) {
                        when (ref.layer) {
                            upperId.value -> upperTiles.add(TileKey(ref.tx, ref.ty))
                            lowerId.value -> lowerTiles.add(TileKey(ref.tx, ref.ty))
                            else -> return null
                        }
                    }
                    HistoryEntry.LayerMerge(
                        activeBefore = activeBefore, activeAfter = activeAfter,
                        upper = upper,
                        upperIndex = int(header, "upperIndex") ?: return null,
                        upperTiles = upperTiles,
                        lower = lower,
                        lowerTiles = lowerTiles,
                    )
                }
                "LayerDuplicate" -> HistoryEntry.LayerDuplicate(
                    activeBefore = activeBefore, activeAfter = activeAfter,
                    sourceId = LayerId(
                        header.data["sourceId"]?.jsonPrimitive?.content ?: return null,
                    ),
                    copy = record(header, "copy") ?: return null,
                    index = int(header, "index") ?: return null,
                )
                "LayerClear" -> HistoryEntry.LayerClear(
                    activeBefore = activeBefore, activeAfter = activeAfter,
                    layerId = one ?: return null, tiles = tiles,
                )
                "Flatten" -> {
                    val layers = header.data["layers"]?.let {
                        json.decodeFromJsonElement<List<LayerRecord>>(it)
                    } ?: return null
                    val ids = layers.map { LayerId(it.id) }
                    val grouped = LinkedHashMap<LayerId, MutableList<TileKey>>()
                    for (id in ids) grouped[id] = ArrayList()
                    for (ref in header.payloads) {
                        val list = grouped[LayerId(ref.layer)] ?: return null
                        list.add(TileKey(ref.tx, ref.ty))
                    }
                    HistoryEntry.Flatten(
                        activeBefore = activeBefore, activeAfter = activeAfter,
                        layers = layers,
                        tilesPerLayer = grouped,
                        result = record(header, "result") ?: return null,
                    )
                }
                "PaperColor" -> HistoryEntry.PaperColor(
                    activeBefore = activeBefore, activeAfter = activeAfter,
                    before = int(header, "before") ?: return null,
                    after = int(header, "after") ?: return null,
                )
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            // A malformed LayerId, a record whose shape does not decode, a
            // primitive where an object was expected: all one answer. Only
            // IAE — anything else is a programming error and stays loud.
            null
        }
    }

    fun kindOf(entry: HistoryEntry): String = when (entry) {
        is HistoryEntry.Stroke -> "Stroke"
        is HistoryEntry.Fill -> "Fill"
        is HistoryEntry.LayerAdd -> "LayerAdd"
        is HistoryEntry.LayerDelete -> "LayerDelete"
        is HistoryEntry.LayerReorder -> "LayerReorder"
        is HistoryEntry.LayerProps -> "LayerProps"
        is HistoryEntry.LayerMerge -> "LayerMerge"
        is HistoryEntry.LayerDuplicate -> "LayerDuplicate"
        is HistoryEntry.LayerClear -> "LayerClear"
        is HistoryEntry.Flatten -> "Flatten"
        is HistoryEntry.PaperColor -> "PaperColor"
    }

    private fun record(header: EntryHeader, field: String): LayerRecord? =
        header.data[field]?.let { json.decodeFromJsonElement<LayerRecord>(it) }

    private fun int(header: EntryHeader, field: String): Int? =
        header.data[field]?.jsonPrimitive?.int
}
