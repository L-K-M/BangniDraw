package ch.lkmc.bangnidraw.engine.core

/**
 * One stroke, from pen samples to dab batches
 * (`docs/plan/03-canvas-engine.md` §6's pipeline, middle three steps).
 *
 * ```
 * CanvasTouchHandler → [begin/sample/end] → Stabilizer → DabGenerator → DabBatch
 * ```
 *
 * §6 draws that chain but no class owns it, so without this the wiring would
 * live in the GL session — where it could not be tested, because a
 * `GLFrontBufferedRenderer` cannot be built on the JVM. Everything here is
 * pure: the same samples produce the same dabs on the JVM as on a device, and
 * `StrokeDriverTest` checks the properties §6 and §7.1 promise.
 *
 * **The batch is the caller's and is reused.** [sample] fills it and returns
 * how many dabs it added; the caller stamps and clears. Nothing here allocates
 * after construction, because this runs on the touch path where
 * `10-performance.md` §2.4 allows none.
 *
 * Main-thread-only, like the input path it sits on.
 */
class StrokeDriver(
    private val preset: BrushPreset,
    /**
     * Per stroke, not per app: `DabGenerator` derives every dab's jitter and
     * grain phase from it, so reusing one seed would make every stroke of a
     * jittering brush identical (§6's `seed` field).
     */
    seed: Long,
    zoom: Float = 1f,
) {

    private val generator = DabGenerator(preset, seed)
    private val stabilizer = Stabilizer(preset.stabilizer, zoom)

    /** The sample the stabilizer produces; fed to the generator. */
    private val smoothed = StrokeInput()

    /** Scratch for the caller's raw sample, so callers can pass primitives. */
    private val raw = StrokeInput()

    var isActive: Boolean = false
        private set

    /**
     * The stroke's opacity ceiling — `preset.opacity · pressureOpacityMax`
     * (04 §3.3), which §7.4 caps the merged buffer at.
     *
     * Only meaningful once the stroke has ended: `pressureOpacityMax` is the
     * running maximum over the pressures actually seen, so reading it
     * mid-stroke would cap at whatever the user had reached so far.
     */
    val opacityCeiling: Float
        get() = (preset.opacity * generator.pressureOpacityMax).coerceIn(0f, 1f)

    /** How the preset's dabs land in the buffer (§7.2). */
    val bufferMode: BufferMode get() = preset.bufferMode

    /** Re-tunes the stabilizer for a new zoom — §4's leash is in canvas px. */
    fun setZoom(zoom: Float) = stabilizer.retune(zoom = zoom)

    /**
     * Opens the stroke at the first sample and emits its first dabs.
     *
     * Returns the number of dabs written to [out].
     */
    fun begin(
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        timeNs: Long,
        source: StrokeSource,
        out: DabBatch,
    ): Int {
        raw.set(x, y, pressure, tilt, orientation, timeNs, source, predicted = false)
        stabilizer.reset(raw)
        stabilizer.current(smoothed)
        isActive = true
        return generator.begin(smoothed, out)
    }

    /**
     * Feeds one sample and emits whatever dabs it completes.
     *
     * Returns 0 when the stabilizer swallowed the sample — it holds the pen
     * back on a leash, so a slow hand produces fewer dabs than samples, which
     * is the whole point of §4's smoothing.
     */
    fun sample(
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        timeNs: Long,
        source: StrokeSource,
        out: DabBatch,
    ): Int {
        if (!isActive) return 0
        raw.set(x, y, pressure, tilt, orientation, timeNs, source, predicted = false)
        if (!stabilizer.push(raw, smoothed)) return 0
        return generator.advance(smoothed, out)
    }

    /**
     * Closes the stroke, flushing the stabilizer's leash and the generator's
     * remainder.
     *
     * §4: the smoothed point lags the pen, so a stroke that simply stopped
     * would end short of where the user lifted — visibly, on every stroke. The
     * flush walks the remaining distance at the current spacing.
     */
    fun end(out: DabBatch): Int {
        if (!isActive) return 0
        isActive = false
        var emitted = 0
        stabilizer.finish(generator.currentStep(), smoothed) { s ->
            emitted += generator.advance(s, out)
        }
        emitted += generator.end(out)
        return emitted
    }

    /**
     * Abandons the stroke without flushing — `ACTION_CANCEL` and palm
     * rejection (§4). No dabs, no trace.
     */
    fun cancel() {
        isActive = false
    }

    // ------------------------------------------------------- the tail (§9)

    /**
     * The tail's own stabilizer and generator, re-synced from the real ones on
     * every [predict] rather than allocated per frame.
     *
     * Null until the first prediction: a finger stroke, a mouse stroke, or a
     * device whose predictor never returns anything never pays for them.
     */
    private var tailStabilizer: Stabilizer? = null
    private var tailGenerator: DabGenerator? = null

    /** Scratch for [predict], so it never touches [raw] or [smoothed]. */
    private val tailRaw = StrokeInput()
    private val tailSmoothed = StrokeInput()

    /**
     * Runs [samples] through **copies** of the stabilizer and generator and
     * writes the dabs into [out], returning how many
     * (`docs/plan/03-canvas-engine.md` §9).
     *
     * This is the whole of §9's claim in one method. The tail continues the
     * stabilized line — same leash position, same spacing remainder, same
     * jitter sequence — so its dabs are exactly the dabs the real samples
     * would produce *if the prediction is right*; and because it runs on
     * copies, a prediction that was wrong costs nothing: the real state has
     * not moved, and the next real sample carries on from where it was.
     *
     * The dabs are marked predicted ([DabBatch.markPredictedFromHere]), which
     * is what routes them to the removable tail rather than the stroke buffer.
     *
     * Returns 0 for a stroke that is not open — a tail with nothing to
     * continue is not a tail — and 0 for an empty batch.
     */
    fun predict(samples: StrokeInputBatch, out: DabBatch): Int {
        if (!isActive || samples.size == 0) return 0
        // On the first frame `copy()` builds the pair AND is the sync; on every
        // later one the same two objects are re-synced in place, which is what
        // keeps a per-frame path allocation-free.
        var stab = tailStabilizer
        var gen = tailGenerator
        if (stab == null || gen == null) {
            stab = stabilizer.copy()
            gen = generator.copy()
            tailStabilizer = stab
            tailGenerator = gen
        } else {
            stabilizer.copyInto(stab)
            generator.copyInto(gen)
        }
        out.markPredictedFromHere()
        var emitted = 0
        for (i in 0 until samples.size) {
            val s = samples[i]
            // `predicted = true` regardless of what the caller set, because
            // everything downstream keys the tail off this flag and a sample
            // that reached here IS predicted by construction.
            tailRaw.set(
                s.x, s.y, s.pressure, s.tilt, s.orientation, s.timeNs, s.source,
                predicted = true,
            )
            if (stab.push(tailRaw, tailSmoothed)) emitted += gen.advance(tailSmoothed, out)
        }
        return emitted
    }
}
