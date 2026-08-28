package ch.lkmc.bangnidraw.engine.core

/**
 * What a stroke does to the layer it lands on
 * (`docs/plan/03-canvas-engine.md` §6, §7.4).
 *
 * `MIX` is `PAINT` with pigment mixing at merge, not a separate compositing
 * path: §7.4's table gives it the same alpha arithmetic and differs only in
 * how the two straight colours are interpolated.
 */
enum class StrokeMode {
    PAINT,
    ERASE,
    MIX,
}

/**
 * Direct read-modify-write tools cannot use a stroke buffer: their result at
 * dab *n* depends on the layer as modified by dab *n−1*,
 * so they write the layer directly, dab by dab, in order.
 *
 * Declared here because [StrokeSpec] has to be able to say "this stroke
 * bypasses the buffer" before `SmudgePass` exists — the branch is what keeps
 * `StrokeBuffer` from being handed a stroke it cannot represent.
 */
enum class RmwMixing { Linear, Pigment }

/** The journal entry created after a buffered pixel commit. */
enum class PixelCommitKind { Stroke, Fill }

/** Parameters fixed for one direct-to-layer stroke. */
sealed interface RmwSpec {
    data class Smudge(
        val pickupRate: Float,
        val pickupEdge: Int,
        val mixing: RmwMixing,
    ) : RmwSpec {
        init {
            require(pickupRate in 0f..1f) { "pickupRate must be 0..1, was $pickupRate" }
            require(pickupEdge > 0) { "pickupEdge must be positive, was $pickupEdge" }
        }
    }

    data class Blur(val radius: Int) : RmwSpec {
        init {
            require(radius in BlurKernel.MIN_RADIUS..BlurKernel.MAX_RADIUS) {
                "blur radius must be ${BlurKernel.MIN_RADIUS}..${BlurKernel.MAX_RADIUS}, was $radius"
            }
        }
    }

    data class Watercolor(
        val behavior: WatercolorBehavior,
        val mixing: RmwMixing,
    ) : RmwSpec

    data class Water(
        val behavior: WatercolorBehavior,
        val mixing: RmwMixing,
    ) : RmwSpec
}

/**
 * Everything about a stroke that is fixed for its whole life
 * (`docs/plan/03-canvas-engine.md` §6).
 *
 * Fixed at pen-down and never re-read per dab, which is what lets colour and
 * [opacity] be shader *uniforms* rather than per-dab attributes: §6's dab
 * layout carries eight per-dab floats and no colour, because a stroke is one
 * colour by definition.
 *
 * [opacity] is the stroke's **ceiling**, not a per-dab weight. Dabs accumulate
 * flow in the stroke buffer and the buffer is capped at this value once, at
 * merge — which is why no number of overlapping dabs can exceed it (§7.1), and
 * why `flow` stays the per-dab weight it is meant to be.
 */
data class StrokeSpec(
    val layerId: LayerId,
    val mode: StrokeMode,
    /** `preset.opacity · pressureOpacityMax` — one number per stroke (04 §3.3). */
    val opacity: Float,
    /** From the layer, not the preset (05 §1). */
    val alphaLock: Boolean = false,
    /** `preset.dilution`, 0 for non-mixing presets (09 §3.1). Only read in [StrokeMode.MIX]. */
    val dilution: Float = 0f,
    /** Procedural dab modulation; texture grains remain post-v1. */
    val grainMode: GrainMode = GrainMode.None,
    /** Non-null bypasses the stroke buffer entirely (§7.6). */
    val rmw: RmwSpec? = null,
    /** Fill shares the merge path but remains distinct in history. */
    val commitKind: PixelCommitKind = PixelCommitKind.Stroke,
) {
    init {
        require(opacity in 0f..1f) { "opacity must be 0..1, was $opacity" }
        require(dilution in 0f..1f) { "dilution must be 0..1, was $dilution" }
        // An RMW stroke has no buffer to cap or merge, so a caller that built
        // one with a buffer mode has misunderstood which path it is on. Caught
        // here rather than at the first dab, where the failure would be a
        // missing stroke buffer and read as a pool bug.
        require(rmw == null || mode != StrokeMode.ERASE) {
            "ERASE has no read-modify-write form"
        }
    }

    /** Direct tools write the layer in dab order and never allocate a stroke buffer. */
    val usesStrokeBuffer: Boolean get() = rmw == null

    /** Replaces pen-down's provisional opacity with the measured stroke peak. */
    fun withOpacityCeiling(value: Float): StrokeSpec = copy(opacity = value)
}
