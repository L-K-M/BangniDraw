package ch.lkmc.bangnidraw.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPrefsTest {

    @Test
    fun `corrupt preferences reset instead of blocking desktop startup`() {
        val file = Files.createTempDirectory("bangnidraw-prefs")
            .resolve("desktop.preferences_pb")
            .toFile()
        file.writeBytes(byteArrayOf(0x80.toByte()))
        val prefs = DesktopPrefs(file)

        try {
            assertNull(runBlocking { prefs.readBrushId() })
            assertNull(runBlocking { prefs.readColorArgb() })
        } finally {
            prefs.close()
        }
    }

    @Test
    fun `queued writes preserve call order through close`() {
        val file = Files.createTempDirectory("bangnidraw-prefs")
            .resolve("desktop.preferences_pb")
            .toFile()
        val first = DesktopPrefs(file)

        repeat(WRITE_COUNT) { value -> first.writeColor(value) }
        first.close()

        val reopened = DesktopPrefs(file)
        try {
            assertEquals(WRITE_COUNT - 1, runBlocking { reopened.readColorArgb() })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `writer rethrows coroutine cancellation`() {
        val source = repoFile(
            "desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopPrefs.kt",
        ).readText(Charsets.UTF_8)
        val writer = source
            .substringAfter("private val writer")
            .substringBefore("val brushId")

        assertTrue(writer.contains("catch (cancelled: CancellationException)"))
        assertTrue(writer.contains("throw cancelled"))
        assertFalse(writer.contains("runCatching"))
    }

    @Test
    fun `late restore cannot overwrite a fresh user choice`() {
        val gate = DesktopPreferenceRestoreGate()

        assertTrue(gate.allows(DesktopPreferenceKind.Brush))
        assertTrue(gate.allows(DesktopPreferenceKind.Color))

        gate.markChanged(DesktopPreferenceKind.Color)

        assertTrue(gate.allows(DesktopPreferenceKind.Brush))
        assertFalse(gate.allows(DesktopPreferenceKind.Color))
    }

    private companion object {
        const val WRITE_COUNT = 100
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
