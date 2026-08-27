package ch.lkmc.bangnidraw.engine.core

/**
 * What the debug overlay reads (`docs/plan/10-performance.md` §5.3,
 * `03-canvas-engine.md` §11's budgets).
 *
 * §11 states three budgets — dab stamping ≤ 1 ms, the dirty-rect recomposite
 * ≤ 2 ms, readback issue ≈ 0 — and until something measures them they are
 * aspirations. This is the something. It is also the only way those numbers can
 * be checked at all in this project: they are per-frame GL timings on a real
 * panel, and no device has ever been available to any PR here.
 *
 * **Written on the GL thread, read on the main thread**, which is why every
 * published field is `@Volatile` and why nothing here allocates after
 * construction. §5.3 calls for "plain `@Volatile` fields the GL thread updates
 * without allocation, sampled by the overlay at 4 Hz"; a lock would put the
 * render thread behind the sampler, and a data class would allocate once a
 * frame on the path `10-performance.md` §2.4 is about.
 *
 * `@Volatile` per field rather than one published snapshot object: the overlay
 * shows numbers to a human at 4 Hz, so a torn read across two fields is
 * invisible — one of them is 250 ms stale on a display that is already sampling
 * a moving target. Paying for consistency nobody can perceive would cost the
 * render thread a write barrier it does not need.
 *
 * **Only fields something actually feeds are here.** §5.3's table also lists
 * `tilesPending`, `mirrorBytes`, `journalSteps`/`journalBytes` and
 * `readbackStalls`; their producers — reopen streaming, the CPU mirror, the
 * history journal, the readback stall detector — arrive with step 3. A field
 * wired to nothing would read 0 on the overlay and look like a measurement.
 *
 * The prediction numbers §8 asks the overlay to show are **not** mirrored here
 * either, for the same reason read the other way round: `PredictionGate` and
 * [LatencyTrace] live on the main thread with the handler, and the overlay
 * recomposes on the main thread, so it reads them directly. Copying them across
 * a `@Volatile` would add a second copy that can disagree with the first, to
 * cross a thread boundary that is not there.
 */
class PerfStats {

    /**
     * Milliseconds the last front-buffered frame spent stamping dabs — §11's
     * "≤ 1 ms for a typical batch".
     */
    @Volatile
    var stampMs: Float = 0f

    /**
     * Milliseconds the last front-buffered frame spent compositing the dirty
     * rect — §11's "≤ 2 ms".
     */
    @Volatile
    var compositeMs: Float = 0f

    /** The worst [stampMs] since [resetPeaks]. */
    @Volatile
    var stampMsMax: Float = 0f

    /** The worst [compositeMs] since [resetPeaks]. */
    @Volatile
    var compositeMsMax: Float = 0f

    /** Milliseconds the last pen-up spent merging and presenting (§8.3). */
    @Volatile
    var commitMs: Float = 0f

    /** Dabs stamped in the last front-layer render. */
    @Volatile
    var dabsPerFrame: Int = 0

    /** Front-buffered frames drawn since [resetPeaks] — the denominator for the rest. */
    @Volatile
    var frames: Int = 0

    /** Slices the pool has handed out, and how many it holds (§2.1). */
    @Volatile
    var tilesResident: Int = 0

    @Volatile
    var tilesBudget: Int = 0

    /**
     * Records one front-buffered frame's timings, keeping the peaks.
     *
     * Takes milliseconds already divided rather than raw nanos, because the
     * caller has the two timestamps and this should not care which clock they
     * came from.
     */
    fun frame(stampMs: Float, compositeMs: Float, dabs: Int) {
        this.stampMs = stampMs
        this.compositeMs = compositeMs
        if (stampMs > stampMsMax) stampMsMax = stampMs
        if (compositeMs > compositeMsMax) compositeMsMax = compositeMs
        dabsPerFrame = dabs
        frames++
    }

    /**
     * Clears the peaks and the frame count.
     *
     * **Called on the GL thread**, from `CanvasRenderer.beginStroke` — which
     * `EngineSession.beginStroke` queues through the shared `GLRenderer`, like
     * every other command it sends the renderer. So this is the same thread
     * that runs [frame] and writes `commitMs`, and the read-modify-write on the
     * peaks needs nothing beyond the `@Volatile` that publishes it to the
     * reader.
     *
     * Spelled out because "at pen-down" — which is when it happens — reads as
     * "on the input thread", and a reviewer drew exactly that inference. If a
     * caller ever *does* reset from the main thread, the peaks need a pending
     * flag applied inside [frame] rather than a direct write: `@Volatile` does
     * not make `if (x > max) max = x` atomic against a concurrent zeroing, and
     * a pre-reset peak surviving into the new stroke would answer "did this
     * stroke stay in budget" with the previous stroke's worst frame.
     *
     * Per stroke rather than per session, because that is the question the
     * overlay answers: "did *this* stroke stay inside the budget". A maximum
     * that survived every stroke since the app opened would be pinned by the
     * first frame after a cold shader compile and would never move again.
     *
     * The last-frame values are deliberately left alone: they describe a frame
     * that really happened, and blanking them would make the overlay flicker to
     * zero at every pen-down.
     */
    fun resetPeaks() {
        stampMsMax = 0f
        compositeMsMax = 0f
        frames = 0
    }
}
