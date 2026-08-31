package ch.lkmc.bangnidraw.desktop

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.HsvColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class DesktopPrefs {

    private val scope = CoroutineScope(DispatcherIO + SupervisorJob())
    private val store = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { java.io.File(DesktopPlatform.configDir(), "desktop.preferences_pb") },
    )

    val brushId: Flow<String?> = store.data.map { it[BRUSH_ID] }
    val colorArgb: Flow<Int?> = store.data.map { it[COLOR] }

    suspend fun readBrushId(): String? = brushId.first()
    suspend fun readColorArgb(): Int? = colorArgb.first()

    fun writeBrush(preset: BrushPreset) {
        scope.launchEdit {
            it[BRUSH_ID] = preset.id
        }
    }

    fun writeColor(argb: Int) {
        scope.launchEdit {
            it[COLOR] = argb
        }
    }

    fun close() {
        // Drain queued edit writes so the last brush/color isn't lost on
        // exit; the children run on Dispatchers.IO, so blocking here is safe.
        kotlinx.coroutines.runBlocking {
            scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.join() }
        }
        scope.cancel()
    }

    private fun CoroutineScope.launchEdit(
        block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        launch { store.edit(block) }
    }

    private companion object {
        val BRUSH_ID = stringPreferencesKey("brush_id")
        val COLOR = intPreferencesKey("color_argb")
        val DispatcherIO = Dispatchers.IO
    }
}

/** Swatch starter colors for the picker, in the engine's HSV terms. */
object DesktopPalette {
    val SWATCHES = listOf(
        0xFF212121.toInt(), 0xFF6D4C41.toInt(), 0xFFD84F45.toInt(), 0xFFFF9E1B.toInt(),
        0xFFFFDB44.toInt(), 0xFF7CB342.toInt(), 0xFF26A69A.toInt(), 0xFF3E86C8.toInt(),
        0xFF5C4BC8.toInt(), 0xFFB65AC4.toInt(), 0xFFFFFFFF.toInt(), 0x00000000,
    )

    fun toStrokeRgb(argb: Int): FloatArray {
        val hsv = HsvColor.fromArgb(argb)
        return floatArrayOf(hsv.h, hsv.s, hsv.v)
    }
}
