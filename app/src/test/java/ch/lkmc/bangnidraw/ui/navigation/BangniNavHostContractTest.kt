package ch.lkmc.bangnidraw.ui.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class BangniNavHostContractTest {

    @Test
    fun `opening one painting twice keeps one canvas destination`() {
        val source = File(repositoryRoot(), NAV_HOST_PATH).readText()
        val openPainting = sourceSection(
            source = source,
            startMarker = OPEN_PAINTING_START,
            endMarker = OPEN_PAINTING_END,
        )

        assertTrue(
            "launchSingleTop = true" in openPainting,
            "painting navigation must ignore a second launch during transition",
        )
    }

    private fun sourceSection(
        source: String,
        startMarker: String,
        endMarker: String,
    ): String {
        val startIndex = source.indexOf(startMarker)
        if (startIndex < 0) fail("missing source marker: $startMarker")

        val endIndex = source.indexOf(endMarker, startIndex + startMarker.length)
        if (endIndex < 0) fail("missing source marker: $endMarker")

        return source.substring(startIndex, endIndex)
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
        const val NAV_HOST_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/navigation/BangniNavHost.kt"
        const val OPEN_PAINTING_START = "onOpenPainting ="
        const val OPEN_PAINTING_END = "openSettings ="
    }
}
