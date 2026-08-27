package ch.lkmc.bangnidraw.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The share sheet's staging area (`docs/plan/06-document-and-persistence.md`
 * §9.5): `cacheDir/share/`, served by the `FileProvider` path in
 * `res/xml/file_paths.xml` — a cache path named `share` only, never the
 * project store. Cache, not files: a staged copy is transient by nature and
 * the OS may reclaim it once the receiving app has read it.
 */
@Singleton
class ShareCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Writes [bytes] as [fileName] and returns a grantable content URI.
     * Older staged files beyond the last few are rotated out first — the
     * receiving app of a *previous* share may still be reading its copy, so
     * the newest are kept rather than sweeping everything.
     *
     * @throws IllegalArgumentException if [fileName] is not one non-empty path segment
     */
    @Throws(IOException::class)
    fun stage(fileName: String, bytes: ByteArray): Uri {
        val file = ShareStaging(File(context.cacheDir, DIR_NAME)).stage(fileName, bytes)
        return FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }

    private companion object {
        const val DIR_NAME = "share"
        const val AUTHORITY_SUFFIX = ".fileprovider"
    }
}
