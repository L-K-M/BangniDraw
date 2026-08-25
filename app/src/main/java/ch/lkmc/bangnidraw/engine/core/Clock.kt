package ch.lkmc.bangnidraw.engine.core

/**
 * Time, injected. Everything in `engine/core` that needs "now" takes one of
 * these so a test can hand it a value instead of waiting for the wall clock.
 *
 * [nowNanos] is **monotonic and opaque** — `System.nanoTime`'s zero point is
 * arbitrary — so it measures durations and nothing else. It must never be
 * converted into a wall-clock time. The epoch-millisecond timestamps a
 * document carries (`Document.createdAt`/`updatedAt`,
 * `docs/plan/06-document-and-persistence.md` §3) are minted at the `data/`
 * boundary that writes them, never in `engine/core`; if a later change needs
 * wall-clock time in core, add a second method here rather than deriving one
 * from nanos.
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

    companion object {
        /** The one production source, so jitter cannot diverge per call site. */
        val SYSTEM = RandomSource { kotlin.random.Random.nextFloat() }
    }
}
