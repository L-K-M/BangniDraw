package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasAppearanceContractTest {

    @Test
    fun `theme canvas void crosses the Compose to GL boundary`() {
        val screen = source(CANVAS_SCREEN_PATH)
        val session = source(ENGINE_SESSION_PATH)
        val appearance = section(session, APPEARANCE_START, APPEARANCE_END)

        assertTrue(THEME_VOID in screen, "CanvasScreen must resolve the current theme's void")
        assertTrue(APPEARANCE_CALL in screen, "CanvasScreen must send appearance to its session")
        assertTrue(VOID_ARGUMENT in screen, "the themed void must be part of that update")
        assertTrue(VOID_ASSIGNMENT in appearance, "EngineSession must forward the void to GL")
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue(startIndex >= 0, "section start is missing: $start")
        val contentStart = startIndex + start.length
        val endIndex = source.indexOf(end, contentStart)
        assertTrue(endIndex >= contentStart, "section end is missing: $end")
        return source.substring(contentStart, endIndex)
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
        const val CANVAS_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt"
        const val ENGINE_SESSION_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/EngineSession.kt"
        const val APPEARANCE_START = "fun setCanvasAppearance("
        const val APPEARANCE_END = "fun sampleColor("
        const val THEME_VOID =
            "val canvasVoid = canvasVoidColor(LocalThemeTone.current).toArgb()"
        const val APPEARANCE_CALL = "session?.setCanvasAppearance("
        const val VOID_ARGUMENT = "canvasVoid = canvasVoid,"
        const val VOID_ASSIGNMENT = "renderer.canvasVoid = canvasVoid"
    }
}
