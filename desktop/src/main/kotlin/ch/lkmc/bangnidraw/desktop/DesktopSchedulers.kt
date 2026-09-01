package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.input.GestureDeadlineScheduler
import java.util.IdentityHashMap
import javax.swing.Timer

internal object DesktopDeadlinePolicy {
    fun delayMillis(nowNs: Long, deadlineNs: Long): Int {
        val remaining = (deadlineNs - nowNs).coerceAtLeast(0)
        val roundedUp = (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
        return roundedUp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}

/** Swing timer bridge so a stationary mouse press starts before release. */
internal class SwingGestureDeadlineScheduler : GestureDeadlineScheduler {
    private val timers = IdentityHashMap<Runnable, Timer>()

    override fun scheduleAt(deadlineNs: Long, callback: Runnable) {
        cancel(callback)

        val timer = Timer(
            DesktopDeadlinePolicy.delayMillis(System.nanoTime(), deadlineNs),
        ) {
            timers.remove(callback)
            callback.run()
        }
        timer.isRepeats = false
        timers[callback] = timer
        timer.start()
    }

    override fun cancel(callback: Runnable) {
        timers.remove(callback)?.stop()
    }
}
