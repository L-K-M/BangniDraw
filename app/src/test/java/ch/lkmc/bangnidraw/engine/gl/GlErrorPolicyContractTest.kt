package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class GlErrorPolicyContractTest {

    @Test
    fun `release pass checks return before querying GL`() {
        val source = File(repositoryRoot(), GL_PATH).readText()
        val body = section(source, CHECK_START, CHECK_END)

        val guardIndex = body.indexOf(RELEASE_GUARD)
        val drainIndex = body.indexOf(DRAIN_CALL)

        assertTrue(guardIndex >= 0, "release pass guard is missing")
        assertTrue(drainIndex >= 0, "debug pass check must still drain GL errors")
        assertTrue(guardIndex < drainIndex, "release builds must return before glGetError")
    }

    @Test
    fun `targeted checks report stale errors in strict mode before the operation`() {
        val source = File(repositoryRoot(), GL_PATH).readText()
        val body = section(source, ALLOCATION_START, ALLOCATION_END)

        val clearIndex = body.indexOf(STALE_DRAIN)
        val strictIndex = body.indexOf(STRICT_STALE_GUARD)
        val throwIndex = body.indexOf(THROW_CALL, strictIndex)
        val operationIndex = body.indexOf(OPERATION_CALL)
        val checkIndex = body.indexOf(DRAIN_CALL, operationIndex)

        assertTrue(clearIndex >= 0, "targeted check does not clear stale errors")
        assertTrue(strictIndex > clearIndex, "strict mode must inspect the stale error")
        assertTrue(throwIndex > strictIndex, "strict mode must report the stale error")
        assertTrue(body.contains(STALE_ERROR_NAME), "the failure must name the stale error")
        assertTrue(throwIndex < operationIndex, "a stale strict error must stop the operation")
        assertTrue(operationIndex > clearIndex, "guarded operation must follow the stale-error drain")
        assertTrue(checkIndex > operationIndex, "fresh errors must be read after the guarded operation")
    }

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
        const val GL_PATH = "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/Gl.kt"
        const val CHECK_START = "fun checkGlDebug(pass: String) {"
        const val CHECK_END = "fun reset()"
        const val ALLOCATION_START =
            "fun checkAllocation(what: String, operation: () -> Unit): Int {"
        const val ALLOCATION_END = "fun checkGlDebug(pass: String) {"
        const val RELEASE_GUARD = "if (!strict) return"
        const val DRAIN_CALL = "drain()"
        const val STALE_DRAIN = "val stale = drain()"
        const val STRICT_STALE_GUARD = "if (strict && stale != GLES30.GL_NO_ERROR)"
        const val THROW_CALL = "throw IllegalStateException("
        const val STALE_ERROR_NAME = "name(stale)"
        const val OPERATION_CALL = "operation()"
    }
}
