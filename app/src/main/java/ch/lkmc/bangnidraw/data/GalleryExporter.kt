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

    /** What a probe of one recorded row found. */
    private class RowState(
        val present: Boolean,
        val owned: Boolean,
        val modifiedByOther: Boolean,
        val threw: Boolean,
    )

    /**
     * §9.2's probe: is the recorded row still there, still ours, and still
     * byte-for-byte what we last wrote? A `SecurityException` from the query
     * is recorded as [RowState.threw] — a row that became inaccessible is no
     * longer safe to touch.
     */
    private fun probeRow(
        uri: Uri,
        recordedModifiedAt: Long,
        recordedBytes: Long,
    ): RowState {
        try {
            context.contentResolver.query(
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
                    val isOwner = cursor.getString(0) == context.packageName
                    val rowModified = cursor.getLong(1)
                    val rowBytes = cursor.getLong(2)
                    // 0/0 recorded = unknown (pre-rule folders): treated as
                    // ours (§9.2).
                    val modifiedByOther = recordedModifiedAt != 0L && recordedBytes != 0L &&
                        (rowModified != recordedModifiedAt || rowBytes != recordedBytes)
                    return RowState(true, isOwner, modifiedByOther, threw = false)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "gallery probe refused", e)
            return RowState(present = true, owned = false, modifiedByOther = false, threw = true)
        }
        return RowState(present = false, owned = false, modifiedByOther = false, threw = false)
    }

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
            val row = probeRow(uri, recordedModifiedAt, recordedBytes)
            uriPresent = row.present
            isOwner = row.owned
            modifiedByOther = row.modifiedByOther
            probeThrew = row.threw
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
            } catch (e: RuntimeException) {
                // The same containment `withdraw` already applies, for the
                // same reason: a provider that throws something other than
                // SecurityException/IOException — a malformed-URI
                // IllegalArgumentException, an SQLiteException, one of the
                // OEM MediaStore quirks — is a retryable fault, not a reason
                // to end the process. This runs on StudioViewModel's
                // background sweep (viewModelScope), so an escape here kills
                // the app while the user is only browsing the shelf. Ordered
                // after SecurityException, which is itself a RuntimeException
                // and keeps its own ownership-lost handling above.
                Log.w(TAG, "gallery rewrite failed unexpectedly; will retry", e)
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
        } catch (e: RuntimeException) {
            Log.w(TAG, "gallery insert failed unexpectedly; will retry", e)
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

    /**
     * Withdraws the reference variant — the second item a painting kept
     * while its tracing image was visible — once the image stops qualifying.
     * The mirror's own rule inverted: the row is deleted only while it is
     * still ours and untampered; an edit by another app, or a row we can no
     * longer probe, is the user's and stays.
     *
     * Returns whether the row is **settled**: gone, or no longer ours to
     * touch — in both cases the caller may forget the URI, exactly as a
     * REINSERT forgets. False means the delete failed in a way worth
     * retrying, and the recorded URI must survive for the next attempt.
     */
    fun withdraw(
        recordedUri: String?,
        recordedModifiedAt: Long,
        recordedBytes: Long,
    ): Boolean {
        val uri = recordedUri?.let(Uri::parse) ?: return true
        val row = try {
            probeRow(uri, recordedModifiedAt, recordedBytes)
        } catch (e: IllegalArgumentException) {
            // A URI the provider will never accept is permanent: the row it
            // names can be neither probed nor deleted, so forgetting is the
            // only exit, not an orphaned retry loop.
            Log.w(TAG, "gallery withdraw probe refused a malformed URI", e)
            return true
        } catch (e: RuntimeException) {
            // The same containment as the delete below: an unexpected
            // provider failure is retryable, not a reason to orphan an
            // owned row — this one can hold the reference photo.
            Log.w(TAG, "gallery withdraw probe failed; will retry", e)
            return false
        }
        // Ownership joins the guard: with 0/0 recorded state the tamper half
        // is forced false, and a row id MediaStore recycled after our item
        // vanished must not be deletable just because it sits there.
        if (!row.present || !row.owned || row.threw || row.modifiedByOther) return true

        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "gallery withdraw refused; the item is the user's now", e)
        } catch (e: RuntimeException) {
            // The same containment as the probe's: an unexpected provider
            // failure is retryable, not a reason to orphan the row.
            Log.w(TAG, "gallery withdraw failed; will retry", e)
            return false
        }
        return true
    }

    /**
     * Drops a row this class was midway through writing, so no half-written
     * state survives — §9.2's never-a-ghost rule, extended to never a *corrupt*
     * or *stranded* one.
     *
     * Guarded, because a provider that also refuses the delete must not replace
     * the failure being reported with its own: the log line for the real fault
     * is the only diagnostic either caller leaves behind.
     */
    private fun discardRow(uri: Uri, what: String) {
        runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { Log.w(TAG, "could not delete the row a failed $what left behind", it) }
    }

    private fun rewrite(uri: Uri, displayName: String, png: ByteArray): Outcome {
        val resolver = context.contentResolver
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
        // Claiming the row pending and truncating it is the start of one
        // transaction that only the publish below closes, so the write and the
        // publish share a single failure path: either the row ends up
        // published, or it ends up gone.
        //
        // "wt" truncates on open, so by the time a write can fail the previous
        // pixels are already destroyed. Clearing IS_PENDING regardless (this
        // used to be a `finally`) published whatever survived — a partial PNG,
        // visible in the user's gallery — and it stayed there, because the
        // failure is not self-correcting the way it looks: `sync` catches and
        // returns null, so `project.json` keeps the *pre-write* size and date,
        // the next `probeRow` reads that mismatch as another app's edit, and
        // `GallerySyncDecision.REINSERT` answers that by design with "the URI
        // is forgotten, the item stays as the user left it". The tamper guard
        // built to protect someone else's edit ended up protecting our own
        // corruption, permanently, beside a fresh duplicate.
        //
        // The publish is inside the same `try` for exactly that reason: a row
        // left IS_PENDING with *complete* pixels strands identically — the
        // size/date still mismatch what was recorded, so REINSERT abandons a
        // perfectly good image as an invisible pending row while the user's
        // gallery item stays missing until a duplicate lands. Same defect, one
        // call later.
        try {
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("no stream for $uri"))
                .use { it.write(png) }
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
                },
                null,
                null,
            )
        } catch (e: Throwable) {
            discardRow(uri, "rewrite")
            throw e
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
            // Guarded like rewrite's, so a provider that refuses the delete
            // cannot mask the failure this is reporting.
            discardRow(uri, "insert")
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
