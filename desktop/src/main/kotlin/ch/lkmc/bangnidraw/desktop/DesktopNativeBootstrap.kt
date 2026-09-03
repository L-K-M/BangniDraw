package ch.lkmc.bangnidraw.desktop

import java.awt.Toolkit
import java.io.File
import org.lwjgl.system.Configuration

internal enum class DesktopGlBackend {
    SystemEgl,
    AngleMetal,
}

internal class DesktopNativeEnvironment(
    val backend: DesktopGlBackend,
    val angle: DesktopNativeBootstrap.AngleLibraries? = null,
)


/** Prepares AWT and native libraries before any GLFW class is loaded. */
internal object DesktopNativeBootstrap {
    data class AngleLibraries(
        val directory: File,
        val egl: File,
        val gles: File,
    )

    fun prepare(report: DesktopGlReport): DesktopNativeEnvironment {
        // AWT must own the macOS application lifecycle before async GLFW.
        Toolkit.getDefaultToolkit()
        val backend = backendFor(System.getProperty("os.name", ""))
        if (backend == DesktopGlBackend.SystemEgl) return DesktopNativeEnvironment(backend)

        Configuration.GLFW_LIBRARY_NAME.set(GLFW_ASYNC_LIBRARY)

        val explicit = System.getProperty(ANGLE_DIRECTORY_PROPERTY)?.let(::File)
        val packaged = System.getProperty(COMPOSE_RESOURCES_PROPERTY)?.let(::File)
        val working = File(System.getProperty("user.dir"))
        val angle = resolveAngle(explicit, packaged, working)
        if (angle == null) {
            report.fail("ANGLE", "$EGL_DYLIB and $GLES_DYLIB were not found")
            report.note(
                "searched: " + listOfNotNull(explicit, packaged, working).joinToString(", "),
            )
            report.note("set -D$ANGLE_DIRECTORY_PROPERTY=/path/to/angle to point at them")
            return DesktopNativeEnvironment(backend)
        }

        // Absolute paths: LWJGL loads these itself, so no dyld search order
        // decides whether the bundled ANGLE is found.
        Configuration.EGL_LIBRARY_NAME.set(angle.egl.absolutePath)
        Configuration.OPENGLES_LIBRARY_NAME.set(angle.gles.absolutePath)
        report.note(describe(angle))

        return DesktopNativeEnvironment(backend, angle)
    }

    /**
     * Names the ANGLE that will be loaded, plus the two facts that make a
     * present-but-unloadable library look identical to a missing one: the
     * architecture it was built for, and the oldest macOS it loads on.
     */
    fun describe(
        angle: AngleLibraries,
        osArch: String = System.getProperty("os.arch", ""),
    ): String {
        val egl = MachOLibrary.describe(angle.egl)
        val gles = MachOLibrary.describe(angle.gles)
        val mismatch = MachOLibrary.runsOn(angle.egl, osArch) == false ||
            MachOLibrary.runsOn(angle.gles, osArch) == false
        val minimum = MachOLibrary.minimumMacOs(angle.egl)

        return buildString {
            append("ANGLE: ${angle.directory} ($EGL_DYLIB $egl, $GLES_DYLIB $gles")
            if (minimum != null) append(", macOS $minimum+")
            append(")")
            if (mismatch) append(" — built for another architecture, this JVM is $osArch")
        }
    }

    fun resolveAngle(
        explicitDirectory: File?,
        packagedDirectory: File?,
        workingDirectory: File?,
    ): AngleLibraries? {
        val candidates = listOfNotNull(explicitDirectory, packagedDirectory, workingDirectory)
        for (candidate in candidates) {
            val directory = candidate.canonicalFile
            val egl = File(directory, EGL_DYLIB)
            val gles = File(directory, GLES_DYLIB)
            if (!egl.isFile || !gles.isFile) continue

            return AngleLibraries(directory, egl, gles)
        }

        return null
    }

    fun backendFor(osName: String): DesktopGlBackend =
        if (osName.startsWith(MAC_OS_PREFIX)) DesktopGlBackend.AngleMetal else DesktopGlBackend.SystemEgl

    const val EGL_DYLIB = "libEGL.dylib"
    const val GLES_DYLIB = "libGLESv2.dylib"

    private const val MAC_OS_PREFIX = "Mac"
    private const val GLFW_ASYNC_LIBRARY = "glfw_async"
    private const val ANGLE_DIRECTORY_PROPERTY = "bangnidraw.angle.dir"
    private const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"
}
