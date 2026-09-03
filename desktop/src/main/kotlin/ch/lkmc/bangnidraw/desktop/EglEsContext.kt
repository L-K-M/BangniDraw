package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import ch.lkmc.bangnidraw.engine.gl.platform.GlLog
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10
import org.lwjgl.egl.EGL11
import org.lwjgl.egl.EGL12
import org.lwjgl.egl.EGL14
import org.lwjgl.egl.EGL15
import org.lwjgl.opengles.GLES
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

/**
 * The engine's offscreen ES 3.0 context, created straight from EGL.
 *
 * This is the primary context host on every desktop platform, and on macOS it
 * is the one that can find a bundled ANGLE at all: GLFW opens EGL with a bare
 * `dlopen("libEGL.dylib")`, and dyld resolves a leaf name from the process
 * working directory only for *unrestricted* processes — so the same bundled
 * libraries a jpackage launcher finds are invisible to a hardened JVM (a
 * developer's `gradlew run`, for one). Here EGL is loaded by absolute path
 * through `Configuration.EGL_LIBRARY_NAME`, which no dyld search order can
 * defeat, and ANGLE's own libEGL reaches libGLESv2 through `@loader_path`.
 *
 * Nothing about this context needs a window, the process main thread or an
 * AppKit run loop: rendering goes to an offscreen FBO and reaches Compose as
 * pixels (DESKTOP.md architecture 1). The 1x1 pbuffer exists only because a
 * config must be complete; it is never drawn to.
 */
internal class EglEsContext private constructor(
    private val display: Long,
    private val surface: Long,
    private val context: Long,
    private val releasesThread: Boolean,
    private val creationThread: Thread,
) : DesktopEsContext {
    @Volatile
    private var active = false
    @Volatile
    private var activationThread: Thread? = null
    @Volatile
    private var abandonedAfterOwnerTimeout = false

    override fun activate() {
        check(!active) { "GL context is already active" }

        if (!EGL10.eglMakeCurrent(display, surface, surface, context)) {
            error("eglMakeCurrent failed: ${errorName(EGL10.eglGetError())}")
        }
        try {
            GLES.createCapabilities()

            val version = GLES30.glGetString(GLES30.GL_VERSION)
            val renderer = GLES30.glGetString(GLES30.GL_RENDERER)
            val vendor = GLES30.glGetString(GLES30.GL_VENDOR)
            GlLog.i(TAG, "GL context: $version / $renderer / $vendor")
            activationThread = Thread.currentThread()
            active = true
        } catch (failure: Throwable) {
            GLES.setCapabilities(null)
            EGL10.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            throw failure
        }
    }

    override fun deactivate() {
        // Activation can fail before installing capabilities; cleanup stays idempotent.
        if (!active) return
        check(Thread.currentThread() === activationThread) {
            "GL context must be deactivated on its activation thread"
        }

        GLES.setCapabilities(null)
        EGL10.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        if (releasesThread) EGL12.eglReleaseThread()
        activationThread = null
        active = false
    }

    override fun abandonAfterOwnerTimeout() {
        abandonedAfterOwnerTimeout = true
    }

    override fun destroy() {
        check(Thread.currentThread() === creationThread) {
            "EGL context must be destroyed on its creation thread"
        }
        if (abandonedAfterOwnerTimeout) {
            GlLog.e(TAG, ABANDONED_CONTEXT_MESSAGE, null)
            return
        }
        check(!active) { "GL context is still active on the render thread" }

        EGL10.eglDestroySurface(display, surface)
        EGL10.eglDestroyContext(display, context)
        EGL10.eglTerminate(display)
    }

    companion object {
        private const val TAG = "EglEsContext"

        /**
         * Creates the context, recording every step in [report]. Returns null
         * rather than throwing, so startup can try another host (Linux) or
         * show the user every stage that refused (macOS).
         */
        fun create(
            width: Int,
            height: Int,
            backend: DesktopGlBackend,
            report: DesktopGlReport,
        ): EglEsContext? = try {
            createOrNull(width, height, backend, report)
        } catch (failure: Throwable) {
            // A missing or unloadable EGL library surfaces as a linkage error
            // from LWJGL's class initializer, not as an EGL error code.
            report.fail("EGL", describe(failure))
            null
        }

        private fun createOrNull(
            width: Int,
            height: Int,
            backend: DesktopGlBackend,
            report: DesktopGlReport,
        ): EglEsContext? {
            val clientExtensions =
                EGL10.eglQueryString(EGL10.EGL_NO_DISPLAY, EGL10.EGL_EXTENSIONS).orEmpty()
            // Without EGL_EXT_client_extensions that probe latches
            // EGL_BAD_DISPLAY, which a later failure would then misreport.
            EGL10.eglGetError()
            report.note("EGL client extensions: ${clientExtensions.ifEmpty { "(none)" }}")

            val metal = backend == DesktopGlBackend.AngleMetal &&
                clientExtensions.contains(ANGLE_EXTENSION) &&
                clientExtensions.contains(ANGLE_METAL_EXTENSION)
            if (metal) {
                val display = angleMetalDisplay(report)
                if (display != null) {
                    createOn(display, width, height, report)?.let { return it }
                    // ANGLE has other backends. A Mac whose Metal display
                    // refuses should still start on whatever ANGLE picks.
                    report.note("EGL display: retrying ANGLE's default backend")
                }
            }

            val display = EGL10.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            report.note("EGL display: default")
            if (display == EGL10.EGL_NO_DISPLAY) {
                report.fail("EGL", "no display: ${errorName(EGL10.eglGetError())}")
                return null
            }

            return createOn(display, width, height, report)
        }

        /** Brings up one display: initialize, choose a config, context, surface. */
        private fun createOn(
            display: Long,
            width: Int,
            height: Int,
            report: DesktopGlReport,
        ): EglEsContext? {
            MemoryStack.stackPush().use { stack ->
                val major = stack.mallocInt(1)
                val minor = stack.mallocInt(1)
                if (!EGL10.eglInitialize(display, major, minor)) {
                    report.fail("EGL", "eglInitialize failed: ${errorName(EGL10.eglGetError())}")
                    return null
                }
                report.note(
                    "EGL ${major.get(0)}.${minor.get(0)} " +
                        "${EGL10.eglQueryString(display, EGL10.EGL_VENDOR)} " +
                        "(${EGL10.eglQueryString(display, EGL12.EGL_CLIENT_APIS)})",
                )

                // OpenGL ES is EGL's default bound API, so a display without
                // EGL 1.2 needs no binding call — and asking LWJGL for one it
                // never resolved would throw rather than return false.
                val releasesThread = EGL.getCapabilities().EGL12
                if (releasesThread && !EGL12.eglBindAPI(EGL12.EGL_OPENGL_ES_API)) {
                    return failed(display, report, "eglBindAPI failed")
                }

                val configs = stack.mallocPointer(1)
                val configCount = IntArray(1)
                val chosen = EGL10.eglChooseConfig(display, CONFIG_ATTRIBUTES, configs, configCount)
                if (!chosen || configCount[0] == 0) {
                    return failed(display, report, "no ES 3.0 pbuffer config")
                }
                val config = configs.get(0)

                val context = EGL10.eglCreateContext(
                    display,
                    config,
                    EGL10.EGL_NO_CONTEXT,
                    intArrayOf(EGL15.EGL_CONTEXT_MAJOR_VERSION, GLES_MAJOR_VERSION, EGL10.EGL_NONE),
                )
                if (context == EGL10.EGL_NO_CONTEXT) {
                    return failed(display, report, "eglCreateContext failed")
                }

                val surface = EGL10.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(EGL10.EGL_WIDTH, width, EGL10.EGL_HEIGHT, height, EGL10.EGL_NONE),
                )
                if (surface == EGL10.EGL_NO_SURFACE) {
                    EGL10.eglDestroyContext(display, context)
                    return failed(display, report, "eglCreatePbufferSurface failed")
                }

                report.succeed("EGL")
                return EglEsContext(display, surface, context, releasesThread, Thread.currentThread())
            }
        }

        /** Reports [reason] with EGL's own error, releases the display, gives up on it. */
        private fun failed(display: Long, report: DesktopGlReport, reason: String): EglEsContext? {
            report.fail("EGL", "$reason: ${errorName(EGL10.eglGetError())}")
            EGL10.eglTerminate(display)

            return null
        }

        /**
         * ANGLE's Metal display, or null when ANGLE cannot provide one.
         *
         * The entry point is resolved by name rather than through
         * `EXTPlatformBase`, because LWJGL 3.4.3's `EGL.createClientCapabilities`
         * reads the client extension string only to null-check it: it registers
         * the core versions and no client extension, so every
         * `EGL_EXT_platform_base` pointer in `EGLCapabilities` is NULL and
         * calling one throws NullPointerException — which is exactly how this
         * path first failed on macOS CI.
         */
        private fun angleMetalDisplay(report: DesktopGlReport): Long? {
            val entryPoint = EGL.getFunctionProvider().getFunctionAddress(ANGLE_DISPLAY_FUNCTION)
            if (entryPoint == 0L) {
                report.note("EGL display: ANGLE/Metal unavailable ($ANGLE_DISPLAY_FUNCTION)")
                return null
            }

            val display = MemoryStack.stackPush().use { stack ->
                val attributes = stack.ints(
                    EGL_PLATFORM_ANGLE_TYPE_ANGLE,
                    EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE,
                    EGL10.EGL_NONE,
                )
                JNI.invokePPP(
                    EGL_PLATFORM_ANGLE_ANGLE,
                    EGL14.EGL_DEFAULT_DISPLAY,
                    MemoryUtil.memAddress(attributes),
                    entryPoint,
                )
            }
            if (display == EGL10.EGL_NO_DISPLAY) {
                report.note("EGL display: ANGLE/Metal refused (${errorName(EGL10.eglGetError())})")
                return null
            }
            report.note("EGL display: ANGLE/Metal")

            return display
        }

        private fun describe(failure: Throwable): String =
            failure.message ?: failure::class.qualifiedName ?: "unknown failure"

        private fun errorName(error: Int): String = when (error) {
            EGL10.EGL_SUCCESS -> "EGL_SUCCESS"
            EGL10.EGL_NOT_INITIALIZED -> "EGL_NOT_INITIALIZED"
            EGL10.EGL_BAD_ACCESS -> "EGL_BAD_ACCESS"
            EGL10.EGL_BAD_ALLOC -> "EGL_BAD_ALLOC"
            EGL10.EGL_BAD_ATTRIBUTE -> "EGL_BAD_ATTRIBUTE"
            EGL10.EGL_BAD_CONFIG -> "EGL_BAD_CONFIG"
            EGL10.EGL_BAD_CONTEXT -> "EGL_BAD_CONTEXT"
            EGL10.EGL_BAD_CURRENT_SURFACE -> "EGL_BAD_CURRENT_SURFACE"
            EGL10.EGL_BAD_DISPLAY -> "EGL_BAD_DISPLAY"
            EGL10.EGL_BAD_MATCH -> "EGL_BAD_MATCH"
            EGL10.EGL_BAD_NATIVE_PIXMAP -> "EGL_BAD_NATIVE_PIXMAP"
            EGL10.EGL_BAD_NATIVE_WINDOW -> "EGL_BAD_NATIVE_WINDOW"
            EGL10.EGL_BAD_PARAMETER -> "EGL_BAD_PARAMETER"
            EGL10.EGL_BAD_SURFACE -> "EGL_BAD_SURFACE"
            EGL11.EGL_CONTEXT_LOST -> "EGL_CONTEXT_LOST"
            else -> "EGL error"
        } + " (0x${error.toString(16)})"

        private val CONFIG_ATTRIBUTES = intArrayOf(
            EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
            EGL12.EGL_RENDERABLE_TYPE, EGL15.EGL_OPENGL_ES3_BIT,
            EGL10.EGL_RED_SIZE, CHANNEL_BITS,
            EGL10.EGL_GREEN_SIZE, CHANNEL_BITS,
            EGL10.EGL_BLUE_SIZE, CHANNEL_BITS,
            EGL10.EGL_ALPHA_SIZE, CHANNEL_BITS,
            EGL10.EGL_NONE,
        )

        // ANGLE's own EGL extension headers; LWJGL has no binding for them.
        private const val EGL_PLATFORM_ANGLE_ANGLE = 0x3202
        private const val EGL_PLATFORM_ANGLE_TYPE_ANGLE = 0x3203
        private const val EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE = 0x3489
        private const val ANGLE_EXTENSION = "EGL_ANGLE_platform_angle"
        private const val ANGLE_METAL_EXTENSION = "EGL_ANGLE_platform_angle_metal"
        private const val ANGLE_DISPLAY_FUNCTION = "eglGetPlatformDisplayEXT"
        private const val ABANDONED_CONTEXT_MESSAGE =
            "GL owner did not stop; leaving EGL state for process teardown"
    }
}

private const val CHANNEL_BITS = 8
private const val GLES_MAJOR_VERSION = 3
