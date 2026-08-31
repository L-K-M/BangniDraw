package ch.lkmc.bangnidraw.engine.core

import kotlin.math.roundToInt

/** Straight ARGB color mixing shared by UI and stroke references. */
interface ColorMixer {
    val isPigment: Boolean

    /** Alpha is ignored; the result is opaque. */
    fun mix(a: Int, b: Int, t: Float): Int
}

/** A mixer whose colors can be combined in a linear latent space. */
interface LatentColorMixer : ColorMixer {
    val latentSize: Int

    fun toLatent(argb: Int, out: FloatArray)

    fun fromLatent(latent: FloatArray): Int

    fun mixWeighted(colors: IntArray, weights: FloatArray): Int {
        require(colors.isNotEmpty()) { "at least one color is required" }
        require(colors.size == weights.size) { "colors and weights must have equal sizes" }
        require(weights.all { it.isFinite() && it >= 0f }) { "weights must be finite and non-negative" }
        val total = weights.sum()
        require(total > 0f && total.isFinite()) { "weight total must be positive and finite" }

        val mixed = FloatArray(latentSize)
        val current = FloatArray(latentSize)
        for (index in colors.indices) {
            toLatent(colors[index], current)
            val normalizedWeight = weights[index] / total
            for (component in mixed.indices) {
                mixed[component] += current[component] * normalizedWeight
            }
        }

        return fromLatent(mixed)
    }
}

/** Component-linear interpolation of stored sRGB bytes. */
object RgbMixer : LatentColorMixer {
    override val isPigment = false
    override val latentSize = 3

    override fun mix(a: Int, b: Int, t: Float): Int {
        val amount = if (t.isFinite()) t.coerceIn(0f, 1f) else 0f

        return opaqueArgb(
            lerpChannel(Composite.red(a), Composite.red(b), amount),
            lerpChannel(Composite.green(a), Composite.green(b), amount),
            lerpChannel(Composite.blue(a), Composite.blue(b), amount),
        )
    }

    override fun toLatent(argb: Int, out: FloatArray) {
        require(out.size == latentSize) { "RGB latent output must have $latentSize components" }

        out[0] = Composite.red(argb) / CHANNEL_MAX_F
        out[1] = Composite.green(argb) / CHANNEL_MAX_F
        out[2] = Composite.blue(argb) / CHANNEL_MAX_F
    }

    override fun fromLatent(latent: FloatArray): Int {
        require(latent.size == latentSize) { "RGB latent input must have $latentSize components" }

        return opaqueArgb(
            quantize(latent[0]),
            quantize(latent[1]),
            quantize(latent[2]),
        )
    }

    private fun lerpChannel(from: Int, to: Int, t: Float): Int =
        (from + (to - from) * t).roundToInt()

    private fun quantize(value: Float): Int {
        if (!value.isFinite()) return 0

        return (value.coerceIn(0f, 1f) * CHANNEL_MAX).roundToInt()
    }

    private fun opaqueArgb(red: Int, green: Int, blue: Int): Int =
        Composite.argb(CHANNEL_MAX, red, green, blue)

    private const val CHANNEL_MAX = 255
    private const val CHANNEL_MAX_F = CHANNEL_MAX.toFloat()
}
