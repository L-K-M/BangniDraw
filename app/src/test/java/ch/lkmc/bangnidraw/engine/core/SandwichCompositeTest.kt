package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.MAX_LAYERS
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class SandwichCompositeTest {

    private val key = TileKey(0, 0)
    private val paper = 0xFFE0D0C0.toInt()

    @Test
    fun `full and sandwich composites stay within one LSB for every mode below`() {
        for (mode in BlendMode.entries) {
            val layers = listOf(
                layer("ground", mode, 0x80804020.toInt()),
                layer("active", BlendMode.OVERLAY, 0xA0408020.toInt()),
                layer("upper-1", BlendMode.NORMAL, 0x60402060),
                layer("upper-2", BlendMode.NORMAL, 0x80301040.toInt()),
            )
            val pixels = reader(layers)
            val direct = Composite.tile(layers, key, paper, pixels)

            val below = Composite.tile(layers.take(1), key, paper, pixels)
            val activePixels = pixels.read(layers[1].id, key)!!
            val active = IntArray(PIXELS_PER_TILE) { i ->
                Composite.blend(below[i], activePixels[i], layers[1].props.blendMode, 1f)
            }
            val above = Composite.tile(layers.drop(2), key, paper = 0, pixels)
            val sandwich = IntArray(PIXELS_PER_TILE) { i -> Composite.over(active[i], above[i]) }

            assertWithinOneLsb(direct, sandwich, "$mode below active")
        }
    }

    @Test
    fun `RGBA8 grouping drift stays within one LSB per grouped layer`() {
        val random = Random(42)
        repeat(2_000) {
            val belowMode = BlendMode.entries[random.nextInt(BlendMode.entries.size)]
            val below = Composite.blend(randomPremultiplied(random), randomPremultiplied(random), belowMode, 1f)
            val active = Composite.blend(
                below,
                randomPremultiplied(random),
                BlendMode.entries[random.nextInt(BlendMode.entries.size)],
                1f,
            )
            val aboveSources = List(random.nextInt(MAX_ABOVE_LAYERS + 1)) { randomPremultiplied(random) }
            val direct = aboveSources.fold(active) { dst, src -> Composite.over(dst, src) }
            val above = aboveSources.fold(0) { dst, src -> Composite.over(dst, src) }
            val sandwich = Composite.over(active, above)

            assertPixelsWithin(
                direct,
                sandwich,
                tolerance = maxOf(1, aboveSources.size),
                label = "random grouping",
            )
        }
    }

    private fun assertWithinOneLsb(expected: IntArray, actual: IntArray, label: String) {
        for (i in expected.indices) {
            assertPixelsWithinOneLsb(expected[i], actual[i], "$label at pixel $i")
        }
    }

    private fun assertPixelsWithinOneLsb(expected: Int, actual: Int, label: String) {
        assertPixelsWithin(expected, actual, tolerance = 1, label = label)
    }

    private fun assertPixelsWithin(expected: Int, actual: Int, tolerance: Int, label: String) {
        val differences = intArrayOf(
            Composite.alpha(expected) - Composite.alpha(actual),
            Composite.red(expected) - Composite.red(actual),
            Composite.green(expected) - Composite.green(actual),
            Composite.blue(expected) - Composite.blue(actual),
        )
        assertTrue(
            differences.all { kotlin.math.abs(it) <= tolerance },
            "$label exceeds $tolerance LSB: ${differences.toList()}",
        )
    }

    private fun randomPremultiplied(random: Random): Int {
        val alpha = random.nextInt(256)
        return Composite.argb(
            alpha,
            random.nextInt(alpha + 1),
            random.nextInt(alpha + 1),
            random.nextInt(alpha + 1),
        )
    }

    private fun layer(id: String, mode: BlendMode, pixel: Int): Layer =
        Layer(LayerProps(LayerId(id), id, blendMode = mode), setOf(key)).also {
            tilePixels[it.id] = IntArray(PIXELS_PER_TILE) { pixel }
        }

    private fun reader(layers: List<Layer>): TileReader {
        val present = layers.map { it.id }.toSet()
        return TileReader { id, tile ->
            if (tile == key && id in present) tilePixels[id] else null
        }
    }

    private val tilePixels = LinkedHashMap<LayerId, IntArray>()

    private companion object {
        const val PIXELS_PER_TILE = TILE_SIZE * TILE_SIZE
        const val MAX_ABOVE_LAYERS = MAX_LAYERS - 2
    }
}
