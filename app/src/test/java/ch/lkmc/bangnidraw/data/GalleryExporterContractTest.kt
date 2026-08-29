package ch.lkmc.bangnidraw.data

import java.io.File
import kotlin.test.Test
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
    }
}
