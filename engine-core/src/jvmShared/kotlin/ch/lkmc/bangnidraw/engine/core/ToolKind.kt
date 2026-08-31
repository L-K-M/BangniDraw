package ch.lkmc.bangnidraw.engine.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Smudge parameters (`docs/plan/04-tools.md` §6). Read-modify-write: the
 * result depends on what was already under the dab, so smudge bypasses the
 * stroke buffer and "never exceed opacity" is meaningless for it.
 */
@Serializable
data class SmudgeParams(
    val size: Float = 40f,
    val sizeMin: Float = 4f,
    val sizeMax: Float = 400f,
    val hardness: Float = 0.5f,
    /** Fraction of the radius, as `BrushPreset`; `03` §7.6 floors RMW spacing at 0.25·r. */
    val spacing: Float = 0.16f,
    /** How much of the pickup is deposited per dab. */
    val strength: Float = 0.7f,
    /** How much fresh colour the pickup buffer absorbs per dab; 0 is a "clean finger". */
    val pickupRate: Float = 0.5f,
    /** Mixbox latent blending in the pickup. */
    val mixing: Boolean = true,
    val pressureStrength: Curve = Curve.Linear,
    val stabilizer: Float = 0.3f,
) {
    init {
        requireToolSizes("smudge", size, sizeMin, sizeMax)
        requireUnit("smudge", "hardness", hardness)
        requireUnit("smudge", "strength", strength)
        requireUnit("smudge", "pickupRate", pickupRate)
        requireUnit("smudge", "stabilizer", stabilizer)
        requireSpacing("smudge", spacing)
    }
}

/**
 * Blur parameters (`docs/plan/04-tools.md` §6). Read-modify-write, like
 * smudge; the kernel is a separable box blur run twice.
 */
@Serializable
data class BlurParams(
    val size: Float = 60f,
    val sizeMin: Float = 8f,
    val sizeMax: Float = 400f,
    /** Blend of the blurred result over the original. */
    val strength: Float = 0.5f,
    /** Kernel radius is `size · radiusFraction`, clamped to 1..24 px. */
    val radiusFraction: Float = 0.15f,
    val spacing: Float = 0.30f,
    val pressureStrength: Curve = Curve.Linear,
) {
    init {
        requireToolSizes("blur", size, sizeMin, sizeMax)
        requireUnit("blur", "strength", strength)
        requireUnit("blur", "radiusFraction", radiusFraction)
        requireSpacing("blur", spacing)
    }
}

/** Clear water that reactivates and carries pigment without adding color. */
@Serializable
data class WaterParams(
    val size: Float = 72f,
    val sizeMin: Float = 8f,
    val sizeMax: Float = 400f,
    val hardness: Float = 0.2f,
    val spacing: Float = 0.18f,
    val waterLoad: Float = 0.75f,
    val spread: Float = 0.65f,
    val granulation: Float = 0.2f,
    val edgeDarkening: Float = 0.3f,
    val pressureWater: Curve = Curve.Linear,
    val stabilizer: Float = 0.2f,
) {
    init {
        requireToolSizes("water", size, sizeMin, sizeMax)
        require(sizeMax <= WatercolorDabPlan.MAX_DIAMETER_PX) {
            "water size exceeds the GLES scratch bound"
        }
        requireUnit("water", "hardness", hardness)
        requireSpacing("water", spacing)
        requireUnit("water", "waterLoad", waterLoad)
        requireUnit("water", "spread", spread)
        requireUnit("water", "granulation", granulation)
        requireUnit("water", "edgeDarkening", edgeDarkening)
        requireUnit("water", "stabilizer", stabilizer)
    }

    val behavior: WatercolorBehavior
        get() = WatercolorBehavior(
            waterLoad = waterLoad,
            spread = spread,
            granulation = granulation,
            edgeDarkening = edgeDarkening,
        )

    fun withSize(value: Float): WaterParams =
        copy(size = if (value.isNaN()) size else value.coerceIn(sizeMin, sizeMax))

    fun withWaterLoad(value: Float): WaterParams =
        copy(waterLoad = if (value.isNaN()) waterLoad else value.coerceIn(0f, 1f))
}

/** What a fill measures its tolerance against (`docs/plan/04-tools.md` §7). */
@Serializable
enum class FillReference { CurrentLayer, Composite }

/** Fill parameters (`docs/plan/04-tools.md` §7). */
@Serializable
data class FillParams(
    /** `0..1` of the maximum colour distance. */
    val tolerance: Float = 0.1f,
    /** False means global: every matching pixel, not just the connected region. */
    val contiguous: Boolean = true,
    val reference: FillReference = FillReference.Composite,
    /** Pixels of dilation, to close anti-aliased outlines. */
    val expand: Int = 2,
    val antialias: Boolean = true,
    val opacity: Float = 1f,
) {
    init {
        requireUnit("fill", "tolerance", tolerance)
        requireUnit("fill", "opacity", opacity)
        require(expand in 0..MAX_EXPAND) { "fill expand must be 0..$MAX_EXPAND px, was $expand" }
    }

    companion object {
        const val MAX_EXPAND = 8
    }
}

/** What the eyedropper reads (`docs/plan/04-tools.md` §8). */
@Serializable
enum class SampleSource { CurrentLayer, Composite }

/** Eyedropper parameters (`docs/plan/04-tools.md` §8). */
@Serializable
data class EyedropperParams(
    val source: SampleSource = SampleSource.Composite,
    /** 0 is a single pixel; 1 is a 3×3 average. */
    val radius: Int = 0,
) {
    init {
        require(radius in 0..MAX_RADIUS) {
            "eyedropper radius must be 0..$MAX_RADIUS, was $radius"
        }
    }

    companion object {
        const val MAX_RADIUS = 4
    }
}

/**
 * What kind of tool this is, and its parameters
 * (`docs/plan/04-tools.md` §1).
 *
 * `engine/core` knows *kinds and parameters*; `tools/` knows what engine
 * operations a kind performs when the pen moves. Nothing here imports
 * Android or GL, which is what lets the whole catalogue be unit-tested.
 *
 * There is no `Eraser` arm on purpose: an eraser is a [BrushPreset] with
 * `eraseMode` set, so it shares the stabilizer, the dab generator and the
 * stroke buffer with every other brush and differs only at the merge. A
 * separate kind would duplicate almost all of the brush path for one
 * boolean. The rail still shows "Eraser" as its own slot — a rail slot is a
 * *preset*, not a kind.
 */
@Serializable
sealed interface ToolKind {
    @Serializable
    @SerialName("brush")
    data class Brush(val preset: BrushPreset) : ToolKind

    @Serializable
    @SerialName("smudge")
    data class Smudge(val params: SmudgeParams = SmudgeParams()) : ToolKind

    @Serializable
    @SerialName("blur")
    data class Blur(val params: BlurParams = BlurParams()) : ToolKind

    @Serializable
    @SerialName("water")
    data class Water(val params: WaterParams = WaterParams()) : ToolKind

    @Serializable
    @SerialName("fill")
    data class Fill(val params: FillParams = FillParams()) : ToolKind

    @Serializable
    @SerialName("eyedropper")
    data class Eyedropper(val params: EyedropperParams = EyedropperParams()) : ToolKind
}

private fun requireUnit(tool: String, field: String, value: Float) {
    require(value.isFinite() && value in 0f..1f) { "$tool $field must be 0..1, was $value" }
}

private fun requireSpacing(tool: String, spacing: Float) {
    require(spacing.isFinite() && spacing in BrushPreset.MIN_SPACING..BrushPreset.MAX_SPACING) {
        "$tool spacing must be ${BrushPreset.MIN_SPACING}..${BrushPreset.MAX_SPACING}" +
            " of the radius, was $spacing"
    }
}

private fun requireToolSizes(tool: String, size: Float, min: Float, max: Float) {
    require(min.isFinite() && min >= BrushPreset.MIN_SIZE) {
        "$tool sizeMin must be at least ${BrushPreset.MIN_SIZE}, was $min"
    }
    require(max.isFinite() && max <= BrushPreset.MAX_SIZE && max >= min) {
        "$tool sizeMax must be $min..${BrushPreset.MAX_SIZE}, was $max"
    }
    require(size.isFinite() && size in min..max) { "$tool size must be $min..$max, was $size" }
}
