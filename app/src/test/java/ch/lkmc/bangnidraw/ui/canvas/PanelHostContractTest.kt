package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class PanelHostContractTest {

    @Test
    fun `handed panel alignment never mirrors with locale direction`() {
        val source = File(repositoryRoot(), PANEL_HOST_PATH)
            .readLines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith(LINE_COMMENT) ||
                    trimmed.startsWith(KDOC_LINE) ||
                    trimmed.startsWith(BLOCK_COMMENT_OPEN)
            }
            .joinToString("\n")

        assertTrue("AbsoluteAlignment.CenterRight" in source)
        assertTrue("AbsoluteAlignment.CenterLeft" in source)
        assertFalse("Alignment.CenterEnd" in source)
        assertFalse("Alignment.CenterStart" in source)
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
        const val LINE_COMMENT = "//"
        const val KDOC_LINE = "*"
        const val BLOCK_COMMENT_OPEN = "/*"
        const val PANEL_HOST_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/PanelHost.kt"
    }
}
