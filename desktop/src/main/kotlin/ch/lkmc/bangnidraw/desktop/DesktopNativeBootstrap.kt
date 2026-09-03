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
        report.note("$ANGLE_DIRECTORY_PROPERTY: ${explicit?.path ?: "(not set)"}")
        val angle = resolveAngle(explicit, packaged, working, report = report)
        if (angle == null) {
            report.fail("ANGLE", "$EGL_DYLIB and $GLES_DYLIB were not found")
            report.note("set -D$ANGLE_DIRECTORY_PROPERTY=/path/to/angle to point at them")
            return DesktopNativeEnvironment(backend)
        }

        // Absolute paths: LWJGL loads these itself, so no dyld search order
        // decides whether the bundled ANGLE is found.
        Configuration.EGL_LIBRARY_NAME.set(angle.egl.absolutePath)
        Configuration.OPENGLES_LIBRARY_NAME.set(angle.gles.absolutePath)
        // Still configured when no candidate can load: the attempt produces
        // dlopen's own error, which beats a guess. But say so up front rather
        // than leaving it to a note the reader has to compare architectures in.
        val osArch = System.getProperty("os.arch", "")
        if (runsOn(angle, osArch)) {
            report.note("ANGLE: ${describe(angle, osArch)}")
        } else {
            report.fail("ANGLE", "no candidate loads on $osArch; trying ${describe(angle, osArch)}")
        }

        return DesktopNativeEnvironment(backend, angle)
    }

    /**
     * Names one candidate's libraries, plus the two facts that make a
     * present-but-unloadable library look identical to a missing one: the
     * architecture it was built for, and the oldest macOS it loads on.
     */
    fun describe(
        angle: AngleLibraries,
        osArch: String = System.getProperty("os.arch", ""),
    ): String {
        val egl = MachOLibrary.describe(angle.egl)
        val gles = MachOLibrary.describe(angle.gles)
        // Either dylib can carry the newer floor, and dyld refuses on the
        // higher of the two.
        val minimum = listOfNotNull(
            MachOLibrary.minimumMacOs(angle.egl, osArch),
            MachOLibrary.minimumMacOs(angle.gles, osArch),
        ).maxWithOrNull(::compareVersions)

        return buildString {
            append("${angle.directory} ($EGL_DYLIB $egl, $GLES_DYLIB $gles")
            if (minimum != null) append(", macOS $minimum+")
            append(")")
            if (!runsOn(angle, osArch)) {
                append(" — built for another architecture, this JVM is $osArch")
            }
        }
    }

    /**
     * The first candidate this JVM can actually open, falling back to the first
     * one that merely exists.
     *
     * First-match-wins would be a trap: a stale `-D$ANGLE_DIRECTORY_PROPERTY`
     * (README hands it to `JAVA_TOOL_OPTIONS`, which every JVM in that shell
     * inherits) shadows a correct packaged ANGLE, and a thin dylib for the
     * other architecture is *present* and unloadable — indistinguishable from a
     * missing one once dlopen refuses it. An unreadable header never disquali-
     * fies a candidate, though: only a header that positively says another
     * architecture does.
     */
    fun resolveAngle(
        explicitDirectory: File?,
        packagedDirectory: File?,
        workingDirectory: File?,
        osArch: String = System.getProperty("os.arch", ""),
        report: DesktopGlReport? = null,
    ): AngleLibraries? {
        val candidates = listOfNotNull(explicitDirectory, packagedDirectory, workingDirectory)
        var unloadable: AngleLibraries? = null
        for (candidate in candidates) {
            val directory = candidate.canonicalFile
            val egl = File(directory, EGL_DYLIB)
            val gles = File(directory, GLES_DYLIB)
            if (!egl.isFile || !gles.isFile) {
                report?.note("ANGLE candidate: $directory (no $EGL_DYLIB/$GLES_DYLIB)")
                continue
            }

            val libraries = AngleLibraries(directory, egl, gles)
            report?.note("ANGLE candidate: ${describe(libraries, osArch)}")
            if (runsOn(libraries, osArch)) return libraries
            if (unloadable == null) unloadable = libraries
        }

        return unloadable
    }

    /** Orders "12.0" above "9.4" — the components are numbers, not text. */
    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val order = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (order != 0) return order
        }

        return 0
    }

    private fun runsOn(angle: AngleLibraries, osArch: String): Boolean =
        MachOLibrary.runsOn(angle.egl, osArch) != false &&
            MachOLibrary.runsOn(angle.gles, osArch) != false

    fun backendFor(osName: String): DesktopGlBackend =
        if (osName.startsWith(MAC_OS_PREFIX)) DesktopGlBackend.AngleMetal else DesktopGlBackend.SystemEgl

    const val EGL_DYLIB = "libEGL.dylib"
    const val GLES_DYLIB = "libGLESv2.dylib"

    private const val MAC_OS_PREFIX = "Mac"
    private const val GLFW_ASYNC_LIBRARY = "glfw_async"
    private const val ANGLE_DIRECTORY_PROPERTY = "bangnidraw.angle.dir"
    private const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"
}
