package ch.lkmc.bangnidraw.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class GalleryExporterContractTest {

    @Test
    fun `recorded row probe contains lost provider access`() {
        val exporter = File(repositoryRoot(), EXPORTER_PATH).readText()
        val probe = exporter
            .substringAfter("if (uri != null) {")
            .substringBefore("var action = GallerySyncDecision.decide")

        assertTrue("catch (e: SecurityException)" in probe)
        assertTrue("probeThrew = true" in probe)
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
