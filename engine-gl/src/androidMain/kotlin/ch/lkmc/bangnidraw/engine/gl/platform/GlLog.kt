package ch.lkmc.bangnidraw.engine.gl.platform

import android.util.Log

/** The Android actual: plain Logcat. */
actual object GlLog {
    actual fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    actual fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    actual fun e(tag: String, msg: String, tr: Throwable?) {
        Log.e(tag, msg, tr)
    }
}
