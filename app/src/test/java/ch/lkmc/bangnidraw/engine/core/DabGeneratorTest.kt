package ch.lkmc.bangnidraw.engine.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `docs/plan/11-testing.md` §3.4, against `04-tools.md` §3. */
class DabGeneratorTest {

    private val pxEps = 1e-2f

    private val plain = BrushPreset(
        id = "test.plain",
        name = "Plain",
        size = 20f,
        sizeMin = 1f,
        sizeMax = 200f,
        spacing = 0.5f,
        pressureSize = Curve.One,
        pressureFlow = Curve.One,
        pressureOpacity = Curve.One,
    )

    private fun sample(
        x: Float,
        y: Float,
        pressure: Float = 1f,
        tilt: Float = 0f,
        orientation: Float = 0f,
        timeMs: Long = 0L,
    ) = StrokeInput().apply {
        set(x, y, pressure, tilt, orientation, timeMs * 1_000_000L)
    }

    /** Feeds a whole path through one generator and returns every dab. */
    private fun run(
        preset: BrushPreset,
        path: List<StrokeInput>,
        seed: Long = 1L,
        capacity: Int = 4096,
        end: Boolean = true,
    ): List<Dab> {
        val generator = DabGenerator(preset, seed)
        val batch = DabBatch(capacity)
        generator.begin(path.first(), batch)
        for (i in 1 until path.size) generator.advance(path[i], batch)
        if (end) generator.end(batch)
        return batch.toList()
    }

    private fun straightPath(
        from: Float,
        to: Float,
        steps: Int,
        pressure: Float = 1f,
        msPerStep: Long = 8L,
    ) = List(steps + 1) {
        val t = it.toFloat() / steps
        sample(from + (to - from) * t, 0f, pressure = pressure, timeMs = it * msPerStep)
    }

    // ---------------------------------------------------------------- taps

    @Test
    fun `a tap yields exactly one dab`() {
        val single = run(plain, listOf(sample(50f, 60f)))
        assertEquals(1, single.size, "one down sample is one dab")
        assertEquals(50f, single[0].x, pxEps)
        assertEquals(60f, single[0].y, pxEps)

        val downUp = run(plain, listOf(sample(50f, 60f), sample(50f, 60f, timeMs = 20)))
        assertEquals(1, downUp.size, "down and up at the same point is still one dab")
    }

    @Test
    fun `a zero-length move emits no additional dab`() {
        // A stationary pen can press harder without moving; the dynamics must
        // carry forward but nothing may be emitted, or the batch would fill
        // with dabs stacked on one point.
        val path = listOf(
            sample(10f, 10f, pressure = 0.2f),
            sample(10f, 10f, pressure = 0.5f, timeMs = 8),
            sample(10f, 10f, pressure = 0.9f, timeMs = 16),
        )
        assertEquals(1, run(plain, path).size)
    }

    @Test
    fun `a stroke shorter than one step does not double-dot`() {
        // `04` §3.4: `begin` already placed a dab, so the residual carry must
        // not emit one on top of it.
        val step = plain.spacing * plain.baseRadius
        assertTrue(step > 1f, "the premise: this preset's step must exceed the move below")
        val dabs = run(plain, listOf(sample(0f, 0f), sample(0.5f, 0f, timeMs = 8)))
        assertEquals(1, dabs.size, "a sub-step stroke is one dot, not two")
    }

    @Test
    fun `a tap with a pressure ramp is redrawn at the pressure it reached`() {
        // The ACTION_DOWN sample of an S Pen almost always reports near-zero
        // pressure. Without this the tap leaves an invisible speck.
        val preset = plain.copy(pressureSize = Curve.Linear, pressureFlow = Curve.Linear)
        val generator = DabGenerator(preset, seed = 1L)
        val batch = DabBatch(64)
        generator.begin(sample(10f, 10f, pressure = 0.02f), batch)
        generator.advance(sample(10.2f, 10f, pressure = 0.9f, timeMs = 12), batch)
        val faint = batch[0]
        generator.end(batch)
        val fixed = batch[0]
        assertEquals(1, batch.count, "the tap must still be one dab")
        assertTrue(
            fixed.radius > faint.radius,
            "the tap should have been redrawn wider: ${faint.radius} -> ${fixed.radius}",
        )
        assertTrue(fixed.flow > faint.flow, "and darker: ${faint.flow} -> ${fixed.flow}")
    }

    @Test
    fun `a real stroke is not rewritten by end`() {
        val before = run(plain, straightPath(0f, 200f, 20), end = false)
        val after = run(plain, straightPath(0f, 200f, 20), end = true)
        assertEquals(before, after, "end() must only fix up taps")
    }

    // ------------------------------------------------------------- spacing

    @Test
    fun `spacing is measured along the path, not per input sample`() {
        // A fast stroke with four samples and a slow one with forty over the
        // same geometry must produce the same dabs. This is the property that
        // makes a stroke look the same however the digitizer batches it.
        val few = run(plain, straightPath(0f, 300f, 4, msPerStep = 80))
        val many = run(plain, straightPath(0f, 300f, 40, msPerStep = 8))
        assertEquals(few.size, many.size, "sample count changed the dab count")
        for (i in few.indices) {
            assertEquals(few[i].x, many[i].x, pxEps, "dab $i moved")
        }
    }

    @Test
    fun `leftover distance carries across batches`() {
        // Splitting one input list into two must give the same dabs as one
        // pass; otherwise the spacing restarts at every MotionEvent and dabs
        // cluster at the sample points.
        val path = straightPath(0f, 300f, 24)
        val whole = run(plain, path)

        val generator = DabGenerator(plain, seed = 1L)
        val split = mutableListOf<Dab>()
        val first = DabBatch(4096)
        generator.begin(path[0], first)
        for (i in 1..12) generator.advance(path[i], first)
        split += first.toList()
        val second = DabBatch(4096)
        for (i in 13 until path.size) generator.advance(path[i], second)
        generator.end(second)
        split += second.toList()

        assertEquals(whole.size, split.size, "splitting changed the dab count")
        for (i in whole.indices) {
            assertEquals(whole[i].x, split[i].x, pxEps, "dab $i moved when the input was split")
        }
    }

    @Test
    fun `consecutive dabs sit one step apart`() {
        val dabs = run(plain, straightPath(0f, 400f, 40))
        val step = plain.spacing * plain.baseRadius
        assertTrue(dabs.size > 10, "the premise: this stroke must produce many dabs")
        for (i in 1 until dabs.size) {
            val gap = hypot(dabs[i].x - dabs[i - 1].x, dabs[i].y - dabs[i - 1].y)
            assertEquals(step, gap, pxEps, "gap between dab ${i - 1} and $i")
        }
    }

    @Test
    fun `dab spacing is invariant under canvas scale`() {
        // The test that catches "the brush goes sparse when you zoom in".
        // The same *screen* gesture at view scale 1 and 4 arrives as canvas
        // paths differing by that factor, and the dab count per canvas unit of
        // radius must be identical.
        for (scale in listOf(1f, 4f, 0.25f)) {
            val path = List(41) { sample(it * 10f / scale, 0f, timeMs = it * 8L) }
            val dabs = run(plain, path)
            val length = 400f / scale
            val step = plain.spacing * plain.baseRadius
            val expected = (length / step).toInt() + 1
            assertTrue(
                abs(dabs.size - expected) <= 1,
                "at scale $scale: got ${dabs.size} dabs, expected about $expected",
            )
            for (i in 1 until dabs.size) {
                val gap = dabs[i].x - dabs[i - 1].x
                assertEquals(step, gap, pxEps, "at scale $scale, gap $i")
            }
        }
    }

    @Test
    fun `the step never goes below half a pixel`() {
        // `04` §3.1's floor. Without it a hair-thin brush at tight spacing
        // would emit thousands of dabs per pixel and fill the ring.
        val hair = plain.copy(size = 1f, sizeMin = 1f, spacing = 0.01f)
        val dabs = run(hair, straightPath(0f, 100f, 10))
        assertTrue(
            dabs.size <= 100 / 0.5f + 2,
            "a hair brush emitted ${dabs.size} dabs over 100 px, past the half-pixel floor",
        )
    }

    // ------------------------------------------------------------ dynamics

    @Test
    fun `pressure maps through the preset's curves for size, flow and opacity`() {
        // Each independently: a preset that put pressure into flow must not
        // also widen, and the opacity ceiling is a per-stroke number, not a
        // per-dab one.
        val sizeOnly = plain.copy(pressureSize = Curve.Linear)
        val light = run(sizeOnly, straightPath(0f, 200f, 20, pressure = 0.2f))
        val heavy = run(sizeOnly, straightPath(0f, 200f, 20, pressure = 1f))
        assertTrue(light[0].radius < heavy[0].radius, "pressure must widen this preset")
        assertEquals(light[0].flow, heavy[0].flow, 1e-4f, "and must not darken it")

        val flowOnly = plain.copy(pressureFlow = Curve.Linear)
        val lightFlow = run(flowOnly, straightPath(0f, 200f, 20, pressure = 0.2f))
        val heavyFlow = run(flowOnly, straightPath(0f, 200f, 20, pressure = 1f))
        assertTrue(lightFlow[0].flow < heavyFlow[0].flow, "pressure must darken this preset")
        assertEquals(lightFlow[0].radius, heavyFlow[0].radius, 1e-4f, "and must not widen it")
    }

    @Test
    fun `the stroke opacity ceiling is the maximum pressure seen, not the last`() {
        // `04` §3.3: a stroke that starts light and presses hard ends up at the
        // hard-pressure opacity *everywhere*. The alternative — the latest
        // value — would change the ceiling mid-stroke and leave a seam.
        val preset = plain.copy(pressureOpacity = Curve.Linear)
        val generator = DabGenerator(preset, seed = 1L)
        val batch = DabBatch(4096)
        generator.begin(sample(0f, 0f, pressure = 0.1f), batch)
        generator.advance(sample(100f, 0f, pressure = 1f, timeMs = 8), batch)
        generator.advance(sample(200f, 0f, pressure = 0.1f, timeMs = 16), batch)
        generator.end(batch)
        assertEquals(1f, generator.pressureOpacityMax, 1e-3f, "the ceiling must remember the peak")
    }

    @Test
    fun `radius never leaves the preset's own range`() {
        // Property over random pressure, tilt and velocity: every multiplier
        // in the chain is applied before the clamp, so no combination of them
        // can produce a dab the preset does not allow.
        val random = Random(19)
        val wild = plain.copy(
            size = 20f,
            sizeMin = 4f,
            sizeMax = 40f,
            pressureSize = Curve.Linear,
            tilt = TiltEffect(sizeAtFlat = 4f, elongate = true),
            velocity = VelocityEffect(sizeAtFast = 0.3f, fastPxPerMs = 1f),
            jitter = Jitter(size = 0.9f, position = 0.5f),
        )
        repeat(30) { trial ->
            val path = List(30) {
                sample(
                    it * random.nextFloat() * 40f,
                    it * random.nextFloat() * 40f,
                    pressure = random.nextFloat(),
                    tilt = random.nextFloat() * (Math.PI / 2).toFloat(),
                    timeMs = it * (1L + random.nextInt(20)),
                )
            }
            for (dab in run(wild, path, seed = trial.toLong())) {
                assertTrue(
                    dab.radius >= wild.sizeMin / 2f - 1e-4f,
                    "trial $trial: radius ${dab.radius} below sizeMin/2 ${wild.sizeMin / 2f}",
                )
                assertTrue(
                    dab.radius <= wild.sizeMax / 2f + 1e-4f,
                    "trial $trial: radius ${dab.radius} above sizeMax/2 ${wild.sizeMax / 2f}",
                )
                assertTrue(dab.flow in 0f..1f, "trial $trial: flow ${dab.flow} left 0..1")
                assertTrue(dab.aspect in 0f..1f, "trial $trial: aspect ${dab.aspect} left 0..1")
            }
        }
    }

    @Test
    fun `tilt widens and lightens a pencil-like preset`() {
        val pencil = plain.copy(
            tilt = TiltEffect(sizeAtFlat = 2.2f, opacityAtFlat = 0.5f, elongate = false),
        )
        val upright = run(pencil, straightPath(0f, 200f, 20))
        val flat = run(
            pencil,
            List(21) { sample(it * 10f, 0f, tilt = (Math.PI / 2).toFloat(), timeMs = it * 8L) },
        )
        assertTrue(flat[0].radius > upright[0].radius * 2f, "a flat pencil must be much wider")
        assertTrue(flat[0].flow < upright[0].flow, "and lighter")
    }

    @Test
    fun `elongation stretches along the tilt azimuth and leaves the minor axis`() {
        // The side of the lead: the major axis grows, the minor does not, so
        // the stored radius (the major semi-axis) scales up and the aspect
        // scales down by the same factor.
        val pencil = plain.copy(tilt = TiltEffect(elongate = true))
        val azimuth = 0.9f
        val upright = run(pencil, straightPath(0f, 200f, 20))[0]
        val flat = run(
            pencil,
            List(21) {
                sample(
                    it * 10f,
                    0f,
                    tilt = (Math.PI / 2).toFloat(),
                    orientation = azimuth,
                    timeMs = it * 8L,
                )
            },
        )[0]
        assertEquals(1f, upright.aspect, 1e-4f, "an upright round tip stays round")
        assertEquals(2f, flat.radius / upright.radius, 1e-3f, "the major axis must double at full tilt")
        assertEquals(0.5f, flat.aspect, 1e-3f, "the minor axis must be unchanged")
        assertEquals(azimuth, flat.angle, 1e-4f, "elongation must align to the tilt azimuth")
    }

    @Test
    fun `velocity dynamics are computed from canvas-space speed`() {
        // So zoom does not change the feel: the same canvas geometry covered
        // in the same time gives the same multiplier whatever the view scale.
        val quick = plain.copy(velocity = VelocityEffect(sizeAtFast = 0.5f, fastPxPerMs = 2f))
        val slow = run(quick, straightPath(0f, 300f, 30, msPerStep = 100))
        val fast = run(quick, straightPath(0f, 300f, 30, msPerStep = 1))
        assertTrue(
            fast.last().radius < slow.last().radius,
            "a fast stroke must thin: ${fast.last().radius} vs ${slow.last().radius}",
        )
    }

    @Test
    fun `two samples sharing a timestamp keep the previous velocity`() {
        // Some devices give every historical sample the batch's event time.
        // Dividing by that zero would be an infinite speed and a dab clamped
        // to the wrong end of its range.
        val quick = plain.copy(velocity = VelocityEffect(sizeAtFast = 0.5f, fastPxPerMs = 2f))

        // Cold start: nothing has moved yet, so the velocity must *stay* at
        // zero rather than dividing by the zero delta and snapping to the fast
        // end of the range.
        val cold = run(quick, List(20) { sample(it * 15f, 0f, timeMs = 0L) })
        assertTrue(cold.isNotEmpty())
        for (dab in cold) {
            assertEquals(plain.baseRadius, dab.radius, 1e-3f, "a shared timestamp gave ${dab.radius}")
        }

        // And mid-stroke, which is what the name actually promises: the walk
        // above cannot tell "keep the previous velocity" from "reset it to
        // zero", because the previous velocity is zero in both readings. Give
        // the stroke a real speed first, then freeze the clock: keeping it
        // leaves the stroke thinned, resetting widens it back out. A device
        // that stamps a whole historical run with the batch's event time would
        // otherwise visibly fatten a stroke mid-gesture with nothing failing.
        val warm = listOf(sample(0f, 0f), sample(30f, 0f, timeMs = 10))
        val frozen = List(20) { sample(30f + it * 15f, 0f, timeMs = 10L) }
        val dabs = run(quick, warm + frozen)
        assertTrue(
            dabs.last().radius < plain.baseRadius - 1e-3f,
            "the frozen clock reset the velocity instead of keeping it: ${dabs.last().radius}",
        )
    }

    // -------------------------------------------------------------- shape

    @Test
    fun `jitter is deterministic for a given stroke seed`() {
        val jittery = plain.copy(jitter = Jitter(size = 0.4f, position = 0.3f))
        val path = straightPath(0f, 300f, 30)
        val a = run(jittery, path, seed = 99L)
        val b = run(jittery, path, seed = 99L)
        assertEquals(a, b, "the same seed must replay the same stroke exactly")
        val c = run(jittery, path, seed = 100L)
        assertTrue(a != c, "a different seed must produce a different stroke")
    }

    @Test
    fun `jitter moves dabs without changing how many there are`() {
        // `04` §3.2: jitter perturbs where a dab is painted, not how far along
        // the path we are. If it fed back into the spacing, the dab count
        // would depend on the random stream and the batch-split property
        // would stop holding.
        val path = straightPath(0f, 300f, 30)
        val clean = run(plain, path)
        val jittery = run(plain.copy(jitter = Jitter(size = 0.9f, position = 0.5f)), path, seed = 7L)
        assertEquals(clean.size, jittery.size, "jitter changed the dab count")
        assertTrue(
            jittery.indices.any { abs(jittery[it].x - clean[it].x) > pxEps },
            "the premise: jitter must actually move something",
        )
    }

    @Test
    fun `a marker dab's angle follows the stylus orientation`() {
        val marker = plain.copy(
            tip = TipShape.Flat(0.3f),
            orientation = TipOrientation.Stylus,
        )
        val dabs = run(
            marker,
            List(21) { sample(it * 10f, 0f, orientation = 1.1f, timeMs = it * 8L) },
        )
        for (dab in dabs) {
            assertEquals(1.1f, dab.angle, 1e-3f, "a stylus-oriented tip must follow the pen")
            assertEquals(0.3f, dab.aspect, 1e-4f, "a flat tip keeps its aspect")
        }
    }

    @Test
    fun `a stroke-direction tip follows the path`() {
        val brush = plain.copy(
            tip = TipShape.Flat(0.7f),
            orientation = TipOrientation.StrokeDirection,
        )
        val right = run(brush, List(21) { sample(it * 10f, 0f, timeMs = it * 8L) })
        val down = run(brush, List(21) { sample(0f, it * 10f, timeMs = it * 8L) })
        // The first dab comes from `begin`, before any direction exists; the
        // ones after it come from a segment and must carry its angle.
        assertEquals(0f, right[1].angle, 1e-3f, "rightward is angle 0")
        assertEquals((Math.PI / 2).toFloat(), down[1].angle, 1e-3f, "downward is angle pi/2")
    }

    @Test
    fun `a fixed round tip is angle zero and aspect one`() {
        for (dab in run(plain, straightPath(0f, 200f, 20))) {
            assertEquals(0f, dab.angle, "a fixed tip must not rotate")
            assertEquals(1f, dab.aspect, "a round tip must stay round")
        }
    }

    @Test
    fun `erase-mode presets emit the same geometry as their non-erase twin`() {
        // `04` §3.7: identical dab generation and stroke buffer; only the
        // merge differs. If erase changed the geometry, an eraser would not
        // remove exactly what the same brush would have painted.
        val path = straightPath(0f, 300f, 30)
        val painted = run(plain, path, seed = 5L)
        val erased = run(plain.copy(eraseMode = true), path, seed = 5L)
        assertEquals(painted, erased, "erase mode must not touch the dabs")
    }

    // --------------------------------------------------------------- misc

    @Test
    fun `dabs carry the batch's dirty rect`() {
        val batch = DabBatch(4096)
        val generator = DabGenerator(plain, seed = 1L)
        val path = straightPath(0f, 200f, 20)
        generator.begin(path[0], batch)
        for (i in 1 until path.size) generator.advance(path[i], batch)
        generator.end(batch)
        val r = batch.dirty
        assertTrue(!r.isEmpty, "a stroke must dirty something")
        for (i in 0 until batch.count) {
            val dab = batch[i]
            assertTrue(dab.x - dab.radius >= r.left - 1f, "dab $i escaped the dirty rect on the left")
            assertTrue(dab.x + dab.radius <= r.right + 1f, "dab $i escaped it on the right")
            // The vertical extent too. This path runs along y = 0, so a union
            // that only accumulated x would still cover every dab horizontally
            // and leave ghosting above and below the repainted strip.
            assertTrue(dab.y - dab.radius >= r.top - 1f, "dab $i escaped it on the top")
            assertTrue(dab.y + dab.radius <= r.bottom + 1f, "dab $i escaped it on the bottom")
        }
    }

    @Test
    fun `a full batch stops the generator rather than losing count`() {
        // The producer publishes and takes the next ring slot; the dabs that
        // did land must be intact.
        val batch = DabBatch(capacity = 4)
        val generator = DabGenerator(plain, seed = 1L)
        val path = straightPath(0f, 1000f, 100)
        generator.begin(path[0], batch)
        for (i in 1 until path.size) generator.advance(path[i], batch)
        assertEquals(4, batch.count, "the batch must fill exactly to capacity")
        assertEquals(4, generator.dabCount, "the generator must not count dabs it could not place")
        // And the dabs that landed are the right ones. Counting alone would
        // pass for a batch whose slots were overwritten or left unwritten,
        // which is the realistic failure when a ring buffer hits capacity.
        // Two invariants ride on this expression, so a future change to either
        // fails here rather than somewhere less obvious: the stroke's first dab
        // is at the start point, not one spacing in, and the step is a fraction
        // of the *radius*, not the diameter.
        val step = plain.spacing * plain.baseRadius
        for (i in 0 until batch.count) {
            assertEquals(i * step, batch[i].x, pxEps, "dab $i must have landed at its step")
        }
    }

    @Test
    fun `the generator's step matches what the stabilizer catch-up should use`() {
        // `Stabilizer.finish` walks the tail in steps of the current dab
        // spacing. If these two disagreed the tail would be denser or sparser
        // than the rest of the stroke, and the seam would be visible.
        val generator = DabGenerator(plain, seed = 1L)
        val batch = DabBatch(64)
        generator.begin(sample(0f, 0f), batch)
        generator.advance(sample(100f, 0f, timeMs = 8), batch)
        assertEquals(plain.spacing * plain.baseRadius, generator.currentStep(), 1e-4f)
    }

    @Test
    fun `beginning a second stroke forgets the first one entirely`() {
        // Every accumulator, not most of them: a leftover peak pressure would
        // make the next tap inherit the previous stroke's width, which is the
        // kind of bug that only shows up as "sometimes the first dot is fat".
        val preset = plain.copy(pressureSize = Curve.Linear, pressureOpacity = Curve.Linear)
        val generator = DabGenerator(preset, seed = 1L)
        val heavy = DabBatch(4096)
        generator.begin(sample(0f, 0f, pressure = 1f), heavy)
        generator.advance(sample(300f, 0f, pressure = 1f, timeMs = 8), heavy)
        generator.end(heavy)
        assertEquals(1f, generator.pressureOpacityMax, 1e-3f, "the premise: a heavy first stroke")

        val light = DabBatch(4096)
        generator.begin(sample(0f, 0f, pressure = 0.1f), light)
        generator.advance(sample(0.2f, 0f, pressure = 0.1f, timeMs = 8), light)
        generator.end(light)
        assertTrue(
            generator.pressureOpacityMax < 0.2f,
            "the second stroke inherited the first's ceiling: ${generator.pressureOpacityMax}",
        )
        val fresh = run(preset, listOf(sample(0f, 0f, pressure = 0.1f)))
        assertEquals(fresh[0].radius, light[0].radius, 1e-4f, "and its width")
    }

    @Test
    fun `a brush smaller than the engine can draw still draws`() {
        // `BrushPreset.MIN_SIZE` is half a pixel of *diameter* while
        // `Dab.MIN_RADIUS` is half a pixel of *radius*, so a legal preset can
        // ask for a brush the shader floors. That must be the smallest dab, not
        // a crash: this range used to make the generator's own min exceed its
        // max and every `coerceIn` threw on the first dab of every stroke.
        val subPixel = BrushPreset(id = "t.tiny", name = "Tiny", size = 0.55f, sizeMin = 0.5f, sizeMax = 0.6f)
        val dabs = run(subPixel, straightPath(0f, 40f, 20))
        assertTrue(dabs.isNotEmpty(), "a sub-pixel brush must still leave dabs")
        for (dab in dabs) {
            assertEquals(Dab.MIN_RADIUS, dab.radius, 1e-4f, "the smallest dab the shader can draw")
        }
    }

    @Test
    fun `a NaN pressure is no pressure, and the stroke keeps going`() {
        // The digitizer can report one. Every pressure path funnels through
        // `Curve.lookup`, which maps NaN to the curve at x = 0, so the sample
        // is treated as no pressure rather than propagating into `step` — where
        // a NaN would make `carry` NaN and silently end the stroke's dabs for
        // good, with nothing failing.
        val preset = plain.copy(pressureSize = Curve.Linear, pressureFlow = Curve.Linear)
        val generator = DabGenerator(preset, seed = 1L)
        val batch = DabBatch(4096)
        generator.begin(sample(0f, 0f), batch)
        generator.advance(sample(50f, 0f, timeMs = 8), batch)
        val beforeNaN = batch.count
        generator.advance(sample(100f, 0f, pressure = Float.NaN, timeMs = 16), batch)
        val afterNaN = batch.count
        generator.advance(sample(150f, 0f, timeMs = 24), batch)
        assertTrue(afterNaN > beforeNaN, "the NaN segment must still emit dabs")
        assertTrue(batch.count > afterNaN, "and the stroke must keep emitting after it")
        for (i in 0 until batch.count) {
            val dab = batch[i]
            assertTrue(dab.radius.isFinite(), "dab $i has a ${dab.radius} radius")
            assertTrue(dab.flow.isFinite(), "dab $i has a ${dab.flow} flow")
            assertTrue(dab.x.isFinite() && dab.y.isFinite(), "dab $i is at ${dab.x},${dab.y}")
        }
    }

    @Test
    fun `advancing without beginning starts the stroke`() {
        // Defensive, but the input path can drop an ACTION_DOWN when a
        // gesture is reclassified mid-flight, and losing the whole stroke
        // would be worse than starting it late.
        val generator = DabGenerator(plain, seed = 1L)
        val batch = DabBatch(64)
        assertEquals(1, generator.advance(sample(10f, 10f), batch))
        assertEquals(1, batch.count)
    }


    // ------------------------------------------- §9's predicted-tail copy

    @Test
    fun `a copy emits exactly what the original would have emitted`() {
        // §9's "continues the stabilized line", in its strong form. Comparing
        // the copy against the ORIGINAL fed the same sample is the only shape
        // that pins every piece of carried state at once: a first draft asserted
        // only that the tail's first dab landed past the last real one and
        // within 1.6 spacing steps, which a copy with `carry = 0` also
        // satisfies — it survived that mutation.
        // The preset has to EXERCISE the carried state or the comparison is
        // vacuous. A first draft used `plain` with 10 px samples: spacing 0.5 on
        // a radius of 10 makes the step exactly 5 px, so the carry is always 0,
        // `plain` has no jitter so `dabIndex` is unused, and no velocity
        // dynamics so the EMA is unused. Zeroing any of those in `copy()`
        // survived. This preset gives each of them something to change —
        // 7 px samples against a 3 px step leave a rolling remainder, the
        // jitter reads `dabIndex`, and the velocity terms read the EMA.
        val exercised = plain.copy(
            spacing = 0.3f,
            jitter = Jitter(position = 0.8f),
            velocity = VelocityEffect(sizeAtFast = 0.5f, opacityAtFast = 0.5f),
        )
        val real = DabGenerator(exercised, seed = 7L)
        val out = DabBatch()
        real.begin(sample(0f, 0f, timeMs = 0L), out)
        for (i in 1..8) real.advance(sample(i * 7f, 0f, timeMs = i * 8L), out)
        // One sample sharing the previous timestamp, so `pendingDistance` is
        // non-zero when the copy is taken. Devices really do stamp a whole
        // historical run with the batch's event time, which is why
        // `updateVelocity` defers the travel instead of dropping it — and
        // without this line that deferred distance is 0 and copying it is
        // untested.
        real.advance(sample(59f, 0f, timeMs = 64L), out)

        val next = sample(65f, 4f, timeMs = 72L)
        val tailOut = DabBatch()
        real.copy().advance(next, tailOut)
        val realOut = DabBatch()
        real.advance(next, realOut)

        assertTrue(tailOut.count > 0, "the copy must keep emitting, not stall")
        assertEquals(realOut.count, tailOut.count, "the copy emitted a different number of dabs")
        for (i in 0 until realOut.count) {
            assertEquals(realOut.x[i], tailOut.x[i], 0f, "dab $i x differs from the real continuation")
            assertEquals(realOut.y[i], tailOut.y[i], 0f, "dab $i y differs from the real continuation")
            assertEquals(realOut.radius[i], tailOut.radius[i], 0f, "dab $i radius differs")
            assertEquals(realOut.flow[i], tailOut.flow[i], 0f, "dab $i flow differs")
            assertEquals(realOut.angle[i], tailOut.angle[i], 0f, "dab $i angle differs")
        }
    }

    @Test
    fun `a copy never advances the generator it came from`() {
        // The half that matters for correctness: a predicted sample must leave
        // no trace on the real stroke, or the next REAL sample is spaced
        // against a remainder the user never drew and the committed stroke
        // depends on how many frames happened to be predicted.
        val real = DabGenerator(plain, seed = 7L)
        val out = DabBatch()
        real.begin(sample(0f, 0f, timeMs = 0L), out)
        for (i in 1..5) real.advance(sample(i * 10f, 0f, timeMs = i * 8L), out)

        val countBefore = real.dabCount
        val ceilingBefore = real.pressureOpacityMax
        val tail = real.copy()
        val tailOut = DabBatch()
        repeat(6) { tail.advance(sample(60f + it * 10f, 0f, timeMs = 48L + it * 8L), tailOut) }
        assertTrue(tailOut.count > 0, "the tail must have done something to be worth checking")
        assertEquals(countBefore, real.dabCount, "the copy advanced the original's dab count")
        assertEquals(ceilingBefore, real.pressureOpacityMax, "the copy advanced the original's ceiling")

        // Strongest form: the real stroke's continuation is bit-identical to
        // what it would have been had no tail ever run.
        val withTail = DabBatch()
        real.advance(sample(60f, 0f, timeMs = 48L), withTail)

        val control = DabGenerator(plain, seed = 7L)
        val controlOut = DabBatch()
        control.begin(sample(0f, 0f, timeMs = 0L), controlOut)
        for (i in 1..5) control.advance(sample(i * 10f, 0f, timeMs = i * 8L), controlOut)
        val controlNext = DabBatch()
        control.advance(sample(60f, 0f, timeMs = 48L), controlNext)

        assertEquals(controlNext.count, withTail.count, "the tail changed how many dabs the real stroke emits")
        for (i in 0 until withTail.count) {
            assertEquals(controlNext.x[i], withTail.x[i], 0f, "dab $i x diverged after a tail ran")
            assertEquals(controlNext.y[i], withTail.y[i], 0f, "dab $i y diverged after a tail ran")
            assertEquals(controlNext.radius[i], withTail.radius[i], 0f, "dab $i radius diverged after a tail ran")
        }
    }

    @Test
    fun `a copy cannot rewrite the real stroke's first dab`() {
        // `end()` rewrites a tap's first dab IN PLACE, through a reference to
        // the caller's batch, using the maximum pressure the tap saw. A copy
        // carrying that reference could reach into a committed stroke and
        // change a dab already merged onto the layer.
        //
        // The pressure curve and the extra sample are both load-bearing. With
        // `Curve.One` the rewrite recomputes the SAME radius and carrying the
        // reference is invisible; with a linear curve and a harder press seen
        // only by the copy, the two radii differ — so the mutation that carries
        // `firstBatch` over is caught rather than surviving, as it did against
        // a first draft of this test.
        val pressured = plain.copy(pressureSize = Curve.Linear)
        val real = DabGenerator(pressured, seed = 7L)
        val out = DabBatch()
        real.begin(sample(5f, 5f, pressure = 0.1f, timeMs = 0L), out)
        assertTrue(out.count > 0, "a tap emits its opening dab")
        val firstRadius = out.radius[0]

        val tail = real.copy()
        // Same position, so no dab and no path length — only the copy's
        // maxPressure moves, which is exactly what end() would write.
        tail.advance(sample(5f, 5f, pressure = 1f, timeMs = 8L), DabBatch())
        tail.end(DabBatch())

        assertEquals(firstRadius, out.radius[0], 0f, "the copy's end() rewrote the real batch's first dab")

        // And the guard is not vacuous: the real generator's own end() DOES
        // rewrite it, so the value being compared is one end() can change.
        real.advance(sample(5f, 5f, pressure = 1f, timeMs = 8L), DabBatch())
        real.end(DabBatch())
        assertTrue(
            out.radius[0] != firstRadius,
            "end() must actually rewrite a tap's first dab, or this test proves nothing",
        )
    }
}
