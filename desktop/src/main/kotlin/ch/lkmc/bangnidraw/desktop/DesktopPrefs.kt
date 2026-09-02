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

    private sealed interface PreferenceUpdate {
        data class Brush(val id: String) : PreferenceUpdate
        data class Color(val argb: Int) : PreferenceUpdate
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
                        is PreferenceUpdate.Brush -> preferences[BRUSH_ID] = update.id
                        is PreferenceUpdate.Color -> preferences[COLOR] = update.argb
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                System.err.println("desktop preferences could not be saved: ${error.message}")
            }
        }
    }

    val brushId: Flow<String?> = store.data.map { it[BRUSH_ID] }
    val colorArgb: Flow<Int?> = store.data.map { it[COLOR] }

    suspend fun readBrushId(): String? = brushId.first()
    suspend fun readColorArgb(): Int? = colorArgb.first()

    fun writeBrush(preset: BrushPreset) {
        send(PreferenceUpdate.Brush(preset.id))
    }

    fun writeColor(argb: Int) {
        send(PreferenceUpdate.Color(argb))
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
        val BRUSH_ID = stringPreferencesKey("brush_id")
        val COLOR = intPreferencesKey("color_argb")
        val DispatcherIO = Dispatchers.IO
    }
}

internal enum class DesktopPreferenceKind {
    Brush,
    Color,
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

    /** The stroke color as normalized RGB floats — what the engine's `u_color` takes. */
    fun toStrokeRgb(argb: Int): FloatArray = floatArrayOf(
        ((argb ushr 16) and 0xFF) / 255f,
        ((argb ushr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
    )
}
