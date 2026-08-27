package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import android.util.Log

/** Logcat tag for every GL-side message; one tag makes a filtered capture readable. */
const val GL_TAG = "BangniGl"

/** Dedicated unit for the one context-wide Mixbox LUT (`09` §5.1). */
const val MIXBOX_LUT_UNIT = 7

/** A shader failed to compile or a program failed to link (`docs/plan/03-canvas-engine.md` §13). */
class GlProgramException(message: String) : IllegalStateException(message)

/**
 * GL error policy of §13, in one place.
 *
 * - After **every allocation** (`glTexStorage3D`, `glBufferData`) and after
 *   shader compile and program link, in **all** builds — those are the errors
 *   that mean something the caller must handle, and `GL_OUT_OF_MEMORY` becomes
 *   a refused operation rather than a torn frame.
 * - After **every pass** in debug builds only, where [checkGlDebug] throws.
 *   Release passes do not query the driver: some implementations synchronize
 *   `glGetError`, and a release frame cannot act on the result.
 */
object GlErrors {

    /** Set once at session start from `BuildConfig.DEBUG`; keeps `BuildConfig` out of `engine/`. */
    @JvmStatic
    var strict: Boolean = false

    private var loggedDrainExhausted = false

    /**
     * Drains the GL error queue and returns the first error, or 0.
     *
     * Drains rather than reads once: `glGetError` pops one flag per call, and
     * a single read after a pass that raised two leaves the second to be
     * blamed on whatever runs next.
     */
    fun drain(): Int {
        var first = GLES30.GL_NO_ERROR
        // Bounded, because the unbounded form has no floor under it. GL's
        // error queue is normally short and clears to GL_NO_ERROR, but a lost
        // context is specified to keep reporting `GL_CONTEXT_LOST` until the
        // context is recreated — so on a driver that surfaces a reset that way
        // (ANGLE-backed stacks do) `while (true)` never terminates. That would
        // turn a GPU reset, the one device condition this whole policy exists
        // to survive, into a hung render thread and an ANR: strictly worse
        // than the torn frame §13 is willing to accept. Plain ES 3.0 without
        // the robustness extension reports loss through EGL instead, so this
        // is insurance rather than a reproduction — which is the point, since
        // the failure it insures against cannot be recovered from in-process.
        repeat(MAX_DRAIN) {
            val e = GLES30.glGetError()
            if (e == GLES30.GL_NO_ERROR) return first
            if (first == GLES30.GL_NO_ERROR) first = e
        }
        // Falling out of the loop is the sticky-error regime the bound exists
        // for, and it is silent without this: `first` is still the right
        // answer, so every later caller reports a plausible-looking error at
        // its own call site and nothing says the queue stopped clearing. The
        // condition can never be reproduced in CI, so the one line it emits in
        // the field is the whole diagnosis — and it is one line, because a
        // driver in this state saturates the bound on every frame.
        if (!loggedDrainExhausted) {
            loggedDrainExhausted = true
            Log.w(
                GL_TAG,
                "glGetError did not clear within $MAX_DRAIN calls (first=${name(first)}); " +
                    "the context is likely lost and every error from here is sticky",
            )
        }
        return first
    }

    /**
     * How many `glGetError` calls one [drain] will make.
     *
     * Far above any real queue — errors are raised singly by the passes here —
     * so reaching it means the driver is not clearing, not that errors were
     * lost.
     */
    private const val MAX_DRAIN = 32

    /**
     * Runs an allocation, link, or upload with an attributable error check.
     *
     * The first drain discards flags left by unchecked release passes. The
     * second therefore belongs to [operation], so a stale pass error cannot
     * refuse a valid allocation. Returns the fresh error for callers that turn
     * `GL_OUT_OF_MEMORY` into a refusal (§2.1); never throws by itself because
     * those are device conditions, not bugs.
     */
    fun checkAllocation(what: String, operation: () -> Unit): Int {
        drain()
        operation()
        val e = drain()
        if (e != GLES30.GL_NO_ERROR) Log.w(GL_TAG, "$what: ${name(e)}")
        return e
    }

    /**
     * Checks after a pass in strict (debug) builds. Release builds return
     * before querying the driver, keeping the render loop asynchronous.
     */
    fun checkGlDebug(pass: String) {
        if (!strict) return

        val e = drain()
        if (e == GLES30.GL_NO_ERROR) return

        throw IllegalStateException("$pass: ${name(e)}")
    }

    /** Forgets session-scoped diagnostics when a new GL context starts. */
    fun reset() {
        loggedDrainExhausted = false
    }

    private fun name(e: Int): String = when (e) {
        GLES30.GL_INVALID_ENUM -> "GL_INVALID_ENUM"
        GLES30.GL_INVALID_VALUE -> "GL_INVALID_VALUE"
        GLES30.GL_INVALID_OPERATION -> "GL_INVALID_OPERATION"
        GLES30.GL_INVALID_FRAMEBUFFER_OPERATION -> "GL_INVALID_FRAMEBUFFER_OPERATION"
        GLES30.GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY"
        else -> "GL error 0x${Integer.toHexString(e)}"
    }
}
