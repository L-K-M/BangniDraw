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
        val directory = angleDirectory(root.resolve("angle").toFile(), MachOFixtures.CPU_TYPE_X86_64)
        val angle = checkNotNull(
            DesktopNativeBootstrap.resolveAngle(
                explicitDirectory = directory,
                packagedDirectory = null,
                workingDirectory = null,
                osArch = "aarch64",
            ),
        )

        // x86_64 dylibs on an aarch64 host: present, unloadable, and reported
        // as such rather than left for dlopen to call missing.
        val described = DesktopNativeBootstrap.describe(angle, osArch = "aarch64")

        assertTrue(described.startsWith(directory.canonicalFile.toString()))
        assertTrue(described.contains(DesktopNativeBootstrap.EGL_DYLIB))
        assertTrue(described.contains(DesktopNativeBootstrap.GLES_DYLIB))
        assertTrue(described.contains("x86_64"))
        assertTrue(described.contains("built for another architecture"))
    }

    @Test
    fun `the reported macOS floor is the newer of the two dylibs`() {
        val root = Files.createTempDirectory("bangnidraw-angle")
        val directory = root.resolve("angle").toFile().apply { toPath().createDirectories() }
        MachOFixtures.write(
            MachOFixtures.thinBytes(MachOFixtures.CPU_TYPE_ARM64, MachOFixtures.version(10, 13, 0)),
            directory,
            DesktopNativeBootstrap.EGL_DYLIB,
        )
        MachOFixtures.write(
            MachOFixtures.thinBytes(MachOFixtures.CPU_TYPE_ARM64, MachOFixtures.version(12, 0, 0)),
            directory,
            DesktopNativeBootstrap.GLES_DYLIB,
        )
        val angle = checkNotNull(
            DesktopNativeBootstrap.resolveAngle(directory, null, null, osArch = "aarch64"),
        )

        // dyld refuses on the higher floor, and 12.0 outranks 10.13 by number,
        // not by text.
        assertTrue(DesktopNativeBootstrap.describe(angle, "aarch64").contains("macOS 12.0+"))
    }

    @Test
    fun `a loadable ANGLE wins over a wrong-architecture one that comes first`() {
        val root = Files.createTempDirectory("bangnidraw-angle")
        val intel = angleDirectory(root.resolve("stale-override").toFile(), MachOFixtures.CPU_TYPE_X86_64)
        val packaged = angleDirectory(root.resolve("packaged").toFile(), MachOFixtures.CPU_TYPE_ARM64)

        val found = DesktopNativeBootstrap.resolveAngle(
            explicitDirectory = intel,
            packagedDirectory = packaged,
            workingDirectory = null,
            osArch = "aarch64",
        )

        // A stale -Dbangnidraw.angle.dir must not shadow the bundle: its dylibs
        // are present and unloadable, which dlopen reports as simply missing.
        assertEquals(packaged.canonicalFile, found?.directory)
    }

    @Test
    fun `a wrong-architecture candidate is still used when nothing else exists`() {
        val root = Files.createTempDirectory("bangnidraw-angle")
        val intel = angleDirectory(root.resolve("only").toFile(), MachOFixtures.CPU_TYPE_X86_64)

        val found = DesktopNativeBootstrap.resolveAngle(
            explicitDirectory = intel,
            packagedDirectory = null,
            workingDirectory = null,
            osArch = "aarch64",
        )

        // Reporting a dylib that cannot load beats reporting nothing at all.
        assertEquals(intel.canonicalFile, found?.directory)
    }

    @Test
    fun `EGL is tried first and macOS never falls back to GLFW`() {
        val startup = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopGlStartup.kt")
        val direct = startup.indexOf("EglEsContext.create(")
        val fallback = startup.indexOf("GlfwEsContext.create(")
        val macOsGuard = startup.indexOf("environment.backend == DesktopGlBackend.AngleMetal")

        assertTrue(direct >= 0, "the direct EGL host must be attempted")
        assertTrue(direct < fallback, "EGL must be attempted before the GLFW fallback")
        // GLFW on macOS means a leaf-name dlopen for a bundled ANGLE, which is
        // the failure this host order replaced.
        assertTrue(macOsGuard in (direct + 1) until fallback, "macOS must stop before the fallback")
    }

    @Test
    fun `a padded GL host property still names a host`() {
        val startup = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopGlStartup.kt")

        // "-Dbangnidraw.gl.host=egl " is the same request as "egl"; untrimmed it
        // reads as unknown, which on Linux behaves like no request at all.
        assertTrue(startup.contains(".orEmpty().trim().lowercase()"))
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
            // paths replaced it. Pinned on the lookup rather than the word, so
            // a comment explaining why it is gone does not fail the build.
            assertFalse(text.contains("\"chdir\""), "$name must not move the process directory")
            assertFalse(text.contains("DynamicLinkLoader"), "$name must not resolve libc by hand")
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

    private fun angleDirectory(directory: File, cpuType: Int? = null): File {
        val path = directory.toPath().createDirectories()
        listOf(DesktopNativeBootstrap.EGL_DYLIB, DesktopNativeBootstrap.GLES_DYLIB).forEach { name ->
            val file = path.resolve(name).createFile().toFile()
            if (cpuType != null) {
                file.writeBytes(MachOFixtures.thinBytes(cpuType))
            }
        }

        return directory
    }
}
