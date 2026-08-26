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
     * listed from `layers/<id>/`. Null when no readable layer remains — a
     * document with no layers at all is corrupt (a stack is never empty).
     *
     * Roadmap 3a group 2 makes this degrade per layer (R-001, R-029); for
     * now any bad record fails the open, which is strictly more cautious.
     */
    private fun buildStack(id: String, file: ProjectFile): Pair<LayerStack, Int>? {
        if (file.layers.isEmpty()) return null
        val grid = try {
            TileGrid(file.width, file.height)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "project $id: invalid geometry", e)
            return null
        }
        val layers = try {
            file.layers.map { record ->
                val props = record.toProps()
                val keys = TileStore(layerDir(id, props.id)).list()
                    .filterTo(LinkedHashSet()) { grid.contains(it) }
                Layer(props, keys)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "project $id: unreadable layer stack", e)
            return null
        }
        val activeIndex = layers.indexOfFirst { it.id.value == file.activeLayerId }
            .let { if (it >= 0) it else 0 }
        val highWater = file.layers.maxOfOrNull { defaultLayerNameNumber(it.name) ?: 0 } ?: 0
        val stack = try {
            LayerStack(
                layers = layers,
                activeIndex = activeIndex,
                nextName = maxOf(file.nextLayerName, highWater + 1),
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "project $id: unreadable layer stack", e)
            return null
        }
        return stack to 0
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
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
