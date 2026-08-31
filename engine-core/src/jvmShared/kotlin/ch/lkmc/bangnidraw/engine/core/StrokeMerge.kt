package ch.lkmc.bangnidraw.engine.core

/**
 * The pure-JVM twin of `merge.frag`'s `mergeStroke`
 * (`docs/plan/03-canvas-engine.md` §7.4, §15's rule).
 *
 * §15 requires everything decision-shaped to have a twin here that the GL
 * class calls or is tested against, and the merge arithmetic is the most
 * decision-shaped thing in the engine: it decides what a stroke *is*. The
 * shader cannot run on the JVM, so this file is what the tests pin, and
 * `GlShaderContractTest` checks that `merge.frag` carries the same branches.
 *
 * **Premultiplied throughout**, matching §2.4 and the pipeline's RGBA8. A
 * premultiplied colour has `rgb ≤ a` componentwise; [Rgba.isPremultiplied]
 * says so, and every function here preserves it.
 *
 * Floats, not the packed Ints of [Composite], because this is the semantics of
 * a shader that works in floats — quantising in the middle would build
 * rounding into the reference. `StrokeMergeTest` cross-checks it against
 * [Composite]'s Int path at 8-bit precision, which is what
 * `docs/plan/12-roadmap.md`'s 2.4b row asks for and PLAN.md §7 means by "the
 * CPU reference is what pins the shader semantics".
 */
object StrokeMerge {

    /** Premultiplied RGBA, 0..1 per channel. */
    data class Rgba(val r: Float, val g: Float, val b: Float, val a: Float) {

        /** Premultiplication is an invariant, not a convention: `rgb ≤ a`. */
        fun isPremultiplied(epsilon: Float = 1e-5f): Boolean =
            a >= -epsilon && a <= 1f + epsilon &&
                r >= -epsilon && g >= -epsilon && b >= -epsilon &&
                r <= a + epsilon && g <= a + epsilon && b <= a + epsilon

        operator fun times(k: Float) = Rgba(r * k, g * k, b * k, a * k)

        companion object {
            val TRANSPARENT = Rgba(0f, 0f, 0f, 0f)

            /** Straight colour plus coverage → premultiplied. */
            fun straight(r: Float, g: Float, b: Float, a: Float) = Rgba(r * a, g * a, b * a, a)
        }
    }

    /**
     * How the two straight colours are interpolated in [StrokeMode.MIX].
     *
     * A seam, not indirection: §7.4 makes mixing a **compile-time shader
     * variant** (`MIXLERP` is `mix` or `mixbox_lerp`), so the JVM twin needs
     * the same seam or it can only pin one of the two programs. [Linear] is
     * the `mix` variant, which decision 5's `RgbMixer` makes the default and
     * the only one `Shaders` builds today; the pigment variant arrives with
     * `MixboxMixer` in `09-color-and-mixing.md` §5 and plugs in here.
     */
    fun interface ColorLerp {
        /** Straight (non-premultiplied) colours; `t` is already clamped to 0..1. */
        fun lerp(from: FloatArray, to: FloatArray, t: Float, out: FloatArray)

        companion object {
            val Linear = ColorLerp { from, to, t, out ->
                for (i in 0..2) out[i] = from[i] + (to[i] - from[i]) * t
            }
        }
    }

    /**
     * Reusable scratch for [merge]'s straight-colour round trip.
     *
     * A field rather than three `FloatArray(3)` default arguments, because the
     * real callers — a tile-wide fill (§10.3), the cross-check tests — run this
     * 65 536 times per tile, and defaulted arguments would allocate on every
     * one of them. [StrokeMode.MIX] is the only mode that touches it.
     */
    class Scratch {
        internal val from = FloatArray(3)
        internal val to = FloatArray(3)
        internal val out = FloatArray(3)
    }

    /**
     * The stroke buffer capped at the stroke's opacity ceiling: §7.4's
     * `S' = S · min(1, o / S.a)`, so `S'.a = min(S.a, o)` with colour scaled
     * to stay premultiplied.
     *
     * Guarded on `S.a > o` rather than written as an unconditional
     * `min(1, o/S.a)` because `S.a == 0` would divide by zero — the shader has
     * the same guard for the same reason, and a fully transparent buffer tile
     * is the common case wherever a stroke's bounding box overhangs its marks.
     */
    fun cap(s: Rgba, opacity: Float): Rgba =
        if (s.a > opacity) s * (opacity / s.a) else s

    /**
     * §7.4's table, for one pixel. [layer] and [stroke] are premultiplied; the
     * result is premultiplied.
     *
     * [stroke] is the **raw** buffer value — capping happens here, once, so no
     * caller can forget it and none can apply it twice.
     */
    fun merge(
        layer: Rgba,
        stroke: Rgba,
        spec: StrokeSpec,
        lerp: ColorLerp = ColorLerp.Linear,
        scratch: Scratch = Scratch(),
    ): Rgba {
        // §7.6: an RMW stroke writes the layer directly, dab by dab, and never
        // reaches a merge — so a spec with `rmw` set here is a caller that has
        // mistaken which path it is on. Without this it would quietly return a
        // plausible PAINT composite for a stroke that should have been smudged,
        // and a reference that answers wrong questions confidently is worse
        // than one that refuses. Same reasoning as `StrokeSpec.init`'s guard.
        require(spec.rmw == null) {
            "RMW strokes bypass the stroke buffer and never merge (§7.6): $spec"
        }
        val s = cap(stroke, spec.opacity)
        return when (spec.mode) {
            // 05 §1: the eraser is a no-op on an alpha-locked layer. Not "erase
            // and then restore alpha" — that would strip the colour and keep
            // the coverage, leaving a locked layer full of transparent black.
            // Nothing happens at all.
            StrokeMode.ERASE ->
                if (spec.alphaLock) layer else layer * (1f - s.a)

            StrokeMode.PAINT ->
                if (spec.alphaLock) {
                    Rgba(
                        r = s.r * layer.a + layer.r * (1f - s.a),
                        g = s.g * layer.a + layer.g * (1f - s.a),
                        b = s.b * layer.a + layer.b * (1f - s.a),
                        a = layer.a,
                    )
                } else {
                    Rgba(
                        r = s.r + layer.r * (1f - s.a),
                        g = s.g + layer.g * (1f - s.a),
                        b = s.b + layer.b * (1f - s.a),
                        a = s.a + layer.a * (1f - s.a),
                    )
                }

            StrokeMode.MIX -> mix(layer, s, spec, lerp, scratch)
        }
    }

    /**
     * `bangni_mix_over` of `09-color-and-mixing.md` §3.1, in §7.4's symbols.
     *
     * Alpha is ordinary source-over; the interpolation weight is the share the
     * stroke would have had in that source-over, reduced by `dilution` where
     * the layer already holds paint.
     */
    private fun mix(layer: Rgba, s: Rgba, spec: StrokeSpec, lerp: ColorLerp, scratch: Scratch): Rgba {
        var aOut = s.a + layer.a * (1f - s.a)
        var t = if (aOut > 0f) s.a / aOut else 0f
        if (layer.a > 0f) t *= 1f - spec.dilution
        if (spec.alphaLock) {
            // 05 §1's lerp(cd, cs, s.a), with the layer's coverage kept.
            t = s.a
            aOut = layer.a
        }
        if (aOut <= 0f) return Rgba.TRANSPARENT

        // §7.4's guards, and they are semantics rather than an optimisation:
        // the straight colour of a zero-alpha pixel does not exist, so there is
        // nothing to interpolate towards or from. Returning the other side
        // whole is also exact, where reconstructing it as `rgb / a * aOut`
        // would round twice for no gain — this is what §7.4 means by keeping
        // MIX bit-exact with PAINT where only one side has colour.
        if (layer.a <= 0f) return if (s.a <= 0f) Rgba.TRANSPARENT else s
        if (s.a <= 0f) return layer

        scratch.from[0] = layer.r / layer.a
        scratch.from[1] = layer.g / layer.a
        scratch.from[2] = layer.b / layer.a
        scratch.to[0] = s.r / s.a
        scratch.to[1] = s.g / s.a
        scratch.to[2] = s.b / s.a
        lerp.lerp(scratch.from, scratch.to, t.coerceIn(0f, 1f), scratch.out)
        return Rgba(scratch.out[0] * aOut, scratch.out[1] * aOut, scratch.out[2] * aOut, aOut)
    }
}
