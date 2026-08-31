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
            "resolver.delete(uri, null, null)" in rewrite,
            "a failed rewrite must delete the row it truncated",
        )
        // The publish must be reachable only on success; a finally block here
        // is exactly the defect, because it clears IS_PENDING even when the
        // write threw. The needle carries its brace on purpose: this window
        // includes comments, and the prose explaining the fix names the
        // keyword, so a bare-word check would fail on its own documentation.
        assertFalse(
            "finally {" in rewrite,
            "clearing IS_PENDING in a finally block publishes a truncated image",
        )
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
