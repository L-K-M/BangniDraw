package ch.lkmc.bangnidraw.desktop

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `a wrong-architecture ANGLE is named rather than left to fail silently`() {
        val root = Files.createTempDirectory("bangnidraw-angle")
        val directory = angleDirectory(root.resolve("angle").toFile())
        val angle = checkNotNull(
            DesktopNativeBootstrap.resolveAngle(
                explicitDirectory = directory,
                packagedDirectory = null,
                workingDirectory = null,
            ),
        )

        // The staged files are empty here, so the header reads as unknown: the
        // description must still name the directory it will load from.
        val described = DesktopNativeBootstrap.describe(angle, osArch = "aarch64")

        assertTrue(described.startsWith("ANGLE: ${directory.canonicalFile}"))
        assertTrue(described.contains(DesktopNativeBootstrap.EGL_DYLIB))
        assertTrue(described.contains(DesktopNativeBootstrap.GLES_DYLIB))
    }

    @Test
    fun `EGL is tried first and GLFW is handed absolute library paths`() {
        val startup = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopGlStartup.kt")
        val glfw = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/GlfwEsContext.kt")
        val direct = startup.indexOf("EglEsContext.create(")
        val fallback = startup.indexOf("GlfwEsContext.create(")

        assertTrue(direct >= 0, "the direct EGL host must be attempted")
        assertTrue(direct < fallback, "EGL must be attempted before the GLFW fallback")
        assertTrue(glfw.contains("GLFWNativeEGL.setEGLPath(angle.egl.absolutePath)"))
        assertTrue(glfw.contains("GLFWNativeEGL.setGLESPath(angle.gles.absolutePath)"))
    }

    @Test
    fun `no GL host changes the process working directory`() {
        listOf(
            "DesktopNativeBootstrap.kt",
            "DesktopGlStartup.kt",
            "GlfwEsContext.kt",
            "EglEsContext.kt",
        ).forEach { name ->
            val text = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/$name")

            // A process-wide chdir used to be how ANGLE was found; absolute
            // paths replaced it, and reintroducing it would break a hardened
            // process all over again.
            assertFalse(text.contains("chdir"), "$name must not move the process directory")
        }
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
