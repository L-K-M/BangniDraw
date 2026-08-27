package ch.lkmc.bangnidraw.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import ch.lkmc.bangnidraw.BuildConfig
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.DishState
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.PaletteCatalog
import ch.lkmc.bangnidraw.engine.core.PenButtonAction
import ch.lkmc.bangnidraw.engine.core.PigmentAvailability
import ch.lkmc.bangnidraw.engine.core.StoredColors
import ch.lkmc.bangnidraw.engine.core.TouchDrawingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class BrushTuning(val size: Float?, val opacity: Float?)

/**
 * The app's few durable settings (`docs/plan/06-document-and-persistence.md`
 * §12), on Preferences DataStore — created by roadmap 3c, which is why 2.5d's
 * debug overlay could only *name* `debugLatency` in comments.
 *
 * The file lives under `files/datastore/`, the one path the backup rules
 * allowlist (06 §11): settings survive a device transfer, paintings —
 * deliberately — do not.
 */
@Singleton
class Prefs @Inject constructor(@ApplicationContext context: Context) {

    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(STORE_NAME) },
    )

    /**
     * Mints the number for a new painting's title — "Sketch N" (06 §10).
     * Incremented at creation and persisted in the same atomic edit, so
     * numbers never repeat even after deletes.
     */
    suspend fun nextSketchNumber(): Int {
        var minted = 1
        dataStore.edit { prefs ->
            minted = (prefs[KEY_NEXT_SKETCH] ?: 1)
            prefs[KEY_NEXT_SKETCH] = minted + 1
        }
        return minted
    }

    /** 06 §9.4; no writer until the gallery mirror lands (roadmap step 4). */
    val gallerySync: Flow<Boolean> =
        dataStore.data.map { it[KEY_GALLERY_SYNC] ?: true }

    suspend fun setGallerySync(enabled: Boolean) {
        dataStore.edit { it[KEY_GALLERY_SYNC] = enabled }
    }

    internal val handedness: Flow<Hand> =
        dataStore.data.map { Hand.fromStored(it[KEY_HANDEDNESS]) }

    internal suspend fun setHandedness(hand: Hand) {
        dataStore.edit { it[KEY_HANDEDNESS] = hand.name }
    }

    internal val touchDrawingMode: Flow<TouchDrawingMode> =
        dataStore.data.map { TouchDrawingMode.fromStored(it[KEY_TOUCH_DRAWING]) }

    internal suspend fun setTouchDrawingMode(mode: TouchDrawingMode) {
        dataStore.edit { it[KEY_TOUCH_DRAWING] = mode.name }
    }

    internal val hapticsMode: Flow<HapticsMode> =
        dataStore.data.map { HapticsMode.fromStored(it[KEY_HAPTICS]) }

    internal suspend fun setHapticsMode(mode: HapticsMode) {
        dataStore.edit { it[KEY_HAPTICS] = mode.name }
    }

    val hintShown: Flow<Boolean> =
        dataStore.data.map { it[KEY_HINT_SHOWN] ?: false }

    suspend fun markHintShown() {
        dataStore.edit { it[KEY_HINT_SHOWN] = true }
    }

    /**
     * The debug overlay's switch (`10-performance.md` §5.3). The overlay
     * still gates on `BuildConfig.DEBUG` until the Settings screen (roadmap
     * step 10) gives this a toggle — a pref nothing can flip would only turn
     * the overlay off for the developers it exists for.
     */
    val debugLatency: Flow<Boolean> =
        dataStore.data.map { it[KEY_DEBUG_LATENCY] ?: false }

    suspend fun setDebugLatency(enabled: Boolean) {
        dataStore.edit { it[KEY_DEBUG_LATENCY] = enabled }
    }

    val penButtonAction: Flow<PenButtonAction> =
        dataStore.data.map { PenButtonAction.fromStored(it[KEY_PEN_BUTTON_ACTION]) }

    suspend fun setPenButtonAction(action: PenButtonAction) {
        dataStore.edit { it[KEY_PEN_BUTTON_ACTION] = action.name }
    }

    val eraserEndPreset: Flow<String> =
        dataStore.data.map { it[KEY_ERASER_END_PRESET] ?: BrushPresets.HARD_ERASER_ID }

    suspend fun setEraserEndPreset(id: String) {
        dataStore.edit { it[KEY_ERASER_END_PRESET] = id }
    }

    val mixerChoice: Flow<MixerChoice> =
        dataStore.data.map { MixerChoice.fromStored(it[KEY_MIXER], pigmentAvailability) }

    suspend fun setMixerChoice(choice: MixerChoice) {
        val stored = MixerChoice.fromStored(choice.name, pigmentAvailability)
        dataStore.edit { it[KEY_MIXER] = stored.name }
    }

    val activePaletteId: Flow<String> =
        dataStore.data.map { it[KEY_ACTIVE_PALETTE] ?: PaletteCatalog.PAINTERS_ID }

    suspend fun setActivePalette(id: String) {
        dataStore.edit { it[KEY_ACTIVE_PALETTE] = id }
    }

    val dish: Flow<DishState> = dataStore.data.map {
        DishState(
            a = it[KEY_DISH_A] ?: PaletteCatalog.ULTRAMARINE_BLUE_ARGB.toInt(),
            b = it[KEY_DISH_B] ?: PaletteCatalog.CADMIUM_YELLOW_ARGB.toInt(),
        )
    }

    suspend fun setDishWells(a: Int, b: Int) {
        dataStore.edit {
            it[KEY_DISH_A] = a
            it[KEY_DISH_B] = b
        }
    }

    val recentColors: Flow<List<Int>> =
        dataStore.data.map { StoredColors.decode(it[KEY_RECENT_COLORS]) }

    suspend fun setRecentColors(colors: List<Int>) {
        dataStore.edit { it[KEY_RECENT_COLORS] = StoredColors.encode(colors) }
    }

    /** Rail size/opacity live outside preset JSON (`04-tools.md` §5.1). */
    internal suspend fun brushTunings(ids: Iterable<String>): Map<String, BrushTuning> {
        val snapshot = dataStore.data.first()
        return ids.associateWith { id ->
            BrushTuning(snapshot[sizeKey(id)], snapshot[opacityKey(id)])
        }
    }

    internal suspend fun setBrushTuning(id: String, size: Float, opacity: Float) {
        dataStore.edit {
            it[sizeKey(id)] = size
            it[opacityKey(id)] = opacity
        }
    }

    internal suspend fun clearBrushTuning(id: String) {
        dataStore.edit {
            it.remove(sizeKey(id))
            it.remove(opacityKey(id))
        }
    }

    private fun sizeKey(id: String) = floatPreferencesKey("brushSize.$id")

    private fun opacityKey(id: String) = floatPreferencesKey("brushOpacity.$id")

    private val pigmentAvailability: PigmentAvailability
        get() = if (BuildConfig.MIXBOX) PigmentAvailability.AVAILABLE else PigmentAvailability.ABSENT

    private companion object {
        const val STORE_NAME = "bangni"
        val KEY_NEXT_SKETCH = intPreferencesKey("nextSketchNumber")
        val KEY_GALLERY_SYNC = booleanPreferencesKey("gallerySync")
        val KEY_HANDEDNESS = stringPreferencesKey("handedness")
        val KEY_TOUCH_DRAWING = stringPreferencesKey("touchDrawing")
        val KEY_HAPTICS = stringPreferencesKey("haptics")
        val KEY_HINT_SHOWN = booleanPreferencesKey("hintShown")
        val KEY_DEBUG_LATENCY = booleanPreferencesKey("debugLatency")
        val KEY_PEN_BUTTON_ACTION = stringPreferencesKey("penButtonAction")
        val KEY_ERASER_END_PRESET = stringPreferencesKey("eraserEndPreset")
        val KEY_MIXER = stringPreferencesKey("mixer")
        val KEY_ACTIVE_PALETTE = stringPreferencesKey("activePalette")
        val KEY_DISH_A = intPreferencesKey("dishA")
        val KEY_DISH_B = intPreferencesKey("dishB")
        val KEY_RECENT_COLORS = stringPreferencesKey("recent_colors")
    }
}
