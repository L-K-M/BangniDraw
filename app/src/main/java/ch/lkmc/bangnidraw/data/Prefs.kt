package ch.lkmc.bangnidraw.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import ch.lkmc.bangnidraw.BuildConfig
import ch.lkmc.bangnidraw.engine.core.AppTheme
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.CompositionGuideVisibility
import ch.lkmc.bangnidraw.engine.core.DishState
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.PaletteCatalog
import ch.lkmc.bangnidraw.engine.core.PaintSlotAssignments
import ch.lkmc.bangnidraw.engine.core.PenButtonAction
import ch.lkmc.bangnidraw.engine.core.PigmentAvailability
import ch.lkmc.bangnidraw.engine.core.PressurePreference
import ch.lkmc.bangnidraw.engine.core.StoredColors
import ch.lkmc.bangnidraw.engine.core.StoredPaintSlots
import ch.lkmc.bangnidraw.engine.core.TouchDrawingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
class Prefs @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope applicationScope: CoroutineScope,
) {

    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { error ->
            Log.w(TAG, PREFERENCE_CORRUPTION_MESSAGE, error)
            emptyPreferences()
        },
        produceFile = { context.preferencesDataStoreFile(STORE_NAME) },
    )

    // One app-scoped writer makes the last assignment win across canvases.
    private val paintSlotUpdates = Channel<List<String>>(Channel.CONFLATED)
    private val paintSlotState = MutableStateFlow<List<String>?>(null)
    private val paintSlotLock = Any()

    init {
        applicationScope.launch {
            val stored = runCatching {
                StoredPaintSlots.decode(dataStore.data.first()[KEY_PAINT_SLOTS])
            }.onFailure {
                Log.w(TAG, "paint slots could not be loaded", it)
            }.getOrDefault(emptyList())

            synchronized(paintSlotLock) {
                if (paintSlotState.value == null) paintSlotState.value = stored
            }

            for (presetIds in paintSlotUpdates) {
                runCatching {
                    dataStore.edit {
                        it[KEY_PAINT_SLOTS] = StoredPaintSlots.encode(presetIds)
                    }
                }.onFailure {
                    Log.w(TAG, "paint slots could not be saved", it)
                }
            }
        }
    }

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

    internal val appTheme: Flow<AppTheme> =
        dataStore.data
            .map { AppTheme.fromStored(it[KEY_APP_THEME]) }
            .retryIoWithInitialFallback(
                fallback = AppTheme.DEFAULT,
                onFirstIoFailure = { error ->
                    Log.w(TAG, THEME_READ_FAILURE_MESSAGE, error)
                },
                onRetriesExhausted = { error ->
                    Log.w(TAG, THEME_READ_EXHAUSTED_MESSAGE, error)
                },
                pauseBeforeRetry = { attempt ->
                    delay(PREFERENCE_READ_RETRY_DELAY_MS shl attempt.toInt())
                },
            )

    internal suspend fun setAppTheme(theme: AppTheme) {
        dataStore.edit { it[KEY_APP_THEME] = theme.name }
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

    internal val pressurePreference: Flow<PressurePreference> =
        dataStore.data.map { PressurePreference.fromStored(it[KEY_PRESSURE_PREFERENCE]) }

    internal suspend fun setPressurePreference(preference: PressurePreference) {
        dataStore.edit { it[KEY_PRESSURE_PREFERENCE] = preference.name }
    }

    /** Right-angle rotation snapping while navigating (07 §7), off by default. */
    internal val snapRightAngles: Flow<Boolean> =
        dataStore.data.map { it[KEY_SNAP_RIGHT_ANGLES] ?: false }

    internal suspend fun setSnapRightAngles(enabled: Boolean) {
        dataStore.edit { it[KEY_SNAP_RIGHT_ANGLES] = enabled }
    }

    /** The canvas-only composition overlay, hidden by default. */
    internal val compositionGuideVisibility: Flow<CompositionGuideVisibility> =
        dataStore.data.map {
            if (it[KEY_COMPOSITION_GUIDES] == true) {
                CompositionGuideVisibility.VISIBLE
            } else {
                CompositionGuideVisibility.HIDDEN
            }
        }

    internal suspend fun setCompositionGuideVisibility(visibility: CompositionGuideVisibility) {
        dataStore.edit {
            it[KEY_COMPOSITION_GUIDES] = visibility == CompositionGuideVisibility.VISIBLE
        }
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
            t = it[KEY_DISH_T] ?: DishState.DEFAULT_T,
        )
    }

    suspend fun setDishWells(a: Int, b: Int) {
        dataStore.edit {
            it[KEY_DISH_A] = a
            it[KEY_DISH_B] = b
        }
    }

    suspend fun setDishT(t: Float) {
        if (t.isNaN()) return
        val clamped = t.coerceIn(0f, 1f)
        dataStore.edit { it[KEY_DISH_T] = clamped }
    }

    val recentColors: Flow<List<Int>> =
        dataStore.data.map { StoredColors.decode(it[KEY_RECENT_COLORS]) }

    suspend fun setRecentColors(colors: List<Int>) {
        dataStore.edit { it[KEY_RECENT_COLORS] = StoredColors.encode(colors) }
    }

    internal val paintSlotIds: Flow<List<String>> = paintSlotState.filterNotNull()

    /** Resolves and canonicalizes stored slots as one atomic operation. */
    internal suspend fun loadPaintSlots(
        cataloguePresetIds: List<String>,
    ): PaintSlotAssignments {
        paintSlotIds.first()

        return synchronized(paintSlotLock) {
            val assignments = PaintSlotAssignments.restore(
                cataloguePresetIds,
                requireLoadedPaintSlotIds(),
            )
            publishPaintSlotIdsLocked(assignments.presetIds)
            assignments
        }
    }

    /** Swaps against the latest shared assignment, never a canvas snapshot. */
    internal fun assignPaintSlot(
        cataloguePresetIds: List<String>,
        activeIndex: Int,
        presetId: String,
    ): PaintSlotAssignments = synchronized(paintSlotLock) {
        val assignments = PaintSlotAssignments
            .restore(cataloguePresetIds, requireLoadedPaintSlotIds())
            .activate(activeIndex)
            .assign(presetId)
        publishPaintSlotIdsLocked(assignments.presetIds)
        assignments
    }

    private fun publishPaintSlotIdsLocked(presetIds: List<String>) {
        val snapshot = presetIds.toList()
        if (paintSlotState.value == snapshot) return

        paintSlotState.value = snapshot
        check(paintSlotUpdates.trySend(snapshot).isSuccess) {
            "paint slot writer is unavailable"
        }
    }

    private fun requireLoadedPaintSlotIds(): List<String> =
        checkNotNull(paintSlotState.value) { "paint slots are not loaded" }

    /**
     * The last size the New Canvas dialog's Custom row created, so the row
     * pre-fills with it instead of always starting at 2048² (08 §2.1).
     * Absent until the first custom creation.
     */
    internal val lastCustomSize: Flow<CanvasSize?> = dataStore.data.map { prefs ->
        val width = prefs[KEY_LAST_CUSTOM_WIDTH] ?: return@map null
        val height = prefs[KEY_LAST_CUSTOM_HEIGHT] ?: return@map null
        if (width <= 0 || height <= 0) return@map null
        CanvasSize(width, height)
    }

    internal suspend fun setLastCustomSize(size: CanvasSize) {
        dataStore.edit {
            it[KEY_LAST_CUSTOM_WIDTH] = size.width
            it[KEY_LAST_CUSTOM_HEIGHT] = size.height
        }
    }

    /** Rail size and brush-specific secondary values live outside preset JSON. */
    internal suspend fun brushTunings(ids: Iterable<String>): Map<String, BrushTuning> {
        val snapshot = dataStore.data.first()
        return ids.associateWith { id ->
            BrushTuning(
                size = snapshot[sizeKey(id)],
                opacity = snapshot[opacityKey(id)],
                flow = snapshot[flowKey(id)],
            )
        }
    }

    internal suspend fun setBrushTuning(id: String, tuning: BrushTuning) {
        dataStore.edit { stored ->
            stored.setOrRemove(sizeKey(id), tuning.size)
            stored.setOrRemove(opacityKey(id), tuning.opacity)
            stored.setOrRemove(flowKey(id), tuning.flow)
        }
    }

    internal suspend fun clearBrushTuning(id: String) {
        dataStore.edit {
            it.remove(sizeKey(id))
            it.remove(opacityKey(id))
            it.remove(flowKey(id))
        }
    }

    private fun sizeKey(id: String) = floatPreferencesKey("brushSize.$id")

    private fun opacityKey(id: String) = floatPreferencesKey("brushOpacity.$id")

    private fun flowKey(id: String) = floatPreferencesKey("brushFlow.$id")

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<Float>, value: Float?) {
        if (value == null) {
            remove(key)
            return
        }

        this[key] = value
    }

    private val pigmentAvailability: PigmentAvailability
        get() = if (BuildConfig.MIXBOX) PigmentAvailability.AVAILABLE else PigmentAvailability.ABSENT

    private companion object {
        const val STORE_NAME = "bangni"
        const val TAG = "Prefs"
        const val THEME_READ_FAILURE_MESSAGE = "theme read failed; retrying"
        const val THEME_READ_EXHAUSTED_MESSAGE =
            "theme read keeps failing; keeping the current theme"
        const val PREFERENCE_CORRUPTION_MESSAGE = "preferences corrupted; resetting"
        const val PREFERENCE_READ_RETRY_DELAY_MS = 1_000L
        val KEY_NEXT_SKETCH = intPreferencesKey("nextSketchNumber")
        val KEY_GALLERY_SYNC = booleanPreferencesKey("gallerySync")
        val KEY_APP_THEME = stringPreferencesKey("appTheme")
        val KEY_HANDEDNESS = stringPreferencesKey("handedness")
        val KEY_TOUCH_DRAWING = stringPreferencesKey("touchDrawing")
        val KEY_HAPTICS = stringPreferencesKey("haptics")
        val KEY_PRESSURE_PREFERENCE = stringPreferencesKey("pressurePreference")
        val KEY_SNAP_RIGHT_ANGLES = booleanPreferencesKey("snapRightAngles")
        val KEY_COMPOSITION_GUIDES = booleanPreferencesKey("compositionGuides")
        val KEY_HINT_SHOWN = booleanPreferencesKey("hintShown")
        val KEY_DEBUG_LATENCY = booleanPreferencesKey("debugLatency")
        val KEY_PEN_BUTTON_ACTION = stringPreferencesKey("penButtonAction")
        val KEY_ERASER_END_PRESET = stringPreferencesKey("eraserEndPreset")
        val KEY_MIXER = stringPreferencesKey("mixer")
        val KEY_ACTIVE_PALETTE = stringPreferencesKey("activePalette")
        val KEY_DISH_A = intPreferencesKey("dishA")
        val KEY_DISH_B = intPreferencesKey("dishB")
        val KEY_DISH_T = floatPreferencesKey("dishT")
        val KEY_RECENT_COLORS = stringPreferencesKey("recent_colors")
        val KEY_PAINT_SLOTS = stringPreferencesKey("paintSlots")
        val KEY_LAST_CUSTOM_WIDTH = intPreferencesKey("lastCustomWidth")
        val KEY_LAST_CUSTOM_HEIGHT = intPreferencesKey("lastCustomHeight")
    }
}
