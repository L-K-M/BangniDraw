package ch.lkmc.bangnidraw.engine.core

/** Reports crossing a 60-degree hue detent along the shortest circular arc. */
object HueMilestone {

    fun crossed(fromDegrees: Float, toDegrees: Float): Boolean {
        if (!fromDegrees.isFinite() || !toDegrees.isFinite()) return false

        val start = normalize(fromDegrees)
        val delta = shortestDelta(start, normalize(toDegrees))
        if (delta == 0f) return false

        val end = start + delta
        for (detent in DETENTS) {
            for (turn in -1..1) {
                val unwrapped = detent + turn * FULL_TURN
                if (delta > 0f && unwrapped > start && unwrapped <= end) return true
                if (delta < 0f && unwrapped < start && unwrapped >= end) return true
            }
        }
        return false
    }

    private fun normalize(degrees: Float): Float {
        val remainder = degrees % FULL_TURN
        return if (remainder < 0f) remainder + FULL_TURN else remainder
    }

    private fun shortestDelta(from: Float, to: Float): Float {
        var delta = (to - from) % FULL_TURN
        if (delta >= HALF_TURN) delta -= FULL_TURN
        if (delta < -HALF_TURN) delta += FULL_TURN
        return delta
    }

    private val DETENTS = floatArrayOf(0f, 60f, 120f, 180f, 240f, 300f)
    private const val HALF_TURN = 180f
    private const val FULL_TURN = 360f
}
