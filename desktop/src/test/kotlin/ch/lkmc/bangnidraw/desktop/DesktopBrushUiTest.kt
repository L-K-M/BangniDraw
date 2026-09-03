package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBrushUiTest {

    @Test
    fun `brush labels never expose Android resource keys`() {
        for (brush in DesktopBrushes.loadAll()) {
            assertFalse(DesktopBrushUi.label(brush).startsWith("@string/"))
        }
    }

    @Test
    fun `the rail's tool column scrolls rather than clipping presets`() {
        val rail =
            repoFile("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopToolRail.kt")
                .readText(Charsets.UTF_8)
        // indexOf, not substringAfter/Before: the latter silently returns the
        // rest of the file when the end marker does not follow the start one,
        // so a reordered file would assert against the wrong region.
        val start = rail.indexOf("internal fun DesktopToolRail(")
        val end = rail.indexOf("internal fun DesktopSliderLedge(", startIndex = start + 1)
        require(start >= 0 && end > start) { "DesktopToolRail source markers not found or misordered" }
        val column = rail.substring(start, end).replace(Regex("\\s+"), "")

        // The Android rail overflows extra presets into a settings sheet this
        // shell has no equivalent of, so a window too short for the budget
        // must let the column give instead of pushing icons off the window.
        assertTrue(
            column.contains(".weight(1f,fill=false).verticalScroll(rememberScrollState())"),
            "the rail's tool column must scroll when presets overflow the rail",
        )
        // Exactly one scroll region: a second one on the rail's outer column
        // would take the sliders with it, and they are the tuning a stroke in
        // progress needs within reach.
        assertEquals(
            1,
            Regex("verticalScroll").findAll(column).count(),
            "only the rail's tool column may scroll",
        )
    }

    @Test
    fun `size range follows each preset`() {
        for (brush in DesktopBrushes.loadAll()) {
            assertEquals(
                brush.sizeMin..brush.sizeMax,
                DesktopBrushUi.sizeRange(brush),
            )
        }
    }

    private fun repoFile(path: String): File = File(repoRoot(), path)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }
}
