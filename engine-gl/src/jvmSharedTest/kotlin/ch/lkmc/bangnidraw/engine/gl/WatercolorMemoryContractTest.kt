package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.WatercolorScratchBudget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class WatercolorMemoryContractTest {

    @Test
    fun `maximum retained watercolor scratch is budgeted`() {
        assertEquals(19_136_512L, WatercolorScratchBudget.MAX_BYTES)
    }

    @Test
    fun `renderer diagnostics report composite and watercolor scratch`() {
        val renderer = source(RENDERER_PATH)
        val watercolor = source(WATERCOLOR_PATH)

        assertTrue("append(\" | scratch \"" in renderer)
        assertTrue("watercolorPass?.scratchBytes" in renderer)
        assertTrue("val scratchBytes: Long" in watercolor)
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
        const val RENDERER_PATH =
            "engine-gl/src/jvmShared/kotlin/ch/lkmc/bangnidraw/engine/gl/CanvasRenderer.kt"
        const val WATERCOLOR_PATH =
            "engine-gl/src/jvmShared/kotlin/ch/lkmc/bangnidraw/engine/gl/WatercolorPass.kt"
    }
}
