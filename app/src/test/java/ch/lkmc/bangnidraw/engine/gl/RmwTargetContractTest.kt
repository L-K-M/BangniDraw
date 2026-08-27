package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class RmwTargetContractTest {

    @Test
    fun `pressure-sized RMW targets retain sufficient capacity`() {
        val source = File(repositoryRoot(), SMUDGE_PASS_PATH).readText()

        assertTrue("before.ensureCapacity(" in source)
        assertTrue("work.ensureCapacity(" in source)
        assertTrue("state.viewport(0, 0, work.width, work.height)" in source)
        assertTrue("before.width.toFloat() / before.capacityWidth" in source)
        assertTrue("1f / before.capacityWidth" in source)
        assertTrue("1f / work.capacityWidth" in source)
    }

    @Test
    fun `offscreen targets separate logical size from allocated capacity`() {
        val source = File(repositoryRoot(), OFFSCREEN_TARGET_PATH).readText()

        assertTrue("val capacityWidth:" in source)
        assertTrue("val capacityHeight:" in source)
        assertTrue("capacity.rgba8Bytes" in source)
        assertTrue("Sizing.GROW_ONLY -> capacity.growTo(width, height)" in source)
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
        const val SMUDGE_PASS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/SmudgePass.kt"
        const val OFFSCREEN_TARGET_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/OffscreenTarget.kt"
    }
}
