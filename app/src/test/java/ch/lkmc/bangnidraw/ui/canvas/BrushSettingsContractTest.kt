package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class BrushSettingsContractTest {

    @Test
    fun `pigment controls require the active pigment mixer`() {
        val sheet = source(BRUSH_SETTINGS_PATH)
        val paintGroup = section(sheet, PAINT_GROUP_START, PAINT_GROUP_END)

        assertTrue(MIXER_PARAMETER in sheet, "brush settings do not receive the active mixer")
        assertTrue(PIGMENT_GUARD in paintGroup, "RGB mixing still exposes pigment controls")
    }

    @Test
    fun `Canvas passes the resolved mixer to brush settings`() {
        val canvas = source(CANVAS_SCREEN_PATH)

        assertTrue(RESOLVED_MIXER in canvas, "Canvas does not resolve the active mixer")
        assertTrue(MIXER_ARGUMENT in canvas, "brush settings receive no resolved mixer state")
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
        const val BRUSH_SETTINGS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/BrushSettingsSheet.kt"
        const val CANVAS_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt"
        const val PAINT_GROUP_START = "SettingsGroup(stringResource(R.string.brush_group_paint))"
        const val PAINT_GROUP_END = "ChoiceLabel(stringResource(R.string.brush_buffer_mode))"
        const val MIXER_PARAMETER = "mixerChoice: MixerChoice"
        const val PIGMENT_GUARD =
            "if (!active.eraseMode && mixerChoice == MixerChoice.PIGMENT)"
        const val RESOLVED_MIXER = "val mixerChoice = if (state.color.mixerIsPigment)"
        const val MIXER_ARGUMENT = "mixerChoice = mixerChoice"
    }
}
