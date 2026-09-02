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
    private val timerLock = Any()
    private val timers = IdentityHashMap<Runnable, Timer>()

    override fun scheduleAt(deadlineNs: Long, callback: Runnable) {
        lateinit var timer: Timer
        timer = Timer(
            DesktopDeadlinePolicy.delayMillis(System.nanoTime(), deadlineNs),
        ) {
            val current = synchronized(timerLock) {
                if (timers[callback] !== timer) {
                    false
                } else {
                    timers.remove(callback)
                    true
                }
            }
            if (current) callback.run()
        }
        timer.isRepeats = false

        synchronized(timerLock) {
            timers.remove(callback)?.stop()
            timers[callback] = timer
            timer.start()
        }
    }

    override fun cancel(callback: Runnable) {
        synchronized(timerLock) {
            timers.remove(callback)?.stop()
        }
    }
}
