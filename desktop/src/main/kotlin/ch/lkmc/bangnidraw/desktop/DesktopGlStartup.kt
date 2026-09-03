package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.gl.platform.GlLog

/**
 * What startup tried, in the order it tried it.
 *
 * A user who launches the app from Finder never sees stdout, so a context
 * failure has to carry its own evidence: the window shows [failures] and
 * [details], and `--gl-report` prints the same thing to a terminal.
 */
internal class DesktopGlReport {
    private val notes = mutableListOf<String>()
    private val stageFailures = mutableListOf<String>()

    /** The context host that succeeded, or null while startup is still failing. */
    var path: String? = null
        private set

    fun note(line: String) {
        notes += line
        GlLog.i(TAG, line)
    }

    fun fail(stage: String, reason: String) {
        stageFailures += "$stage: $reason"
        note("$stage failed: $reason")
    }

    fun succeed(stage: String) {
        path = stage
        note("$stage context created")
    }

    /** Why each context host refused, newest last. */
    fun failures(): String = stageFailures.joinToString("\n")

    /** Every recorded line, indented for the failure window. */
    fun details(): String = notes.joinToString("\n") { "  $it" }

    /** Every recorded line, for the log and for `--gl-report`. */
    fun text(): String = notes.joinToString("\n")

    private companion object {
        const val TAG = "DesktopGl"
    }
}

/**
 * Brings up the engine's GLES context.
 *
 * EGL first, on every platform: it loads its libraries by absolute path and
 * needs neither a window nor the process main thread. Where EGL comes from the
 * system rather than a bundle — Linux — the hidden-window GLFW host stays as a
 * fallback, so a machine whose EGL the direct path cannot open still runs. The
 * report always says which host answered.
 */
internal object DesktopGlStartup {
    data class Result(
        val context: DesktopEsContext?,
        val report: DesktopGlReport,
    )

    fun start(width: Int, height: Int): Result {
        val report = DesktopGlReport()
        report.note(hostLine())

        val environment = DesktopNativeBootstrap.prepare(report)
        // Trimmed: a trailing space from a CI matrix or launch script would
        // otherwise read as an unknown host, and on Linux an unknown host runs
        // exactly like an unforced one — the ambiguity the note below exists
        // to prevent.
        val requested = System.getProperty(HOST_PROPERTY).orEmpty().trim().lowercase()
        if (requested.isNotEmpty()) {
            report.note("requested GL host: $requested")
            if (requested != EGL_HOST && requested != GLFW_HOST) {
                // The lever CI uses to prove each host: a typo must not read as
                // an unforced run that happens to land on the same place.
                report.note("unknown $HOST_PROPERTY; expected $EGL_HOST or $GLFW_HOST")
            }
        }

        if (requested != GLFW_HOST) {
            val direct = EglEsContext.create(width, height, environment.backend, report)
            if (direct != null) return Result(direct, report)
            // -Dbangnidraw.gl.host=egl is how CI proves the direct path works
            // rather than silently landing on the fallback.
            if (requested == EGL_HOST) return Result(null, report)

            if (environment.backend == DesktopGlBackend.AngleMetal) {
                // Nothing to fall back to: on macOS GLFW would look for the
                // bundled ANGLE by leaf name (the failure this replaced), and
                // after EGL has already loaded it, LWJGL 3.4.3's glfw_async
                // build crashes the JVM at init (CI run 33735259059). Only an
                // explicit -Dbangnidraw.gl.host=glfw still tries it.
                report.note("GLFW host is not used on macOS")
                return Result(null, report)
            }
        }

        val fallback = GlfwEsContext.create(width, height, environment.backend, report)

        return Result(fallback, report)
    }

    private fun hostLine(): String = buildString {
        append("host: ")
        append(System.getProperty("os.name", "?"))
        append(' ')
        append(System.getProperty("os.version", "?"))
        append(' ')
        append(System.getProperty("os.arch", "?"))
        append(", JVM ")
        append(System.getProperty("java.version", "?"))
        append(" (")
        append(System.getProperty("java.vendor", "?"))
        append(')')
    }

    /** Forces one host, so both can be exercised on a machine that has them. */
    private const val HOST_PROPERTY = "bangnidraw.gl.host"
    private const val EGL_HOST = "egl"
    private const val GLFW_HOST = "glfw"
}
