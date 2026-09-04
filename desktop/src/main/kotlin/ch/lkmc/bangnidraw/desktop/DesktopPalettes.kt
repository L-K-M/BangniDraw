package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.DishWell
import ch.lkmc.bangnidraw.engine.core.Palette
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Where an armed eyedropper read goes instead of the paint colour. */
internal sealed interface DesktopPickTarget {
    data class Well(val well: DishWell) : DesktopPickTarget

    data class Swatch(val paletteId: String, val index: Int) : DesktopPickTarget
}

/**
 * The user's palettes as one preference value.
 *
 * `Palette` is already `@Serializable` for `:app`'s per-palette files, so
 * this is that same encoding in a list. Decoding is total: a value the store
 * held from an older build, or one edited by hand, yields null and leaves the
 * session's palettes alone rather than throwing on the restore path — and a
 * built-in id inside it is dropped, because the catalogue owns those and a
 * stored copy would shadow one with stale swatches.
 */
internal object DesktopPaletteCodec {

    fun encode(palettes: List<Palette>): String =
        json.encodeToString(ListSerializer(Palette.serializer()), palettes)

    fun decode(stored: String?): List<Palette>? {
        if (stored.isNullOrBlank()) return null

        return try {
            json.decodeFromString(ListSerializer(Palette.serializer()), stored)
                .filterNot(Palette::builtIn)
        } catch (error: SerializationException) {
            System.err.println("stored palettes are unreadable; kept the defaults: ${error.message}")
            null
        } catch (error: IllegalArgumentException) {
            // `Palette`'s own init guards: a blank name or an unsafe id.
            System.err.println("stored palettes are invalid; kept the defaults: ${error.message}")
            null
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
