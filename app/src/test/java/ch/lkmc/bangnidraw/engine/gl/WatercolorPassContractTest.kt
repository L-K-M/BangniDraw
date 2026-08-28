package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class WatercolorPassContractTest {

    @Test
    fun `each batch carries current monotonic time`() {
        val source = source()

        assertTrue("fun begin(layer: LayerId, spec: RmwSpec, nowNanos: Long)" in source)
        assertTrue("resetActiveEpoch(layerId, nowNanos)" in source)
        assertTrue("val nowTick = WatercolorKernel.tickAt(nowNanos)" in source)
        assertTrue("layer.updatedAtNanos[wetGrid.index(key)] = nowNanos" in source)
        assertFalse("strokeNowNanos" in source)
    }

    @Test
    fun `expired wet pages are reclaimed before a gesture`() {
        val source = source()

        assertTrue("resetDryEpoch(nowNanos)" in source)
        assertTrue("pruneExpired(nowNanos)" in source)
        assertTrue("WatercolorKernel.isExpired(nowNanos, layer.updatedAtNanos[keyIndex])" in source)
        assertTrue("layer.textures.remove(key)" in source)
    }

    @Test
    fun `blank clear water bypasses color and history tracking`() {
        val source = source()
        val layerTextures = File(repositoryRoot(), LAYER_TEXTURES_PATH).readText()

        assertTrue(
            "val affectsColor = spec is RmwSpec.Watercolor || layer.hasColorContent(" in source,
        )
        assertTrue("dabBounds.sourceLeft" in source)
        assertFalse("plan.source" in source)
        assertTrue("if (affectsColor) {" in source)
        assertFalse("hasColorSource(" in source)
        assertTrue("content.mayContainColor(TileKey(out[index]), rect)" in layerTextures)
    }

    @Test
    fun `watercolor uses flow and tip dynamics`() {
        val source = source()

        assertTrue("behavior.waterLoad * batch.flow[index]" in source)
        assertTrue("active.uniform1f(\"u_strength\", batch.flow[index])" in source)
        assertFalse("stroke.opacity" in source)
        assertTrue("if (batch.flow[index] <= 0f) continue" in source)
        assertTrue("program.uniform2f(\"u_tip\", batch.angle[index], batch.aspect[index])" in source)
    }

    @Test
    fun `a dab reserves every output slice before its first write`() {
        val stamp = source()
            .substringAfter("fun stamp(")
            .substringBefore("fun cancel()")
        val backup = stamp.indexOf("backupWet(")
        val reserve = stamp.indexOf("reserveDabOutput(")
        val firstWrite = stamp.indexOf("drawColor(")

        assertTrue(backup >= 0)
        assertTrue(reserve > backup)
        assertTrue(firstWrite > reserve)
        assertTrue("rollbackDabReservation" in source())
    }

    @Test
    fun `wet output is rebound after a new pool slice is cleared`() {
        val write = source()
            .substringAfter("private fun writeWet")
            .substringBefore("private fun blitWetIntersection")
        val allocation = write.indexOf("sliceForWrite(key)")
        val rebound = write.indexOf(
            "bindTexture2d(wetAfter.texture, GLES30.GL_READ_FRAMEBUFFER)",
            startIndex = allocation,
        )

        assertTrue(allocation >= 0)
        assertTrue(rebound > allocation)
    }

    @Test
    fun `written color tiles invalidate semantic occupancy`() {
        val draw = source()
            .substringAfter("private fun drawOutputTiles")
            .substringBefore("private fun writeWet")
        val write = draw.indexOf("quad.draw(")
        val invalidate = draw.indexOf("layer.markColorWritten(key)")

        assertTrue(write >= 0)
        assertTrue(invalidate > write)
    }

    @Test
    fun `tick rollover rebases live pages without dropping fresh water`() {
        val reset = source()
            .substringAfter("private fun resetDryEpoch")
            .substringBefore("private fun resetActiveEpoch")

        assertTrue("rebaseWetPages(nowNanos)" in reset)
        assertFalse("dryAll()" in reset)
    }

    @Test
    fun `wet invalidation follows edit authorization`() {
        val renderer = File(repositoryRoot(), RENDERER_PATH).readText()
        val apply = renderer.substringAfter("fun applyPixelOps(")
            .substringBefore("private fun prepareCopy")
        val authorization = apply.indexOf("if (!beforeCommit())")
        val invalidation = apply.indexOf("applyWetInvalidation")

        assertTrue(authorization >= 0)
        assertTrue(invalidation > authorization)
        assertFalse("watercolorPass?.dryAll()" in apply.substring(0, authorization))
    }

    @Test
    fun `raw RMW lifecycle restores known GL state`() {
        val renderer = File(repositoryRoot(), RENDERER_PATH).readText()
        val begin = renderer.substringAfter("fun beginStroke(")
            .substringBefore("fun stampDabs(")
        val cancel = renderer.substringAfter("fun cancelStroke(")
            .substringBefore("val strokeDirty")
        val restore = source().substringAfter("private fun copyWetKey(")
            .substringBefore("private fun rebaseWetPages")

        val invalidation = begin.indexOf("state.invalidate()")
        val watercolor = begin.indexOf("watercolorPass?.begin")
        assertTrue(invalidation >= 0)
        assertTrue(watercolor > invalidation)
        assertTrue("state.invalidate()" in cancel)
        assertTrue("state.scissorOff()" in restore)
    }

    @Test
    fun `wet fixed-point writes disable dithering`() {
        val glState = File(repositoryRoot(), GL_STATE_PATH).readText()
        val wetPass = source()

        assertTrue("fun ditherOff()" in glState)
        assertTrue("GLES30.glDisable(GLES30.GL_DITHER)" in glState)
        assertTrue("state.ditherOff()" in wetPass)
    }

    @Test
    fun `watercolor hot loop reuses primitive bounds and a unit quad`() {
        val source = source()
        val stamp = source.substringAfter("fun stamp(").substringBefore("fun cancel()")

        assertTrue("private val dabBounds = WatercolorDabBounds(" in source)
        assertFalse("WatercolorDabPlan.forDab" in stamp)
        assertFalse("RmwTileScissor.forRect" in source)
        assertFalse("quad.draw(wetAfter" in source)
        assertFalse("quad.draw(TILE_SIZE" in source)
        assertTrue("quad.draw(UNIT_QUAD_EDGE, UNIT_QUAD_EDGE)" in source)
    }

    @Test
    fun `grow-only targets clear only their logical rect`() {
        val clear = source()
            .substringAfter("private fun clear(target: OffscreenTarget)")
            .substringBefore("private fun bindDab")
        val scissor = clear.indexOf("state.scissor(0, 0, target.width, target.height)")
        val erase = clear.indexOf("drawFbo.clear(0f, 0f, 0f, 0f)")

        assertTrue(scissor >= 0)
        assertTrue(erase > scissor)
    }

    @Test
    fun `wet backup uses preallocated primitive records`() {
        val source = source()
        val backup = source.substringAfter("private fun backupWet")
            .substringBefore("private fun restoreWetKey")

        assertTrue("private val backedWetStates = ByteArray(wetGrid.tileCount)" in source)
        assertTrue("private val backedWetKeys = IntArray(wetGrid.tileCount)" in source)
        assertTrue("private var backedWetKeyCount = 0" in source)
        assertFalse("LinkedHashSet<TileKey>" in source)
        assertFalse("HashSet<TileKey>" in source)
        assertFalse("key in backedWetKeys" in backup)
        assertFalse("backedWetKeys += key" in backup)
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
        const val RENDERER_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/CanvasRenderer.kt"
        const val GL_STATE_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/GlState.kt"
        const val LAYER_TEXTURES_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/LayerTextures.kt"
    }
}
