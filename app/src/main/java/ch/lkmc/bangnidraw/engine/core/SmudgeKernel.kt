package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.StrokeMerge.Rgba
import kotlin.math.roundToInt

/** Pure reference for smudge deposit, pickup, and blur shaders. */
object SmudgeKernel {

    fun deposit(
        layer: Rgba,
        pickup: Rgba,
        weight: Float,
        lerp: StrokeMerge.ColorLerp = StrokeMerge.ColorLerp.Linear,
        scratch: StrokeMerge.Scratch = StrokeMerge.Scratch(),
    ): Rgba = mixPremultiplied(layer, pickup, weight, lerp, scratch)

    fun absorb(
        pickup: Rgba,
        layerBeforeDeposit: Rgba,
        weight: Float,
        lerp: StrokeMerge.ColorLerp = StrokeMerge.ColorLerp.Linear,
        scratch: StrokeMerge.Scratch = StrokeMerge.Scratch(),
    ): Rgba = mixPremultiplied(pickup, layerBeforeDeposit, weight, lerp, scratch)

    private fun mixPremultiplied(
        destination: Rgba,
        source: Rgba,
        weight: Float,
        lerp: StrokeMerge.ColorLerp,
        scratch: StrokeMerge.Scratch,
    ): Rgba {
        val w = weight.coerceIn(0f, 1f)
        val alpha = destination.a + (source.a - destination.a) * w
        if (alpha < ALPHA_EPSILON) return Rgba.TRANSPARENT

        scratch.from[0] = destination.r / maxOf(destination.a, ALPHA_EPSILON)
        scratch.from[1] = destination.g / maxOf(destination.a, ALPHA_EPSILON)
        scratch.from[2] = destination.b / maxOf(destination.a, ALPHA_EPSILON)
        scratch.to[0] = source.r / maxOf(source.a, ALPHA_EPSILON)
        scratch.to[1] = source.g / maxOf(source.a, ALPHA_EPSILON)
        scratch.to[2] = source.b / maxOf(source.a, ALPHA_EPSILON)
        val pigmentShare = (w * source.a / alpha).coerceIn(0f, 1f)
        lerp.lerp(scratch.from, scratch.to, pigmentShare, scratch.out)

        return Rgba(
            scratch.out[0] * alpha,
            scratch.out[1] * alpha,
            scratch.out[2] * alpha,
            alpha,
        )
    }

    /** Half an RGBA8 alpha step, shared with the GLSL guard. */
    const val ALPHA_EPSILON = 1f / 512f
}

/** CPU oracle for the blur variant's clamped separable box kernel. */
object BlurKernel {

    fun radius(size: Float, fraction: Float): Int =
        (size * fraction).roundToInt().coerceIn(MIN_RADIUS, MAX_RADIUS)

    fun separable(
        source: Array<Rgba>,
        width: Int,
        height: Int,
        radius: Int,
        scratch: Array<Rgba>,
        out: Array<Rgba>,
    ) {
        require(width > 0 && height > 0) { "blur dimensions must be positive" }
        require(radius in MIN_RADIUS..MAX_RADIUS) { "blur radius must be $MIN_RADIUS..$MAX_RADIUS" }
        val pixels = width * height
        require(source.size == pixels && scratch.size == pixels && out.size == pixels) {
            "blur buffers must hold $pixels pixels"
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                scratch[y * width + x] = mean(source, width, height, x, y, radius, Axis.Horizontal)
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = mean(scratch, width, height, x, y, radius, Axis.Vertical)
            }
        }
    }

    private fun mean(
        pixels: Array<Rgba>,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        radius: Int,
        axis: Axis,
    ): Rgba {
        var r = 0f
        var g = 0f
        var b = 0f
        var a = 0f
        for (offset in -radius..radius) {
            val sx = if (axis == Axis.Horizontal) (x + offset).coerceIn(0, width - 1) else x
            val sy = if (axis == Axis.Vertical) (y + offset).coerceIn(0, height - 1) else y
            val pixel = pixels[sy * width + sx]
            r += pixel.r
            g += pixel.g
            b += pixel.b
            a += pixel.a
        }
        val scale = 1f / (radius * 2 + 1)
        return Rgba(r * scale, g * scale, b * scale, a * scale)
    }

    private enum class Axis { Horizontal, Vertical }

    const val MIN_RADIUS = 1
    const val MAX_RADIUS = 24
}
