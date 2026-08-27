package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class WatercolorPassContractTest {

    @Test
    fun `one gesture uses one monotonic time`() {
        val source = source()

        assertTrue("fun begin(layer: LayerId, spec: RmwSpec, nowNanos: Long)" in source)
        assertTrue("strokeNowNanos = nowNanos" in source)
        assertTrue("strokeTick = WatercolorKernel.tickAt(nowNanos)" in source)
        assertTrue("layer.updatedAtNanos[wetGrid.index(key)] = strokeNowNanos" in source)
        assertFalse("nowTick: Int," in source)
    }

    @Test
    fun `expired wet pages are reclaimed before a gesture`() {
        val source = source()

        assertTrue("if (lastTickEpoch != null && lastTickEpoch != epoch) dryAll()" in source)
        assertTrue("pruneExpired(nowNanos)" in source)
        assertTrue("WatercolorKernel.isExpired(nowNanos, layer.updatedAtNanos[keyIndex])" in source)
        assertTrue("layer.textures.remove(key)" in source)
    }

    @Test
    fun `blank clear water bypasses color and history tracking`() {
        val source = source()

        assertTrue(
            "val affectsColor = spec is RmwSpec.Watercolor || hasColorSource(layer, plan.source)" in
                source,
        )
        assertTrue("if (affectsColor) {" in source)
    }

    @Test
    fun `watercolor consumes opacity and tip dynamics`() {
        val source = source()

        assertTrue("behavior.waterLoad * batch.flow[index] * stroke.opacity" in source)
        assertTrue("batch.flow[index] * stroke.opacity" in source)
        assertTrue("program.uniform2f(\"u_tip\", batch.angle[index], batch.aspect[index])" in source)
    }

    private fun source(): String = File(repositoryRoot(), WATER_PASS_PATH).readText()

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
        const val WATER_PASS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/WatercolorPass.kt"
    }
}
