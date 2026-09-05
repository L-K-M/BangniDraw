package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.data.shared.BangniCodec
import ch.lkmc.bangnidraw.data.shared.BangniDocument
import java.io.File

/**
 * Writing a `.bangni` to disk, with the same publish-or-nothing rule the PNG
 * export uses: the bytes go to a sibling temporary file and are moved into
 * place only once the whole document has been written.
 *
 * That matters more here than for an export. A `.bangni` is the user's
 * *document*, so a half-written one over the top of yesterday's work is data
 * loss, not a failed export they can retry.
 */
internal object DesktopBangniWriter {

    fun write(document: BangniDocument, file: File): DesktopSaveResult {
        if (Thread.currentThread().isInterrupted) {
            return DesktopSaveResult.Failed(INTERRUPTED)
        }

        val target = file.absoluteFile
        if (target.exists() && !target.isFile) {
            return DesktopSaveResult.Failed("${target.absolutePath} is not a file")
        }
        val parent = target.parentFile
            ?: return DesktopSaveResult.Failed("${target.absolutePath} has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            return DesktopSaveResult.Failed("could not create ${parent.absolutePath}")
        }

        var partial: File? = null
        return try {
            partial = java.nio.file.Files
                .createTempFile(parent.toPath(), PARTIAL_PREFIX, PARTIAL_SUFFIX)
                .toFile()
            // Closing the stream reaches the page cache, not the device.
            // The rename below is atomic either way, so without this sync a
            // crash can commit the rename over the previous version while the
            // new bytes are still only in cache — losing both. `AtomicFiles`
            // syncs for the same reason on the Android side.
            //
            // The buffer is flushed by hand and never closed: closing a
            // `BufferedOutputStream` closes what it wraps, and `sync()` on a
            // descriptor that is already closed throws `SyncFailedException`
            // — an IOException, which the catch below turns into a failed
            // save. Only the outer `use` closes, after the sync.
            java.io.FileOutputStream(partial).use { raw ->
                val out = raw.buffered()
                BangniCodec.write(out, document)
                out.flush()
                raw.fd.sync()
            }
            if (Thread.currentThread().isInterrupted) {
                return DesktopSaveResult.Failed(INTERRUPTED)
            }

            publish(partial, target)
            DesktopSaveResult.Saved(target.absolutePath)
        } catch (failure: Exception) {
            DesktopPng.failureResult(failure)
        } finally {
            partial?.let { scratch ->
                if (scratch.exists() && !scratch.delete()) scratch.deleteOnExit()
            }
        }
    }

    private fun publish(partial: File, target: File) {
        try {
            java.nio.file.Files.move(
                partial.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                partial.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private const val INTERRUPTED = "the save was interrupted"
    private const val PARTIAL_PREFIX = ".bangnidraw-save-"
    private const val PARTIAL_SUFFIX = ".tmp"
}
