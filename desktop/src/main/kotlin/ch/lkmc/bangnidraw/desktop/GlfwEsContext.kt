package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import ch.lkmc.bangnidraw.engine.gl.platform.GlLog
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback

/**
 * A GLES 3.0 context on a hidden GLFW window (DESKTOP.md "Rendering").
 *
 * The visible window is Compose's; this exists purely as the context the
 * engine renders its offscreen FBOs under. GLFW with
 * `GLFW_OPENGL_ES_API` + `GLFW_EGL_CONTEXT_API` picks the native EGL on
 * Linux (Mesa/NVIDIA ship GLES natively) and ANGLE on macOS, where the
 * `GLFW_ANGLE_PLATFORM_TYPE` **init** hint must name Metal before
 * `glfwInit` — the same plumbing libGDX ships in production.
 *
 * Single-threaded by design: created, current, used, and destroyed on the
 * one GL thread [DesktopEngine] owns. GLFW init stays for the process
 * lifetime (`glfwTerminate` would destroy the window with it).
 */
class GlfwEsContext private constructor(
    val width: Int,
    val height: Int,
    private val window: Long,
) {

    init {
        GLFW.glfwMakeContextCurrent(window)
        // Plain swapchain posture (DESKTOP.md "Latency"): no front-buffer
        // path exists on desktop; nothing is presented from here, so the
        // interval only matters if a future change starts swapping.
        GLFW.glfwSwapInterval(0)

        val version = GLES30.glGetString(GLES30.GL_VERSION)
        val renderer = GLES30.glGetString(GLES30.GL_RENDERER)
        val vendor = GLES30.glGetString(GLES30.GL_VENDOR)
        GlLog.i(TAG, "GL context: $version / $renderer / $vendor")
    }

    fun makeCurrent() {
        GLFW.glfwMakeContextCurrent(window)
    }

    fun destroy() {
        GLFW.glfwMakeContextCurrent(0)
        GLFW.glfwDestroyWindow(window)
    }

    companion object {
        private const val TAG = "GlfwEsContext"

        /**
         * Creates the context, or returns null with the reason logged — a
         * missing ES 3.0 context must be a clear message, not a crash
         * (M4's failure contract).
         */
        fun create(width: Int, height: Int): GlfwEsContext? {
            GLFWErrorCallback.create { error, description ->
                GlLog.w(TAG, "GLFW error $error: ${GLFWErrorCallback.getDescription(description)}")
            }.set()

            // The ANGLE backend choice is an init hint: it must land before
            // glfwInit, and only macOS reads it (harmless elsewhere).
            GLFW.glfwInitHint(GLFW.GLFW_ANGLE_PLATFORM_TYPE, GLFW.GLFW_ANGLE_PLATFORM_TYPE_METAL)
            if (!GLFW.glfwInit()) {
                GlLog.e(TAG, "glfwInit failed — no window/context backend available", null)
                return null
            }

            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_ES_API)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 0)

            val window = GLFW.glfwCreateWindow(width, height, "BangniDraw GL", 0L, 0L)
            if (window == 0L) {
                GlLog.e(
                    TAG,
                    "could not create a GLES 3.0 context (EGL). On Linux this needs " +
                        "libEGL/libGLESv2 (Mesa or the vendor driver); on macOS it needs " +
                        "ANGLE's dylibs reachable (see the README's desktop section).",
                    null,
                )
                return null
            }
            return GlfwEsContext(width, height, window)
        }
    }
}
