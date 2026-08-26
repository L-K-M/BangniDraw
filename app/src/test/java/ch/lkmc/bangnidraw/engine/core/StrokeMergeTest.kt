package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.StrokeMerge.Rgba
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/plan/12-roadmap.md`'s 2.4b row names this the JVM evidence for the
 * whole PR: *"the merge blend math cross-checked against PR 2.1's CPU
 * `Composite` reference wherever no GL context is needed"*, which PLAN.md §7
 * glosses as "the CPU reference is what pins the shader semantics".
 *
 * So the load-bearing tests here are not "does the formula compute what I
 * typed" — that would only pin my own transcription, which is exactly the
 * failure mode `docs/plan/05-layers.md` §8 and `07-input-and-stylus.md` §7 both
 * turned out to be. They are: **does `StrokeMerge` agree, pixel for pixel at
 * 8-bit precision, with the independently written `Composite` from PR 2.1?**
 * Two implementations derived from different documents agreeing is evidence;
 * one implementation agreeing with itself is not.
 */
class StrokeMergeTest {

    private val scratch = StrokeMerge.Scratch()

    private fun spec(
        mode: StrokeMode,
        opacity: Float = 1f,
        alphaLock: Boolean = false,
        dilution: Float = 0f,
    ) = StrokeSpec(LayerId("l"), mode, opacity, alphaLock, dilution)

    // ---------------------------------------------------- packed <-> float

    private fun toPacked(c: Rgba): Int = Composite.argb(
        (c.a * 255f).roundToInt().coerceIn(0, 255),
        (c.r * 255f).roundToInt().coerceIn(0, 255),
        (c.g * 255f).roundToInt().coerceIn(0, 255),
        (c.b * 255f).roundToInt().coerceIn(0, 255),
    )

    private fun fromPacked(p: Int) = Rgba(
        Composite.red(p) / 255f,
        Composite.green(p) / 255f,
        Composite.blue(p) / 255f,
        Composite.alpha(p) / 255f,
    )

    /**
     * A spread of premultiplied pixels covering the cases the formulas branch
     * on: empty, opaque, and partial coverage on both sides, with colours that
     * are not equal to their own alpha so a channel swap cannot pass.
     */
    private fun samples(): List<Rgba> = buildList {
        add(Rgba.TRANSPARENT)
        for (a in intArrayOf(1, 40, 128, 200, 255)) {
            val af = a / 255f
            add(Rgba(af, 0f, 0f, af))                     // saturated red
            add(Rgba(0f, af * 0.5f, af, af))              // teal-ish
            add(Rgba(af * 0.25f, af * 0.75f, af * 0.1f, af))
            add(Rgba(af, af, af, af))                     // white
            add(Rgba(0f, 0f, 0f, af))                     // black at that coverage
        }
    }

    private fun assertMatchesPacked(
        expectedPacked: Int,
        actual: Rgba,
        tolerance: Int,
        what: String,
    ) {
        val got = toPacked(actual)
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val e = (expectedPacked ushr shift) and 0xFF
            val g = (got ushr shift) and 0xFF
            assertTrue(
                abs(e - g) <= tolerance,
                "$what: channel at shift $shift differs by ${abs(e - g)} " +
                    "(Composite ${e}, StrokeMerge ${g}); packed " +
                    "expected 0x${expectedPacked.toUInt().toString(16)} " +
                    "got 0x${got.toUInt().toString(16)}",
            )
        }
    }

    // --------------------------------------------- the cross-check itself

    @Test
    fun `PAINT at full opacity is Composite's source-over, pixel for pixel`() {
        var checked = 0
        for (layer in samples()) {
            for (stroke in samples()) {
                val expected = Composite.over(toPacked(layer), toPacked(stroke))
                val actual = StrokeMerge.merge(layer, stroke, spec(StrokeMode.PAINT), scratch = scratch)
                // Tolerance 1: Composite quantizes to 8 bits at every step and
                // StrokeMerge stays in float, so the two can land either side
                // of a rounding boundary. Anything larger is a real difference.
                assertMatchesPacked(expected, actual, tolerance = 1, what = "PAINT over L=$layer S=$stroke")
                checked++
            }
        }
        assertTrue(checked >= 600, "the cross-check must actually run a spread, ran $checked")
    }

    @Test
    fun `ERASE is Composite's erase, pixel for pixel`() {
        for (layer in samples()) {
            for (stroke in samples()) {
                val expected = Composite.erase(toPacked(layer), toPacked(stroke))
                val actual = StrokeMerge.merge(layer, stroke, spec(StrokeMode.ERASE), scratch = scratch)
                assertMatchesPacked(expected, actual, tolerance = 1, what = "ERASE L=$layer S=$stroke")
            }
        }
    }

    @Test
    fun `PAINT with alpha lock is Composite's alphaLocked, pixel for pixel`() {
        // The one formula 05 §1's prose can be read two ways; Composite.alphaLocked's
        // KDoc says outright that it is "the semantics the GLSL must match", so
        // agreeing with it here is the whole point of the cross-check.
        for (layer in samples()) {
            for (stroke in samples()) {
                val expected = Composite.alphaLocked(toPacked(layer), toPacked(stroke))
                val actual = StrokeMerge.merge(
                    layer, stroke, spec(StrokeMode.PAINT, alphaLock = true), scratch = scratch,
                )
                assertMatchesPacked(expected, actual, tolerance = 1, what = "alphaLock L=$layer S=$stroke")
            }
        }
    }

    @Test
    fun `MIX over a transparent layer equals PAINT, and over a fully covered one keeps its alpha`() {
        // §7.4's guards: where only one side has colour, MIX must not differ
        // from PAINT. This is what stops a pigment LUT round trip from tinting
        // the first stroke on an empty layer.
        for (stroke in samples()) {
            val paint = StrokeMerge.merge(Rgba.TRANSPARENT, stroke, spec(StrokeMode.PAINT), scratch = scratch)
            val mix = StrokeMerge.merge(Rgba.TRANSPARENT, stroke, spec(StrokeMode.MIX), scratch = scratch)
            assertEquals(paint, mix, "MIX over an empty layer must equal PAINT for S=$stroke")
        }
        for (layer in samples()) {
            val mix = StrokeMerge.merge(layer, Rgba.TRANSPARENT, spec(StrokeMode.MIX), scratch = scratch)
            assertEquals(layer, mix, "an empty stroke must leave the layer untouched, L=$layer")
        }
    }

    // ------------------------------------------------------- the opacity cap

    @Test
    fun `no number of overlapping dabs can exceed the stroke opacity`() {
        // §7.1's promise, and the reason opacity is a merge-time cap rather
        // than a per-dab weight. Accumulate a buffer far past the ceiling and
        // the merged result must still stop at it.
        val opacity = 0.4f
        var buffer = Rgba.TRANSPARENT
        val dab = Rgba(0.3f, 0f, 0f, 0.3f)
        repeat(40) { buffer = DabStamp.blendIntoBuffer(buffer, dab, BufferMode.Accumulate) }
        assertTrue(buffer.a > 0.99f, "precondition: the buffer must saturate, was ${buffer.a}")

        val merged = StrokeMerge.merge(
            Rgba.TRANSPARENT, buffer, spec(StrokeMode.PAINT, opacity = opacity), scratch = scratch,
        )
        assertEquals(opacity, merged.a, 1e-5f, "the merged alpha is the ceiling, not the buffer's")
        assertTrue(merged.isPremultiplied(), "capping must scale colour with alpha: $merged")
    }

    @Test
    fun `capping below the ceiling leaves the buffer alone`() {
        val s = Rgba(0.1f, 0.05f, 0f, 0.2f)
        assertEquals(s, StrokeMerge.cap(s, 0.5f), "a buffer under the ceiling is untouched")
        assertEquals(s, StrokeMerge.cap(s, 0.2f), "a buffer exactly at the ceiling is untouched")
    }

    @Test
    fun `capping a fully transparent buffer does not divide by zero`() {
        val capped = StrokeMerge.cap(Rgba.TRANSPARENT, 0.5f)
        assertEquals(Rgba.TRANSPARENT, capped)
        assertTrue(capped.a.isFinite(), "a transparent buffer must not produce NaN")
    }

    // --------------------------------------------------------- MIX specifics

    @Test
    fun `dilution pulls the mix back towards the layer, but only where the layer has paint`() {
        val layer = Rgba.straight(1f, 0f, 0f, 1f)      // opaque red
        val stroke = Rgba.straight(0f, 0f, 1f, 0.5f)   // half-covered blue
        val plain = StrokeMerge.merge(layer, stroke, spec(StrokeMode.MIX), scratch = scratch)
        val diluted = StrokeMerge.merge(
            layer, stroke, spec(StrokeMode.MIX, dilution = 0.5f), scratch = scratch,
        )
        assertTrue(
            diluted.b < plain.b,
            "dilution must reduce the stroke's share: plain ${plain.b} vs diluted ${diluted.b}",
        )
        assertEquals(plain.a, diluted.a, 1e-6f, "dilution changes colour only, never coverage")

        // Over an empty layer there is nothing to dilute into, so 09 §3.1
        // leaves t alone — the first stroke on blank paper is full strength
        // whatever the preset says.
        val onEmpty = StrokeMerge.merge(Rgba.TRANSPARENT, stroke, spec(StrokeMode.MIX), scratch = scratch)
        val onEmptyDiluted = StrokeMerge.merge(
            Rgba.TRANSPARENT, stroke, spec(StrokeMode.MIX, dilution = 0.9f), scratch = scratch,
        )
        assertEquals(onEmpty, onEmptyDiluted, "dilution must not weaken a stroke on an empty layer")
    }

    @Test
    fun `MIX respects alpha lock by keeping the layer's coverage`() {
        val layer = Rgba.straight(1f, 0f, 0f, 0.5f)
        val stroke = Rgba.straight(0f, 0f, 1f, 0.75f)
        val merged = StrokeMerge.merge(
            layer, stroke, spec(StrokeMode.MIX, alphaLock = true), scratch = scratch,
        )
        assertEquals(layer.a, merged.a, 1e-6f, "alpha lock keeps the layer's alpha exactly")
        assertTrue(merged.isPremultiplied(), "$merged")
    }

    @Test
    fun `an eraser is a no-op on an alpha-locked layer`() {
        // 05 §1. The tempting alternative — erase, then restore alpha — leaves
        // the coverage with the colour stripped out, i.e. a locked layer full
        // of transparent-looking black.
        val layer = Rgba.straight(0.2f, 0.6f, 1f, 0.8f)
        val stroke = Rgba(0f, 0f, 0f, 0.9f)
        val merged = StrokeMerge.merge(
            layer, stroke, spec(StrokeMode.ERASE, alphaLock = true), scratch = scratch,
        )
        assertEquals(layer, merged, "the eraser must not touch a locked layer at all")
    }

    // ------------------------------------------------------------ invariants

    @Test
    fun `every mode preserves premultiplication over the whole sample spread`() {
        // The invariant the rest of the pipeline assumes: rgb <= a. A formula
        // that produces rgb > a shows up on screen as a channel clipping only
        // at certain coverages, which is near-impossible to trace back.
        val specs = listOf(
            spec(StrokeMode.PAINT), spec(StrokeMode.PAINT, alphaLock = true),
            spec(StrokeMode.ERASE), spec(StrokeMode.ERASE, alphaLock = true),
            spec(StrokeMode.MIX), spec(StrokeMode.MIX, alphaLock = true),
            spec(StrokeMode.MIX, dilution = 0.7f), spec(StrokeMode.PAINT, opacity = 0.35f),
        )
        for (sp in specs) {
            for (layer in samples()) {
                for (stroke in samples()) {
                    val merged = StrokeMerge.merge(layer, stroke, sp, scratch = scratch)
                    assertTrue(
                        merged.isPremultiplied(),
                        "${sp.mode} lock=${sp.alphaLock} dil=${sp.dilution}: " +
                            "L=$layer S=$stroke gave a non-premultiplied $merged",
                    )
                    assertTrue(merged.a.isFinite(), "${sp.mode}: L=$layer S=$stroke gave alpha ${merged.a}")
                }
            }
        }
    }

    @Test
    fun `a stroke opacity of zero leaves the layer exactly as it was`() {
        // The cap makes S' fully transparent, so every mode degenerates to
        // identity. Worth pinning because a brush at opacity 0 is reachable
        // from the UI slider and "draws faint" would be a bug, not a feature.
        for (mode in StrokeMode.entries) {
            for (layer in samples()) {
                val merged = StrokeMerge.merge(
                    layer, Rgba(0.5f, 0.5f, 0.5f, 1f), spec(mode, opacity = 0f), scratch = scratch,
                )
                assertEquals(layer, merged, "$mode at opacity 0 must be identity on $layer")
            }
        }
    }

    @Test
    fun `round-tripping a straight colour through premultiplication is stable`() {
        val c = Rgba.straight(0.25f, 0.5f, 0.75f, 0.6f)
        assertTrue(c.isPremultiplied(), "$c")
        assertEquals(0.6f, c.a, 1e-6f)
        assertEquals(0.25f * 0.6f, c.r, 1e-6f)
    }
}
