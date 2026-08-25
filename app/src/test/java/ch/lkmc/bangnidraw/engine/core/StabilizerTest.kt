package ch.lkmc.bangnidraw.engine.core

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `docs/plan/11-testing.md` §3.3, against `04-tools.md` §4. */
class StabilizerTest {

    private val eps = 1e-4f

    private fun sample(
        x: Float,
        y: Float,
        pressure: Float = 1f,
        tilt: Float = 0f,
        orientation: Float = 0f,
        timeNs: Long = 0L,
    ) = StrokeInput().apply { set(x, y, pressure, tilt, orientation, timeNs) }

    /** Feeds a path and returns every emitted output point. */
    private fun run(
        stabilizer: Stabilizer,
        path: List<StrokeInput>,
    ): List<Pair<Float, Float>> {
        val out = StrokeInput()
        val result = mutableListOf<Pair<Float, Float>>()
        stabilizer.reset(path.first())
        result += path.first().x to path.first().y
        for (i in 1 until path.size) {
            stabilizer.push(path[i], out)
            result += out.x to out.y
        }
        return result
    }

    @Test
    fun `strength zero passes samples through unchanged`() {
        val stabilizer = Stabilizer(strength = 0f)
        val out = StrokeInput()
        stabilizer.reset(sample(0f, 0f))
        for (i in 1..20) {
            // Every channel, not the four obvious ones: a pass-through that
            // dropped orientation or timeNs would otherwise still have passed,
            // and timeNs is what the velocity dynamics downstream are computed
            // from. The sweep stays inside (-pi, pi] because that is the
            // documented domain of `orientation` — the input layer wraps it
            // there (`07-input-and-stylus.md` §2). Wrap-around easing itself
            // is exercised by `the orientation eases the short way around the
            // circle` below, not here.
            val raw = sample(
                i * 7f,
                i * -3f,
                pressure = i / 20f,
                tilt = i / 40f,
                orientation = i / 10f,
                timeNs = i * 8_000_000L,
            )
            stabilizer.push(raw, out)
            assertEquals(raw.x, out.x, eps, "x at $i")
            assertEquals(raw.y, out.y, eps, "y at $i")
            assertEquals(raw.pressure, out.pressure, eps, "pressure at $i")
            assertEquals(raw.tilt, out.tilt, eps, "tilt at $i")
            assertEquals(raw.orientation, out.orientation, eps, "orientation at $i")
            assertEquals(raw.timeNs, out.timeNs, "timeNs at $i")
        }
    }

    @Test
    fun `the first sample is emitted unchanged`() {
        // No dead start: a tap lands exactly where it was made, at every
        // strength. A stabilizer that eased from some origin would put the
        // heaviest presets' first dab somewhere the user did not touch.
        for (strength in listOf(0f, 0.3f, 0.7f, 1f)) {
            val stabilizer = Stabilizer(strength)
            val out = StrokeInput()
            val first = sample(
                123.5f,
                -47.25f,
                pressure = 0.4f,
                tilt = 0.9f,
                orientation = 2.1f,
                timeNs = 1_234_567_890L,
            )
            stabilizer.reset(first)
            stabilizer.current(out)
            assertEquals(first.x, out.x, eps, "strength $strength")
            assertEquals(first.y, out.y, eps, "strength $strength")
            assertEquals(first.pressure, out.pressure, eps, "strength $strength")
            // tilt, orientation and timeNs too: reset is a field-by-field
            // copy, and a missed one would give a chisel tip the wrong angle on
            // the first dab of every stroke, or start every stroke's velocity
            // from a zero timestamp.
            assertEquals(first.tilt, out.tilt, eps, "strength $strength")
            assertEquals(first.orientation, out.orientation, eps, "strength $strength")
            assertEquals(first.timeNs, out.timeNs, "strength $strength")
        }
    }

    @Test
    fun `the stabilizer never overshoots`() {
        // Property: for an input path monotone in x, the output is monotone in
        // x and never passes the latest input. Overshoot would show up as the
        // brush swinging past the pen on every direction change.
        val random = Random(21)
        repeat(40) { trial ->
            val strength = random.nextFloat()
            val stabilizer = Stabilizer(strength)
            val out = StrokeInput()
            var x = 0f
            stabilizer.reset(sample(x, 0f))
            var previous = 0f
            repeat(120) {
                x += random.nextFloat() * 9f
                stabilizer.push(sample(x, random.nextFloat() * 4f), out)
                assertTrue(
                    out.x >= previous - eps,
                    "trial $trial (strength $strength): output went backwards, $previous -> ${out.x}",
                )
                assertTrue(
                    out.x <= x + eps,
                    "trial $trial (strength $strength): output ${out.x} passed the input $x",
                )
                previous = out.x
            }
        }
    }

    @Test
    fun `a sharp corner is rounded, not cut`() {
        // Right angle: out along +x, then up along +y. The output must stay
        // inside the corner (that is the rounding) and must never leave the
        // bounding box of the input polyline (that would be a cut across it).
        val path = buildList {
            for (i in 0..20) add(sample(i * 5f, 0f, timeNs = i * 8_000_000L))
            for (i in 1..20) add(sample(100f, i * 5f, timeNs = (20 + i) * 8_000_000L))
        }
        val points = run(Stabilizer(strength = 0.6f), path)
        var rounded = false
        for ((x, y) in points) {
            assertTrue(x <= 100f + eps, "output passed the corner in x: $x")
            assertTrue(y >= -eps && y <= 100f + eps, "output left the input's box in y: $y")
            // Inside the corner: any point with both a y above zero and an x
            // short of the corner is on the diagonal shortcut the rounding
            // makes, which a cut-through would never produce.
            if (y > 1f && x < 99f) rounded = true
        }
        assertTrue(rounded, "the corner was tracked exactly, not rounded")
    }

    @Test
    fun `on pen-up the output catches up to the last input exactly`() {
        // A stroke ends where the pen lifted, not where the leash was.
        val stabilizer = Stabilizer(strength = 0.8f)
        val out = StrokeInput()
        stabilizer.reset(sample(0f, 0f, pressure = 1f))
        for (i in 1..30) stabilizer.push(sample(i * 12f, 0f, pressure = 1f), out)

        stabilizer.current(out)
        val lagged = out.x
        assertTrue(lagged < 360f - 1f, "the premise: a strong stabilizer must be lagging here")

        val tail = mutableListOf<Float>()
        val emitted = stabilizer.finish(step = 2f, out = out) { tail += it.x }
        assertTrue(emitted > 0, "a lagging stabilizer must emit catch-up samples")
        assertEquals(emitted, tail.size, "every catch-up sample must reach the sink")
        assertEquals(360f, tail.last(), eps, "the stroke must end where the pen lifted")
        // Monotone toward the pen, so the tail is a line and not a jump back.
        for (i in 1 until tail.size) {
            assertTrue(tail[i] >= tail[i - 1] - eps, "the tail went backwards at $i")
        }
    }

    @Test
    fun `the catch-up tail decays pressure to the final raw pressure`() {
        // Otherwise the tail is drawn at the pressure from before the lift and
        // the stroke ends in a blob rather than a taper.
        val stabilizer = Stabilizer(strength = 0.8f)
        val out = StrokeInput()
        stabilizer.reset(sample(0f, 0f, pressure = 1f))
        for (i in 1..30) stabilizer.push(sample(i * 12f, 0f, pressure = 1f), out)
        val lift = sample(400f, 0f, pressure = 0.05f)
        stabilizer.push(lift, out)

        val pressures = mutableListOf<Float>()
        stabilizer.finish(step = 4f, out = out) { pressures += it.pressure }
        assertTrue(pressures.size >= 2, "the premise: this tail must have several samples")
        assertEquals(0.05f, pressures.last(), eps, "the tail must end at the lift pressure")
        for (i in 1 until pressures.size) {
            assertTrue(pressures[i] <= pressures[i - 1] + eps, "pressure rose during the taper")
        }
    }

    @Test
    fun `a pen-up with nothing to catch up emits nothing`() {
        val stabilizer = Stabilizer(strength = 0f)
        val out = StrokeInput()
        stabilizer.reset(sample(10f, 10f))
        stabilizer.push(sample(20f, 20f), out)
        var emitted = 0
        val count = stabilizer.finish(step = 1f, out = out) { emitted++ }
        assertEquals(0, count, "a pass-through stabilizer is already at the pen")
        assertEquals(0, emitted)
    }

    @Test
    fun `output is invariant under a canvas-space translation`() {
        // Property: the smoothing is a function of the path's shape, not of
        // where on the canvas it happens.
        val random = Random(5)
        repeat(20) {
            val strength = random.nextFloat()
            val dx = (random.nextFloat() - 0.5f) * 4000f
            val dy = (random.nextFloat() - 0.5f) * 4000f
            val path = List(60) {
                sample(random.nextFloat() * 300f, random.nextFloat() * 300f, timeNs = it * 8L)
            }
            val shifted = path.map { sample(it.x + dx, it.y + dy, timeNs = it.timeNs) }
            val a = run(Stabilizer(strength), path)
            val b = run(Stabilizer(strength), shifted)
            for (i in a.indices) {
                assertEquals(a[i].first + dx, b[i].first, 1e-2f, "x at $i, strength $strength")
                assertEquals(a[i].second + dy, b[i].second, 1e-2f, "y at $i, strength $strength")
            }
        }
    }

    @Test
    fun `pressure and tilt are smoothed with the same window as position`() {
        // A taper has to follow the smoothed geometry. If pressure eased
        // faster than position, the stroke would thin before it turned.
        val stabilizer = Stabilizer(strength = 0.5f)
        val out = StrokeInput()
        // A path whose x and pressure both step by the same normalized amount,
        // so the two can be compared directly.
        stabilizer.reset(sample(0f, 0f, pressure = 0f, tilt = 0f))
        stabilizer.push(sample(1f, 0f, pressure = 1f, tilt = 1f), out)
        assertEquals(out.x, out.pressure, eps, "pressure lagged differently from position")
        assertEquals(out.x, out.tilt, eps, "tilt lagged differently from position")
    }

    @Test
    fun `the leash bounds the lag however fast the pen moves`() {
        // The point of the string, as opposed to plain exponential smoothing:
        // a fast stroke does not fall ever further behind.
        val stabilizer = Stabilizer(strength = 1f)
        val out = StrokeInput()
        stabilizer.reset(sample(0f, 0f))
        var x = 0f
        repeat(200) {
            x += 500f
            stabilizer.push(sample(x, 0f), out)
            assertTrue(
                x - out.x <= stabilizer.leash + eps,
                "lag ${x - out.x} exceeded the leash ${stabilizer.leash}",
            )
        }
    }

    @Test
    fun `zoom shrinks both the leash and the smoothing`() {
        // `11-testing.md` §3.3 and `04-tools.md` §4: at 4x the raw jitter is
        // already 4x smaller on paper, so full stabilization there feels like
        // drawing in syrup.
        val atOne = Stabilizer(strength = 0.8f, zoom = 1f)
        val atFour = Stabilizer(strength = 0.8f, zoom = 4f)
        assertTrue(atFour.leash < atOne.leash, "the leash must shrink with zoom")
        assertTrue(
            (1f - atFour.k) < (1f - atOne.k),
            "the smoothing must weaken with zoom: 1-k was ${1f - atFour.k} vs ${1f - atOne.k}",
        )
        assertTrue(atFour.effectiveStrength < atOne.effectiveStrength)
    }

    @Test
    fun `the zoom nudge stops at its floor`() {
        // Otherwise a deep zoom would turn every preset into pass-through and
        // the ink pen would wobble exactly where it is least forgivable.
        val deep = Stabilizer(strength = 1f, zoom = 64f)
        assertEquals(Stabilizer.MIN_ZOOM_FACTOR, deep.effectiveStrength, eps)
        assertTrue(deep.k < 1f, "even at a deep zoom some smoothing must remain")
    }

    @Test
    fun `retuning mid-stroke does not move the brush`() {
        val stabilizer = Stabilizer(strength = 0.7f, zoom = 1f)
        val out = StrokeInput()
        stabilizer.reset(sample(0f, 0f))
        repeat(10) { stabilizer.push(sample(it * 20f, 0f), out) }
        stabilizer.current(out)
        val before = out.x to out.y
        stabilizer.retune(strength = 0.1f, zoom = 4f)
        stabilizer.current(out)
        assertEquals(before.first, out.x, eps, "retune moved the brush in x")
        assertEquals(before.second, out.y, eps, "retune moved the brush in y")
    }

    @Test
    fun `a copy continues the line without advancing the original`() {
        // The predicted tail runs through a copy (`03-canvas-engine.md` §9).
        // If it shared state, every predicted sample would drag the real
        // stroke forward and the committed line would overshoot the pen.
        val stabilizer = Stabilizer(strength = 0.6f)
        val out = StrokeInput()
        stabilizer.reset(sample(0f, 0f))
        repeat(10) { stabilizer.push(sample(it * 15f, 0f), out) }
        stabilizer.current(out)
        val realX = out.x

        val predictor = stabilizer.copy()
        val predictedOut = StrokeInput()
        predictor.current(predictedOut)
        assertEquals(realX, predictedOut.x, eps, "a copy must start exactly where the original is")

        repeat(5) { predictor.push(sample(500f + it * 30f, 0f), predictedOut) }
        assertTrue(predictedOut.x > realX, "the premise: the tail must have moved on")
        stabilizer.current(out)
        assertEquals(realX, out.x, eps, "the tail advanced the real stabilizer")
    }

    @Test
    fun `a sample that barely moves the output is reported as such`() {
        // The caller drops it rather than stacking a dab on the last one. The
        // state still advances, so the motion is not lost, only the sample.
        val stabilizer = Stabilizer(strength = 0.9f)
        val out = StrokeInput()
        stabilizer.reset(sample(0f, 0f))
        assertTrue(!stabilizer.push(sample(0.001f, 0f), out), "a sub-threshold move must report false")
        assertTrue(stabilizer.push(sample(400f, 0f), out), "a real move must report true")
    }

    @Test
    fun `the orientation eases the short way around the circle`() {
        // A pen crossing from +pi to -pi must not spin a chisel tip through
        // half a turn on one sample.
        val stabilizer = Stabilizer(strength = 0.5f)
        val out = StrokeInput()
        val nearPi = (Math.PI - 0.05).toFloat()
        val nearMinusPi = (-Math.PI + 0.05).toFloat()
        stabilizer.reset(sample(0f, 0f, orientation = nearPi))
        stabilizer.push(sample(1f, 0f, orientation = nearMinusPi), out)
        // The short way is 0.1 rad across the branch cut; the long way is
        // about 6.18. Half of the short way keeps |orientation| near pi.
        assertTrue(
            abs(out.orientation) > Math.PI - 0.1,
            "orientation took the long way round: ${out.orientation}",
        )
    }

    @Test
    fun `invalid strength, zoom or catch-up step is refused`() {
        assertFailsWith<IllegalArgumentException> { Stabilizer(strength = -0.1f) }
        assertFailsWith<IllegalArgumentException> { Stabilizer(strength = 1.1f) }
        assertFailsWith<IllegalArgumentException> { Stabilizer(strength = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Stabilizer(strength = 0.5f, zoom = 0f) }
        assertFailsWith<IllegalArgumentException> { Stabilizer(strength = 0.5f, zoom = -2f) }
        val stabilizer = Stabilizer(strength = 0.5f)
        stabilizer.reset(sample(0f, 0f))
        val out = StrokeInput()
        assertFailsWith<IllegalArgumentException> { stabilizer.finish(0f, out) {} }
        assertFailsWith<IllegalArgumentException> { stabilizer.finish(-1f, out) {} }
    }

    @Test
    fun `the leash is applied before the ease, not after`() {
        // Order matters and is easy to swap. Easing first and snapping after
        // would park a fast stroke permanently at the end of the leash with no
        // smoothing left to give, so the lag would sit exactly at `leash`
        // every sample instead of staying below it.
        val stabilizer = Stabilizer(strength = 0.9f)
        val out = StrokeInput()
        stabilizer.reset(sample(0f, 0f))
        var x = 0f
        val lags = mutableListOf<Float>()
        repeat(60) {
            x += 300f
            stabilizer.push(sample(x, 0f), out)
            lags += x - out.x
        }
        val settled = lags.takeLast(20)
        assertTrue(
            settled.all { it < stabilizer.leash - eps },
            "a settled fast stroke should lag strictly less than the leash, saw $settled",
        )
    }

    @Test
    fun `k and the leash follow their stated formulas`() {
        // These two numbers are the whole feel of every preset's stabilizer
        // slider, and nothing else in the suite would notice a changed
        // constant.
        for (strength in listOf(0f, 0.25f, 0.5f, 0.7f, 1f)) {
            val expectedK = 1f - sqrt(strength) * (1f - Stabilizer.MIN_K)
            assertEquals(expectedK, Stabilizer.kOf(strength), eps, "k at $strength")
        }
        assertEquals(1f, Stabilizer.kOf(0f), eps, "strength 0 must pass through")
        assertEquals(Stabilizer.MIN_K, Stabilizer.kOf(1f), eps, "strength 1 must be the heaviest")
        assertEquals(
            Stabilizer.LEASH_PX_AT_FULL,
            Stabilizer.leashOf(1f, zoom = 1f),
            eps,
            "the leash at full strength and no zoom",
        )
        assertEquals(0f, Stabilizer.leashOf(0f, zoom = 1f), eps, "no strength, no leash")
    }
}
