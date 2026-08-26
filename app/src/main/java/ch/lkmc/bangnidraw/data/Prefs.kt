package ch.lkmc.bangnidraw.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    private companion object {
        const val STORE_NAME = "bangni"
        val KEY_NEXT_SKETCH = intPreferencesKey("nextSketchNumber")
        val KEY_GALLERY_SYNC = booleanPreferencesKey("gallerySync")
        val KEY_DEBUG_LATENCY = booleanPreferencesKey("debugLatency")
    }
}
