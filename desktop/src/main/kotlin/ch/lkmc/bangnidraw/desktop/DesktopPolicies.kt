package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BrushMixingPolicy
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.ColorMixer
import ch.lkmc.bangnidraw.engine.core.StrokeMode
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.TileKey

internal enum class DesktopMouseMode {
    Draw,
    Erase,
}

/** Desktop-only adaptations that stay independent from Compose and GL. */
internal object DesktopStrokePolicy {
    fun source(source: StrokeSource, mouseMode: DesktopMouseMode): StrokeSource {
        if (source != StrokeSource.MOUSE) return source

        return if (mouseMode == DesktopMouseMode.Erase) {
            StrokeSource.ERASER_END
        } else {
            StrokeSource.MOUSE
        }
    }

    fun mode(source: StrokeSource, preset: BrushPreset, mixer: ColorMixer): StrokeMode {
        if (source == StrokeSource.ERASER_END) return StrokeMode.ERASE

        return BrushMixingPolicy.mode(preset, mixer)
    }
}

internal object DesktopTileMirror {
    fun apply(
        target: MutableMap<TileKey, ByteArray>,
        images: Map<TileKey, ByteArray?>,
    ) {
        for ((key, pixels) in images) {
            if (pixels == null) {
                target.remove(key)
                continue
            }

            target[key] = pixels.copyOf()
        }
    }

    fun snapshot(
        source: Map<TileKey, ByteArray>?,
        keys: List<TileKey>,
    ): Map<TileKey, ByteArray?> {
        val snapshot = HashMap<TileKey, ByteArray?>(keys.size)
        for (key in keys) snapshot[key] = source?.get(key)?.copyOf()

        return snapshot
    }
}

internal object DesktopBrushUi {
    fun label(preset: BrushPreset): String {
        val key = preset.name.removePrefix(ANDROID_STRING_PREFIX)
        if (key == preset.name) return preset.name

        return key
            .removePrefix(PRESET_PREFIX)
            .split('_')
            .joinToString(" ") { word ->
                word.replaceFirstChar { first -> first.uppercase() }
            }
    }

    fun sizeRange(preset: BrushPreset): ClosedFloatingPointRange<Float> =
        preset.sizeMin..preset.sizeMax

    private const val ANDROID_STRING_PREFIX = "@string/"
    private const val PRESET_PREFIX = "preset_"
}
