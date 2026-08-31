package ch.lkmc.bangnidraw.engine.gl.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The desktop actual: ISO-timestamped lines on stderr, the JVM's only
 * universally available log sink. Logcat levels map onto prefixes so
 * `2>&1 | grep` works the same way `adb logcat -s` does.
 */
actual object GlLog {
    actual fun w(tag: String, msg: String) = line("W", tag, msg)
    actual fun i(tag: String, msg: String) = line("I", tag, msg)
    actual fun e(tag: String, msg: String, tr: Throwable?) {
        line("E", tag, msg)
        tr?.printStackTrace()
    }

    private fun line(level: String, tag: String, msg: String) {
        System.err.println("${TIMESTAMP.format(java.time.Instant.ofEpochMilli(System.currentTimeMillis()))} $level/$tag: $msg")
    }

    private val TIMESTAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
}
