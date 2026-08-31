package ch.lkmc.bangnidraw.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
     * Distinguishes one in-flight write from another on the same target.
     *
     * Deriving the temp path from the target name alone gave every writer of
     * a file the *same* scratch path, and `FileOutputStream` truncates on
     * open, so overlapping writers shared one inode. The first to rename
     * published whatever that inode held at that instant and returned
     * success; every other writer then found its temp file gone and threw at
     * the rename. The outcome inverts: measured over six concurrent 1 MiB
     * writers, one or two reported success and the bytes actually published
     * belonged, every run, to a writer that had reported **failure**. A save
     * the user was told succeeded was not the save on disk.
     *
     * Worse than the swap, the loser's descriptor survives the rename and
     * still points at the now-published inode, so it goes on writing into the
     * *target* in place — outside the tmp-then-rename protocol readers depend
     * on, which is the torn file this pattern exists to rule out.
     *
     * A per-write token gives each writer its own inode, so the rename stays
     * the single commit point and concurrent writers of one target resolve
     * the only way a rename can: one of them wins, whole. Preventing the
     * *lost update* that implies is a caller's job (`@Synchronized` on the
     * store, as `PaletteStore` and `BrushPresetStore` do); this class only
     * promises that whatever lands is complete.
     *
     * Process-local, which is where the race is — the app is single-process,
     * and a token reused across runs can only collide with a crashed run's
     * leftover, which is swept and would be truncated anyway.
     */
    private val nextToken = AtomicLong()

    /**
     * Temp paths currently being written, so [sweepTmp] cannot delete one out
     * from under a live writer. `ProjectStore.checkpoint` sweeps *before* it
     * writes, so without this a checkpoint would abort any concurrent write
     * to the same directory with a rename failure.
     *
     * The check is sound despite being lock-free: a path is registered before
     * its file is created, so any temp file a sweep can list is already in
     * this set if a writer still owns it.
     */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        val tmp = File(
            target.parentFile,
            target.name + "." + nextToken.getAndIncrement() + TMP_SUFFIX,
        )
        // Registered before the file exists, which is what makes sweepTmp's
        // check sound.
        inFlight.add(tmp.path)
        try {
            FileOutputStream(tmp).use { out ->
                write(out)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                throw IOException("could not rename $tmp to $target")
            }
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            throw e
        } finally {
            inFlight.remove(tmp.path)
        }
    }

    /**
     * Deletes every `*.tmp` directly in [dir] — a crashed writer's leftovers,
     * swept on load and on every save (§2). Ignores a missing directory.
     *
     * A **live** writer's temp file is left alone. §2's "on every save" makes
     * this reachable rather than theoretical: `ProjectStore.checkpoint`
     * sweeps its directory before writing, and a concurrent write to the same
     * directory would otherwise lose its temp file and fail at the rename.
     */
    fun sweepTmp(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isFile && child.name.endsWith(TMP_SUFFIX) && child.path !in inFlight) {
                child.delete()
            }
        }
    }
}
