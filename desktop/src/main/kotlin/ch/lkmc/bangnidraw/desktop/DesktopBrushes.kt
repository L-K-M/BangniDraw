package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BrushPreset
import kotlinx.serialization.json.Json

/**
 * The shipped brush presets for the desktop shell, read from the single
 * copy under `app/src/main/assets/brushes` that ships as desktop classpath
 * resources (`brushes` JSON files). The Android app reads the same files as
 * assets; the engine-core tests pin their shipped values.
 */
object DesktopBrushes {

    fun loadAll(): List<BrushPreset> {
        val json = Json { ignoreUnknownKeys = true }
        val names = BRUSHES_INDEX.map { "$BRUSH_DIR/$it.json" }
        val presets = names.mapNotNull { path ->
            val text = javaClass.getResourceAsStream("/$path")?.bufferedReader()?.use { it.readText() }
            if (text == null) {
                System.err.println("missing brush resource: $path")
                null
            } else {
                json.decodeFromString<BrushPreset>(text)
            }
        }
        check(presets.isNotEmpty()) { "no brush presets found on the classpath" }
        return presets
    }

    /** The rail order the Android app shows (`BrushPresets.RAIL_ORDER`). */
    private val BRUSHES_INDEX = listOf(
        "pencil", "ink_pen", "paintbrush", "airbrush", "marker",
        "spray_can", "charcoal", "soft_pastel", "technical_pen",
        "calligraphy", "dry_brush", "oil_paint", "pigment_wash",
        "hard_eraser", "soft_eraser",
    )

    private const val BRUSH_DIR = "brushes"
}
