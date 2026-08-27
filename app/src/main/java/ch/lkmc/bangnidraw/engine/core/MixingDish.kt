package ch.lkmc.bangnidraw.engine.core

/** Nine-step UI mixture between two color wells. */
object MixingDish {
    fun gradient(a: Int, b: Int, mixer: ColorMixer): IntArray =
        IntArray(STEPS) { index -> mixer.mix(a, b, index.toFloat() / LAST_INDEX) }

    const val STEPS = 9
    private const val LAST_INDEX = STEPS - 1
}
