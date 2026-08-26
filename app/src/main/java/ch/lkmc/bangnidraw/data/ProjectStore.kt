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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The project-folder lifecycle: `<root>/<uuid>/` with `project.json` as the
 * commit point (`docs/plan/06-document-and-persistence.md` §2, §3, §7).
 *
 * Takes a plain [File] root — the Hilt module passes
 * `File(context.filesDir, "projects")` — so the whole class runs on the JVM
 * suite against a temp dir (`docs/plan/11-testing.md` §5). IO thread only.
 */
class ProjectStore(private val root: File) {

    sealed interface LoadResult {
        /**
         * The document opened. [unreadableLayers] counts layer records the
         * loader had to drop — reported *beside* the tile count, never folded
         * into it, because a lost layer shown as N lost tiles is a misleading
         * readout (`docs/plan/12-roadmap.md` step 3).
         */
        data class Loaded(val document: Document, val unreadableLayers: Int) : LoadResult

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
        val dir = projectDir(document.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
        sweepTmp(dir)
        val bytes = json.encodeToString(ProjectFile.serializer(), document.toProjectFile())
            .toByteArray(Charsets.UTF_8)
        AtomicFiles.write(File(dir, ProjectFile.FILE_NAME), bytes)
    }

    /**
     * Opens one painting: `project.json` → [Document], with the layer tile
     * sets rebuilt from the directory listing (an absent file is an empty
     * tile, so the listing *is* the tile set). Tile pixels are not read here
     * — the caller streams them per layer through [TileStore] (06 §5.7).
     */
    fun load(id: String): LoadResult {
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
        return LoadResult.Loaded(document, unreadableLayers)
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

    internal companion object {
        const val TAG = "ProjectStore"
        const val LAYERS_DIR = "layers"
        const val THUMB_NAME = "thumb.png"
        const val DELETING_SUFFIX = ".deleting"

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
