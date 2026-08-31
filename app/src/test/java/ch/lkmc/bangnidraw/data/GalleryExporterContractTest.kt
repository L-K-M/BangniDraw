package ch.lkmc.bangnidraw.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class GalleryExporterContractTest {

    @Test
    fun `recorded row probe contains lost provider access`() {
        val exporter = File(repositoryRoot(), EXPORTER_PATH).readText()
        // substringAfter/Before degrade to the whole file when a delimiter
        // disappears, and the whole file can satisfy both asserts — so pin
        // the opening delimiter, then that the closing one follows it.
        check("private fun probeRow(" in exporter) {
            "probeRow delimiter not found — probe would match the whole file"
        }
        val afterProbe = exporter.substringAfter("private fun probeRow(")
        check("fun sync(" in afterProbe) {
            "'fun sync(' must appear after probeRow — otherwise the probe window is unbounded"
        }
        val probe = afterProbe.substringBefore("fun sync(")

        assertTrue("catch (e: SecurityException)" in probe)
        assertTrue("threw = true" in probe)
    }

    /**
     * A failed rewrite must leave no row behind. `"wt"` truncates the row on
     * open, so the previous pixels are already gone by the time the write can
     * fail; publishing what survived (clearing `IS_PENDING`) puts a partial
     * PNG in the user's gallery *and* strands it there, because `sync`
     * returns null, `project.json` keeps the pre-write size/date, and the
     * next probe reads that mismatch as another app's edit — which
     * `GallerySyncDecision.REINSERT` answers by forgetting the URI and
     * leaving the item as the user "left" it. Deleting instead matches
     * `insert`'s own never-a-ghost rule and makes the next sync a clean
     * INSERT.
     */
    @Test
    fun `a failed rewrite deletes its row instead of publishing a partial image`() {
        val rewrite = section("private fun rewrite(", "private fun insert(")

        assertTrue(
            "catch (e: Throwable)" in rewrite,
            "rewrite must own its failure path, not leak a truncated row",
        )
        assertTrue(
            """discardRow(uri, "rewrite")""" in rewrite,
            "a failed rewrite must discard the row it truncated",
        )
        // The publish must be reachable only on success; a finally block here
        // is exactly the defect, because it clears IS_PENDING even when the
        // write threw. The needle carries its brace on purpose: this window
        // includes comments, and the prose explaining the fix names the
        // keyword, so a bare-word check would fail on its own documentation.
        // Both spellings: section() collapses whitespace *runs*, and there is
        // no run to collapse in `finally{`, so the spaced needle alone was the
        // one negative assertion in this file a formatting choice could
        // silently defeat. Every other needle here fails loudly through an
        // indexOf guard instead.
        assertFalse(
            "finally {" in rewrite || "finally{" in rewrite,
            "clearing IS_PENDING in a finally block publishes a truncated image",
        )
        // ...and the publish must sit INSIDE the guarded region rather than
        // after it. A row left IS_PENDING with *complete* pixels strands
        // exactly like a truncated one: the recorded size/date still mismatch,
        // so the next probe reads another app's edit and REINSERT abandons a
        // perfectly good image as an invisible pending row while the user's
        // gallery item stays missing. Same defect, one call later. Pinned as
        // an ordering, so a publish moved back out past the catch fails.
        // Both edges, not just the second: publish-before-catch alone
        // would still accept a publish hoisted ABOVE the write, which
        // republishes the row before "wt" truncates it — the original
        // defect with its window merely moved earlier.
        // The truncating write must sit inside the guard too. Ordering the
        // write, publish and catch relative to each other still allows the
        // whole pair to be hoisted above `try {`, where a failure strands a
        // truncated row with no discardRow at all — the original defect.
        val guarded = rewrite.indexOf("try {")
        val write = rewrite.indexOf("""openOutputStream(uri, "wt")""")
        val guard = rewrite.indexOf("catch (e: Throwable)")
        val publish = rewrite.indexOf("MediaStore.Images.Media.IS_PENDING, 0")
        if (guarded < 0 || write < 0 || guard < 0 || publish < 0) {
            fail("rewrite lost its try, its write, its guard, or its publish step")
        }
        assertTrue(
            guarded < write,
            "the truncating write must be inside the guarded region",
        )
        assertTrue(
            write < publish,
            "the publish must follow the write, not precede the truncation",
        )
        assertTrue(
            publish < guard,
            "the publish must be inside the guarded region, not after it",
        )
    }

    /**
     * The never-a-ghost rule covers both writers, and the cleanup delete is
     * guarded itself: a provider that also refuses the delete must not replace
     * the failure being reported with its own, which is the only diagnostic
     * either caller leaves behind.
     */
    @Test
    fun `both writers discard through the guarded helper`() {
        val insert = section("private fun insert(", "private fun outcomeOf(")
        assertTrue(
            """discardRow(uri, "insert")""" in insert,
            "a failed insert must discard its pending row",
        )

        val helper = section("private fun discardRow(", "private fun rewrite(")
        assertTrue(
            "runCatching" in helper,
            "the cleanup delete must not mask the failure it is reporting",
        )
        // runCatching alone does not say "does not rethrow": a helper written
        // as runCatching { … }.getOrThrow() satisfies the check above while
        // replacing the write's failure with the delete's, which is exactly
        // what the doc forbids. Mirrors the outcomeOf test.
        assertFalse(
            "getOrThrow" in helper,
            "rethrowing from the cleanup delete masks the failure it reports",
        )
    }

    /**
     * The post-publish baseline read is not allowed to fail the export.
     *
     * `outcomeOf` runs after the row is published, so a throw there used to
     * leave `sync` returning null with the pre-write size and date still in
     * `project.json`. The next probe reads that mismatch as another app's
     * edit, and REINSERT answers by abandoning the freshly published row and
     * inserting a duplicate — a permanent second copy caused by one flaky
     * metadata read after a completely successful export.
     *
     * Contained rather than discarded: a throw degrades to the same 0/0 a
     * null cursor already produced, which `probeRow` treats as "unknown …
     * ours", so the next sync REWRITEs the row in place. Deleting the
     * published row instead would destroy a correct image to protect a
     * baseline the design already declares optional.
     */
    @Test
    fun `a failed baseline read cannot orphan a published row`() {
        val outcome = section("private fun outcomeOf(", "private companion object")

        assertTrue("runCatching" in outcome, "the post-publish probe must not throw out of outcomeOf")
        // Degrades to the 0/0 the null-cursor path already yields, not to a
        // delete and not to a rethrow.
        assertTrue("var modified = 0L" in outcome && "var size = 0L" in outcome)
        assertFalse("discardRow" in outcome, "a published row must not be deleted over its baseline")
        assertFalse("getOrThrow" in outcome, "rethrowing is what stranded the row")
    }

    /**
     * Both writers clean up after an `Error`, not only after an `Exception`.
     * The realistic one here is `OutOfMemoryError` during the PNG write —
     * ISSUES.md item 3 records the ~128 MiB peak at 4096² — and an Error that
     * skipped the cleanup would strand the pending row the rule forbids.
     */
    @Test
    fun `both writers clean up after an Error too`() {
        for (writer in listOf("private fun rewrite(", "private fun insert(")) {
            val body = section(writer, if (writer.contains("rewrite")) "private fun insert(" else "private fun outcomeOf(")
            assertTrue(
                "catch (e: Throwable)" in body,
                "$writer must clean up after an Error, not just an Exception",
            )
        }
    }

    /**
     * `withdraw` already contains unexpected provider failures ("retryable,
     * not a reason to orphan the row"); `sync` runs the same MediaStore
     * surface from `StudioViewModel`'s background sweep on `viewModelScope`,
     * where an uncaught `RuntimeException` ends the process while the user is
     * only browsing the shelf.
     */
    @Test
    fun `sync contains unexpected provider failures like withdraw does`() {
        val sync = section("fun sync(", "fun saveAs(")

        assertTrue(
            "catch (e: RuntimeException)" in sync,
            "an OEM provider fault must not crash the app from a background sweep",
        )
    }

    /** Whitespace-normalized, and loud when either delimiter moves. */
    private fun section(startMarker: String, endMarker: String): String {
        val exporter = File(repositoryRoot(), EXPORTER_PATH)
            .readText()
            .replace(WHITESPACE, " ")
        val start = exporter.indexOf(startMarker)
        if (start < 0) fail("missing $startMarker in $EXPORTER_PATH — renamed?")
        val end = exporter.indexOf(endMarker, start + startMarker.length)
        if (end <= start) fail("missing $endMarker after $startMarker in $EXPORTER_PATH")

        return exporter.substring(start, end)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull {
                File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory
            }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val EXPORTER_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/data/GalleryExporter.kt"
        val WHITESPACE = Regex("\\s+")
    }
}
