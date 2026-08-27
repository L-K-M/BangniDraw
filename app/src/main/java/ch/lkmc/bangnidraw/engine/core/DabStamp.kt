package ch.lkmc.bangnidraw.engine.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pure-JVM twin of `dab.vert` + `dab.frag` and the stroke buffer's blend
 * state (`docs/plan/03-canvas-engine.md` §7.2, §7.3, §15's rule).
 *
 * What a dab covers, and how overlapping dabs accumulate in the buffer, are
 * both decision-shaped, so §15 puts them here and has `DabPass` issue the GL
 * calls that reproduce them. §7.2 states the property this file exists to
 * pin: *"dab overlap within a batch is deterministic and identical to the CPU
 * reference `DabStamp` in `engine/core`"* — which is only checkable if the
 * reference exists and the tests compare against it.
 *
 * Coordinates are canvas px. Colour is premultiplied throughout, matching the
 * buffer's RGBA8 and [StrokeMerge].
 */
object DabStamp {

    /**
     * The radius the shader actually draws, which is not `radius`.
     *
     * §7.3's `dab.vert` clamps to 1 px (`max(i_radius, 1.0)`) so a sub-pixel
     * dab still covers a pixel to be anti-aliased, and compensates by scaling
     * flow by the dab's true area — [areaWeight]. Without the clamp a 0.3 px
     * dab would land between sample points and disappear at some positions and
     * not others, which reads as a dotted line rather than a thin one.
     */
    fun drawRadius(radius: Float): Float = max(radius, 1f)

    /**
     * §7.3's `area`: a sub-pixel dab is drawn a pixel wide and dimmed by the
     * area it should have covered, so a 0.5 px brush is half-strength rather
     * than double-width.
     */
    fun areaWeight(radius: Float): Float = if (radius < 1f) radius * radius else 1f

    /**
     * §7.3's hardness falloff, for a point at distance [d] from the centre in
     * "major-axis px" (the ellipse already unwarped to a circle).
     *
     * Circular dabs have a one-pixel feather. Ellipses use [coverageAt], which
     * widens that band in local coordinates by the distance gradient so it
     * remains one canvas pixel across either axis (REVIEW.md R-055).
     */
    fun coverage(d: Float, radius: Float, hardness: Float): Float {
        return coverage(d, radius, hardness, feather = 1f)
    }

    /**
     * GLSL `smoothstep`, spelled out because the JVM has no equivalent and the
     * shader's exact curve is the thing being pinned.
     *
     * The `edge0 >= edge1` guard is GLSL's undefined case; returning a hard
     * step keeps the reference total. It is reachable only if [coverage]'s
     * clamp is ever loosened, which is precisely when a test should notice.
     */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 >= edge1) return if (x < edge1) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The distance `dab.frag` sees at canvas point ([px], [py]): the point in
     * the dab's local frame, with the minor axis divided by [aspect] so an
     * elliptical dab is evaluated as a circle (§7.3's `v_local`).
     */
    fun localDistance(
        px: Float,
        py: Float,
        centerX: Float,
        centerY: Float,
        angle: Float,
        aspect: Float,
    ): Float {
        val c = cos(angle)
        val s = sin(angle)
        val dx = px - centerX
        val dy = py - centerY
        val major = dx * c + dy * s
        val minor = (-dx * s + dy * c) / aspect
        return sqrt(major * major + minor * minor)
    }

    /**
     * One dab's premultiplied contribution at a canvas point, before the
     * buffer's blend: §7.3's `v_color * m`, where `v_color` is
     * `vec4(colour, 1) · (flow · area)`.
     *
     * [colorRgb] is **straight** sRGB, as `i_color` is; the premultiplication
     * is the `vec4(i_color, 1.0)` in the vertex shader — which is why an
     * eraser, whose colour is zero, still accumulates coverage in alpha.
     */
    fun contribution(
        px: Float,
        py: Float,
        dab: Dab,
        colorRgb: FloatArray,
        grainMode: GrainMode = GrainMode.None,
    ): StrokeMerge.Rgba {
        var m = coverageAt(px, py, dab)
        if (grainMode == GrainMode.Procedural) m *= proceduralGrain(px, py)
        if (m <= 0f) return StrokeMerge.Rgba.TRANSPARENT
        val w = dab.flow * areaWeight(dab.radius) * m
        return StrokeMerge.Rgba(colorRgb[0] * w, colorRgb[1] * w, colorRgb[2] * w, w)
    }

    /** CPU twin of `dab.frag`'s `fwidth(d)` ellipse feather. */
    private fun coverageAt(px: Float, py: Float, dab: Dab): Float {
        val c = cos(dab.angle)
        val s = sin(dab.angle)
        val dx = px - dab.x
        val dy = py - dab.y
        val major = dx * c + dy * s
        val minorScaled = (-dx * s + dy * c) / dab.aspect
        val d = sqrt(major * major + minorScaled * minorScaled)
        if (d <= GRADIENT_EPSILON) return coverage(d, dab.radius, dab.hardness)

        // fwidth is |dFdx| + |dFdy|. At the minor rim this is 1/aspect,
        // widening the local falloff so its canvas-space width stays 1 px.
        val invAspect = 1f / dab.aspect
        val gx = (major * c - minorScaled * s * invAspect) / d
        val gy = (major * s + minorScaled * c * invAspect) / d
        val feather = abs(gx) + abs(gy)
        return coverage(d, dab.radius, dab.hardness, feather)
    }

    /** Stable, canvas-anchored pencil grain shared with `dab.frag`. */
    fun proceduralGrain(px: Float, py: Float): Float {
        val x = floor(px.coerceAtLeast(0f)).toInt().toUInt()
        val y = floor(py.coerceAtLeast(0f)).toInt().toUInt()
        var hash = x * GRAIN_HASH_X.toUInt() + y * GRAIN_HASH_Y.toUInt()
        hash = hash xor (hash shr GRAIN_HASH_SHIFT)
        val unit = (hash and GRAIN_HASH_MASK.toUInt()).toFloat() / GRAIN_HASH_MASK
        return GRAIN_MIN_WEIGHT + (1f - GRAIN_MIN_WEIGHT) * unit
    }

    private fun coverage(d: Float, radius: Float, hardness: Float, feather: Float): Float {
        val r = drawRadius(radius)
        val inner = minOf(r * hardness, r - feather).coerceIn(0f, r)
        return 1f - smoothstep(inner, r, d)
    }

    /**
     * How a dab's contribution lands in the stroke buffer — §7.2's blend
     * state, which is the whole difference between a pencil and an ink pen.
     *
     * [BufferMode.Accumulate] is `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`:
     * premultiplied source-over, so flow builds up where a stroke crosses
     * itself. [BufferMode.Max] is `glBlendEquation(GL_MAX)`, which GL applies
     * **componentwise and without the blend factors** — so it is `max` on each
     * of r, g, b, a separately, not "keep whichever pixel has more alpha".
     * With one colour per stroke the two agree, but writing it as a
     * whole-pixel choice would silently diverge the day a grain texture
     * modulates colour per dab.
     */
    fun blendIntoBuffer(
        buffer: StrokeMerge.Rgba,
        dab: StrokeMerge.Rgba,
        mode: BufferMode,
    ): StrokeMerge.Rgba = when (mode) {
        BufferMode.Accumulate -> StrokeMerge.Rgba(
            r = dab.r + buffer.r * (1f - dab.a),
            g = dab.g + buffer.g * (1f - dab.a),
            b = dab.b + buffer.b * (1f - dab.a),
            a = dab.a + buffer.a * (1f - dab.a),
        )

        BufferMode.Max -> StrokeMerge.Rgba(
            r = max(buffer.r, dab.r),
            g = max(buffer.g, dab.g),
            b = max(buffer.b, dab.b),
            a = max(buffer.a, dab.a),
        )
    }

    /** Keeps the analytic and shader gradients finite at the dab centre. */
    const val GRADIENT_EPSILON = 1e-6f

    const val GRAIN_HASH_X = 1_664_525
    const val GRAIN_HASH_Y = 1_013_904_223
    const val GRAIN_HASH_SHIFT = 16
    const val GRAIN_HASH_MASK = 1_023
    const val GRAIN_MIN_WEIGHT = 0.55f
}
