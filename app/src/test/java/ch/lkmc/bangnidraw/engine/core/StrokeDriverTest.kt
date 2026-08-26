package ch.lkmc.bangnidraw.engine.core

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/plan/03-canvas-engine.md` §6's pipeline as one object: samples in,
 * dabs out, with the stabilizer and the generator in between.
 *
 * This is the class that makes the chain testable at all. Wiring it inside the
 * GL session instead would put it behind a `GLFrontBufferedRenderer`, which
 * cannot be constructed on the JVM — so the properties below would have been
 * device-only, and 2.4b's device check is not something this project has ever
 * been able to run.
 */
class StrokeDriverTest {

    private fun preset(
        spacing: Float = 0.3f,
        stabilizer: Float = 0f,
        size: Float = 20f,
        // The production default, deliberately. An earlier revision defaulted
        // this to Curve.Linear to make the opacity-ceiling test strict, which
        // also silently swapped every OTHER test in this file off the curve
        // real presets ship — so `Curve.One`'s own path (a flat curve, whose
        // lookup and clamping are their own code) would have lost all coverage
        // here to buy strictness in one test. The ceiling test asks for Linear
        // explicitly instead.
        pressureOpacity: Curve = Curve.One,
    ) = BrushPreset(
        id = "t",
        name = "test",
        size = size,
        spacing = spacing,
        stabilizer = stabilizer,
        pressureOpacity = pressureOpacity,
    )

    private fun driver(
        spacing: Float = 0.3f,
        stabilizer: Float = 0f,
        size: Float = 20f,
        pressureOpacity: Curve = Curve.One,
    ) = StrokeDriver(preset(spacing, stabilizer, size, pressureOpacity), seed = 1L)

    private fun batch() = DabBatch()

    private fun StrokeDriver.line(
        out: DabBatch,
        fromX: Float,
        toX: Float,
        y: Float = 100f,
        steps: Int = 20,
        pressure: Float = 1f,
    ): Int {
        var emitted = begin(fromX, y, pressure, 0f, 0f, 0L, StrokeSource.STYLUS, out)
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            emitted += sample(
                fromX + (toX - fromX) * t, y, pressure, 0f, 0f,
                i * 8_000_000L, StrokeSource.STYLUS, out,
            )
        }
        emitted += end(out)
        return emitted
    }

    @Test
    fun `a straight drag emits dabs along the path`() {
        val out = batch()
        val d = driver()
        val emitted = d.line(out, 100f, 300f)
        assertTrue(emitted > 0, "a 200 px drag must emit dabs")
        assertEquals(emitted, out.count, "every emitted dab must be in the batch")
        for (i in 0 until out.count) {
            assertTrue(out.x[i] in 99f..301f, "dab $i strayed off the path at x=${out.x[i]}")
            assertTrue(out.y[i] in 99f..101f, "dab $i strayed off the path at y=${out.y[i]}")
        }
    }

    @Test
    fun `spacing is measured along the path, not per sample`() {
        // §6: "spacing invariant under resolution". The same line sampled at
        // four times the rate must produce essentially the same dab count —
        // this is what stops a 240 Hz digitizer from laying down four times
        // the ink of a 60 Hz one.
        val coarse = batch()
        val fine = batch()
        driver().line(coarse, 100f, 300f, steps = 10)
        driver().line(fine, 100f, 300f, steps = 40)
        val difference = kotlin.math.abs(coarse.count - fine.count)
        assertTrue(
            difference <= 2,
            "sample rate must not change the dab count: ${coarse.count} vs ${fine.count}",
        )
    }

    @Test
    fun `consecutive dabs are about one spacing step apart`() {
        val out = batch()
        val spacing = 0.5f
        val radius = 10f // size 20 -> radius 10
        driver(spacing = spacing).line(out, 100f, 400f, steps = 60)
        assertTrue(out.count >= 3, "need several dabs to measure spacing, got ${out.count}")
        val expected = spacing * radius
        for (i in 1 until out.count) {
            val step = hypot(out.x[i] - out.x[i - 1], out.y[i] - out.y[i - 1])
            assertTrue(
                step in expected * 0.5f..expected * 1.6f,
                "gap $i was $step, expected about $expected",
            )
        }
    }

    @Test
    fun `the stroke ends where the pen lifted, not where the leash was`() {
        // §4: the smoothed point lags the pen, so a stroke that merely stopped
        // would end visibly short on every stroke. `end` flushes the leash.
        val loose = batch()
        val d = driver(stabilizer = 0.9f)
        d.line(loose, 100f, 300f, steps = 30)
        assertTrue(loose.count > 0, "a stabilized stroke must still emit dabs")
        val lastX = loose.x[loose.count - 1]
        assertTrue(
            lastX > 280f,
            "the flush must carry the stroke to the lift point, ended at $lastX",
        )
    }

    @Test
    fun `a cancelled stroke emits nothing more and leaves no trace`() {
        val out = batch()
        val d = driver()
        d.begin(100f, 100f, 1f, 0f, 0f, 0L, StrokeSource.STYLUS, out)
        d.sample(150f, 100f, 1f, 0f, 0f, 8_000_000L, StrokeSource.STYLUS, out)
        val before = out.count
        d.cancel()
        val after = d.sample(200f, 100f, 1f, 0f, 0f, 16_000_000L, StrokeSource.STYLUS, out)
        assertEquals(0, after, "a cancelled stroke must not accept samples")
        assertEquals(0, d.end(out), "and must not flush on end")
        assertEquals(before, out.count, "the batch must be untouched after cancel")
        assertTrue(!d.isActive, "a cancelled stroke is not active")
    }

    @Test
    fun `samples before begin are ignored rather than crashing`() {
        val out = batch()
        val d = driver()
        assertEquals(0, d.sample(1f, 1f, 1f, 0f, 0f, 0L, StrokeSource.STYLUS, out))
        assertEquals(0, d.end(out))
        assertEquals(0, out.count)
    }

    @Test
    fun `the opacity ceiling reflects the pressure actually used`() {
        // §7.4's `o = preset.opacity · pressureOpacityMax` (04 §3.3), which is
        // the number the merge caps the buffer at. A light stroke must cap
        // lower than a heavy one, or pressure-opacity does nothing.
        // Linear here, not the shared default: with BrushPreset's shipped
        // Curve.One, pressureOpacityMax is 1 whatever the pressure, both
        // ceilings are 1, and a driver that ignored pressure entirely would
        // pass. The curve has to vary for the property to exist at all.
        val light = driver(pressureOpacity = Curve.Linear)
        val heavy = driver(pressureOpacity = Curve.Linear)
        light.line(batch(), 100f, 300f, pressure = 0.2f)
        heavy.line(batch(), 100f, 300f, pressure = 1f)
        assertTrue(
            light.opacityCeiling < heavy.opacityCeiling,
            "a light stroke must cap STRICTLY lower: " +
                "light ${light.opacityCeiling} vs heavy ${heavy.opacityCeiling}",
        )
        assertTrue(heavy.opacityCeiling in 0f..1f, "the ceiling is a fraction: ${heavy.opacityCeiling}")
    }

    @Test
    fun `two strokes with different seeds are not identical for a jittering brush`() {
        // §6's per-dab `seed`. One shared seed would make every stroke of a
        // jittering brush trace the same wobble.
        val jitter = preset().copy(jitter = Jitter(position = 0.8f))
        val a = DabBatch()
        val b = DabBatch()
        StrokeDriver(jitter, seed = 1L).line(a, 100f, 400f, steps = 40)
        StrokeDriver(jitter, seed = 999L).line(b, 100f, 400f, steps = 40)
        assertTrue(a.count > 2 && b.count > 2, "need dabs to compare")
        var differing = 0
        for (i in 0 until minOf(a.count, b.count)) {
            if (a.x[i] != b.x[i] || a.y[i] != b.y[i]) differing++
        }
        assertTrue(differing > 0, "different seeds must produce different jitter")
    }

    @Test
    fun `the same seed reproduces the same stroke exactly`() {
        // The other half: undo/redo and the journal replay strokes, so a
        // stroke must be a pure function of its samples and its seed.
        val jitter = preset().copy(jitter = Jitter(position = 0.8f))
        val a = DabBatch()
        val b = DabBatch()
        StrokeDriver(jitter, seed = 42L).line(a, 100f, 400f, steps = 40)
        StrokeDriver(jitter, seed = 42L).line(b, 100f, 400f, steps = 40)
        assertEquals(a.count, b.count, "the same seed must emit the same dab count")
        for (i in 0 until a.count) {
            assertEquals(a.x[i], b.x[i], 0f, "dab $i x diverged")
            assertEquals(a.y[i], b.y[i], 0f, "dab $i y diverged")
            assertEquals(a.radius[i], b.radius[i], 0f, "dab $i radius diverged")
            // Every field the generator varies, not the three that were easy
            // to name. The test's own justification is journal replay, which
            // needs the WHOLE dab to come back — a flow or aspect drawn from
            // wall-clock time, accumulated distance or a mis-wired RNG stream
            // would replay visibly differently while x, y and radius matched.
            assertEquals(a.flow[i], b.flow[i], 0f, "dab $i flow diverged")
            assertEquals(a.aspect[i], b.aspect[i], 0f, "dab $i aspect diverged")
            assertEquals(a.angle[i], b.angle[i], 0f, "dab $i angle diverged")
            assertEquals(a.hardness[i], b.hardness[i], 0f, "dab $i hardness diverged")
        }
    }

    @Test
    fun `every emitted dab has a drawable radius and a sane aspect`() {
        val out = batch()
        driver().line(out, 100f, 400f, steps = 40)
        assertTrue(out.count > 0)
        for (i in 0 until out.count) {
            assertTrue(
                out.radius[i] >= Dab.MIN_RADIUS && out.radius[i] <= Dab.MAX_RADIUS,
                "dab $i radius ${out.radius[i]} is outside what the shader can draw",
            )
            assertTrue(out.aspect[i] > 0f && out.aspect[i] <= 1f, "dab $i aspect ${out.aspect[i]}")
            assertTrue(out.flow[i] in 0f..1f, "dab $i flow ${out.flow[i]}")
            assertTrue(out.radius[i].isFinite() && out.x[i].isFinite(), "dab $i is not finite")
        }
    }

    // --------------------------------------------- the predicted tail (§9)

    /** A batch of predicted samples along a straight continuation. */
    private fun samples(fromX: Float, toX: Float, y: Float, fromNs: Long, count: Int): StrokeInputBatch {
        val batch = StrokeInputBatch()
        for (i in 1..count) {
            val t = i / count.toFloat()
            val s = batch.next() ?: break
            s.set(
                x = fromX + (toX - fromX) * t,
                y = y,
                timeNs = fromNs + i * 4_000_000L,
                source = StrokeSource.STYLUS,
                predicted = true,
            )
        }
        return batch
    }

    private fun assertSameDabs(expected: DabBatch, actual: DabBatch, what: String) {
        assertEquals(expected.count, actual.count, "dab count diverged: $what")
        for (i in 0 until expected.count) {
            assertEquals(expected.x[i], actual.x[i], 0f, "dab $i x: $what")
            assertEquals(expected.y[i], actual.y[i], 0f, "dab $i y: $what")
            assertEquals(expected.radius[i], actual.radius[i], 0f, "dab $i radius: $what")
            assertEquals(expected.flow[i], actual.flow[i], 0f, "dab $i flow: $what")
            assertEquals(expected.angle[i], actual.angle[i], 0f, "dab $i angle: $what")
            assertEquals(expected.seed[i], actual.seed[i], 0f, "dab $i seed: $what")
        }
    }

    /** Drives a stroke up to (but not including) its end, leaving it open. */
    private fun StrokeDriver.halfLine(out: DabBatch, fromX: Float, toX: Float, y: Float, steps: Int) {
        begin(fromX, y, 1f, 0f, 0f, 0L, StrokeSource.STYLUS, out)
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            sample(
                fromX + (toX - fromX) * t, y, 1f, 0f, 0f,
                i * 8_000_000L, StrokeSource.STYLUS, out,
            )
        }
    }

    @Test
    fun `the tail is exactly the dabs the real samples would produce`() {
        // §9's whole claim: the tail runs through a copy of the stabilizer and
        // the generator, so if the prediction is right the ink is right — same
        // leash position, same spacing remainder, same jitter sequence.
        //
        // A stabilizing, jittering preset, because that is what makes the claim
        // non-trivial: with strength 0 and no jitter a tail computed from
        // scratch would pass this too.
        val hard = preset(spacing = 0.25f, stabilizer = 0.6f).copy(
            jitter = Jitter(position = 0.7f, size = 0.4f),
        )

        val tailDriver = StrokeDriver(hard, seed = 7L)
        val tailWarmup = batch()
        tailDriver.halfLine(tailWarmup, 100f, 200f, 100f, steps = 12)
        val tail = batch()
        val emitted = tailDriver.predict(samples(200f, 240f, 100f, 96_000_000L, 4), tail)
        assertTrue(emitted > 0, "a 40 px continuation must produce a tail")

        // The twin: the same stroke, then the same points fed as REAL samples.
        val realDriver = StrokeDriver(hard, seed = 7L)
        val realWarmup = batch()
        realDriver.halfLine(realWarmup, 100f, 200f, 100f, steps = 12)
        assertSameDabs(tailWarmup, realWarmup, "the two twins must start identical")
        val real = batch()
        val predictedPoints = samples(200f, 240f, 100f, 96_000_000L, 4)
        for (i in 0 until predictedPoints.size) {
            val s = predictedPoints[i]
            realDriver.sample(s.x, s.y, s.pressure, s.tilt, s.orientation, s.timeNs, s.source, real)
        }
        assertSameDabs(real, tail, "the tail must be the real stroke's own continuation")
    }

    @Test
    fun `predicting never advances the real state`() {
        // The other half of §9, and the one that shows up as "a hook at
        // pen-up": if a prediction advanced the spacing remainder or the
        // stabilizer's leash, the real samples that follow would resume from a
        // point the pen never visited.
        val hard = preset(spacing = 0.25f, stabilizer = 0.6f).copy(
            jitter = Jitter(position = 0.7f, size = 0.4f),
        )

        val clean = batch()
        StrokeDriver(hard, seed = 7L).line(clean, 100f, 400f, steps = 24)

        val interleaved = batch()
        val d = StrokeDriver(hard, seed = 7L)
        val scratch = batch()
        d.begin(100f, 100f, 1f, 0f, 0f, 0L, StrokeSource.STYLUS, interleaved)
        for (i in 1..24) {
            val t = i / 24f
            // A tail between every pair of real samples, which is roughly the
            // real cadence: one predict() per frame against several samples.
            scratch.clear()
            d.predict(samples(100f + 300f * t, 100f + 300f * t + 30f, 100f, i * 8_000_000L, 3), scratch)
            d.sample(
                100f + 300f * t, 100f, 1f, 0f, 0f,
                i * 8_000_000L, StrokeSource.STYLUS, interleaved,
            )
        }
        d.end(interleaved)
        assertSameDabs(clean, interleaved, "a prediction must leave no trace in the real stroke")
    }

    @Test
    fun `a zoom change mid-stroke retunes the tail with the stroke`() {
        // `setZoom` retunes the real stabilizer's `k` and `leash` — both
        // derived from strength AND zoom — and the tail's copy has to move with
        // it. Copying the two inputs while leaving the three derived values
        // alone gives a tail smoothed for the previous zoom for the rest of the
        // stroke, which reads as a tail that drifts off a heavily stabilized
        // line and only at some zoom levels.
        //
        // No caller drives `setZoom` yet (2.5c/2.5d territory), so this is the
        // only thing exercising that path at all; without it the retune in
        // `Stabilizer.copyInto` is a line no test can fail.
        val hard = preset(spacing = 0.25f, stabilizer = 0.8f)

        val tailDriver = StrokeDriver(hard, seed = 7L)
        val tailWarmup = batch()
        tailDriver.halfLine(tailWarmup, 100f, 200f, 100f, steps = 12)
        // A tail BEFORE the zoom change, which is what makes this test bite:
        // it is what builds the tail's stabilizer, at the old zoom. A first
        // prediction after the change would be copy-constructed already
        // retuned, and the re-sync would have nothing to prove.
        tailDriver.predict(samples(200f, 260f, 100f, 96_000_000L, 4), batch())
        tailDriver.setZoom(4f)
        val tail = batch()
        assertTrue(tailDriver.predict(samples(200f, 260f, 100f, 96_000_000L, 4), tail) > 0)

        val realDriver = StrokeDriver(hard, seed = 7L)
        val realWarmup = batch()
        realDriver.halfLine(realWarmup, 100f, 200f, 100f, steps = 12)
        realDriver.setZoom(4f)
        val real = batch()
        val points = samples(200f, 260f, 100f, 96_000_000L, 4)
        for (i in 0 until points.size) {
            val s = points[i]
            realDriver.sample(s.x, s.y, s.pressure, s.tilt, s.orientation, s.timeNs, s.source, real)
        }
        assertSameDabs(real, tail, "the tail must follow the stroke's retuned smoothing")
    }

    @Test
    fun `the same prediction twice gives the same tail`() {
        // What proves the copy is re-synced from the real state each time
        // rather than carried forward: a tail generator that kept running would
        // put the second frame's dabs 40 px further along, and on screen the
        // tail would crawl away from the pen for as long as the stroke lasted.
        val d = driver(spacing = 0.3f, stabilizer = 0.5f)
        val warmup = batch()
        d.halfLine(warmup, 100f, 200f, 100f, steps = 12)

        val first = batch()
        val second = batch()
        d.predict(samples(200f, 240f, 100f, 96_000_000L, 4), first)
        d.predict(samples(200f, 240f, 100f, 96_000_000L, 4), second)
        assertTrue(first.count > 0)
        assertSameDabs(first, second, "the tail must be rebuilt from the real state every frame")
    }

    @Test
    fun `the tail is marked predicted, so nothing downstream can commit it`() {
        val d = driver()
        val warmup = batch()
        d.halfLine(warmup, 100f, 200f, 100f, steps = 12)
        val tail = batch()
        d.predict(samples(200f, 260f, 100f, 96_000_000L, 4), tail)
        assertTrue(tail.count > 0)
        assertEquals(0, tail.predictedFrom, "every dab in a tail batch is predicted")
        assertEquals(0, tail.committedCount, "and none of them may reach the layer")
    }

    @Test
    fun `a stroke that is not open has no tail to continue`() {
        val d = driver()
        val out = batch()
        assertEquals(0, d.predict(samples(0f, 10f, 0f, 0L, 3), out), "before begin")
        assertEquals(0, out.count)

        val warmup = batch()
        d.halfLine(warmup, 100f, 200f, 100f, steps = 12)
        d.cancel()
        assertEquals(0, d.predict(samples(200f, 260f, 100f, 96_000_000L, 4), out), "after cancel")
        assertEquals(0, out.count)
    }
}
