package ch.lkmc.bangnidraw.data

import android.util.Log
import ch.lkmc.bangnidraw.engine.core.Palette
import ch.lkmc.bangnidraw.engine.core.isSafePathSegment
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Atomic persistence for global user palettes under `filesDir/palettes/`. */
class PaletteStore internal constructor(private val root: File) {

    @Synchronized
    fun load(): List<Palette> {
        AtomicFiles.sweepTmp(root)
        return root.listFiles { file -> file.isFile && file.name.endsWith(JSON_SUFFIX) }
            ?.sortedBy(File::getName)
            ?.mapNotNull(::read)
            .orEmpty()
    }

    @Throws(IOException::class)
    @Synchronized
    fun save(palette: Palette) {
        require(!palette.builtIn) { "built-in palettes are immutable" }
        require(isSafePathSegment(palette.id)) { "palette id must be a safe path segment" }
        if (!root.isDirectory && !root.mkdirs()) throw IOException("could not create $root")

        AtomicFiles.write(file(palette.id), json.encodeToString(palette).toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (!isSafePathSegment(id)) return false
        val target = file(id)
        return !target.exists() || target.delete()
    }

    private fun read(file: File): Palette? = try {
        val palette = json.decodeFromString<Palette>(file.readText(Charsets.UTF_8))
        if (palette.builtIn || file.nameWithoutExtension != palette.id) {
            Log.w(TAG, "user palette ${file.name} has invalid ownership; dropped")
            null
        } else {
            palette
        }
    } catch (e: IOException) {
        Log.w(TAG, "user palette ${file.name} could not be read", e)
        null
    } catch (e: SerializationException) {
        Log.w(TAG, "user palette ${file.name} is unreadable; dropped", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "user palette ${file.name} is invalid; dropped", e)
        null
    }

    private fun file(id: String): File = File(root, "$id$JSON_SUFFIX")

    private companion object {
        const val TAG = "PaletteStore"
        const val JSON_SUFFIX = ".json"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
