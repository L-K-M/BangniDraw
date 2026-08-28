package ch.lkmc.bangnidraw.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.GallerySyncDecision
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal enum class GalleryExportOutcome { SUCCESS, FAILURE }

/**
 * The gallery mirror (`docs/plan/06-document-and-persistence.md` §9): one
 * MediaStore item per painting under `Pictures/帮你Draw`, always the latest
 * version, no permission (API 29+ scoped storage; the manifest's own
 * comment). Every *decision* lives in [GallerySyncDecision], pure and
 * tested; this class only queries rows and moves bytes, which is why it is
 * Android-only and deliberately untested (roadmap step 4's stated split).
 */
@Singleton
class GalleryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** What a successful sync must record in `project.json` (§9.2). */
    data class Outcome(
        val galleryUri: String,
        val syncedAt: Long,
        /** The row's `DATE_MODIFIED` (s) after our write — the tamper check's baseline. */
        val modifiedAt: Long,
        /** The row's `SIZE` after our write, same purpose. */
        val bytes: Long,
    )

    /**
     * Mirrors [png] per §9.2's table. [recordedUri]/[recordedModifiedAt]/
     * [recordedBytes] are the document's last-known row state; null on total
     * failure — the caller leaves its fields untouched and a later trigger
     * retries.
     */
    fun sync(
        recordedUri: String?,
        recordedModifiedAt: Long,
        recordedBytes: Long,
        displayName: String,
        png: ByteArray,
    ): Outcome? {
        val resolver = context.contentResolver
        val uri = recordedUri?.let(Uri::parse)

        var uriPresent = false
        var isOwner = false
        var modifiedByOther = false
        var probeThrew = false
        if (uri != null) {
            try {
                resolver.query(
                    uri,
                    arrayOf(
                        MediaStore.Images.Media.OWNER_PACKAGE_NAME,
                        MediaStore.Images.Media.DATE_MODIFIED,
                        MediaStore.Images.Media.SIZE,
                    ),
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        uriPresent = true
                        isOwner = cursor.getString(0) == context.packageName
                        val rowModified = cursor.getLong(1)
                        val rowBytes = cursor.getLong(2)
                        // 0/0 recorded = unknown (pre-rule folders): treated as
                        // ours (§9.2).
                        modifiedByOther = recordedModifiedAt != 0L && recordedBytes != 0L &&
                            (rowModified != recordedModifiedAt || rowBytes != recordedBytes)
                    }
                }
            } catch (e: SecurityException) {
                // A recorded row that became inaccessible is no longer safe to rewrite.
                Log.w(TAG, "gallery probe refused; inserting a fresh item", e)
                uriPresent = true
                probeThrew = true
            }
        }

        var action = GallerySyncDecision.decide(
            uriPresent = uriPresent,
            isOwner = isOwner,
            threw = probeThrew,
            modifiedByOther = modifiedByOther,
        )

        if (action == GallerySyncDecision.Action.REWRITE && uri != null) {
            try {
                return rewrite(uri, displayName, png)
            } catch (e: SecurityException) {
                // Ownership lost after all (reinstall, or the row changed
                // under us): §9.2 — never prompt through
                // RecoverableSecurityException; a second item is the honest
                // outcome and the old one is the user's to delete.
                Log.w(TAG, "gallery rewrite refused; inserting a fresh item", e)
                action = GallerySyncDecision.decide(
                    uriPresent = true, isOwner = isOwner, threw = true,
                    modifiedByOther = modifiedByOther,
                )
            } catch (e: IOException) {
                Log.w(TAG, "gallery rewrite failed", e)
                return null
            }
        }

        check(action != GallerySyncDecision.Action.REWRITE)
        return try {
            insert(displayName, png, ImageEncode.Format.PNG)
        } catch (e: SecurityException) {
            Log.w(TAG, "gallery insert refused", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "gallery insert failed", e)
            null
        }
    }

    /**
     * §9.5's export "Save as…": a plain insert — a NEW item each time, never
     * the mirror, and no bookkeeping.
     */
    fun saveAs(
        displayName: String,
        bytes: ByteArray,
        format: ImageEncode.Format,
    ): Boolean = try {
        insert(displayName, bytes, format) != null
    } catch (e: SecurityException) {
        Log.w(TAG, "save-as refused", e)
        false
    } catch (e: IOException) {
        Log.w(TAG, "save-as failed", e)
        false
    }

    /**
     * Removes our mirror item — the delete dialog's opt-in checkbox (06 §8).
     * Best effort: a `SecurityException` means the item is no longer ours to
     * delete, and the user can remove it in the gallery app.
     */
    fun delete(uriString: String) {
        try {
            context.contentResolver.delete(Uri.parse(uriString), null, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "gallery delete refused; the item is the user's now", e)
        }
    }

    private fun rewrite(uri: Uri, displayName: String, png: ByteArray): Outcome {
        val resolver = context.contentResolver
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
        try {
            // "wt" = truncate: rewrites in place, keeping the one-item
            // promise (§9.2).
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("no stream for $uri"))
                .use { it.write(png) }
        } finally {
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
                },
                null,
                null,
            )
        }
        return outcomeOf(uri)
    }

    private fun insert(
        displayName: String,
        bytes: ByteArray,
        format: ImageEncode.Format,
    ): Outcome? {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.${format.extension}")
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                // The folder carries the app's name; strings.xml is its only
                // source (AGENTS.md's rename checklist).
                "Pictures/" + context.getString(R.string.app_name),
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("no stream for $uri"))
                .use { it.write(bytes) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            // Never a ghost: a failed insert deletes its pending row (§9.2).
            resolver.delete(uri, null, null)
            throw e
        }
        return outcomeOf(uri)
    }

    /** The row's post-write `DATE_MODIFIED`/`SIZE` — §9.2's tamper baseline. */
    private fun outcomeOf(uri: Uri): Outcome {
        var modified = 0L
        var size = 0L
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE),
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                modified = cursor.getLong(0)
                size = cursor.getLong(1)
            }
        }
        return Outcome(
            galleryUri = uri.toString(),
            syncedAt = System.currentTimeMillis(),
            modifiedAt = modified,
            bytes = size,
        )
    }

    private companion object {
        const val TAG = "GalleryExporter"
    }
}
