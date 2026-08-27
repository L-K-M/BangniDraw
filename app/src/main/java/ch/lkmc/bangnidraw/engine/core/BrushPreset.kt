package ch.lkmc.bangnidraw.engine.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The dab's footprint (`docs/plan/04-tools.md` §2). */
@Serializable
sealed interface TipShape {
    @Serializable
    @SerialName("round")
    data object Round : TipShape

    /** `aspect` is minor/major axis. A flat marker is about 0.3. */
    @Serializable
    @SerialName("flat")
    data class Flat(val aspect: Float) : TipShape {
        init {
            require(aspect.isFinite() && aspect in MIN_ASPECT..1f) {
                "flat tip aspect must be $MIN_ASPECT..1, was $aspect"
            }
        }

        companion object {
            const val MIN_ASPECT = 0.1f
        }
    }
}

/** What sets a dab's angle (`docs/plan/04-tools.md` §2). */
@Serializable
enum class TipOrientation {
    /** Always 0. A round tip cannot tell the difference. */
    Fixed,

    /** The stylus azimuth: a chisel tip that turns with the pen. */
    Stylus,

    /** The tangent of the stabilized path: the brush "rolls" through turns. */
    StrokeDirection,
}

/**
 * What tilting the stylus does (`docs/plan/04-tools.md` §2). Multipliers are
 * at full tilt (π/2, flat against the glass) and lerp by `tilt / (π/2)`.
 */
@Serializable
data class TiltEffect(
    val sizeAtFlat: Float = 1f,
    val opacityAtFlat: Float = 1f,
    /** Stretch the dab along the tilt azimuth — the side of a pencil. */
    val elongate: Boolean = false,
) {
    init {
        require(sizeAtFlat.isFinite() && sizeAtFlat in MIN_MUL..MAX_MUL) {
            "tilt sizeAtFlat must be $MIN_MUL..$MAX_MUL, was $sizeAtFlat"
        }
        require(opacityAtFlat.isFinite() && opacityAtFlat in 0f..MAX_MUL) {
            "tilt opacityAtFlat must be 0..$MAX_MUL, was $opacityAtFlat"
        }
    }

    companion object {
        const val MIN_MUL = 0.05f
        const val MAX_MUL = 8f
        val None = TiltEffect()
    }
}

/**
 * What drawing speed does (`docs/plan/04-tools.md` §2). Speed is measured in
 * **canvas** px per ms, so zoom does not change the feel.
 */
@Serializable
data class VelocityEffect(
    val sizeAtFast: Float = 1f,
    val opacityAtFast: Float = 1f,
    val fastPxPerMs: Float = 4f,
) {
    init {
        require(sizeAtFast.isFinite() && sizeAtFast in TiltEffect.MIN_MUL..TiltEffect.MAX_MUL) {
            "velocity sizeAtFast must be ${TiltEffect.MIN_MUL}..${TiltEffect.MAX_MUL}, was $sizeAtFast"
        }
        require(opacityAtFast.isFinite() && opacityAtFast in 0f..TiltEffect.MAX_MUL) {
            "velocity opacityAtFast must be 0..${TiltEffect.MAX_MUL}, was $opacityAtFast"
        }
        // Divided by, to normalize speed into 0..1.
        require(fastPxPerMs.isFinite() && fastPxPerMs > 0f) {
            "velocity fastPxPerMs must be positive, was $fastPxPerMs"
        }
    }

    companion object {
        val None = VelocityEffect()
    }
}

/**
 * Per-dab randomness (`docs/plan/04-tools.md` §2). No hue jitter in v1: the
 * stroke buffer accumulates coverage only and a stroke has one colour
 * (`09-color-and-mixing.md` §3.1), so the merge could not honour it.
 */
@Serializable
data class Jitter(
    /** ± this fraction of size, uniform. */
    val size: Float = 0f,
    /** ± this fraction of radius, per axis. */
    val position: Float = 0f,
) {
    init {
        require(size.isFinite() && size in 0f..1f) { "size jitter must be 0..1, was $size" }
        require(position.isFinite() && position in 0f..1f) {
            "position jitter must be 0..1, was $position"
        }
    }

    companion object {
        val None = Jitter()
    }
}

/**
 * How a dab lands in the stroke buffer (`docs/plan/04-tools.md` §2,
 * `03-canvas-engine.md` §7.2).
 */
@Serializable
enum class BufferMode {
    /** `a = max(a, dabA)` — flat; overlapping within one stroke never darkens. */
    Max,

    /** `a = a + dabA·(1 − a)` — builds up, still capped at `opacity` by the merge. */
    Accumulate,
}

/** Grain path selected once at pen-down and fixed for the stroke. */
enum class GrainMode(val shaderId: Int) {
    None(0),
    Procedural(1),
}

/**
 * Every parameter a brush can have (`docs/plan/04-tools.md` §2). All
 * serializable, all defaulted, so a preset JSON can omit what it does not
 * care about — and an older build can open a newer preset *provided the
 * parser sets* `ignoreUnknownKeys = true`. The defaults here only cover
 * omitted fields; an unknown key still throws under kotlinx's default
 * configuration, so `BrushPresetStore` owes that setting.
 *
 * Sizes are **canvas** pixels — never screen px, never dp. A pencil is the
 * same width on the paper at any zoom.
 *
 * An eraser is not a separate kind: it is a preset with [eraseMode] set. It
 * goes through the same stabilizer, generator and stroke buffer as a pencil,
 * and only the merge differs (§3.7).
 */
@Serializable
data class BrushPreset(
    /** Stable. `builtin.*` for the shipped set; user copies get UUIDs. */
    val id: String,
    /**
     * `@string/…` resolves through resources at display time; any other value
     * is shown verbatim, so a user's rename survives (`01-product.md` §8).
     * The same rule governs layer names — see `LayerStack`'s name grammar.
     */
    val name: String,
    /** Rail glyph key. */
    val icon: String = "round",
    /** Diameter in canvas px: the slider value. */
    val size: Float = 12f,
    val sizeMin: Float = 1f,
    val sizeMax: Float = 400f,
    /** The stroke-buffer ceiling, applied once in the merge — never per dab. */
    val opacity: Float = 1f,
    /** Per-dab weight. */
    val flow: Float = 1f,
    /** 0 is a gaussian-ish falloff, 1 a solid disc with a 1 px AA skirt. */
    val hardness: Float = 0.8f,
    /**
     * Distance between dab centres as a fraction of the dab **radius**
     * (`PLAN.md` §3.1: `step = spacing · radius`). Most apps' sliders say
     * "% of brush size", i.e. of the diameter, so the settings sheet displays
     * `spacing / 2` — the stored value is the radius fraction.
     */
    val spacing: Float = 0.30f,
    val tip: TipShape = TipShape.Round,
    val orientation: TipOrientation = TipOrientation.Fixed,
    /** Pressure → size multiplier. */
    val pressureSize: Curve = Curve.Linear,
    /** Pressure → the stroke's opacity ceiling. */
    val pressureOpacity: Curve = Curve.One,
    /** Pressure → per-dab alpha. */
    val pressureFlow: Curve = Curve.One,
    val tilt: TiltEffect = TiltEffect.None,
    val velocity: VelocityEffect = VelocityEffect.None,
    val jitter: Jitter = Jitter.None,
    /** `0..1`; see `Stabilizer`. */
    val stabilizer: Float = 0.3f,
    /** Mixbox pigment merge instead of alpha-over (`09` §3.1). */
    val mixing: Boolean = false,
    /** Mixing only: how much the paint's share yields to what is under it. */
    val dilution: Float = 0f,
    /** Reserved `procedural` key now; a tileable asset key when grains land. */
    val grain: String? = null,
    val eraseMode: Boolean = false,
    val bufferMode: BufferMode = BufferMode.Max,
) {
    init {
        require(id.isNotBlank()) { "preset id must not be blank" }
        require(name.isNotBlank()) { "preset $id has a blank name" }
        // Sizes bound the dab radius, which bounds the dirty rect, which sizes
        // the quad DabPass draws. A non-finite or inverted range here becomes
        // a NaN radius that silently paints nothing, or a rect that covers the
        // canvas — both far from this record.
        require(sizeMin.isFinite() && sizeMin >= MIN_SIZE) {
            "preset $id: sizeMin must be at least $MIN_SIZE, was $sizeMin"
        }
        require(sizeMax.isFinite() && sizeMax <= MAX_SIZE && sizeMax >= sizeMin) {
            "preset $id: sizeMax must be $sizeMin..$MAX_SIZE, was $sizeMax"
        }
        require(size.isFinite() && size in sizeMin..sizeMax) {
            "preset $id: size must be $sizeMin..$sizeMax, was $size"
        }
        require(opacity.isFinite() && opacity in 0f..1f) {
            "preset $id: opacity must be 0..1, was $opacity"
        }
        require(flow.isFinite() && flow in 0f..1f) { "preset $id: flow must be 0..1, was $flow" }
        require(hardness.isFinite() && hardness in 0f..1f) {
            "preset $id: hardness must be 0..1, was $hardness"
        }
        // A spacing of 0 would emit dabs forever along any segment. The
        // generator also floors the step at half a pixel, but that floor is a
        // resolution limit, not a licence for a preset to ask for zero.
        require(spacing.isFinite() && spacing in MIN_SPACING..MAX_SPACING) {
            "preset $id: spacing must be $MIN_SPACING..$MAX_SPACING of the radius, was $spacing"
        }
        require(stabilizer.isFinite() && stabilizer in 0f..1f) {
            "preset $id: stabilizer must be 0..1, was $stabilizer"
        }
        require(dilution.isFinite() && dilution in 0f..1f) {
            "preset $id: dilution must be 0..1, was $dilution"
        }
        // Two merges, not one: `04` §3.7's eraser scales the destination's
        // alpha down, `09` §3.1's mixing blends pigment into it. A preset
        // asking for both leaves whichever branch the merge tests first
        // silently winning, and the misbehaviour surfaces in the merge pass
        // rather than at the preset that caused it.
        require(!(eraseMode && mixing)) {
            "preset $id: eraseMode and mixing are different merges (04 §3.7, 09 §3.1)"
        }
        require(grain == null || grain.isNotBlank()) {
            "preset $id: grain must be a key or absent, not blank"
        }
    }

    /** The radius the slider's [size] means, before any dynamics. */
    val baseRadius: Float get() = size / 2f

    /** Unknown texture keys stay inert until the post-v1 texture loader exists. */
    val grainMode: GrainMode
        get() = if (grain == PROCEDURAL_GRAIN) GrainMode.Procedural else GrainMode.None

    /** [size] with the slider moved to [value], clamped into the preset's range. */
    fun withSize(value: Float): BrushPreset =
        copy(size = if (value.isNaN()) size else value.coerceIn(sizeMin, sizeMax))

    /** [opacity] with the slider moved to [value], clamped. */
    fun withOpacity(value: Float): BrushPreset =
        copy(opacity = if (value.isNaN()) opacity else value.coerceIn(0f, 1f))

    companion object {
        /** A dab thinner than this could not be anti-aliased into anything. */
        const val MIN_SIZE = 0.5f

        /**
         * One dab quad must stay a reasonable dirty rect
         * (`04-tools.md` §3.5's hard ceiling, expressed as a diameter).
         */
        const val MAX_SIZE = 2048f

        const val MIN_SPACING = 0.01f
        const val MAX_SPACING = 4f

        const val PROCEDURAL_GRAIN = "procedural"
    }
}
