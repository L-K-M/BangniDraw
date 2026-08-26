package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerRecord
import ch.lkmc.bangnidraw.engine.core.LayerStack
import kotlinx.serialization.Serializable

/**
 * The serialised form of a painting's metadata — `project.json`
 * (`docs/plan/06-document-and-persistence.md` §3). The runtime [Document] in
 * `engine/core` is built from it by `ProjectStore.load` and never holds JSON
 * concerns; keeping them separate is what lets the format change without
 * touching the engine.
 *
 * Every field has a default so a reader of a newer format still decodes what
 * it knows (§13). [nextLayerName] is the `LayerStack.nextName` counter the
 * roadmap's step 3 persists here — `0` means "written before the field
 * existed", and the loader then re-derives the floor from the layer names.
 */
@Serializable
internal data class ProjectFile(
    val formatVersion: Int = FORMAT_VERSION,
    /** == folder name; on mismatch the folder wins, with a log line (§3). */
    val id: String = "",
    val title: String = "",
    val createdAt: Long = 0L,
    /** Last *content* change, not last write (§3). */
    val updatedAt: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    /** Metadata only; export writes it into PNG pHYs later. */
    val dpi: Int = Document.DEFAULT_DPI,
    /** ARGB; alpha 0 = transparent paper. */
    val paperColor: Int = 0,
    /** Bottom → top. */
    val layers: List<LayerRecord> = emptyList(),
    val activeLayerId: String = "",
    /** See the class KDoc; `LayerStack.nextName`'s persisted half. */
    val nextLayerName: Int = 0,
    val history: HistoryRecord = HistoryRecord(),
    val galleryUri: String? = null,
    val lastGallerySyncAt: Long = 0L,
    val galleryModifiedAt: Long = 0L,
    val galleryBytes: Long = 0L,
    val view: ViewRecord? = null,
    val lastTool: ToolRecord? = null,
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "project.json"
    }
}

/** The journal's checkpoint state (§3). All defaults until roadmap 3b writes it. */
@Serializable
internal data class HistoryRecord(
    /** Entries `[oldestSeq, oldestSeq + cursor)` are applied; the rest are redo. */
    val cursor: Int = 0,
    /** Next `<seq>` to allocate; never reused within a project. */
    val nextSeq: Long = 1L,
    /** First entry still on disk (pruning advances it). */
    val oldestSeq: Long = 1L,
    /** Count on disk, for the Studio readout without listing the dir. */
    val entries: Int = 0,
    /** Sum of `.entry` + `.redo` sizes, same purpose. */
    val bytes: Long = 0L,
)

/** Zoom/rotation to restore on reopen; null = fit (§3). Written from step 3c on. */
@Serializable
internal data class ViewRecord(
    val scale: Float,
    val rotation: Float,
    val panX: Float,
    val panY: Float,
    /** The window in px the view was saved against; restored only within 10 %. */
    val windowW: Int,
    val windowH: Int,
)

/** What was in the hand when the canvas was left (§3). Written from step 5 on. */
@Serializable
internal data class ToolRecord(
    /** A string, not the enum, for the same reason `LayerRecord.blend` is (§3). */
    val tool: String,
    val presetId: String?,
    val size: Float,
    val opacity: Float,
    val color: Int,
)

/** [Document] → its serialised form; `ProjectStore.load` is the inverse. */
internal fun Document.toProjectFile(
    history: HistoryRecord = HistoryRecord(cursor = historyCursor),
): ProjectFile = ProjectFile(
    formatVersion = ProjectFile.FORMAT_VERSION,
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    width = width,
    height = height,
    dpi = dpi,
    paperColor = paperColor,
    layers = stack.layers.map { it.props.toRecord() },
    activeLayerId = stack.active.id.value,
    nextLayerName = stack.nextName,
    history = history,
    galleryUri = galleryUri,
)

/**
 * The highest default-name number any record embedded in [entries] carries —
 * the *replay* half of the `nextName` obligation (`docs/plan/12-roadmap.md`
 * step 3b): a crash-recovered journal can hold names the stale checkpoint
 * never saw (a layer added after it, even one the journal then deletes), and
 * the counter must clear every one of them or a reopened painting reissues a
 * name `05-layers.md` §1 says is never reused. The caller floors
 * `LayerStack.nextName` at one past this, alongside the loader's own scan of
 * the checkpointed stack.
 */
internal fun highestDefaultNameIn(entries: List<HistoryEntry>): Int {
    var highest = 0
    fun note(record: LayerRecord) {
        highest = maxOf(highest, defaultLayerNameNumber(record.name) ?: 0)
    }
    for (entry in entries) {
        when (entry) {
            is HistoryEntry.LayerAdd -> note(entry.layer)
            is HistoryEntry.LayerDelete -> note(entry.layer)
            is HistoryEntry.LayerDuplicate -> note(entry.copy)
            is HistoryEntry.LayerProps -> {
                note(entry.before)
                note(entry.after)
            }
            is HistoryEntry.LayerMerge -> {
                note(entry.upper)
                note(entry.lower)
            }
            is HistoryEntry.Flatten -> {
                entry.layers.forEach(::note)
                note(entry.result)
            }
            else -> Unit
        }
    }
    return highest
}

/**
 * The number of a generated default layer name, or null for anything else.
 *
 * Matches exactly `"@string/layer_default N"` — the closed grammar's second
 * form (AGENTS.md) — because the loader floors the persisted [ProjectFile
 * .nextLayerName] at one past the highest default name actually present, so
 * a file written before the field existed (or hand-edited below its layers)
 * cannot reissue a name that is already on a layer.
 */
internal fun defaultLayerNameNumber(name: String): Int? {
    val prefix = LayerStack.DEFAULT_NAME_KEY + " "
    if (!name.startsWith(prefix)) return null
    val digits = name.substring(prefix.length)
    if (digits.isEmpty() || digits.length > 9) return null
    if (digits.length > 1 && digits[0] == '0') return null
    var value = 0
    for (c in digits) {
        if (c !in '0'..'9') return null
        value = value * 10 + (c - '0')
    }
    return value
}
