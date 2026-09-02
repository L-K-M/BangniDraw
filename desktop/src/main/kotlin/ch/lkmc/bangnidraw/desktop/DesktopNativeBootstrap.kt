package ch.lkmc.bangnidraw.desktop

import java.awt.Toolkit
import java.io.File
import org.lwjgl.system.Configuration
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.macosx.DynamicLinkLoader

internal enum class DesktopGlBackend {
    SystemEgl,
    AngleMetal,
}

internal class DesktopNativeEnvironment(
    val backend: DesktopGlBackend,
    private val cleanup: AutoCloseable? = null,
) : AutoCloseable {
    override fun close() {
        cleanup?.close()
    }
}


/** Prepares AWT and native libraries before any GLFW class is loaded. */
internal object DesktopNativeBootstrap {
    data class AngleLibraries(
        val directory: File,
        val egl: File,
        val gles: File,
    )

    fun prepare(): DesktopNativeEnvironment {
        // AWT must own the macOS application lifecycle before async GLFW.
        Toolkit.getDefaultToolkit()
        val backend = backendFor(System.getProperty("os.name", ""))
        if (backend == DesktopGlBackend.SystemEgl) return DesktopNativeEnvironment(backend)

        Configuration.GLFW_LIBRARY_NAME.set(GLFW_ASYNC_LIBRARY)

        val angle = resolveAngle(
            explicitDirectory = System.getProperty(ANGLE_DIRECTORY_PROPERTY)?.let(::File),
            packagedDirectory = System.getProperty(COMPOSE_RESOURCES_PROPERTY)?.let(::File),
            workingDirectory = File(System.getProperty("user.dir")),
        ) ?: error(
            "ANGLE libraries were not found; set -D$ANGLE_DIRECTORY_PROPERTY=/path/to/angle",
        )

        Configuration.EGL_LIBRARY_NAME.set(angle.egl.absolutePath)
        Configuration.OPENGLES_LIBRARY_NAME.set(angle.gles.absolutePath)

        // Keep the returned environment open until the first GLFW window: GLFW dlopens ANGLE lazily.
        // Exposure temporarily changes the process CWD so those fixed names resolve here.
        val exposure = exposeAngleToGlfw(angle)

        return DesktopNativeEnvironment(backend, exposure)
    }

    fun exposeAngleToGlfw(
        angle: AngleLibraries,
        originalDirectory: File = File(".").canonicalFile,
        changeDirectory: (File) -> Unit = ProcessWorkingDirectory::changeTo,
    ): AutoCloseable {
        val directory = angle.directory.canonicalFile
        val original = originalDirectory.canonicalFile
        if (directory == original) return AutoCloseable {}

        changeDirectory(directory)
        return AutoCloseable { changeDirectory(original) }
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

private object ProcessWorkingDirectory {
    fun changeTo(directory: File) {
        MemoryStack.stackPush().use { stack ->
            val path = stack.UTF8(directory.absolutePath)
            val result = JNI.invokePI(MemoryUtil.memAddress(path), changeDirectoryAddress)
            check(result == 0) { "could not enter ANGLE directory: $directory" }
        }
    }

    private val changeDirectoryAddress by lazy {
        val address = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "chdir")
        check(address != 0L) { "macOS chdir symbol was not found" }
        address
    }
}
