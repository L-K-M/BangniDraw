package ch.lkmc.bangnidraw.engine.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The golden stroke (`docs/plan/11-testing.md` §6).
 *
 * A recorded loop is run through `Stabilizer → DabGenerator` and every dab is
 * compared field by field to a pinned file. A change to the dynamics *should*
 * change the golden, and the PR then shows exactly how — which is the point:
 * the other tests pin properties, and this one pins the actual output, so a
 * refactor that quietly reshapes a stroke cannot pass unnoticed.
 *
 * Regenerate with `./gradlew testDebugUnitTest -Dbangni.updateGolden=true` and
 * review the diff like code.
 */
class DabGeneratorGoldenTest {

    @Serializable
    private data class GoldenSample(
        val x: Float,
        val y: Float,
        val pressure: Float,
        val tilt: Float,
        val orientation: Float,
        val timeMs: Long,
    )

    @Serializable
    private data class GoldenDab(
        val x: Float,
        val y: Float,
        val radius: Float,
        val flow: Float,
        val hardness: Float,
        val angle: Float,
        val aspect: Float,
        val seed: Float,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val preset = BrushPresets.INK_PEN
    private val seed = 20260825L

    private fun samples(): List<GoldenSample> {
        val text = checkNotNull(
            javaClass.getResourceAsStream("/$INPUT_RESOURCE")?.bufferedReader()?.use { it.readText() },
        ) { "missing golden fixture $INPUT_RESOURCE" }
        return json.decodeFromString(text)
    }

    /**
     * Deliberately not `StrokeInput().apply { set(x, y, ...) }`: inside that
     * `apply`, `x` and friends resolve to the *inner* receiver's own
     * properties — `StrokeInput` has fields by those names too, and Kotlin
     * picks the innermost implicit receiver. Every sample would silently come
     * out at the origin with default pressure, and the golden would pin a
     * stroke that never moved.
     */
    private fun GoldenSample.toInput(): StrokeInput {
        val s = this
        return StrokeInput().also {
            it.set(s.x, s.y, s.pressure, s.tilt, s.orientation, s.timeMs * 1_000_000L)
        }
    }

    /**
     * The pipeline under test, fed in chunks of [chunk] samples — **each chunk
     * into its own [DabBatch]**, which is what a `MotionEvent` does: the
     * handler fills one batch per event, publishes it, and takes the next ring
     * slot.
     *
     * The batch per chunk is the whole point. An earlier version advanced the
     * loop in chunks but kept one batch, so nothing observable happened at a
     * boundary — the generator saw an identical call sequence for every chunk
     * size, and the "batch-split invariant" test was comparing two runs of the
     * same thing. A spacing carry that reset per batch would have passed it.
     */
    private fun runStroke(chunk: Int): List<GoldenDab> {
        val input = samples()
        val stabilizer = Stabilizer(preset.stabilizer)
        val generator = DabGenerator(preset, seed)
        val smoothed = StrokeInput()

        // Every batch is kept and read only after `end()` has run. `end()`
        // rewrites the stroke's *first* dab for a tap, and on a multi-batch
        // stroke that dab lives in a batch published several chunks earlier —
        // so copying values at the chunk boundary would snapshot them before
        // the fix-up and pin numbers the renderer would never show. Today the
        // fixture is a long stroke and `end()` does nothing to it; the harness
        // has to be able to represent it anyway, or a regression in the
        // fix-up would pass this test.
        val batches = mutableListOf<DabBatch>()
        var batch = DabBatch(capacity = 8192)
        val first = input.first().toInput()
        stabilizer.reset(first)
        generator.begin(first, batch)

        var i = 1
        while (i < input.size) {
            val end = minOf(i + chunk, input.size)
            while (i < end) {
                val raw = input[i].toInput()
                if (stabilizer.push(raw, smoothed)) generator.advance(smoothed, batch)
                i++
            }
            batches += batch
            batch = DabBatch(capacity = 8192)
        }
        stabilizer.finish(generator.currentStep(), smoothed) { generator.advance(it, batch) }
        generator.end(batch)
        batches += batch

        return batches.flatMap { b ->
            List(b.count) {
                val d = b[it]
                GoldenDab(d.x, d.y, d.radius, d.flow, d.hardness, d.angle, d.aspect, d.seed)
            }
        }
    }

    /**
     * One dab per line, six decimals, with a header — the same shape as the
     * composite fixtures next door. A pretty-printed JSON array of the same
     * dabs is eight lines each and a third of a megabyte, which is not a diff
     * anyone reviews; this is one line per dab and reads as a table.
     */
    private fun render(dabs: List<GoldenDab>): String = buildString {
        appendLine("# The golden stroke: BrushPresets.INK_PEN over ink-pen-loop.json,")
        appendLine("# through Stabilizer -> DabGenerator with stroke seed $seed.")
        appendLine("# Regenerate with -Dbangni.updateGolden=true and review the diff like code.")
        appendLine("#")
        appendLine("# Compared by tolerance ($PX_EPS), not by text, which is what makes this")
        appendLine("# fixture portable: a libm that differs in the last ulp of a sin moves")
        appendLine("# these values by ~1e-7, four orders of magnitude inside that. A diff")
        appendLine("# here is a real change in the stroke, not a change of JDK.")
        appendLine("#")
        appendLine("# x y radius flow hardness angle aspect seed")
        for (d in dabs) {
            appendLine(
                listOf(d.x, d.y, d.radius, d.flow, d.hardness, d.angle, d.aspect, d.seed)
                    .joinToString(" ") { fixed(it) },
            )
        }
    }

    private fun parse(text: String): List<GoldenDab> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val f = line.split(' ').filter { it.isNotEmpty() }.map(String::toFloat)
            require(f.size == PerfConstants.DAB_STRIDE) {
                "a golden row must have ${PerfConstants.DAB_STRIDE} fields, was \"$line\""
            }
            GoldenDab(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7])
        }
        .toList()

    /**
     * Four decimals. The comparison is by [PX_EPS] tolerance rather than by
     * text, so precision beyond that buys nothing and costs diff noise: the
     * last digits of a float move with the FPU, the libm, and the
     * architecture, so a six-decimal dump churns on machines where the stroke
     * is identical. 1e-4 quantisation sits an order of magnitude inside the
     * 1e-3 tolerance the test actually applies.
     */
    private fun fixed(v: Float): String = String.format(java.util.Locale.ROOT, "%.4f", v)

    @Test
    fun `the golden stroke produces the pinned dabs`() {
        val actual = runStroke(chunk = 8)
        val file = File(GOLDEN_SOURCE_PATH)
        if (System.getProperty("bangni.updateGolden") == "true") {
            file.parentFile.mkdirs()
            file.writeText(render(actual))
            println("golden updated: ${file.absolutePath} (${actual.size} dabs)")
            return
        }
        val text = checkNotNull(
            javaClass.getResourceAsStream("/$GOLDEN_RESOURCE")?.bufferedReader()?.use { it.readText() },
        ) { "missing golden $GOLDEN_RESOURCE — regenerate with -Dbangni.updateGolden=true" }
        val expected: List<GoldenDab> = parse(text)

        assertEquals(expected.size, actual.size, "the stroke's dab count changed")
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            assertClose(e.x, a.x, "dab $i x")
            assertClose(e.y, a.y, "dab $i y")
            assertClose(e.radius, a.radius, "dab $i radius")
            assertClose(e.flow, a.flow, "dab $i flow")
            assertClose(e.hardness, a.hardness, "dab $i hardness")
            assertClose(e.angle, a.angle, "dab $i angle")
            assertClose(e.aspect, a.aspect, "dab $i aspect")
            assertClose(e.seed, a.seed, "dab $i seed")
        }
    }

    @Test
    fun `the golden stroke is batch-split invariant`() {
        // The same samples delivered in runs of 1, 7 and 64 must produce
        // identical dabs. This is the property that makes a stroke independent
        // of how the digitizer happened to batch it — and it is the one a
        // spacing bug breaks first, because the carry is what crosses a batch
        // boundary.
        val reference = runStroke(chunk = 1)
        assertTrue(reference.isNotEmpty(), "the golden stroke must produce dabs")
        for (chunk in listOf(7, 64, 1000)) {
            val other = runStroke(chunk)
            assertEquals(reference.size, other.size, "chunk $chunk changed the dab count")
            for (i in reference.indices) {
                // All eight fields: an angle or aspect that depended on where a
                // batch boundary fell would be exactly the sort of bug this
                // test is named for, and four of them went unchecked.
                assertEquals(reference[i], other[i], "chunk $chunk, dab $i")
            }
        }
    }

    @Test
    fun `the golden stroke actually exercises the dynamics it is meant to`() {
        // A golden that happened to be a straight line at constant pressure
        // would still pass its own comparison while pinning nothing. This is
        // the guard on the fixture rather than on the code.
        val dabs = runStroke(chunk = 8)
        assertTrue(dabs.size > 100, "the golden stroke is too short to pin much: ${dabs.size}")
        val radii = dabs.map { it.radius }
        assertTrue(
            radii.max() > radii.min() * 2f,
            "the stroke must taper: radii ran ${radii.min()}..${radii.max()}",
        )
        assertTrue(
            dabs.map { it.y }.distinct().size > 50 && dabs.map { it.x }.distinct().size > 50,
            "the stroke must curve: a straight line along either axis is not a loop",
        )
        // And it must stay inside what the preset allows, so a golden can
        // never pin a dab the model would refuse.
        for (d in dabs) {
            assertTrue(d.radius in preset.sizeMin / 2f..preset.sizeMax / 2f, "radius ${d.radius}")
            assertTrue(d.flow in 0f..1f, "flow ${d.flow}")
        }
    }

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(
            abs(expected - actual) <= PX_EPS,
            "$what: expected $expected, was $actual (tolerance $PX_EPS)",
        )
    }

    private companion object {
        /** `11-testing.md` §6's comparison tolerance. */
        const val PX_EPS = 1e-3f

        const val INPUT_RESOURCE = "fixtures/golden-stroke/ink-pen-loop.json"
        const val GOLDEN_RESOURCE = "fixtures/golden-stroke/ink-pen-loop.dabs.txt"

        /**
         * Where the golden is *written* when regenerating. Tests run with the
         * module directory as their working directory, and the resource on the
         * classpath is a build-directory copy — writing there would be
         * discarded on the next clean.
         */
        const val GOLDEN_SOURCE_PATH = "src/test/resources/$GOLDEN_RESOURCE"
    }
}
