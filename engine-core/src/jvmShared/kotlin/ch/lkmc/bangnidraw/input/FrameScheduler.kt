package ch.lkmc.bangnidraw.input

/**
 * Posts a callback for the next display frame, and cancels a posted one —
 * the seam that keeps `Choreographer` out of shared input code. The
 * Android glue supplies the real implementation; a null scheduler leaves
 * the frame-driven paths (predicted tail, hover coalescing) inert, which
 * is exactly the JVM-test posture the handler has always had.
 */
interface FrameScheduler {
    fun post(callback: Runnable)
    fun cancel(callback: Runnable)
}
