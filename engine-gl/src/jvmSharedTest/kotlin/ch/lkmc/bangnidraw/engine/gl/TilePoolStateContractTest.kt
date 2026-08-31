package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class TilePoolStateContractTest {

    @Test
    fun `raw slice clear invalidates the shared GL cache`() {
        val pool = source(TILE_POOL_PATH)
        val clear = pool.substringAfter("fun clear(handle: SliceHandle)")
            .substringBefore("fun free(handle: SliceHandle)")
        val renderer = source(RENDERER_PATH)

        assertTrue("private val state: GlState" in pool)
        assertTrue("state.invalidate()" in clear)
        assertTrue("TilePool(probed, budget, state)" in renderer)
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
        const val TILE_POOL_PATH =
            "engine-gl/src/jvmShared/kotlin/ch/lkmc/bangnidraw/engine/gl/TilePool.kt"
        const val RENDERER_PATH =
            "engine-gl/src/jvmShared/kotlin/ch/lkmc/bangnidraw/engine/gl/CanvasRenderer.kt"
    }
}
