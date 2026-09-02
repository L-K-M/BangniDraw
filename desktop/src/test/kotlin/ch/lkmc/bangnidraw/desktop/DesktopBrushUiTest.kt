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
    fun `the whole side panel scrolls at the minimum window height`() {
        val main =
            repoFile("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
                .readText(Charsets.UTF_8)
        val start = "private fun SidePanel("
        val end = "private fun HsvSliders("
        require(start in main && end in main) { "SidePanel source markers not found" }
        val panel =
            main.substringAfter(start)
                .substringBefore(end)
                .replace(Regex("\\s+"), "")

        assertTrue(
            panel.contains("fillMaxSize().verticalScroll(rememberScrollState())"),
            "the entire side panel must scroll at the 600dp minimum height",
        )
        assertFalse(
            panel.contains("height(240.dp).verticalScroll"),
            "the brush list must not own a clipped nested scroll region",
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
