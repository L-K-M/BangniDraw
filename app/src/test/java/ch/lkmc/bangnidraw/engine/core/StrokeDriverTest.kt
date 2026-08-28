package ch.lkmc.bangnidraw.engine.core

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `a stationary press reaches the flexible tuft through the dynamics-aware gate`() {
        val brush = preset(stabilizer = 0f).copy(
            pressureSize = Curve.Linear,
            model = BrushModel.ChineseInk,
        )
        val out = batch()
        val driver = StrokeDriver(brush, seed = 3L)

        driver.begin(100f, 100f, 0.05f, 0f, 0f, 0L, StrokeSource.STYLUS, out)
        driver.sample(100f, 100f, 0.8f, 0f, 0f, 8_000_000L, StrokeSource.STYLUS, out)

        assertEquals(2, out.count, "pressure-only samples must not stop at stabilization")
        assertTrue(out.radius[1] > out.radius[0])
    }

    @Test
    fun `stationary pressure does not reshape a Standard brush segment`() {
        val brush = preset(stabilizer = 0f).copy(pressureSize = Curve.Linear)
        val control = batch()
        val withStationaryPress = batch()

        StrokeDriver(brush, seed = 5L).run {
            begin(100f, 100f, 0.1f, 0f, 0f, 0L, StrokeSource.STYLUS, control)
            sample(120f, 100f, 1f, 0f, 0f, 16_000_000L, StrokeSource.STYLUS, control)
        }
        StrokeDriver(brush, seed = 5L).run {
            begin(100f, 100f, 0.1f, 0f, 0f, 0L, StrokeSource.STYLUS, withStationaryPress)
            sample(100f, 100f, 1f, 0f, 0f, 8_000_000L, StrokeSource.STYLUS, withStationaryPress)
            sample(120f, 100f, 1f, 0f, 0f, 16_000_000L, StrokeSource.STYLUS, withStationaryPress)
        }

        assertSameDabs(control, withStationaryPress, "a Standard pressure-only sample")
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
    fun `pen-up resumes a catch-up segment across tiny batches`() {
        val driver = driver(spacing = 0.5f, stabilizer = 1f)
        var batch = DabBatch(capacity = 2)
        var emitted = driver.begin(
            0f, 0f, 1f, 0f, 0f, 0L, StrokeSource.STYLUS, batch,
        )
        batch = DabBatch(capacity = 2)
        emitted += driver.sample(
            100f, 0f, 1f, 0f, 0f, 8_000_000L, StrokeSource.STYLUS, batch,
        )
        while (driver.hasPendingDabs) {
            batch = DabBatch(capacity = 2)
            emitted += driver.resumeDabs(batch)
        }

        val beforePenUp = emitted
        var passes = 0
        while (driver.isActive && passes < 64) {
            batch = DabBatch(capacity = 2)
            emitted += driver.end(batch)
            passes++
        }

        assertTrue(!driver.isActive, "pen-up must finish after bounded resumptions")
        assertTrue(emitted - beforePenUp > 2, "the test must overflow its pen-up batch")
    }

    @Test
    fun `samples are ignored while pen-up catch-up is paused`() {
        val driver = driver(spacing = 0.5f, stabilizer = 1f)
        driver.begin(
            0f, 0f, 1f, 0f, 0f, 0L, StrokeSource.STYLUS, DabBatch(capacity = 2),
        )
        driver.sample(
            100f, 0f, 1f, 0f, 0f, 8_000_000L,
            StrokeSource.STYLUS, DabBatch(capacity = 2),
        )
        while (driver.hasPendingDabs) driver.resumeDabs(DabBatch(capacity = 2))

        driver.end(DabBatch(capacity = 2))
        assertTrue(driver.isActive, "the tiny batch must pause pen-up catch-up")

        val stray = DabBatch(capacity = 4096)
        val emitted = driver.sample(
            1_000f, 0f, 1f, 0f, 0f, 16_000_000L, StrokeSource.STYLUS, stray,
        )

        assertEquals(0, emitted, "pen-up owns the driver until catch-up completes")
        assertEquals(0, stray.count, "a late move must not add a backtracking segment")
    }


    @Test
    fun `an exact resume retains the current sample as the next segment`() {
        val driver = driver(spacing = 0.5f, stabilizer = 0f)
        driver.begin(
            0f, 0f, 1f, 0f, 0f, 0L, StrokeSource.STYLUS, DabBatch(capacity = 1),
        )
        driver.sample(
            20f, 0f, 1f, 0f, 0f, 8_000_000L,
            StrokeSource.STYLUS, DabBatch(capacity = 1),
        )
        assertTrue(driver.hasPendingDabs)

        val exact = DabBatch(capacity = 3)
        driver.sample(
            25f, 0f, 1f, 0f, 0f, 16_000_000L, StrokeSource.STYLUS, exact,
        )

        assertTrue(exact.isFull, "the prior suffix must exactly fill this batch")
        assertTrue(driver.hasPendingDabs, "the 25 px sample must remain pending")
        val current = DabBatch(capacity = 1)
        driver.resumeDabs(current)
        assertEquals(25f, current.x[0], 0.001f)
    }

    @Test
    fun `an exact resume retains a stationary ink sample`() {
        val ink = preset(spacing = 0.5f, stabilizer = 0f).copy(
            model = BrushModel.ChineseInk,
        )
        val reference = StrokeDriver(ink, seed = 1L)
        reference.begin(
            0f, 0f, 0.1f, 0f, 0f, 0L, StrokeSource.STYLUS, DabBatch(capacity = 1),
        )
        val allMoving = DabBatch(capacity = 4096)
        reference.sample(
            20f, 0f, 0.1f, 0f, 0f, 8_000_000L, StrokeSource.STYLUS, allMoving,
        )
        val suffixCount = allMoving.count - 1
        assertTrue(suffixCount > 0)

        val driver = StrokeDriver(ink, seed = 1L)
        driver.begin(
            0f, 0f, 0.1f, 0f, 0f, 0L, StrokeSource.STYLUS, DabBatch(capacity = 1),
        )
        driver.sample(
            20f, 0f, 0.1f, 0f, 0f, 8_000_000L,
            StrokeSource.STYLUS, DabBatch(capacity = 1),
        )

        val exact = DabBatch(capacity = suffixCount)
        driver.sample(
            20f, 0f, 1f, 0f, 0f, 16_000_000L, StrokeSource.STYLUS, exact,
        )

        assertTrue(exact.isFull)
        assertTrue(driver.hasPendingDabs, "the stationary pressure dab must remain pending")
        val pressureDab = DabBatch(capacity = 1)
        driver.resumeDabs(pressureDab)
        assertEquals(1, pressureDab.count)
        assertEquals(20f, pressureDab.x[0], 0.001f)
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
        assertSameDabs(a, b, "the same seed must reproduce the whole dab")
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
            assertEquals(expected.hardness[i], actual.hardness[i], 0f, "dab $i hardness: $what")
            assertEquals(expected.angle[i], actual.angle[i], 0f, "dab $i angle: $what")
            assertEquals(expected.aspect[i], actual.aspect[i], 0f, "dab $i aspect: $what")
            assertEquals(expected.seed[i], actual.seed[i], 0f, "dab $i seed: $what")
            assertEquals(expected.wetness[i], actual.wetness[i], 0f, "dab $i wetness: $what")
            assertEquals(
                expected.bristleAlong[i],
                actual.bristleAlong[i],
                0f,
                "dab $i bristle along: $what",
            )
            assertEquals(
                expected.bristleAcross[i],
                actual.bristleAcross[i],
                0f,
                "dab $i bristle across: $what",
            )
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
    fun `prediction waits for pending real dabs to drain`() {
        val driver = driver(spacing = 0.5f, stabilizer = 0f)
        driver.begin(
            0f, 0f, 1f, 0f, 0f, 0L, StrokeSource.STYLUS, DabBatch(capacity = 1),
        )
        driver.sample(
            100f, 0f, 1f, 0f, 0f, 8_000_000L,
            StrokeSource.STYLUS, DabBatch(capacity = 1),
        )
        assertTrue(driver.hasPendingDabs)

        val tail = batch()
        assertEquals(0, driver.predict(samples(100f, 120f, 0f, 8_000_000L, 3), tail))
        assertEquals(0, tail.count)
        assertEquals(-1, tail.predictedFrom, "a skipped prediction must leave the batch real")
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

        // ONE set of points, used by both halves. The test's whole premise is
        // "the same points fed as real samples", and two literal argument lists
        // let a later edit change one and not the other — which fails as
        // "dab count diverged", pointing nowhere near the cause. `predict` only
        // reads the batch, so one instance serves both.
        val points = samples(200f, 240f, 100f, 96_000_000L, 4)

        val tailDriver = StrokeDriver(hard, seed = 7L)
        val tailWarmup = batch()
        tailDriver.halfLine(tailWarmup, 100f, 200f, 100f, steps = 12)
        val tail = batch()
        val emitted = tailDriver.predict(points, tail)
        assertTrue(emitted > 0, "a 40 px continuation must produce a tail")

        // The twin: the same stroke, then the same points fed as REAL samples.
        val realDriver = StrokeDriver(hard, seed = 7L)
        val realWarmup = batch()
        realDriver.halfLine(realWarmup, 100f, 200f, 100f, steps = 12)
        assertSameDabs(tailWarmup, realWarmup, "the two twins must start identical")
        val real = batch()
        for (i in 0 until points.size) {
            val s = points[i]
            realDriver.sample(s.x, s.y, s.pressure, s.tilt, s.orientation, s.timeNs, s.source, real)
        }
        assertSameDabs(real, tail, "the tail must be the real stroke's own continuation")
    }

    @Test
    fun `a Chinese ink tail copies tuft state and transported bristle coordinates`() {
        val ink = preset(spacing = 0.25f, stabilizer = 0.6f, size = 40f).copy(
            tip = TipShape.Flat(0.58f),
            orientation = TipOrientation.StrokeDirection,
            velocity = VelocityEffect(sizeAtFast = 0.96f, fastPxPerMs = 2.5f),
            model = BrushModel.ChineseInk,
        )
        val points = samples(200f, 260f, 130f, 96_000_000L, 6)

        val tailDriver = StrokeDriver(ink, seed = 11L)
        val tailWarmup = batch()
        tailDriver.halfLine(tailWarmup, 100f, 200f, 100f, steps = 12)
        val tail = batch()
        assertTrue(tailDriver.predict(points, tail) > 0)

        val realDriver = StrokeDriver(ink, seed = 11L)
        realDriver.halfLine(batch(), 100f, 200f, 100f, steps = 12)
        val real = batch()
        for (i in 0 until points.size) {
            val sample = points[i]
            realDriver.sample(
                sample.x,
                sample.y,
                sample.pressure,
                sample.tilt,
                sample.orientation,
                sample.timeNs,
                sample.source,
                real,
            )
        }

        assertTrue(
            tail.bristleAlong[0] >= tailWarmup.bristleAlong[tailWarmup.count - 1],
            "an overdue first tail dab may share, but not rewind, its along phase",
        )
        assertTrue(tail.bristleAcross[0] > 0.01f, "the turning tail must transport across-axis motion")
        assertTrue(tail.wetness[0] < 1f, "the tail must continue the real stroke's ink load")
        assertSameDabs(real, tail, "Chinese ink prediction must copy every stateful field")
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

        val points = samples(200f, 260f, 100f, 96_000_000L, 4)

        val tailDriver = StrokeDriver(hard, seed = 7L)
        val tailWarmup = batch()
        tailDriver.halfLine(tailWarmup, 100f, 200f, 100f, steps = 12)
        // A tail BEFORE the zoom change, which is what makes this test bite:
        // it is what builds the tail's stabilizer, at the old zoom. A first
        // prediction after the change would be copy-constructed already
        // retuned, and the re-sync would have nothing to prove.
        tailDriver.predict(points, batch())
        tailDriver.setZoom(4f)
        val tail = batch()
        assertTrue(tailDriver.predict(points, tail) > 0)

        val realDriver = StrokeDriver(hard, seed = 7L)
        val realWarmup = batch()
        realDriver.halfLine(realWarmup, 100f, 200f, 100f, steps = 12)
        realDriver.setZoom(4f)
        val real = batch()
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

        val points = samples(200f, 240f, 100f, 96_000_000L, 4)
        val first = batch()
        val second = batch()
        d.predict(points, first)
        d.predict(points, second)
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
        // The contrast, so the two assertions above read as proof rather than
        // as a coincidence of the defaults: a cleared batch starts at
        // `predictedFrom = -1`, so a real batch commits everything it holds and
        // a `predict()` that never marked would leave the tail at -1 too.
        assertTrue(warmup.count > 0)
        assertEquals(-1, warmup.predictedFrom, "real dabs are not marked predicted")
        assertEquals(warmup.count, warmup.committedCount, "and all of them commit")
    }

    @Test
    fun `a batch that already carries a tail is refused`() {
        // `markPredictedFromHere` marks from the batch's CURRENT count, so a
        // leftover tail would end up below the new mark and be counted as
        // committed — merged into the layer at pen-up, which turns a guess into
        // permanent ink. Silent and unrecoverable, so it is a throw rather than
        // a comment.
        val d = driver()
        val out = batch()
        d.halfLine(out, 100f, 200f, 100f, steps = 12)
        val points = samples(200f, 260f, 100f, 96_000_000L, 4)
        d.predict(points, out)
        assertTrue(out.predictedFrom >= 0, "the first tail marked the batch")
        assertFailsWith<IllegalArgumentException> { d.predict(points, out) }
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

        // After `end()` as well — the state a straggler predicted frame really
        // arrives in, since the Choreographer callback is stopped from the same
        // decision that ends the stroke and one may already be in flight. A
        // tail appended here would be ink past the pen-up point.
        //
        // A STABILIZING driver, so `end()`'s leash flush actually emits into
        // `finishedOut`. With the default strength 0 the output is already on
        // the pen, `finish()` walks nothing and `end()` emits nothing, so the
        // count comparison below would be 0 against 0 — it would pass whether
        // or not `predict` appended, which is the vacuous shape this suite
        // keeps finding. The guard is what says so.
        val finished = driver(stabilizer = 0.7f)
        val finishedOut = batch()
        finished.halfLine(batch(), 100f, 200f, 100f, steps = 12)
        finished.end(finishedOut)
        val committed = finishedOut.count
        assertTrue(committed > 0, "the flush must have emitted something to be worth checking")
        assertEquals(
            0, finished.predict(samples(200f, 260f, 100f, 96_000_000L, 4), finishedOut), "after end",
        )
        assertEquals(committed, finishedOut.count, "an ended stroke must not grow a tail")
    }
}
