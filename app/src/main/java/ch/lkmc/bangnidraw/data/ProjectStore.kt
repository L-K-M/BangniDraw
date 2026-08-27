package ch.lkmc.bangnidraw.data

import android.util.Log
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.isSafePathSegment
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal fun interface DuplicateFileWriter {
    @Throws(IOException::class)
    fun copy(source: File, target: File)
}

private val DEFAULT_DUPLICATE_FILE_WRITER = DuplicateFileWriter { source, target ->
    source.copyTo(target)
}

/**
 * The project-folder lifecycle: `<root>/<uuid>/` with `project.json` as the
 * commit point (`docs/plan/06-document-and-persistence.md` §2, §3, §7).
 *
 * Takes a plain [File] root — the Hilt module passes
 * `File(context.filesDir, "projects")` — so the whole class runs on the JVM
 * suite against a temp dir (`docs/plan/11-testing.md` §5). IO thread only.
 */
class ProjectStore internal constructor(
    private val root: File,
    private val duplicateFileWriter: DuplicateFileWriter,
) {

    constructor(root: File) : this(root, DEFAULT_DUPLICATE_FILE_WRITER)

    internal sealed interface LoadResult {
        /**
         * The document opened. [unreadableLayers] counts layer records the
         * loader had to drop — reported *beside* the tile count, never folded
         * into it, because a lost layer shown as N lost tiles is a misleading
         * readout (`docs/plan/12-roadmap.md` step 3). [history] is the
         * checkpointed journal state `HistoryStore.load` reconciles against
         * what `history/` actually holds (06 §5.6).
         */
        data class Loaded(
            val document: Document,
            val unreadableLayers: Int,
            val history: HistoryRecord,
        ) : LoadResult

        data class Failed(val reason: FailureReason) : LoadResult
    }

    enum class FailureReason {
        /** The id is not a safe path segment; no path was built from it. */
        BAD_ID,

        /** No folder, or no `project.json` — not a painting. */
        NOT_FOUND,

        /**
         * `project.json` exists but cannot be trusted: unparseable, or its
         * geometry or layer stack is beyond degrading. Reported, never
         * silently replaced — we do not overwrite a user's painting with an
         * empty one (`docs/plan/11-testing.md` §5).
         */
        UNREADABLE,

        /** Written by a newer 帮你Draw; refused rather than rewritten (06 §13). */
        NEWER_VERSION,
    }

    /** One shelf row (06 §7). */
    data class Summary(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val width: Int,
        val height: Int,
        val layerCount: Int,
        val thumbnail: File?,
        val bytes: Long,
        val galleryUri: String?,
        /** For §9.3's Studio-open staleness rule; 0 = never synced. */
        val lastGallerySyncAt: Long = 0L,
    )

    /** True when [id] may name a project folder; checked before any path. */
    fun isValidId(id: String): Boolean = isSafePathSegment(id)

    fun projectDir(id: String): File {
        require(isValidId(id)) { "project id must be a single safe path segment" }
        return File(root, id)
    }

    fun layersDir(id: String): File = File(projectDir(id), LAYERS_DIR)

    fun layerDir(id: String, layer: LayerId): File = File(layersDir(id), layer.value)

    /** `true` when a folder with a `project.json` exists for [id]. */
    fun exists(id: String): Boolean =
        isValidId(id) && File(projectDir(id), ProjectFile.FILE_NAME).isFile

    /**
     * Writes `project.json` — the checkpoint's last step, after the tiles the
     * flusher owns are on disk (§5.6 step 4). tmp + rename, so a kill leaves
     * the old file or the new one, never a torn one.
     */
    @Throws(IOException::class)
    fun checkpoint(document: Document) {
        checkpoint(document, HistoryRecord(cursor = document.historyCursor))
    }

    /**
     * The full checkpoint: [history] carries `cursor`, `nextSeq`, `oldestSeq`
     * and the counts as they are on disk at this moment (§6.2) — the plain
     * overload above is for callers with no journal yet.
     */
    @Throws(IOException::class)
    internal fun checkpoint(document: Document, history: HistoryRecord) {
        val dir = projectDir(document.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
        sweepTmp(dir)
        val bytes = json.encodeToString(ProjectFile.serializer(), document.toProjectFile(history))
            .toByteArray(Charsets.UTF_8)
        AtomicFiles.write(File(dir, ProjectFile.FILE_NAME), bytes)
    }

    /**
     * Creates a new painting's folder — a checkpoint of [document] with the
     * guard [checkpoint] deliberately lacks: an id that already names a
     * painting is refused rather than silently overwritten. The New Canvas
     * dialog runs this *before* navigating (08 §2.1), so the Canvas always
     * opens an existing folder.
     */
    @Throws(IOException::class)
    fun create(document: Document) {
        require(!exists(document.id)) { "project ${document.id} already exists" }
        checkpoint(document)
    }

    /**
     * Retitles one painting in place (06 §8): `title` and `updatedAt` move,
     * nothing else — the rest of the file is rewritten byte-for-what-it-was.
     * Runs only from the Studio, so no Canvas holds the document open
     * (single task, single activity). Returns false when the painting could
     * not be read or written; the shelf simply keeps the old name.
     */
    fun rename(id: String, title: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!isValidId(id)) return false
        val jsonFile = File(projectDir(id), ProjectFile.FILE_NAME)
        if (!jsonFile.isFile) return false
        return try {
            val file = json.decodeFromString(
                ProjectFile.serializer(),
                jsonFile.readText(Charsets.UTF_8),
            ).currentForWrite(id) ?: return false
            val bytes = json
                .encodeToString(ProjectFile.serializer(), file.copy(title = title, updatedAt = now))
                .toByteArray(Charsets.UTF_8)
            AtomicFiles.write(jsonFile, bytes)
            true
        } catch (e: SerializationException) {
            Log.w(TAG, "project $id: rename skipped, unreadable project.json", e)
            false
        } catch (e: IOException) {
            Log.w(TAG, "project $id: rename failed", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "project $id: rename skipped, invalid project.json", e)
            false
        }
    }

    /**
     * Copies one painting to a fresh id (06 §8): tiles and thumbnail come
     * along, **history does not** — entries embed the old layer ids, and
     * rewriting a journal is a migration with no payoff for a "start from
     * here". Every layer id is remapped (directory names and records), the
     * copy gets no gallery identity of its own, `view`/`lastTool`/the name
     * counter are kept, and [titleTransform] localizes the " copy" suffix so
     * this class never holds display text. Runs only from the Studio, so no
     * Canvas holds the source open.
     *
     * The copy is assembled under `<uuid>.duplicating`, then renamed after
     * `project.json` commits. A failed copy is removed immediately; a killed
     * process leaves a stage the next store instance sweeps.
     */
    fun duplicate(
        sourceId: String,
        titleTransform: (String) -> String,
        now: Long = System.currentTimeMillis(),
    ): String? {
        if (!isValidId(sourceId)) return null
        val sourceJson = File(projectDir(sourceId), ProjectFile.FILE_NAME)
        if (!sourceJson.isFile) return null
        var stageDir: File? = null

        return try {
            val source = json.decodeFromString(
                ProjectFile.serializer(),
                sourceJson.readText(Charsets.UTF_8),
            ).currentForWrite(sourceId) ?: return null
            val newId = java.util.UUID.randomUUID().toString()
            // The same trust boundary as load: a record id from a
            // hand-editable file never reaches a path join. An unsafe id's
            // layer is dropped from the copy exactly as load would drop it;
            // a source with no safe layer left is corrupt, not duplicable.
            val safeLayers = source.layers.filter { isSafePathSegment(it.id) }
            if (safeLayers.isEmpty()) {
                Log.w(TAG, "project $sourceId: duplicate skipped, no readable layer")
                return null
            }
            val idMap = safeLayers.associate { it.id to java.util.UUID.randomUUID().toString() }
            val targetDir = projectDir(newId)
            if (targetDir.exists()) throw IOException("duplicate target already exists: $targetDir")
            val stage = File(root, newId + DUPLICATING_SUFFIX)
            stageDir = stage
            activeDuplicateStages += stage.name
            if (!stage.mkdirs()) throw IOException("could not create $stage")

            for (record in safeLayers) {
                val newLayerId = idMap.getValue(record.id)
                val sourceDir = File(layersDir(sourceId), record.id)
                val children = sourceDir.listFiles() ?: continue
                val destDir = File(File(stage, LAYERS_DIR), newLayerId)
                if (!destDir.mkdirs()) throw IOException("could not create $destDir")
                for (child in children) {
                    // Tiles only: an in-flight .tmp is a crashed writer's
                    // leftover, not content.
                    if (!child.isFile || TileStore.parseName(child.name) == null) continue
                    duplicateFileWriter.copy(child, File(destDir, child.name))
                }
            }
            File(projectDir(sourceId), THUMB_NAME).takeIf { it.isFile }
                ?.let { duplicateFileWriter.copy(it, File(stage, THUMB_NAME)) }

            val copy = source.copy(
                id = newId,
                title = titleTransform(source.title),
                createdAt = now,
                updatedAt = now,
                layers = safeLayers.map { it.copy(id = idMap.getValue(it.id)) },
                activeLayerId = idMap[source.activeLayerId] ?: idMap.values.first(),
                history = HistoryRecord(),
                galleryUri = null,
                lastGallerySyncAt = 0L,
                galleryModifiedAt = 0L,
                galleryBytes = 0L,
            )
            AtomicFiles.write(
                File(stage, ProjectFile.FILE_NAME),
                json.encodeToString(ProjectFile.serializer(), copy).toByteArray(Charsets.UTF_8),
            )
            if (!stage.renameTo(targetDir)) {
                throw IOException("could not commit duplicate $stage to $targetDir")
            }
            newId
        } catch (e: SerializationException) {
            Log.w(TAG, "project $sourceId: duplicate skipped, unreadable project.json", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "project $sourceId: duplicate failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "project $sourceId: duplicate skipped, invalid project.json", e)
            null
        } finally {
            stageDir?.let { stage ->
                activeDuplicateStages -= stage.name
                stage.deleteRecursively()
            }
        }
    }

    /**
     * Records a gallery sync's outcome on a painting that is not open — the
     * Studio-open background sync (06 §9.3). Same read-modify-write shape as
     * [rename]; `updatedAt` deliberately does not move, because a sync is
     * looking, not painting.
     */
    fun updateGalleryFields(
        id: String,
        galleryUri: String?,
        lastGallerySyncAt: Long,
        galleryModifiedAt: Long,
        galleryBytes: Long,
    ): Boolean {
        if (!isValidId(id)) return false
        val jsonFile = File(projectDir(id), ProjectFile.FILE_NAME)
        if (!jsonFile.isFile) return false
        return try {
            val file = json.decodeFromString(
                ProjectFile.serializer(),
                jsonFile.readText(Charsets.UTF_8),
            ).currentForWrite(id) ?: return false
            val bytes = json.encodeToString(
                ProjectFile.serializer(),
                file.copy(
                    galleryUri = galleryUri,
                    lastGallerySyncAt = lastGallerySyncAt,
                    galleryModifiedAt = galleryModifiedAt,
                    galleryBytes = galleryBytes,
                ),
            ).toByteArray(Charsets.UTF_8)
            AtomicFiles.write(jsonFile, bytes)
            true
        } catch (e: SerializationException) {
            Log.w(TAG, "project $id: gallery fields skipped, unreadable project.json", e)
            false
        } catch (e: IOException) {
            Log.w(TAG, "project $id: gallery fields write failed", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "project $id: gallery fields skipped, invalid project.json", e)
            false
        }
    }

    /**
     * Free space beside the shelf's total — the Studio's storage readout
     * (06 §7). `usableSpace` of a path that does not exist yet is 0, and the
     * root is only created by the first checkpoint, so an empty shelf reads
     * the parent (`filesDir`) — same filesystem, honest number.
     */
    fun freeBytes(): Long =
        (if (root.exists()) root else root.parentFile ?: root).usableSpace

    /**
     * Opens one painting: `project.json` → [Document], with the layer tile
     * sets rebuilt from the directory listing (an absent file is an empty
     * tile, so the listing *is* the tile set). Tile pixels are not read here
     * — the caller streams them per layer through [TileStore] (06 §5.7).
     */
    internal fun load(id: String): LoadResult {
        if (!isValidId(id)) return LoadResult.Failed(FailureReason.BAD_ID)
        val dir = projectDir(id)
        val jsonFile = File(dir, ProjectFile.FILE_NAME)
        if (!jsonFile.isFile) return LoadResult.Failed(FailureReason.NOT_FOUND)
        sweepTmp(dir)

        val file = try {
            json.decodeFromString(ProjectFile.serializer(), jsonFile.readText(Charsets.UTF_8))
        } catch (e: SerializationException) {
            Log.w(TAG, "project $id: unreadable project.json", e)
            return LoadResult.Failed(FailureReason.UNREADABLE)
        } catch (e: IOException) {
            Log.w(TAG, "project $id: could not read project.json", e)
            return LoadResult.Failed(FailureReason.UNREADABLE)
        } catch (e: IllegalArgumentException) {
            // SerializationException extends IllegalArgumentException, so the
            // branch above is a narrower twin of this one; this one also takes
            // any other IAE a decode can surface. Never a plain Exception —
            // that would swallow OOMs' cousins and programming errors too.
            Log.w(TAG, "project $id: invalid project.json", e)
            return LoadResult.Failed(FailureReason.UNREADABLE)
        }

        if (file.formatVersion > ProjectFile.FORMAT_VERSION) {
            return LoadResult.Failed(FailureReason.NEWER_VERSION)
        }
        if (file.id != id) {
            // §3: the folder wins. The id is what every path was derived
            // from; the field is only a copy of it.
            Log.w(TAG, "project $id: project.json claims id ${file.id}; folder wins")
        }

        val stacked = buildStack(id, file) ?: return LoadResult.Failed(FailureReason.UNREADABLE)
        val (stack, unreadableLayers) = stacked

        val document = try {
            Document(
                id = id,
                title = file.title,
                width = file.width,
                height = file.height,
                dpi = if (file.dpi > 0) file.dpi else Document.DEFAULT_DPI,
                paperColor = file.paperColor,
                stack = stack,
                historyCursor = file.history.cursor,
                galleryUri = file.galleryUri,
                lastGallerySyncAt = file.lastGallerySyncAt,
                galleryModifiedAt = file.galleryModifiedAt,
                galleryBytes = file.galleryBytes,
                createdAt = file.createdAt,
                updatedAt = file.updatedAt,
            )
        } catch (e: IllegalArgumentException) {
            // A canvas size outside the format's range has no degraded value:
            // every tile coordinate is relative to it, so "fixing" it would
            // orphan or alias tiles. Corrupt, not partially readable.
            Log.w(TAG, "project $id: invalid geometry", e)
            return LoadResult.Failed(FailureReason.UNREADABLE)
        }
        return LoadResult.Loaded(document, unreadableLayers, file.history)
    }

    /** Refreshes sparse tile sets after journal replay introduces new layers. */
    internal fun relistTiles(document: Document): Document {
        val grid = TileGrid(document.width, document.height)
        val layers = document.stack.layers.map { layer ->
            val keys = TileStore(layerDir(document.id, layer.id)).list()
                .filterTo(LinkedHashSet()) { grid.contains(it) }
            layer.copy(tiles = keys)
        }
        return document.copy(stack = document.stack.copy(layers = layers))
    }

    /**
     * The layer stack from the file's records, with each layer's tile keys
     * listed from `layers/<id>/`, degrading per layer: an unreadable record
     * is dropped and counted, and only a document with *no* readable layer
     * left fails the open (a stack is never empty).
     *
     * The layer-level rule and the tile-level rule are deliberately distinct
     * granularities and must not be collapsed (REVIEW.md R-001): a bad tile
     * shows transparent and the layer survives, while a bad layer *id* has
     * no degraded value at all — it is the key every tile lookup and history
     * reference resolves through — so the whole layer is dropped and no path
     * is ever built from the id. The two losses are counted separately for
     * the same reason: a lost layer reported as N lost tiles misleads.
     */
    private fun buildStack(id: String, file: ProjectFile): Pair<LayerStack, Int>? {
        if (file.layers.isEmpty()) return null
        val grid = try {
            TileGrid(file.width, file.height)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "project $id: invalid geometry", e)
            return null
        }
        var unreadable = 0
        val seenFolded = HashSet<String>()
        val layers = ArrayList<Layer>(file.layers.size)
        for (record in file.layers) {
            // R-001's policy half (the guard itself is LayerId's): a record
            // whose id is not a safe path segment cannot be repaired — a
            // "fixed" id would orphan the layer's tiles — so the layer is
            // dropped whole and counted, and the open goes on.
            val props = record.toPropsOrNull()
            if (props == null) {
                Log.w(TAG, "project $id: dropped a layer whose id is not a safe path segment")
                unreadable += 1
                continue
            }
            // R-029: two ids differing only by case name one directory the
            // moment the folder is copied to Windows or macOS. LayerStack
            // refuses the pair at construction — right for code *building* a
            // stack — but a document that arrives this way has to open, with
            // the second claimant counted among the unreadable. The same
            // locale-independent fold as LayerStack's own invariant.
            if (!seenFolded.add(props.id.value.lowercase())) {
                Log.w(
                    TAG,
                    "project $id: dropped layer ${props.id.value}: case-insensitive id collision",
                )
                unreadable += 1
                continue
            }
            val keys = TileStore(layerDir(id, props.id)).list()
                .filterTo(LinkedHashSet()) { grid.contains(it) }
            layers += Layer(props, keys)
        }
        if (layers.isEmpty()) {
            Log.w(TAG, "project $id: no readable layer remains")
            return null
        }
        // A missing or dropped active id degrades to the bottom layer — a
        // wrong selection has an obvious sane fallback, unlike a wrong id.
        val activeIndex = layers.indexOfFirst { it.id.value == file.activeLayerId }
            .let { if (it >= 0) it else 0 }
        val highWater = file.layers.maxOfOrNull { defaultLayerNameNumber(it.name) ?: 0 } ?: 0
        // The construction cannot throw here: the fold above guarantees the
        // uniqueness invariant, the empty case returned already, and the
        // index is in range by construction.
        val stack = LayerStack(
            layers = layers,
            activeIndex = activeIndex,
            nextName = maxOf(file.nextLayerName, highWater + 1),
        )
        return stack to unreadable
    }

    /**
     * The shelf, newest first by the document's own `updatedAt` — not file
     * mtime, which moves on checkpoints that only saved view state (06 §7).
     * A folder without a decodable `project.json` is skipped but not deleted
     * (mid-save, mid-delete, or corrupt — a support question, not an
     * eviction); `*.deleting` leftovers are swept.
     */
    fun list(): List<Summary> {
        val children = root.listFiles() ?: return emptyList()
        val out = ArrayList<Summary>(children.size)
        for (dir in children) {
            if (isDuplicateStage(dir)) {
                if (dir.name !in activeDuplicateStages) dir.deleteRecursively()
                continue
            }
            if (dir.name.endsWith(DELETING_SUFFIX)) {
                dir.deleteRecursively()
                continue
            }
            if (!dir.isDirectory || !isValidId(dir.name)) continue
            val jsonFile = File(dir, ProjectFile.FILE_NAME)
            if (!jsonFile.isFile) continue
            val file = try {
                json.decodeFromString(ProjectFile.serializer(), jsonFile.readText(Charsets.UTF_8))
            } catch (e: SerializationException) {
                Log.w(TAG, "project ${dir.name}: unreadable project.json, skipped", e)
                continue
            } catch (e: IOException) {
                Log.w(TAG, "project ${dir.name}: unreadable project.json, skipped", e)
                continue
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "project ${dir.name}: unreadable project.json, skipped", e)
                continue
            }
            if (file.formatVersion > ProjectFile.FORMAT_VERSION) {
                // Listed greyed-out with an explanation once the Studio grid
                // exists (06 §13, roadmap 3c); skipped until then.
                Log.w(TAG, "project ${dir.name}: newer format ${file.formatVersion}, skipped")
                continue
            }
            out += Summary(
                id = dir.name,
                title = file.title,
                updatedAt = file.updatedAt,
                width = file.width,
                height = file.height,
                layerCount = file.layers.size,
                thumbnail = File(dir, THUMB_NAME).takeIf { it.isFile },
                bytes = folderBytes(dir),
                galleryUri = file.galleryUri,
                lastGallerySyncAt = file.lastGallerySyncAt,
            )
        }
        out.sortByDescending { it.updatedAt }
        return out
    }

    /**
     * Deletes one painting: rename to `<uuid>.deleting` first — atomic within
     * the same filesystem — so a kill mid-delete leaves a folder [list]
     * ignores and sweeps, then delete recursively (06 §8).
     */
    fun delete(id: String) {
        if (!isValidId(id)) return
        val dir = projectDir(id)
        if (!dir.exists()) return
        val doomed = File(root, id + DELETING_SUFFIX)
        if (doomed.exists()) doomed.deleteRecursively()
        if (dir.renameTo(doomed)) {
            doomed.deleteRecursively()
        } else {
            // The rename failing (already half-deleted?) still must not leave
            // the painting behind.
            dir.deleteRecursively()
        }
    }

    /** §2: `*.tmp` swept from the project dir and every layer dir. */
    private fun sweepTmp(dir: File) {
        AtomicFiles.sweepTmp(dir)
        File(dir, LAYERS_DIR).listFiles()?.forEach { layerDir ->
            if (layerDir.isDirectory) AtomicFiles.sweepTmp(layerDir)
        }
    }

    private fun folderBytes(dir: File): Long =
        dir.walkTopDown().sumOf { if (it.isFile) it.length() else 0L }

    private fun isDuplicateStage(file: File): Boolean {
        if (!file.isDirectory || !file.name.endsWith(DUPLICATING_SUFFIX)) return false

        val id = file.name.removeSuffix(DUPLICATING_SUFFIX)
        return try {
            java.util.UUID.fromString(id)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** Refuse future data; migrate every older metadata rewrite (§13). */
    private fun ProjectFile.currentForWrite(id: String): ProjectFile? {
        if (formatVersion > ProjectFile.FORMAT_VERSION) {
            Log.w(TAG, "project $id: newer format $formatVersion, write skipped")
            return null
        }

        return copy(formatVersion = ProjectFile.FORMAT_VERSION)
    }

    internal companion object {
        // Every store instance must spare a duplicate another instance owns.
        private val activeDuplicateStages = ConcurrentHashMap.newKeySet<String>()

        const val TAG = "ProjectStore"
        const val LAYERS_DIR = "layers"
        const val THUMB_NAME = "thumb.png"
        const val DELETING_SUFFIX = ".deleting"
        const val DUPLICATING_SUFFIX = ".duplicating"

        /**
         * §3's reader/writer settings. `ignoreUnknownKeys` is what lets an
         * older reader open a newer file on the fields it knows.
         *
         * `allowSpecialFloatingPointValues` is R-020: kotlinx's default
         * decoder throws on a `NaN`/`Infinity` token *before* any per-field
         * degrading code runs, so without it §4's "one bad field must never
         * fail an open" could not hold — `LayerRecord.toProps` degrades such
         * an opacity, but only if the token reaches it. On the write side it
         * is inert: nothing here ever produces a non-finite value to encode
         * (`LayerProps` refuses one at construction).
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            allowSpecialFloatingPointValues = true
        }
    }
}
