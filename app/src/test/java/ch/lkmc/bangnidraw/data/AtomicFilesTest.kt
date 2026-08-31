package ch.lkmc.bangnidraw.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `AtomicFiles` promises "a file is always complete or absent"
 * (`docs/plan/06-document-and-persistence.md` §2, §4). These are the cases
 * where two writers meet, which is where that promise used to break.
 *
 * The temp path used to be derived from the target name alone, so every
 * writer of one file shared a scratch path — and `FileOutputStream`
 * truncates on open, so overlapping writers shared one inode. The first to
 * rename published whatever that inode held and returned normally; the rest
 * found their temp file gone and threw. The outcome inverts: over six
 * concurrent 1 MiB writers on the old code, one or two reported success, and
 * the bytes actually published belonged every run to a writer that had
 * reported failure.
 */
class AtomicFilesTest {

    private val dir: File = createTempDirectory("atomic-files").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `concurrent writers of one target never blend their bytes`() {
        val target = File(dir, "project.json")
        val writers = 8
        // Distinct lengths as well as distinct content: a blend of two
        // payloads at independent offsets is very likely to differ in length
        // from both, so this catches interleaving even if the bytes collide.
        val payloads = List(writers) { i -> ("writer-$i:" + "x".repeat(64 * (i + 1))) }
        val start = CountDownLatch(1)
        val failures = mutableListOf<Throwable>()
        val published = mutableListOf<String>()

        val threads = payloads.map { payload ->
            thread {
                start.await()
                try {
                    AtomicFiles.write(target, payload.toByteArray(Charsets.UTF_8))
                    synchronized(published) { published += payload }
                } catch (e: Throwable) {
                    synchronized(failures) { failures += e }
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(30_000) }

        // Each writer has its own scratch file, so none can lose it to
        // another and fail at the rename.
        assertTrue(failures.isEmpty(), "no writer should fail: ${failures.map { it.message }}")
        // Whichever writer renamed last owns the file, and it owns it whole.
        val landed = target.readText(Charsets.UTF_8)
        assertContains(payloads, landed)
        // The half that used to invert: a writer told it succeeded must be
        // the writer whose bytes are on disk. On the shared-path version the
        // published bytes belonged to a writer that had just thrown.
        assertContains(published, landed, "the published bytes must come from a writer that succeeded")
    }

    @Test
    fun `each write gets its own temp file`() {
        val target = File(dir, "project.json")
        val first = tempNameDuring(target)
        val second = tempNameDuring(target)

        assertTrue(first.endsWith(AtomicFiles.TMP_SUFFIX), "the sweep matches on the suffix: $first")
        assertTrue(second.endsWith(AtomicFiles.TMP_SUFFIX), "the sweep matches on the suffix: $second")
        assertTrue(
            first != second,
            "two writers of one target must not share a scratch path (both were $first)",
        )
    }

    @Test
    fun `a sweep leaves a live writer's temp file alone`() {
        // ProjectStore.checkpoint sweeps its directory before writing, so
        // this is §2's "on every save" meeting a concurrent write — not a
        // hypothetical. Deleting the in-flight file fails the other writer at
        // its rename, for no fault of its own.
        val target = File(dir, "project.json")
        val other = File(dir, "palette.json")
        var seenDuringWrite: List<String> = emptyList()

        AtomicFiles.write(target) { out ->
            AtomicFiles.sweepTmp(dir)
            seenDuringWrite = dir.list().orEmpty().filter { it.endsWith(AtomicFiles.TMP_SUFFIX) }
            out.write("payload".toByteArray(Charsets.UTF_8))
        }

        assertEquals(1, seenDuringWrite.size, "the sweep deleted the writer's own temp file")
        assertEquals("payload", target.readText(Charsets.UTF_8))
        assertFalse(other.exists())
    }

    @Test
    fun `a sweep still removes a crashed writer's leftovers`() {
        val stale = File(dir, "project.json.7.tmp")
        val alsoStale = File(dir, "project.json.tmp")
        stale.writeText("half a write")
        alsoStale.writeText("from an older build")

        AtomicFiles.sweepTmp(dir)

        assertFalse(stale.exists(), "a token-suffixed leftover is still swept")
        assertFalse(alsoStale.exists(), "so is one written before temp names carried a token")
    }

    @Test
    fun `a failed write leaves no temp file and no target`() {
        val target = File(dir, "project.json")

        val thrown = runCatching {
            AtomicFiles.write(target) { error("write failed midway") }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException, "the caller's failure propagates: $thrown")
        assertFalse(target.exists(), "a failed first write must not create the target")
        assertEquals(
            emptyList(),
            dir.list().orEmpty().toList(),
            "the temp file is cleaned up, and unregistered so a later sweep is not needed",
        )
    }

    /** The temp file's name as it exists on disk mid-write. */
    private fun tempNameDuring(target: File): String {
        var name = ""
        AtomicFiles.write(target) { out ->
            name = dir.list().orEmpty().single { it.endsWith(AtomicFiles.TMP_SUFFIX) }
            out.write("payload".toByteArray(Charsets.UTF_8))
        }
        return name
    }

    @Test
    fun `a writer whose temp file is destroyed fails rather than reporting success`() {
        // The guard covers AtomicFiles' own sweep; anything else that removes
        // the file still has to surface as a failure, never a silent success.
        val target = File(dir, "project.json")
        val latch = CountDownLatch(1)

        val thrown = runCatching {
            AtomicFiles.write(target) { out ->
                out.write("payload".toByteArray(Charsets.UTF_8))
                dir.listFiles().orEmpty().filter { it.name.endsWith(AtomicFiles.TMP_SUFFIX) }
                    .forEach { it.delete() }
                latch.countDown()
            }
        }.exceptionOrNull()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(thrown != null, "a lost temp file must not be reported as a successful save")
        assertFalse(target.exists())
    }
}
