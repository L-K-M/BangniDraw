package ch.lkmc.bangnidraw.engine.core

/**
 * Time, injected. Everything in `engine/core` that needs "now" takes one of
 * these so a test can hand it a value instead of waiting for the wall clock.
 */
fun interface Clock {
    fun nowNanos(): Long

    companion object {
        val SYSTEM = Clock { System.nanoTime() }
    }
}

/**
 * Randomness, injected — brush jitter and grain phase, so a golden stroke is
 * reproducible.
 */
fun interface RandomSource {
    /** A value in `[0, 1)`. */
    fun nextFloat(): Float
}
