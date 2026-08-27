package ch.lkmc.bangnidraw.data

import android.util.Log
import ch.lkmc.bangnidraw.engine.core.BrushModel
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.isSafePathSegment
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Loads immutable built-ins from assets and user overrides from
 * `filesDir/brushes/` (`docs/plan/04-tools.md` §5.1).
 *
 * The store takes a plain [File] and an asset abstraction. Its decisions and
 * disk behavior therefore run in the JVM suite without a Context.
 */
class BrushPresetStore internal constructor(
    private val root: File,
    private val assets: BrushPresetAssets,
) {

    /** Built-ins in asset order, replaced or extended by valid user files. */
    fun load(): List<BrushPreset> {
        AtomicFiles.sweepTmp(root)

        val presets = LinkedHashMap<String, BrushPreset>()
        loadBuiltIns(presets)
        loadUsers(presets)
        return presets.values.toList()
    }

    /** Writes a user preset or built-in override; asset bytes are never touched. */
    @Throws(IOException::class)
    fun save(preset: BrushPreset) {
        require(isSafePathSegment(preset.id)) {
            "preset id must be a single safe path segment"
        }
        if (!root.isDirectory && !root.mkdirs()) throw IOException("could not create $root")

        AtomicFiles.write(userFile(preset.id), encode(preset).toByteArray(Charsets.UTF_8))
    }

    /** Deletes only the user file, revealing the immutable built-in again. */
    fun reset(id: String): Boolean {
        if (!isSafePathSegment(id)) return false

        val file = userFile(id)
        return !file.exists() || file.delete()
    }

    private fun loadBuiltIns(out: LinkedHashMap<String, BrushPreset>) {
        val names = try {
            assets.names()
        } catch (e: IOException) {
            Log.w(TAG, "brush assets could not be listed", e)
            return
        }

        for (name in names) {
            val preset = try {
                decode(name, assets.read(name), PresetOrigin.Asset)
            } catch (e: IOException) {
                Log.w(TAG, "brush asset $name could not be read", e)
                null
            }
            if (preset == null) continue
            if (out.putIfAbsent(preset.id, preset) == null) continue

            Log.w(TAG, "brush asset $name duplicates preset ${preset.id}; dropped")
        }
    }

    private fun loadUsers(out: LinkedHashMap<String, BrushPreset>) {
        val files = root.listFiles { file -> file.isFile && file.name.endsWith(JSON_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

        for (file in files) {
            val preset = try {
                decode(file.name, file.readText(Charsets.UTF_8), PresetOrigin.User)
            } catch (e: IOException) {
                Log.w(TAG, "user brush ${file.name} could not be read", e)
                null
            }
            if (preset == null) continue
            if (file.nameWithoutExtension == preset.id) {
                out[preset.id] = preset
                continue
            }

            Log.w(TAG, "user brush ${file.name} has id ${preset.id}; dropped")
        }
    }

    private fun decode(source: String, text: String, origin: PresetOrigin): BrushPreset? = try {
        val element = json.parseToJsonElement(text)
        val version = element.jsonObject[VERSION_KEY]?.jsonPrimitive?.intOrNull ?: FORMAT_VERSION
        if (version > FORMAT_VERSION) {
            Log.w(TAG, "brush $source uses newer format $version; dropped")
            return null
        }

        val decoded = json.decodeFromJsonElement<BrushPreset>(element)
        // Old calligraphy overrides predate the model field. Preserve every
        // user edit while adopting the built-in's new rendering semantics.
        val preset = if (
            origin == PresetOrigin.User &&
            decoded.id == BrushPresets.CALLIGRAPHY_ID &&
            MODEL_KEY !in element.jsonObject
        ) {
            decoded.copy(model = BrushModel.ChineseInk)
        } else {
            decoded
        }
        if (!isSafePathSegment(preset.id)) {
            Log.w(TAG, "brush $source has an unsafe id; dropped")
            return null
        }
        preset
    } catch (e: SerializationException) {
        Log.w(TAG, "brush $source is unreadable; dropped", e)
        null
    } catch (e: IllegalArgumentException) {
        // BrushPreset and its nested value types validate on construction.
        Log.w(TAG, "brush $source is invalid; dropped", e)
        null
    }

    private fun encode(preset: BrushPreset): String {
        val fields = json.encodeToJsonElement(preset).jsonObject
        val versioned = buildJsonObject {
            put(VERSION_KEY, FORMAT_VERSION)
            for ((key, value) in fields) put(key, value)
        }
        return json.encodeToString(versioned)
    }

    private fun userFile(id: String): File = File(root, "$id$JSON_SUFFIX")

    private enum class PresetOrigin {
        Asset,
        User,
    }

    private companion object {
        const val TAG = "BrushPresetStore"
        const val FORMAT_VERSION = 1
        const val VERSION_KEY = "v"
        const val MODEL_KEY = "model"
        const val JSON_SUFFIX = ".json"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
