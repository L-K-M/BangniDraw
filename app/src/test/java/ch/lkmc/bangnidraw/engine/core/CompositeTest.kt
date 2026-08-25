package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `docs/plan/11-testing.md` §3.10. The per-mode expectations live in
 * `src/test/resources/fixtures/composite/<MODE>.txt` and were computed from
 * the normative table of `docs/plan/05-layers.md` §4, not from this code.
 */
class CompositeTest {

    private data class Case(val dst: Int, val src: Int, val opacity: Float, val expected: Int, val note: String)

    private fun fixture(mode: BlendMode): List<Case> {
        val path = "/fixtures/composite/${mode.name}.txt"
        val text = javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: fail("missing fixture $path")
        return text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split(Regex("\\s+"))
                check(parts.size == 4) { "malformed fixture line in $path: '$line'" }
                Case(
                    dst = parts[0].toUInt(16).toInt(),
                    src = parts[1].toUInt(16).toInt(),
                    opacity = parts[2].toFloat(),
                    expected = parts[3].toUInt(16).toInt(),
                    note = line,
                )
            }
            .toList()
    }

    private fun hex(p: Int): String = "%08X".format(p)

    @Test
    fun `every blend mode matches its hand-computed pixels`() {
        for (mode in BlendMode.entries) {
            val cases = fixture(mode)
            assertTrue(cases.isNotEmpty(), "fixture for $mode is empty")
            for (c in cases) {
                assertEquals(
                    hex(c.expected),
                    hex(Composite.blend(c.dst, c.src, mode, c.opacity)),
                    "$mode: ${c.note}",
                )
            }
        }
    }

    @Test
    fun `composite with normal at opacity 1 is source-over`() {
        val random = Random(42)
        repeat(2000) {
            val dst = randomPremultiplied(random)
            val src = randomPremultiplied(random)
            val out = Composite.blend(dst, src, BlendMode.NORMAL, 1f)
            val expectedA = quantize(a(src) + a(dst) * (1f - a(src)))
            val expectedR = quantize(r(src) + r(dst) * (1f - a(src)))
            assertEquals(expectedA, Composite.alpha(out), "alpha of ${hex(src)} over ${hex(dst)}")
            assertEquals(expectedR, Composite.red(out), "red of ${hex(src)} over ${hex(dst)}")
            assertEquals(
                quantize(Composite.green(src) / 255f + Composite.green(dst) / 255f * (1f - a(src))),
                Composite.green(out),
                "green of ${hex(src)} over ${hex(dst)}",
            )
            assertEquals(
                quantize(Composite.blue(src) / 255f + Composite.blue(dst) / 255f * (1f - a(src))),
                Composite.blue(out),
                "blue of ${hex(src)} over ${hex(dst)}",
            )
        }
    }

    @Test
    fun `every blend mode at opacity 0 leaves the destination unchanged`() {
        val random = Random(7)
        for (mode in BlendMode.entries) {
            repeat(200) {
                val dst = randomPremultiplied(random)
                // Hoisted so the message names it: a source generated inside
                // the call cannot be read back off a failure, only replayed
                // from the seed.
                val src = randomPremultiplied(random)
                assertEquals(
                    hex(dst),
                    hex(Composite.blend(dst, src, mode, 0f)),
                    "$mode at opacity 0 changed ${hex(dst)} under ${hex(src)}",
                )
            }
        }
    }

    @Test
    fun `every blend mode over a transparent destination at opacity 1 equals the source`() {
        val random = Random(11)
        for (mode in BlendMode.entries) {
            repeat(200) {
                val src = randomPremultiplied(random)
                assertEquals(
                    hex(src),
                    hex(Composite.blend(Composite.TRANSPARENT, src, mode, 1f)),
                    "$mode over nothing must be plain source-over",
                )
            }
        }
    }

    @Test
    fun `blend results stay premultiplied`() {
        val random = Random(13)
        for (mode in BlendMode.entries) {
            repeat(500) {
                val out = Composite.blend(randomPremultiplied(random), randomPremultiplied(random), mode, random.nextFloat())
                val a = Composite.alpha(out)
                assertTrue(
                    Composite.red(out) <= a && Composite.green(out) <= a && Composite.blue(out) <= a,
                    "$mode produced ${hex(out)}, whose colour exceeds its alpha",
                )
            }
        }
    }

    @Test
    fun `an erase dab subtracts alpha and never adds colour`() {
        val dst = 0xFF804020.toInt()
        assertEquals(hex(dst), hex(Composite.erase(dst, 0x00FFFFFF)), "zero coverage erases nothing")
        assertEquals(hex(0), hex(Composite.erase(dst, 0xFF000000.toInt())), "full coverage erases everything")
        val half = Composite.erase(dst, 0x80FFFFFF.toInt())
        assertEquals(127, Composite.alpha(half), "half coverage halves the alpha")
        assertEquals(64, Composite.red(half))
        assertEquals(32, Composite.green(half))
        assertEquals(16, Composite.blue(half))

        val random = Random(17)
        repeat(500) {
            val d = randomPremultiplied(random)
            val out = Composite.erase(d, randomPremultiplied(random))
            assertTrue(Composite.alpha(out) <= Composite.alpha(d), "erasing raised the alpha of ${hex(d)}")
            assertTrue(
                Composite.red(out) <= Composite.red(d) &&
                    Composite.green(out) <= Composite.green(d) &&
                    Composite.blue(out) <= Composite.blue(d),
                "erasing raised a colour channel of ${hex(d)}",
            )
        }
    }

    @Test
    fun `alpha lock keeps the destination alpha exactly`() {
        val random = Random(19)
        repeat(1000) {
            val dst = randomPremultiplied(random)
            val src = randomPremultiplied(random)
            val out = Composite.alphaLocked(dst, src)
            assertEquals(
                Composite.alpha(dst),
                Composite.alpha(out),
                "alpha lock changed the alpha of ${hex(dst)} when ${hex(src)} was painted on it",
            )
        }
        assertEquals(
            hex(Composite.TRANSPARENT),
            hex(Composite.alphaLocked(Composite.TRANSPARENT, 0xFFFFFFFF.toInt())),
            "a transparent pixel stays transparent whatever is painted over it",
        )
        // Every assertion above is also satisfied by an alphaLocked that just
        // returns dst, so pin the colour path too: opaque white onto a
        // half-covered red gives white rescaled to the locked alpha.
        assertEquals(
            hex(0x80808080.toInt()),
            hex(Composite.alphaLocked(0x80800000.toInt(), 0xFFFFFFFF.toInt())),
            "alpha lock composites colour and rescales it to the destination's alpha",
        )
    }

    @Test
    fun `merge down equals compositing the two layers`() {
        val random = Random(23)
        val key = TileKey(0, 0)
        for (mode in BlendMode.entries) {
            val opacity = 0.6f
            val lower = Layer(LayerProps(LayerId("lower"), "lower", opacity = 0.8f), setOf(key))
            val upper = Layer(
                LayerProps(LayerId("upper"), "upper", opacity = opacity, blendMode = mode),
                setOf(key),
            )
            val lowerPixels = IntArray(4) { randomPremultiplied(random) }
            val upperPixels = IntArray(4) { randomPremultiplied(random) }
            val reader = TileReader { id, _ ->
                IntArray(TILE_SIZE * TILE_SIZE) { i ->
                    if (id.value == "lower") lowerPixels[i % 4] else upperPixels[i % 4]
                }
            }
            val merged = Composite.tile(listOf(lower, upper), key, Composite.TRANSPARENT, reader)
            for (i in 0 until 4) {
                val expected = Composite.blend(
                    Composite.blend(Composite.TRANSPARENT, lowerPixels[i], BlendMode.NORMAL, 0.8f),
                    upperPixels[i],
                    mode,
                    opacity,
                )
                assertEquals(hex(expected), hex(merged[i]), "$mode merge of pixel $i")
            }
        }
    }

    @Test
    fun `a tile composites onto the paper and skips layers with no tile there`() {
        val key = TileKey(1, 1)
        val painted = Layer(LayerProps(LayerId("a"), "a"), setOf(key))
        val elsewhere = Layer(LayerProps(LayerId("b"), "b"), setOf(TileKey(9, 9)))
        val reader = TileReader { _, _ -> IntArray(TILE_SIZE * TILE_SIZE) { 0x80800000.toInt() } }
        val paper = 0xFFFFFFFF.toInt()
        val out = Composite.tile(listOf(painted, elsewhere), key, paper, reader)
        assertEquals(TILE_SIZE * TILE_SIZE, out.size)
        assertEquals(hex(Composite.blend(paper, 0x80800000.toInt(), BlendMode.NORMAL, 1f)), hex(out[0]))

        val empty = Composite.tile(listOf(elsewhere), key, paper, reader)
        assertEquals(hex(paper), hex(empty[0]), "a layer with no tile here leaves the paper alone")
    }

    @Test
    fun `a transparent paper leaves the ground transparent`() {
        val out = Composite.tile(emptyList(), TileKey(0, 0), Composite.TRANSPARENT) { _, _ -> null }
        assertTrue(out.all { it == Composite.TRANSPARENT })
    }

    @Test
    fun `a NaN opacity degrades to fully visible instead of erasing the pixel`() {
        // blend is public and is the pinned reference the GLSL must match, so
        // it cannot rely on every caller having gone through LayerProps.
        // coerceIn passes NaN through, `NaN == 0f` is false, and quantize
        // truncates NaN to 0 — which would blank the pixel.
        val dst = 0xFF204060.toInt()
        val src = 0xFF80A0C0.toInt()
        for (mode in BlendMode.entries) {
            assertEquals(
                hex(Composite.blend(dst, src, mode, 1f)),
                hex(Composite.blend(dst, src, mode, Float.NaN)),
                "$mode with a NaN opacity must behave as fully opaque, not erase",
            )
        }
        assertEquals(
            hex(Composite.blend(dst, src, BlendMode.NORMAL, 1f)),
            hex(Composite.blend(dst, src, BlendMode.NORMAL, Float.POSITIVE_INFINITY)),
        )
        assertEquals(
            hex(Composite.blend(dst, src, BlendMode.NORMAL, 1f)),
            hex(Composite.blend(dst, src, BlendMode.NORMAL, Float.NEGATIVE_INFINITY)),
            "every non-finite opacity degrades alike, -inf included",
        )
    }

    @Test
    fun `8-bit rounding is round-to-nearest`() {
        // 1/255 of coverage must survive: a single flow-0.004 dab is the case
        // 03 section 2.4 calls out, and truncation would drop it entirely.
        assertEquals(1, Composite.alpha(Composite.blend(Composite.TRANSPARENT, 0x01000000, BlendMode.NORMAL, 1f)))
        // Half of 255 is 127.5, which rounds up, not down.
        assertEquals(128, Composite.alpha(Composite.blend(Composite.TRANSPARENT, 0xFF000000.toInt(), BlendMode.NORMAL, 0.5f)))
    }

    @Test
    fun `premultiply rounds to nearest and leaves opaque pixels alone`() {
        assertEquals(hex(0xFF804020.toInt()), hex(Composite.premultiply(0xFF804020.toInt())))
        assertEquals(hex(0), hex(Composite.premultiply(0x00FFFFFF)))
        assertEquals(hex(0x80804020.toInt()), hex(Composite.premultiply(0x80FF8040.toInt())))
    }

    // ------------------------------------------------------------------ helpers

    private fun a(p: Int) = Composite.alpha(p) / 255f

    private fun r(p: Int) = Composite.red(p) / 255f

    private fun quantize(v: Float) = ((v * 255f) + 0.5f).toInt().coerceIn(0, 255)

    /** A random pixel that already satisfies the premultiplied invariant. */
    private fun randomPremultiplied(random: Random): Int {
        val alpha = random.nextInt(0, 256)
        return Composite.argb(
            alpha,
            random.nextInt(0, alpha + 1),
            random.nextInt(0, alpha + 1),
            random.nextInt(0, alpha + 1),
        )
    }
}
