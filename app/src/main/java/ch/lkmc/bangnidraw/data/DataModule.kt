package ch.lkmc.bangnidraw.data

import android.content.Context
import ch.lkmc.bangnidraw.engine.core.ColorMixer
import ch.lkmc.bangnidraw.engine.core.RgbMixer
import ch.lkmc.bangnidraw.engine.mixbox.MixboxBinding
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A scope that outlives every screen — checkpoints and tile flushes must
 * finish after the ViewModel that started them is cleared, or leaving the
 * canvas could cancel the very write that makes leaving safe
 * (`docs/plan/06-document-and-persistence.md` §6.2's leave/ON_STOP rows).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * `filesDir`, not `cacheDir`: the OS may evict cache at any time and a
     * painting is the user's work (06 §2).
     */
    @Provides
    @Singleton
    fun provideProjectStore(@ApplicationContext context: Context): ProjectStore =
        ProjectStore(File(context.filesDir, "projects"))

    @Provides
    @Singleton
    fun provideBrushPresetStore(@ApplicationContext context: Context): BrushPresetStore =
        BrushPresetStore(
            root = File(context.filesDir, BRUSH_PRESET_DIRECTORY),
            assets = AndroidBrushPresetAssets(context.assets),
        )

    @Provides
    @Singleton
    fun provideAvailableColorMixer(): ColorMixer = MixboxBinding.create() ?: RgbMixer

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
