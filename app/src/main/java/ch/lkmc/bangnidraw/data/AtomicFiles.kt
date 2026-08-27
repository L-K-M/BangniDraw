package ch.lkmc.bangnidraw.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * tmp + rename in the same directory, the write pattern every file in a
 * project folder uses (`docs/plan/06-document-and-persistence.md` §2, §4:
 * "a file is always complete or absent").
 *
 * Not `androidx.core.util.AtomicFile`: the stores run against plain [File]s
 * on the JVM test suite, and the platform class buys nothing here — the
 * commit point is the rename either way.
 */
internal object AtomicFiles {

    /** The suffix every in-flight write carries; loaders sweep these (§2). */
    const val TMP_SUFFIX = ".tmp"

    /**
     * Writes [bytes] to [target] atomically: `<name>.tmp` in the same
     * directory, fsync, rename. On any failure the tmp file is deleted and
     * the previous [target] — if one existed — is untouched.
     *
     * fsync before rename, because the rename is the commit point: ext4 and
     * f2fs may otherwise commit the rename before the data blocks, and a
     * power cut then leaves a *complete-looking* file of zeros — the torn
     * write this pattern exists to rule out.
     */
    @Throws(IOException::class)
    fun write(target: File, bytes: ByteArray) {
        write(target) { out -> out.write(bytes) }
    }

    /** Streams a large payload without holding a second full copy in memory. */
    @Throws(IOException::class)
    fun write(target: File, write: (OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                write(out)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                throw IOException("could not rename $tmp to $target")
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Deletes every `*.tmp` directly in [dir] — a crashed writer's leftovers,
     * swept on load and on every save (§2). Ignores a missing directory.
     */
    fun sweepTmp(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isFile && child.name.endsWith(TMP_SUFFIX)) child.delete()
        }
    }
}
