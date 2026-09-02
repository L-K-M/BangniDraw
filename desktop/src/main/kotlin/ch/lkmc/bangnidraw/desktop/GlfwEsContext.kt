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
    @Volatile
    private var active = false
    @Volatile
    private var activationThread: Thread? = null
    @Volatile
    private var abandonedAfterOwnerTimeout = false

    /** Attaches the context and LWJGL capabilities to the render thread. */
    fun activate() {
        check(!active) { "GL context is already active" }

        GLFW.glfwMakeContextCurrent(window)
        try {
            GLES.createCapabilities()
            GLFW.glfwSwapInterval(0)

            val version = GLES30.glGetString(GLES30.GL_VERSION)
            val renderer = GLES30.glGetString(GLES30.GL_RENDERER)
            val vendor = GLES30.glGetString(GLES30.GL_VENDOR)
            GlLog.i(TAG, "GL context: $version / $renderer / $vendor")
            activationThread = Thread.currentThread()
            active = true
        } catch (failure: Throwable) {
            GLES.setCapabilities(null)
            GLFW.glfwMakeContextCurrent(0)
            throw failure
        }
    }

    /** Detaches thread-local GLES state before the main thread destroys GLFW. */
    fun deactivate() {
        // Activation can fail before installing capabilities; cleanup stays idempotent.
        if (!active) return
        check(Thread.currentThread() === activationThread) {
            "GL context must be deactivated on its activation thread"
        }

        GLES.setCapabilities(null)
        GLFW.glfwMakeContextCurrent(0)
        activationThread = null
        active = false
    }

    /** Prevents unsafe native teardown when the GL owner did not stop. */
    fun abandonAfterOwnerTimeout() {
        abandonedAfterOwnerTimeout = true
    }

    /** GLFW window destruction is restricted to the thread that created it. */
    fun destroy() {
        check(ownsGlfw) { GLFW_OWNERSHIP_MESSAGE }
        check(Thread.currentThread() === creationThread) {
            "GLFW context must be destroyed on its creation thread"
        }
        if (abandonedAfterOwnerTimeout) {
            GlLog.e(TAG, ABANDONED_CONTEXT_MESSAGE, null)
            return
        }
        check(!active) { "GL context is still active on the render thread" }

        GLFW.glfwDestroyWindow(window)
        terminateOwnedGlfw()
        restoreErrorCallback()
    }

    companion object {
        private const val TAG = "GlfwEsContext"

        private var errorCallback: GLFWErrorCallback? = null
        private var previousErrorCallback: GLFWErrorCallback? = null
        private var ownsGlfw = false

        /** Creates the hidden window on the process main thread. */
        @Synchronized
        fun create(width: Int, height: Int, backend: DesktopGlBackend): GlfwEsContext? {
            check(!ownsGlfw) { "only one GLFW context may exist in this process" }
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
            ownsGlfw = true

            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_ES_API)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, GLES_MAJOR_VERSION)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, GLES_MINOR_VERSION)

            val window = GLFW.glfwCreateWindow(width, height, "${DesktopBrand.displayName} GL", 0L, 0L)
            if (window == 0L) {
                GlLog.e(TAG, DesktopGlDiagnostics.contextFailure, null)
                terminateOwnedGlfw()
                restoreErrorCallback()
                return null
            }

            return GlfwEsContext(window, Thread.currentThread())
        }

        @Synchronized
        private fun terminateOwnedGlfw() {
            check(ownsGlfw) { GLFW_OWNERSHIP_MESSAGE }
            GLFW.glfwTerminate()
            ownsGlfw = false
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
        private const val GLFW_OWNERSHIP_MESSAGE =
            "GLFW initialization is not owned by this context"
        private const val ABANDONED_CONTEXT_MESSAGE =
            "GL owner did not stop; leaving GLFW state for process teardown"
    }
}
