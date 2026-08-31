package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The per-dab instance upload orphans its storage before writing, the same
 * rule `CompositePass` states at its own two upload sites and `SmudgePass`
 * cites as the reason for its quad split.
 *
 * This is the engine's hottest upload: `uploadInstances` runs inside
 * `stamp`'s per-tile loop, immediately before `glDrawArraysInstanced`, so
 * each iteration writes into the range the previous iteration's draw may
 * still be reading — and `ensureInstanceCapacity` only calls `glBufferData`
 * while the capacity is *growing*, so once it settles this is the permanent
 * steady state for every dab of every stroke. Without the orphan a tiler
 * driver stalls the CPU until the GPU catches up instead of renaming the
 * buffer, on exactly the path the front-buffered architecture exists to keep
 * fast.
 */
class DabPassOrphanContractTest {

    @Test
    fun `the instance upload orphans before it writes`() {
        val upload = compactSection(UPLOAD_START, UPLOAD_END)

        val orphan = upload.indexOf(ORPHAN)
        if (orphan < 0) {
            fail("uploadInstances must orphan its storage before writing: missing $ORPHAN")
        }
        val write = upload.indexOf(SUB_DATA)
        if (write < 0) fail("uploadInstances no longer calls $SUB_DATA — renamed?")

        assertTrue(
            orphan < write,
            "the orphan must precede the write, or it renames nothing",
        )
        // The orphan's SIZE is load-bearing too, and ordering alone does not
        // pin it. ensureInstanceCapacity only grows, so an orphan narrowed to
        // this call's own `n` would leave the storage that size: the next
        // tile in the same stamp loop with a larger n — still within the
        // committed capacity, so no reallocation — would glBufferSubData past
        // the end, and the driver answers GL_INVALID_VALUE by dropping that
        // tile's dabs. Silent vanishing ink, which is exactly what this test
        // exists to catch. No spaces: compactSection strips all whitespace.
        assertTrue(
            "instanceCapacityDabs*DAB_FLOATS*4" in upload,
            "the orphan must re-specify the committed capacity — a smaller " +
                "orphan lets a later glBufferSubData overflow the allocation",
        )
    }

    /**
     * Comments are stripped before the needles are applied: this window
     * includes the prose explaining the rule, which names the very calls
     * being pinned.
     */
    private fun compactSection(startMarker: String, endMarker: String): String {
        val source = File(repositoryRoot(), DAB_PASS_PATH)
            .readText()
            .replace(Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""\s+"""), "")
        val start = source.indexOf(startMarker)
        if (start < 0) fail("missing $startMarker in $DAB_PASS_PATH — renamed?")
        val end = source.indexOf(endMarker, start + startMarker.length)
        if (end <= start) fail("missing $endMarker after $startMarker in $DAB_PASS_PATH")

        return source.substring(start, end)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull {
                File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory
            }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val DAB_PASS_PATH =
            "engine-gl/src/jvmShared/kotlin/ch/lkmc/bangnidraw/engine/gl/DabPass.kt"
        const val UPLOAD_START = "privatefunuploadInstances("
        const val UPLOAD_END = "privatefunensureInstanceCapacity("
        const val ORPHAN = "GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,"
        const val SUB_DATA = "GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER,"
    }
}
