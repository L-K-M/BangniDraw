package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class GlErrorPolicyContractTest {

    @Test
    fun `release pass checks return before querying GL`() {
        val source = File(repositoryRoot(), GL_PATH).readText()
        val body = source.substringAfter(CHECK_START).substringBefore(CHECK_END)

        val guardIndex = body.indexOf(RELEASE_GUARD)
        val drainIndex = body.indexOf(DRAIN_CALL)

        assertTrue(guardIndex >= 0, "release pass guard is missing")
        assertTrue(drainIndex >= 0, "debug pass check must still drain GL errors")
        assertTrue(guardIndex < drainIndex, "release builds must return before glGetError")
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
        const val CHECK_END = "/** Forgets"
        const val RELEASE_GUARD = "if (!strict) return"
        const val DRAIN_CALL = "drain()"
    }
}
