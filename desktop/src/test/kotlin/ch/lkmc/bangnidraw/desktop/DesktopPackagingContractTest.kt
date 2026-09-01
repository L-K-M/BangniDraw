package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
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
