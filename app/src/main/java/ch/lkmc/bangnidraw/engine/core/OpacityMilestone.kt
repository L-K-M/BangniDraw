package ch.lkmc.bangnidraw.engine.core

/** Haptic stops crossed by an opacity slider movement. */
internal object OpacityMilestone {
    fun crossed(from: Float, to: Float): List<Float> {
        if (to > from) return STOPS.filter { it > from && it <= to }
        if (to < from) return STOPS.asReversed().filter { it < from && it >= to }

        return emptyList()
    }

    private val STOPS = listOf(0f, 0.5f, 1f)
}
