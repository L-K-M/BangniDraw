package ch.lkmc.bangnidraw.desktop

import java.io.File
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
        assertTrue(build.contains("debMaintainer"))
        assertFalse(build.contains("packageName = \"BangniDraw\""))
        assertFalse(main.contains("BangniDraw Desktop"))
    }

    @Test
    fun `mac installer follows the canonical package name`() {
        val script = source("scripts/build.sh")

        assertTrue(script.contains("DESKTOP_NAME="))
        assertTrue(script.contains("app_name"))
        assertTrue(script.contains("DESKTOP_APP_ROOT="))
        assertTrue(script.contains("find "))
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
    fun `Compose receives mac resources from its actual target directories`() {
        assertTrue(repoFile("desktop/packaging/angle/macos-arm64").isDirectory)
        assertTrue(repoFile("desktop/packaging/angle/macos-x64").isDirectory)
        assertFalse(repoFile("desktop/packaging/angle/darwin-arm64").exists())
        assertFalse(repoFile("desktop/packaging/angle/darwin-x86-64").exists())
    }

    @Test
    fun `GLFW bootstrap disables its menu and initializes GLES bindings`() {
        val glfw = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/GlfwEsContext.kt")
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(glfw.contains("GLFW_COCOA_MENUBAR"))
        assertTrue(glfw.contains("GLES.createCapabilities()"))
        assertTrue(glfw.contains("GLES.setCapabilities(null)"))
        assertTrue(glfw.contains("glfwTerminate()"))
        assertFalse(engine.contains("GlfwEsContext.create"))
        assertTrue(main.contains("DesktopNativeBootstrap.prepare()"))
        assertTrue(main.contains("GlfwEsContext.create"))
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
        val output = process.inputStream.bufferedReader().use { it.readText() }

        return CommandResult(process.waitFor(), output)
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
    )

    private fun source(path: String): String = repoFile(path).readText()

    private fun repoFile(path: String): File = File(repoRoot(), path)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }
}
