package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import ch.lkmc.bangnidraw.engine.gl.platform.GlLog
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengles.GLES

/** A hidden GLFW/EGL host for the engine's offscreen GLES context. */
internal class GlfwEsContext private constructor(
    private val window: Long,
    private val creationThread: Thread,
) {
    private var active = false

    /** Attaches the context and LWJGL capabilities to the render thread. */
    fun activate() {
        check(!active) { "GL context is already active" }

        GLFW.glfwMakeContextCurrent(window)
        try {
            GLES.createCapabilities()
            GLFW.glfwSwapInterval(0)
            active = true

            val version = GLES30.glGetString(GLES30.GL_VERSION)
            val renderer = GLES30.glGetString(GLES30.GL_RENDERER)
            val vendor = GLES30.glGetString(GLES30.GL_VENDOR)
            GlLog.i(TAG, "GL context: $version / $renderer / $vendor")
        } catch (failure: Throwable) {
            GLES.setCapabilities(null)
            GLFW.glfwMakeContextCurrent(0)
            throw failure
        }
    }

    /** Detaches thread-local GLES state before the main thread destroys GLFW. */
    fun deactivate() {
        if (!active) return

        GLES.setCapabilities(null)
        GLFW.glfwMakeContextCurrent(0)
        active = false
    }

    /** GLFW window destruction is restricted to the thread that created it. */
    fun destroy() {
        check(Thread.currentThread() === creationThread) {
            "GLFW context must be destroyed on its creation thread"
        }
        check(!active) { "GL context is still active on the render thread" }

        GLFW.glfwDestroyWindow(window)
        GLFW.glfwTerminate()
        restoreErrorCallback()
    }

    companion object {
        private const val TAG = "GlfwEsContext"

        private var errorCallback: GLFWErrorCallback? = null
        private var previousErrorCallback: GLFWErrorCallback? = null

        /** Creates the hidden window on the process main thread. */
        fun create(width: Int, height: Int, backend: DesktopGlBackend): GlfwEsContext? {
            installErrorCallback()

            // Compose owns macOS menu and Dock integration; GLFW only owns GL.
            GLFW.glfwInitHint(GLFW.GLFW_COCOA_MENUBAR, GLFW.GLFW_FALSE)
            if (backend == DesktopGlBackend.AngleMetal) {
                GLFW.glfwInitHint(GLFW.GLFW_ANGLE_PLATFORM_TYPE, GLFW.GLFW_ANGLE_PLATFORM_TYPE_METAL)
            }
            if (!GLFW.glfwInit()) {
                GlLog.e(TAG, "glfwInit failed — no window/context backend available", null)
                restoreErrorCallback()
                return null
            }

            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_ES_API)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, GLES_MAJOR_VERSION)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, GLES_MINOR_VERSION)

            val window = GLFW.glfwCreateWindow(width, height, "${DesktopBrand.displayName} GL", 0L, 0L)
            if (window == 0L) {
                GlLog.e(TAG, "could not create a GLES 3.0 EGL context", null)
                GLFW.glfwTerminate()
                restoreErrorCallback()
                return null
            }

            return GlfwEsContext(window, Thread.currentThread())
        }

        private fun installErrorCallback() {
            val callback = GLFWErrorCallback.create { error, description ->
                val text = GLFWErrorCallback.getDescription(description)
                GlLog.w(TAG, "GLFW error $error: $text")
            }

            errorCallback?.let {
                GLFW.glfwSetErrorCallback(previousErrorCallback)
                it.free()
            }
            previousErrorCallback = callback.set()
            errorCallback = callback
        }

        private fun restoreErrorCallback() {
            GLFW.glfwSetErrorCallback(previousErrorCallback)
            previousErrorCallback = null
            errorCallback?.free()
            errorCallback = null
        }

        private const val GLES_MAJOR_VERSION = 3
        private const val GLES_MINOR_VERSION = 0
    }
}
