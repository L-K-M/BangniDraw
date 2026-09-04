package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ToolSliderPreset

/**
 * The write half of [ToolSliderPreset] — where the rail's two sliders land in
 * each tool's own parameters.
 *
 * `ToolSliderPreset.forKind`/`secondaryValue` say what the sliders *show*;
 * this says what an edit *changes*. They have to be read together, so the
 * pairing is stated once here rather than inside the shell's state holder,
 * where it could not be tested without a GL context.
 *
 * Every arm clamps into that tool's own bounds and keeps the current value
 * for a NaN: the params types `require` their ranges, so an unclamped write
 * throws from inside a slider drag, and one NaN would poison the tool for the
 * rest of the session.
 */
internal object DesktopToolTuning {

    /** [kind] with its size slider moved to [value], in that tool's units (px). */
    fun withSize(kind: ToolKind, value: Float): ToolKind = when (kind) {
        is ToolKind.Brush -> ToolKind.Brush(kind.preset.withSize(value))
        is ToolKind.Smudge -> ToolKind.Smudge(
            kind.params.copy(
                size = value.clamp(kind.params.sizeMin, kind.params.sizeMax, kind.params.size),
            ),
        )
        // WaterParams.withSize already clamps and already guards NaN, and its
        // maximum is the GLES scratch bound rather than taste.
        is ToolKind.Water -> ToolKind.Water(kind.params.withSize(value))
        is ToolKind.Blur -> ToolKind.Blur(
            kind.params.copy(
                size = value.clamp(kind.params.sizeMin, kind.params.sizeMax, kind.params.size),
            ),
        )
        // Neither has a size; `ToolSliderPreset.forKind` returns null for them
        // and the rail draws no sliders at all.
        is ToolKind.Fill, is ToolKind.Eyedropper -> kind
    }

    /** [kind] with its second slider moved: opacity, flow, strength or water. */
    fun withSecondary(kind: ToolKind, value: Float): ToolKind = when (kind) {
        is ToolKind.Brush -> ToolKind.Brush(ToolSliderPreset.withSecondary(kind.preset, value))
        is ToolKind.Smudge ->
            ToolKind.Smudge(kind.params.copy(strength = value.unit(kind.params.strength)))
        is ToolKind.Water -> ToolKind.Water(kind.params.withWaterLoad(value))
        is ToolKind.Blur ->
            ToolKind.Blur(kind.params.copy(strength = value.unit(kind.params.strength)))
        is ToolKind.Fill, is ToolKind.Eyedropper -> kind
    }

    private fun Float.clamp(min: Float, max: Float, current: Float): Float =
        if (isNaN()) current else coerceIn(min, max)

    private fun Float.unit(current: Float): Float =
        if (isNaN()) current else coerceIn(0f, 1f)
}
