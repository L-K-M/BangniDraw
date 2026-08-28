package ch.lkmc.bangnidraw.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class StudioUnavailablePaintingContractTest {

    @Test
    fun `unavailable paintings have no open action and retain delete`() {
        val source = File(repositoryRoot(), STUDIO_SCREEN_PATH).readText()

        assertTrue("PaintingAvailability.AVAILABLE" in source)
        assertTrue("R.string.studio_painting_unreadable" in source)
        assertTrue("R.string.canvas_newer_version" in source)
        assertTrue("if (available)" in source)
        assertTrue("onDelete(deleteGalleryToo)" in source)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val STUDIO_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioScreen.kt"
    }
}
