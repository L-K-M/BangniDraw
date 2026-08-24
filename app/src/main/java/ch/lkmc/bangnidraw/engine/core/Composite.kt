package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE

/**
 * Reads one tile of one layer as premultiplied ARGB.
 *
 * `null` means "no pixels for this key" and is always a legal answer, even for
 * a key the layer's tile set lists. A disk-backed reader returns it for a tile
 * whose file failed validation, which
 * `docs/plan/06-document-and-persistence.md` §4 requires to be treated as
 * empty and logged rather than allowed to fail the open; a GPU-backed reader
 * returns it for a slice whose readback has not landed yet. [Composite.tile]
 * therefore treats `null` as transparent instead of raising.
 */
fun interface TileReader {
    fun read(layer: LayerId, key: TileKey): IntArray?
}

/**
 * The CPU reference compositor — the pinned semantics the GLSL must match.
 *
 * Pixels are **premultiplied ARGB8888** packed into an `Int`
 * (`0xAARRGGBB`), non-linear sRGB values, exactly as the tiles are stored on
 * the GPU and on disk (`docs/plan/03-canvas-engine.md` §2.4). The arithmetic
 * is `docs/plan/05-layers.md` §4, which is normative: when this file changes,
 * `engine/gl/Shaders`' `blendLayer` changes in the same commit, and vice
 * versa.
 *
 * It is also the flatten/export path when the GPU is unavailable.
 */
object Composite {
    const val TRANSPARENT = 0

    /** Alpha of a premultiplied ARGB pixel, 0..255. */
    fun alpha(p: Int): Int = p ushr 24

    fun red(p: Int): Int = (p ushr 16) and 0xFF

    fun green(p: Int): Int = (p ushr 8) and 0xFF

    fun blue(p: Int): Int = p and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Straight (non-premultiplied) ARGB → premultiplied, round-to-nearest. */
    fun premultiply(straight: Int): Int {
        val a = alpha(straight)
        if (a == 255) return straight
        if (a == 0) return TRANSPARENT
        return argb(a, mul(red(straight), a), mul(green(straight), a), mul(blue(straight), a))
    }

    private fun mul(c: Int, a: Int): Int = (c * a + 127) / 255

    /**
     * One layer pixel [src] over one destination pixel [dst], both
     * premultiplied, with [opacity] applied to the source first (which scales
     * colour and alpha together — correct for premultiplied data).
     *
     * Argument order is `(dst, src)`, the one `CompositeTest` uses.
     */
    fun blend(dst: Int, src: Int, mode: BlendMode, opacity: Float): Int {
        // Not coerceIn: it returns NaN unchanged, `NaN == 0f` is false so the
        // early return below would not fire, every channel would come out NaN,
        // and quantize truncates NaN to 0 — a silently erased pixel, which is
        // the worst failure a paint program has. The same sanitizer the layer
        // model uses, so a corrupt opacity degrades to fully visible here too.
        val o = LayerProps.sanitizeOpacity(opacity)
        if (o == 0f) return dst
        val sa = alpha(src) / 255f * o
        val sr = red(src) / 255f * o
        val sg = green(src) / 255f * o
        val sb = blue(src) / 255f * o
        val da = alpha(dst) / 255f
        val dr = red(dst) / 255f
        val dg = green(dst) / 255f
        val db = blue(dst) / 255f

        val bothR: Float
        val bothG: Float
        val bothB: Float
        when (mode) {
            BlendMode.NORMAL -> {
                bothR = sr * da; bothG = sg * da; bothB = sb * da
            }
            BlendMode.MULTIPLY -> {
                bothR = sr * dr; bothG = sg * dg; bothB = sb * db
            }
            BlendMode.SCREEN -> {
                bothR = sr * da + dr * sa - sr * dr
                bothG = sg * da + dg * sa - sg * dg
                bothB = sb * da + db * sa - sb * db
            }
            BlendMode.OVERLAY -> {
                bothR = overlay(sr, sa, dr, da)
                bothG = overlay(sg, sa, dg, da)
                bothB = overlay(sb, sa, db, da)
            }
            BlendMode.DARKEN -> {
                bothR = minOf(sr * da, dr * sa)
                bothG = minOf(sg * da, dg * sa)
                bothB = minOf(sb * da, db * sa)
            }
            BlendMode.LIGHTEN -> {
                bothR = maxOf(sr * da, dr * sa)
                bothG = maxOf(sg * da, dg * sa)
                bothB = maxOf(sb * da, db * sa)
            }
            BlendMode.ADD -> {
                val cap = sa * da
                bothR = minOf(cap, sr * da + dr * sa)
                bothG = minOf(cap, sg * da + dg * sa)
                bothB = minOf(cap, sb * da + db * sa)
            }
            BlendMode.DIFFERENCE -> {
                bothR = kotlin.math.abs(sr * da - dr * sa)
                bothG = kotlin.math.abs(sg * da - dg * sa)
                bothB = kotlin.math.abs(sb * da - db * sa)
            }
        }
        val outA = sa + da * (1f - sa)
        val outR = sr * (1f - da) + dr * (1f - sa) + bothR
        val outG = sg * (1f - da) + dg * (1f - sa) + bothG
        val outB = sb * (1f - da) + db * (1f - sa) + bothB
        val a8 = quantize(outA)
        return argb(a8, minOf(quantize(outR), a8), minOf(quantize(outG), a8), minOf(quantize(outB), a8))
    }

    /** `B(cs, cd)` for overlay, folded back into the joint-coverage term. */
    private fun overlay(s: Float, sa: Float, d: Float, da: Float): Float {
        val cs = if (sa > 0f) s / sa else 0f
        val cd = if (da > 0f) d / da else 0f
        val b = if (cd <= 0.5f) 2f * cs * cd else 1f - 2f * (1f - cs) * (1f - cd)
        return sa * da * b
    }

    /** Plain source-over at full opacity. */
    fun over(dst: Int, src: Int): Int = blend(dst, src, BlendMode.NORMAL, 1f)

    /**
     * An erase dab: the source's alpha is coverage, its colour is ignored.
     * `Cr = d.rgb·(1 − s.a)`, `Ar = d.a·(1 − s.a)` (`docs/plan/04-tools.md` §3.7).
     */
    fun erase(dst: Int, src: Int): Int {
        val inv = 1f - alpha(src) / 255f
        if (inv >= 1f) return dst
        val a = quantize(alpha(dst) / 255f * inv)
        return argb(
            a,
            minOf(quantize(red(dst) / 255f * inv), a),
            minOf(quantize(green(dst) / 255f * inv), a),
            minOf(quantize(blue(dst) / 255f * inv), a),
        )
    }

    /**
     * Painting onto an alpha-locked layer: normal, with the alpha forced to
     * the destination's (`docs/plan/05-layers.md` §1).
     */
    fun alphaLocked(dst: Int, src: Int): Int {
        val sa = alpha(src) / 255f
        val da = alpha(dst) / 255f
        val a = alpha(dst)
        return argb(
            a,
            minOf(quantize(red(dst) / 255f * (1f - sa) + red(src) / 255f * da), a),
            minOf(quantize(green(dst) / 255f * (1f - sa) + green(src) / 255f * da), a),
            minOf(quantize(blue(dst) / 255f * (1f - sa) + blue(src) / 255f * da), a),
        )
    }

    /**
     * The whole stack over [paper] for one tile — the reference flatten and
     * export path, and what the JVM tests compare merge and flatten against.
     *
     * [layers] are composited bottom to top exactly as given; visibility is
     * the caller's filter, because merge down deliberately composites two
     * layers that the compositor would otherwise treat differently.
     *
     * A layer contributes nothing where [pixels] has no tile for it — see
     * [TileReader]: an unreadable tile is transparent, never an exception,
     * because this is also the flatten and export path and a painting with one
     * bad tile must still open. A tile of the *wrong size* is a different
     * matter and does raise: that is a programming error in the reader, not
     * damage on disk.
     */
    fun tile(layers: List<Layer>, key: TileKey, paper: Int, pixels: TileReader): IntArray {
        val ground = premultiply(paper)
        val out = IntArray(TILE_SIZE * TILE_SIZE) { ground }
        for (layer in layers) {
            if (key !in layer.tiles) continue
            val src = pixels.read(layer.id, key) ?: continue
            require(src.size == out.size) {
                "tile ${key.tx},${key.ty} of ${layer.id.value} has ${src.size} pixels, expected ${out.size}"
            }
            val mode = layer.props.blendMode
            val opacity = layer.props.opacity
            for (i in out.indices) {
                out[i] = blend(out[i], src[i], mode, opacity)
            }
        }
        return out
    }

    /**
     * Round-to-nearest to 8 bits — the same conversion the GPU applies when
     * writing a UNORM8 target, so CPU and GPU agree on the last bit.
     */
    private fun quantize(v: Float): Int = ((v * 255f) + 0.5f).toInt().coerceIn(0, 255)
}
