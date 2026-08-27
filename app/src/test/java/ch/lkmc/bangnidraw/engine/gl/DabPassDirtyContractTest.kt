package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.PerfConstants
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class DabPassDirtyContractTest {

    @Test
    fun `dab pass uploads the complete core dab layout`() {
        val source = File(repositoryRoot(), DAB_PASS_PATH).readText()
        val compact = source.replace(Regex("""\s+"""), "")

        assertEquals(11, PerfConstants.DAB_STRIDE)
        assertTrue("constvalDAB_FLOATS=DAB_STRIDE" in compact)
        assertTrue("instanceData[o++]=batch.seed[i]" in compact)
        assertTrue("instanceData[o++]=batch.wetness[i]" in compact)
        assertTrue("instanceData[o++]=batch.bristleAlong[i]" in compact)
        assertTrue("instanceData[o]=batch.bristleAcross[i]" in compact)
        assertTrue("Shaders.ATTR_DAB_SEEDto1" in compact)
        assertTrue("Shaders.ATTR_DAB_WETNESSto1" in compact)
        assertTrue("Shaders.ATTR_DAB_BRISTLE_ALONGto1" in compact)
        assertTrue("Shaders.ATTR_DAB_BRISTLE_ACROSSto1" in compact)
        assertTrue("program.uniform1i(\"u_brushModel\",brushModel.shaderId)" in compact)
    }

    @Test
    fun `dab pass reports exact selected bounds only after a tile draw`() {
        val source = File(repositoryRoot(), DAB_PASS_PATH).readText()
        val stamp = source.substringAfter(STAMP_START).substringBefore(STAMP_END)

        assertFalse(
            TILE_RECT_DAMAGE.containsMatchIn(stamp),
            "stamp damage must not widen a dab to every touched tile's full rect",
        )

        val drewFlag = DRAW_GUARD.find(stamp)?.groupValues?.get(1)
            ?: fail("stamp must remember whether at least one tile was drawn")
        val drawIndex = stamp.indexOf(DRAW_CALL)
        assertTrue(drawIndex >= 0, "stamp must issue an instanced tile draw")
        val drewIndex = Regex("""\b${Regex.escape(drewFlag)}\s*=\s*true\b""")
            .find(stamp, startIndex = drawIndex)
            ?.range
            ?.first
            ?: fail("stamp must mark $drewFlag only after its tile draw")
        assertTrue(drewIndex > drawIndex, "$drewFlag was marked before the tile draw")

        val dirtyMatch = Regex(
            """val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*if\s*\(\s*${Regex.escape(drewFlag)}\s*\)\s*batch\.bounds\(\s*from\s*,\s*until\s*\)\s*else\s*IntRect\.EMPTY""",
        ).find(stamp) ?: fail(
            "stamp must use batch.bounds(from, until) only when at least one tile was drawn",
        )
        val dirtyName = dirtyMatch.groupValues[1]
        assertTrue(
            Regex("""buffer\.growDirty\(\s*${Regex.escape(dirtyName)}\s*\)""")
                .containsMatchIn(stamp),
            "the stroke buffer must grow by the exact selected dab bounds",
        )
        assertTrue(
            Regex("""return\s+${Regex.escape(dirtyName)}\b""").containsMatchIn(stamp),
            "stamp must return the same exact bounds it adds to the stroke buffer",
        )
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
        const val DAB_PASS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/DabPass.kt"
        const val STAMP_START = "fun stamp("
        const val STAMP_END = "// ------------------------------------------------------------- gathering"
        const val DRAW_CALL = "GLES30.glDrawArraysInstanced("
        val TILE_RECT_DAMAGE = Regex("""\.union\(\s*grid\.tileRect\(""")
        val DRAW_GUARD = Regex("""var\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*false\b""")
    }
}
