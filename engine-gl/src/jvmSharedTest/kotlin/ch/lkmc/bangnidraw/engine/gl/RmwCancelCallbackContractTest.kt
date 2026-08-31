package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class RmwCancelCallbackContractTest {

    @Test
    fun `an empty RMW stroke still reports cancellation`() {
        val source = File(repositoryRoot(), CANVAS_RENDERER_PATH).readText()
        val cancelBody = source.substringAfter(CANCEL_START).substringBefore(CANCEL_END)

        assertTrue(CANCEL_CALLBACK in cancelBody, "RMW cancel callback is missing")
        assertFalse(EMPTY_GUARD in cancelBody, "an empty RMW cancel must still complete")
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
        const val CANVAS_RENDERER_PATH =
            "engine-gl/src/jvmShared/kotlin/ch/lkmc/bangnidraw/engine/gl/CanvasRenderer.kt"
        const val CANCEL_START = "fun cancelStroke("
        const val CANCEL_END = "/** The rect the open stroke has dirtied"
        const val CANCEL_CALLBACK = "onRmwCancelled?.invoke(spec, keys)"
        const val EMPTY_GUARD = "keys.isNotEmpty()"
    }
}
