package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class RmwSettingsWiringTest {

    @Test
    fun `smudge sheet receives and applies the active mixer`() {
        val screen = source(CANVAS_SCREEN_PATH)
        val sheet = source(RMW_SETTINGS_PATH)
        val smudgeCall = screen.substringAfter(SMUDGE_CALL_START).substringBefore(SMUDGE_CALL_END)
        val smudgeSheet = sheet.substringAfter(SMUDGE_SHEET_START).substringBefore(BLUR_SHEET_START)

        assertTrue(MIXER_ARGUMENT in smudgeCall, "smudge settings need the active mixer")
        assertTrue(MIXER_PARAMETER in smudgeSheet, "the sheet must accept the mixer")
        assertTrue(PIGMENT_POLICY in smudgeSheet, "RGB must hide the inert pigment switch")
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

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
        const val RMW_SETTINGS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/RmwSettingsSheet.kt"
        const val SMUDGE_CALL_START = "is ToolKind.Smudge -> SmudgeSettingsSheet("
        const val SMUDGE_CALL_END = "is ToolKind.Blur -> BlurSettingsSheet("
        const val SMUDGE_SHEET_START = "internal fun SmudgeSettingsSheet("
        const val BLUR_SHEET_START = "internal fun BlurSettingsSheet("
        const val MIXER_ARGUMENT = "mixerChoice = state.color.mixerChoice"
        const val MIXER_PARAMETER = "mixerChoice: MixerChoice"
        const val PIGMENT_POLICY =
            "if (RmwSettingsPolicy.showsPigmentControl(mixerChoice))"
    }
}
