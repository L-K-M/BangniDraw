package ch.lkmc.bangnidraw.desktop

import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPackagingContractTest {

    @Test
    fun `packaged runtime includes every module suggested by Compose`() {
        val build = source("desktop/build.gradle.kts")

        assertTrue(build.contains("java.instrument"))
        assertTrue(build.contains("jdk.management"))
        assertTrue(build.contains("jdk.unsupported"))
    }

    @Test
    fun `desktop packages use canonical name and project icon`() {
        val build = source("desktop/build.gradle.kts")
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(build.contains("dockName"))
        assertTrue(build.contains("iconFile"))
        assertTrue(build.contains("appCategory = \"Graphics\""))
        assertTrue(build.contains("decodeXmlText"))
        assertTrue(build.contains("escapeXmlText"))
        assertTrue(build.contains("debMaintainer"))
        assertFalse(build.contains("packageName = \"BangniDraw\""))
        assertFalse(main.contains("BangniDraw Desktop"))
    }

    @Test
    fun `mac package exposes the canonical Finder display name`() {
        val build = source("desktop/build.gradle.kts")

        assertTrue(build.contains("infoPlist {"))
        assertTrue(build.contains("<key>CFBundleDisplayName</key>"))
        assertTrue(build.contains("<string>${'$'}desktopDisplayNameForPlist</string>"))
    }

    @Test
    fun `mac installer follows the canonical package name`() {
        val script = source("scripts/build.sh")

        assertTrue(script.contains("DESKTOP_NAME="))
        assertTrue(script.contains("desktop_display_name app/src/main/res/values/strings.xml || true"))
        assertTrue(script.contains("desktop_find_app \"\$DESKTOP_APP_ROOT\" \"\$DESKTOP_NAME\" || true"))
        assertTrue(script.contains("app_name"))
        assertTrue(script.contains("DESKTOP_APP_ROOT="))
        assertTrue(script.contains("desktop_find_app \"\$DESKTOP_APP_ROOT\" \"\$DESKTOP_NAME\""))
        assertTrue(script.contains("/Applications/${'$'}{DESKTOP_NAME}.app"))
        assertFalse(script.contains("BangniDraw.app"))
        assertFalse(script.contains("darwin-"))
        assertFalse(script.contains("uname -m"))
    }

    @Test
    fun `mac installer helper resolves its inputs`() {
        val fixture = createTempDirectory("desktop-package").toFile()
        try {
            val app = File(fixture, "nested/帮你Draw.app")
            val resources = File(app, "Contents/app/resources").apply { mkdirs() }
            File(resources, "libEGL.dylib").writeBytes(byteArrayOf(1))
            val gles = File(resources, "libGLESv2.dylib").apply {
                writeBytes(byteArrayOf(1))
            }

            val displayName = runHelper(
                "desktop_display_name \"\$STRINGS_FILE\"",
                "STRINGS_FILE" to repoFile("app/src/main/res/values/strings.xml").path,
            )
            assertEquals(0, displayName.exitCode, displayName.output)
            assertEquals("帮你Draw", displayName.output.trim())

            val strings = File(fixture, "strings.xml")
            strings.writeText(
                """
                    <resources>
                        <string translatable="false" name = "app_name"> Draw &amp; Paint </string>
                    </resources>
                """.trimIndent(),
                Charsets.UTF_8,
            )
            val decodedName = runHelper(
                "desktop_display_name \"\$STRINGS_FILE\"",
                "STRINGS_FILE" to strings.path,
            )
            assertEquals(0, decodedName.exitCode, decodedName.output)
            assertEquals("Draw & Paint", decodedName.output.trim())


            val found = runHelper(
                "desktop_find_app \"\$APP_ROOT\" \"\$APP_NAME\"",
                "APP_ROOT" to fixture.path,
                "APP_NAME" to "帮你Draw",
            )
            assertEquals(0, found.exitCode, found.output)
            assertEquals(app.canonicalPath, File(found.output.trim()).canonicalPath)

            assertEquals(0, runHelper("desktop_app_has_angle \"\$APP_PATH\"", "APP_PATH" to app.path).exitCode)
            gles.delete()
            assertTrue(runHelper("desktop_app_has_angle \"\$APP_PATH\"", "APP_PATH" to app.path).exitCode != 0)
        } finally {
            fixture.deleteRecursively()
        }
    }

    @Test
    fun `helper stdout and stderr are drained while the process runs`() {
        val result = runHelper(
            """
                i=0
                while ((i < PIPE_STRESS_REPETITIONS)); do
                  printf '%s' "${'$'}PIPE_STRESS_STDOUT_CHUNK"
                  printf '%s' "${'$'}PIPE_STRESS_STDERR_CHUNK" >&2
                  ((i += 1))
                done
            """.trimIndent(),
            "PIPE_STRESS_REPETITIONS" to PIPE_STRESS_REPETITIONS.toString(),
            "PIPE_STRESS_STDOUT_CHUNK" to PIPE_STRESS_STDOUT_CHUNK,
            "PIPE_STRESS_STDERR_CHUNK" to PIPE_STRESS_STDERR_CHUNK,
        )

        val stdoutBytes = PIPE_STRESS_STDOUT_CHUNK.length * PIPE_STRESS_REPETITIONS
        val stderrBytes = PIPE_STRESS_STDERR_CHUNK.length * PIPE_STRESS_REPETITIONS

        assertEquals(0, result.exitCode, result.output)
        assertEquals(stdoutBytes, result.output.count { it == PIPE_STRESS_STDOUT_MARKER })
        assertEquals(stderrBytes, result.output.count { it == PIPE_STRESS_STDERR_MARKER })
    }

    @Test
    fun `Compose receives mac resources from its actual target directories`() {
        assertTrue(repoFile("desktop/packaging/angle/macos-arm64").isDirectory)
        assertTrue(repoFile("desktop/packaging/angle/macos-x64").isDirectory)
        assertFalse(repoFile("desktop/packaging/angle/darwin-arm64").exists())
        assertFalse(repoFile("desktop/packaging/angle/darwin-x86-64").exists())
    }

    @Test
    fun `both GL hosts install GLES bindings and startup owns their order`() {
        val glfw = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/GlfwEsContext.kt")
        val egl = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/EglEsContext.kt")
        val startup = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopGlStartup.kt")
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(glfw.contains("GLFW_COCOA_MENUBAR"))
        assertTrue(glfw.contains("GLES.createCapabilities()"))
        assertTrue(glfw.contains("GLES.setCapabilities(null)"))
        assertTrue(glfw.contains("glfwTerminate()"))
        assertTrue(egl.contains("GLES.createCapabilities()"))
        assertTrue(egl.contains("GLES.setCapabilities(null)"))
        assertTrue(egl.contains("eglTerminate("))
        assertFalse(engine.contains("GlfwEsContext.create"))
        assertFalse(engine.contains("EglEsContext.create"))
        assertTrue(startup.contains("DesktopNativeBootstrap.prepare(report)"))
        // The primary host as well as the fallback: losing the EGL attempt
        // would restore the macOS failure and leave GLFW quietly answering.
        assertTrue(startup.contains("EglEsContext.create"))
        assertTrue(startup.contains("GlfwEsContext.create"))
        assertTrue(main.contains("DesktopGlStartup.start"))
        assertTrue(main.contains("DesktopAboutHandler.install"))
    }

    @Test
    fun `desktop packages carry only current host LWJGL natives`() {
        val builds = listOf(
            source("desktop/build.gradle.kts"),
            source("engine-gl/build.gradle.kts"),
        )

        builds.forEach { build ->
            assertTrue(build.contains("val lwjglNativeClassifier"))
            assertFalse(build.contains("for (natives in listOf("))
            assertTrue(build.contains("natives-windows-arm64"))
        }
    }

    private fun runHelper(command: String, vararg variables: Pair<String, String>): CommandResult {
        val helper = repoFile("scripts/lib/desktop-package.sh")
        val builder = ProcessBuilder(
            "bash",
            "-c",
            "source \"\$HELPER\"; $command",
        ).redirectErrorStream(true)
        builder.environment()["HELPER"] = helper.path
        variables.forEach { (key, value) -> builder.environment()[key] = value }

        val process = builder.start()
        val outputExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, HELPER_OUTPUT_THREAD_NAME).apply { isDaemon = true }
        }
        val outputFuture = outputExecutor.submit<String> {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        try {
            if (!process.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                if (!process.waitFor(HELPER_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    error("desktop-package.sh helper did not terminate: $command")
                }

                awaitOutput(outputFuture, process, command)
                error("desktop-package.sh helper timed out: $command")
            }

            val output = awaitOutput(outputFuture, process, command)

            return CommandResult(process.exitValue(), output)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(HELPER_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }

            process.inputStream.close()
            outputExecutor.shutdownNow()
            outputExecutor.awaitTermination(HELPER_OUTPUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun awaitOutput(
        outputFuture: Future<String>,
        process: Process,
        command: String,
    ): String = try {
        outputFuture.get(HELPER_OUTPUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (failure: TimeoutException) {
        process.inputStream.close()
        outputFuture.cancel(true)
        throw IllegalStateException("desktop-package.sh output timed out: $command", failure)
    } catch (failure: ExecutionException) {
        throw IllegalStateException("desktop-package.sh output failed: $command", failure.cause)
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
    )

    private fun source(path: String): String = repoFile(path).readText(Charsets.UTF_8)

    private fun repoFile(path: String): File = File(repoRoot(), path)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }

    private companion object {
        const val HELPER_TIMEOUT_SECONDS = 60L
        const val HELPER_TERMINATION_TIMEOUT_SECONDS = 5L
        const val HELPER_OUTPUT_TIMEOUT_SECONDS = 5L
        const val HELPER_OUTPUT_THREAD_NAME = "desktop-package-output"
        const val PIPE_STRESS_REPETITIONS = 32_768
        const val PIPE_STRESS_STDOUT_MARKER = 'o'
        const val PIPE_STRESS_STDERR_MARKER = 'e'
        const val PIPE_STRESS_STDOUT_CHUNK = "oooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo"
        const val PIPE_STRESS_STDERR_CHUNK = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
