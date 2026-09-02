package ch.lkmc.bangnidraw.desktop

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNativeBootstrapTest {

    @Test
    fun `only macOS selects the ANGLE Metal backend`() {
        assertEquals(DesktopGlBackend.AngleMetal, DesktopNativeBootstrap.backendFor("Mac OS X"))
        assertEquals(DesktopGlBackend.SystemEgl, DesktopNativeBootstrap.backendFor("Linux"))
        assertEquals(DesktopGlBackend.SystemEgl, DesktopNativeBootstrap.backendFor("Windows 11"))
    }

    @Test
    fun `explicit ANGLE directory wins over packaged resources`() {
        val root = Files.createTempDirectory("bangnidraw-angle")
        val explicit = angleDirectory(root.resolve("explicit").toFile())
        val packaged = angleDirectory(root.resolve("packaged").toFile())

        val found = DesktopNativeBootstrap.resolveAngle(
            explicitDirectory = explicit,
            packagedDirectory = packaged,
            workingDirectory = null,
        )

        assertEquals(explicit.canonicalFile, found?.directory)
    }

    @Test
    fun `packaged ANGLE directory is the documented default`() {
        val root = Files.createTempDirectory("bangnidraw-angle")
        val packaged = angleDirectory(root.resolve("packaged").toFile())

        val found = DesktopNativeBootstrap.resolveAngle(
            explicitDirectory = null,
            packagedDirectory = packaged,
            workingDirectory = null,
        )

        assertEquals(packaged.canonicalFile, found?.directory)
    }


    @Test
    fun `ANGLE libraries are exposed through the process working directory`() {
        val root = Files.createTempDirectory("bangnidraw-angle")
        val original = root.resolve("original").createDirectories().toFile()
        val directory = angleDirectory(root.resolve("angle").toFile())
        val angle = checkNotNull(
            DesktopNativeBootstrap.resolveAngle(
                explicitDirectory = directory,
                packagedDirectory = null,
                workingDirectory = null,
            ),
        )
        val changes = mutableListOf<File>()

        val exposure = DesktopNativeBootstrap.exposeAngleToGlfw(
            angle = angle,
            originalDirectory = original,
            changeDirectory = { changes += it },
        )
        exposure.close()

        assertEquals(listOf(directory.canonicalFile, original.canonicalFile), changes)
    }

    @Test
    fun `first GLFW window is created while ANGLE is exposed`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
        val scopedCreate = Regex(
            """DesktopNativeBootstrap\.prepare\(\)\.use\s*\{\s*environment\s*->.*?""" +
                """GlfwEsContext\.create\([^)]*environment\.backend\)""",
            RegexOption.DOT_MATCHES_ALL,
        )

        assertTrue(scopedCreate.containsMatchIn(main))
    }

    @Test
    fun `GLFW preserves the ANGLE directory until window creation`() {
        val context = source(
            "desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/GlfwEsContext.kt",
        )
        val create = context.substringAfter("fun create(", "").substringBefore("return GlfwEsContext", "")
        val preserveDirectory = create.indexOf(
            "GLFW.glfwInitHint(GLFW.GLFW_COCOA_CHDIR_RESOURCES, GLFW.GLFW_FALSE)",
        )
        val initialize = create.indexOf("GLFW.glfwInit()")
        val createWindow = create.indexOf("GLFW.glfwCreateWindow(")

        assertTrue(preserveDirectory >= 0, "GLFW must not replace the ANGLE working directory")
        assertTrue(preserveDirectory < initialize, "the Cocoa init hint must precede glfwInit")
        assertTrue(initialize < createWindow, "glfwInit must precede window creation")
    }

    @Test
    fun `incomplete ANGLE directory is rejected`() {
        val root = Files.createTempDirectory("bangnidraw-angle")
        val incomplete = root.resolve("incomplete").createDirectories()
        incomplete.resolve(DesktopNativeBootstrap.EGL_DYLIB).createFile()

        val found = DesktopNativeBootstrap.resolveAngle(
            explicitDirectory = incomplete.toFile(),
            packagedDirectory = null,
            workingDirectory = null,
        )

        assertNull(found)
    }


    private fun source(path: String): String = File(repoRoot(), path).readText(Charsets.UTF_8)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }

        return candidate
    }
    private fun angleDirectory(directory: File): File {
        val path = directory.toPath().createDirectories()
        path.resolve(DesktopNativeBootstrap.EGL_DYLIB).createFile()
        path.resolve(DesktopNativeBootstrap.GLES_DYLIB).createFile()
        return directory
    }
}
