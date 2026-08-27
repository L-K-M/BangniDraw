package ch.lkmc.bangnidraw.engine.core

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Stroke-local bristle contact and canvas-anchored paper tooth shared with `dab.frag`. */
internal object InkBrushMask {

    /**
     * A wet tuft joins into one mass. As ink falls, its fixed hair lanes and
     * paper tooth cross the contact threshold and expose real paper.
     */
    fun weight(px: Float, py: Float, dab: Dab): Float {
        val c = cos(dab.angle)
        val s = sin(dab.angle)
        val dx = px - dab.x
        val dy = py - dab.y
        val localMajor = dx * c + dy * s
        val localMinor = -dx * s + dy * c
        val across = dab.bristleAcross + localMinor
        val along = dab.bristleAlong + localMajor

        val minor = localMinor / dab.aspect
        val normalizedDistance = sqrt(localMajor * localMajor + minor * minor) /
            DabStamp.drawRadius(dab.radius)
        val edge = DabStamp.smoothstep(EDGE_DRY_START, 1f, normalizedDistance)
        val dry = (
            1f - dab.wetness.coerceIn(0f, 1f) + edge * EDGE_DRYING
            ).coerceIn(0f, 1f)
        if (dry <= LOADED_DRYNESS) return 1f

        // Anisotropic value noise yields irregular hair clusters without the
        // mechanical comb pattern of equally spaced stripes.
        val seed = seedKey(dab.seed)
        val fiber = valueNoise2(
            across / BRISTLE_WIDTH_PX + dab.seed * BRISTLE_PHASE,
            along / BREAK_LENGTH_PX + dab.seed * BREAK_PHASE,
            seed,
        )
        val tuft = valueNoise1(
            across / TUFT_WIDTH_PX + dab.seed * TUFT_PHASE,
            seed.toInt(),
            seed xor TUFT_SALT,
        )

        val paperX = floor(px).toInt()
        val paperY = floor(py).toInt()
        val paper = hashUnit(paperX, paperY, seed xor PAPER_SALT)

        var height = lerp(fiber, tuft, TUFT_WEIGHT)
        height -= dry * PAPER_TOOTH_DEPTH * (1f - paper)

        val thresholdCurve = dry.pow(DRY_THRESHOLD_POWER)
        val threshold = lerp(DRY_THRESHOLD_MIN, DRY_THRESHOLD_MAX, thresholdCurve)
        return DabStamp.smoothstep(
            threshold - CONTACT_FEATHER,
            threshold + CONTACT_FEATHER,
            height,
        )
    }

    private fun seedKey(seed: Float): UInt {
        val finite = if (seed.isFinite()) seed else 0f
        return floor(finite.coerceIn(0f, 1f) * SEED_SCALE).toUInt()
    }

    private fun hashUnit(x: Int, y: Int, seed: UInt): Float {
        var hash = x.toUInt() * HASH_X + y.toUInt() * HASH_Y + seed * HASH_SEED
        hash = hash xor (hash shr HASH_SHIFT_A)
        hash *= HASH_MIX
        hash = hash xor (hash shr HASH_SHIFT_B)
        return (hash and HASH_MASK).toFloat() / HASH_MASK.toFloat()
    }

    private fun valueNoise1(position: Float, y: Int, seed: UInt): Float {
        val index = floor(position).toInt()
        val fraction = position - floor(position)
        val blend = DabStamp.smoothstep(0f, 1f, fraction)
        return lerp(hashUnit(index, y, seed), hashUnit(index + 1, y, seed), blend)
    }

    private fun valueNoise2(x: Float, y: Float, seed: UInt): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val blendX = DabStamp.smoothstep(0f, 1f, x - floor(x))
        val blendY = DabStamp.smoothstep(0f, 1f, y - floor(y))
        val lower = lerp(hashUnit(x0, y0, seed), hashUnit(x0 + 1, y0, seed), blendX)
        val upper = lerp(
            hashUnit(x0, y0 + 1, seed),
            hashUnit(x0 + 1, y0 + 1, seed),
            blendX,
        )
        return lerp(lower, upper, blendY)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    const val BRISTLE_WIDTH_PX = 3.6f
    const val BREAK_LENGTH_PX = 32f
    const val DRY_THRESHOLD_MAX = 0.38f

    const val TUFT_WIDTH_PX = 12f
    const val LOADED_DRYNESS = 0.015f
    const val BRISTLE_PHASE = 37f
    const val BREAK_PHASE = 11f
    const val TUFT_PHASE = 19f
    const val TUFT_WEIGHT = 0.42f
    const val EDGE_DRY_START = 0.72f
    const val EDGE_DRYING = 0.22f
    const val PAPER_TOOTH_DEPTH = 0.1f
    const val DRY_THRESHOLD_MIN = 0.06f
    const val DRY_THRESHOLD_POWER = 1.35f
    const val CONTACT_FEATHER = 0.025f
    const val SEED_SCALE = 65_535f

    const val HASH_X = 1_664_525u
    const val HASH_Y = 1_013_904_223u
    const val HASH_SEED = 747_796_405u
    const val HASH_MIX = 2_246_822_519u
    const val HASH_MASK = 65_535u
    const val HASH_SHIFT_A = 16
    const val HASH_SHIFT_B = 13
    const val TUFT_SALT = 2_891_336_453u
    const val PAPER_SALT = 1_597_334_677u
}
