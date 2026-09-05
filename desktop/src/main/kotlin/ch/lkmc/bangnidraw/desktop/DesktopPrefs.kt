package ch.lkmc.bangnidraw.desktop

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

/**
 * Desktop preferences on the JVM DataStore artifact (DESKTOP.md "Data
 * layer"): the same `PreferenceDataStoreFactory` API the Android app uses,
 * over a file in the OS config directory. v1 persists the brush choice and
 * the paint color; everything else is session state.
 */
internal class DesktopPrefs(
    private val file: java.io.File = java.io.File(DesktopPlatform.configDir(), STORE_FILE),
) {

    /**
     * One queued write. Generic rather than one arm per setting: the shell
     * grew from two preferences to a settings window's worth, and a sealed
     * arm each would be a list to forget to extend.
     */
    private sealed interface PreferenceUpdate {
        data class Text(val key: String, val value: String) : PreferenceUpdate
        data class Number(val key: String, val value: Int) : PreferenceUpdate
    }

    private val scope = CoroutineScope(DispatcherIO + SupervisorJob())
    private val storeScope = CoroutineScope(DispatcherIO + SupervisorJob())
    private val store = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { error ->
            System.err.println("corrupt desktop preferences reset: ${error.message}")
            emptyPreferences()
        },
        scope = storeScope,
        produceFile = { file },
    )
    private val updates = Channel<PreferenceUpdate>(Channel.UNLIMITED)
    private val writer = scope.launch {
        for (update in updates) {
            try {
                store.edit { preferences ->
                    when (update) {
                        is PreferenceUpdate.Text ->
                            preferences[stringPreferencesKey(update.key)] = update.value
                        is PreferenceUpdate.Number ->
                            preferences[intPreferencesKey(update.key)] = update.value
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                System.err.println("desktop preferences could not be saved: ${error.message}")
            }
        }
    }

    val brushId: Flow<String?> = text(BRUSH_ID)
    val colorArgb: Flow<Int?> = number(COLOR)

    suspend fun readBrushId(): String? = brushId.first()
    suspend fun readColorArgb(): Int? = colorArgb.first()

    fun writeBrush(preset: BrushPreset) {
        writeText(BRUSH_ID, preset.id)
    }

    fun writeColor(argb: Int) {
        writeNumber(COLOR, argb)
    }

    /**
     * A DataStore `Preferences.Key` compares and hashes by **name alone**, so
     * `stringPreferencesKey("hand")` and `intPreferencesKey("hand")` are one
     * slot. A name written through [writeNumber] and read through here throws
     * `ClassCastException` inside the flow's `map`, taking down every
     * collector of it, far from the write that caused it. Each name below is
     * text or number for the life of the app, never both.
     */
    fun text(key: String): Flow<String?> = store.data.map { it[stringPreferencesKey(key)] }

    fun number(key: String): Flow<Int?> = store.data.map { it[intPreferencesKey(key)] }

    suspend fun readText(key: String): String? = text(key).first()

    suspend fun readNumber(key: String): Int? = number(key).first()

    fun writeText(key: String, value: String) {
        send(PreferenceUpdate.Text(key, value))
    }

    fun writeNumber(key: String, value: Int) {
        send(PreferenceUpdate.Number(key, value))
    }

    fun close() {
        updates.close()
        kotlinx.coroutines.runBlocking {
            writer.join()
            storeScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
        scope.cancel()
    }

    private fun send(update: PreferenceUpdate) {
        if (updates.trySend(update).isSuccess) return

        System.err.println("desktop preference ignored after close")
    }

    private companion object {
        const val STORE_FILE = "desktop.preferences_pb"
        const val BRUSH_ID = "brush_id"
        const val COLOR = "color_argb"
        val DispatcherIO = Dispatchers.IO
    }
}

internal enum class DesktopPreferenceKind {
    Brush,
    Color,
    Settings,
}

/** The setting keys the shell persists, in one place so none is misspelled twice. */
internal object DesktopPreferenceKeys {
    const val THEME = "app_theme"
    const val HAND = "hand"
    const val MIXER = "mixer_choice"
    /** A number, 1/0 — see [DesktopPrefs.text] for why the choice is binding. */
    const val SNAP_RIGHT_ANGLES = "snap_right_angles"
    const val RECENT_COLORS = "recent_colors"
    const val PALETTES = "user_palettes"
}

/** Prevents a slow initial DataStore read from replacing fresh UI input. */
internal class DesktopPreferenceRestoreGate {
    private val changed = java.util.EnumSet.noneOf(DesktopPreferenceKind::class.java)

    fun markChanged(kind: DesktopPreferenceKind) {
        changed.add(kind)
    }

    fun allows(kind: DesktopPreferenceKind): Boolean = kind !in changed
}

/** Swatch starter colors for the picker, in the engine's HSV terms. */
internal object DesktopPalette {
    val SWATCHES = listOf(
        0xFF212121.toInt(), 0xFF6D4C41.toInt(), 0xFFD84F45.toInt(), 0xFFFF9E1B.toInt(),
        0xFFFFDB44.toInt(), 0xFF7CB342.toInt(), 0xFF26A69A.toInt(), 0xFF3E86C8.toInt(),
        0xFF5C4BC8.toInt(), 0xFFB65AC4.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
    )

    /**
     * The paper choices `:app`'s layer panel offers, in its order and with
     * its colours (`ui/theme/Color.kt`'s `PaperSwatch*`). Transparent is one
     * of them: the renderer draws its checker, and an export keeps the alpha.
     */
    val PAPERS = listOf(
        "paper_white" to 0xFFFFFFFF.toInt(),
        "paper_warm" to 0xFFF8F1E3.toInt(),
        "paper_gray" to 0xFF9E9E9E.toInt(),
        "paper_black" to 0xFF000000.toInt(),
        "paper_transparent" to 0x00000000,
    )

    /** The stroke color as normalized RGB floats — what the engine's `u_color` takes. */
    fun toStrokeRgb(argb: Int): FloatArray = floatArrayOf(
        ((argb ushr 16) and 0xFF) / 255f,
        ((argb ushr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
    )
}
