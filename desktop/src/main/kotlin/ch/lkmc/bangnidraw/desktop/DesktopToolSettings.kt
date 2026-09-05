package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.BlurParams
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.FillReference
import ch.lkmc.bangnidraw.engine.core.SampleSource
import ch.lkmc.bangnidraw.engine.core.SmudgeParams
import ch.lkmc.bangnidraw.engine.core.WaterParams
import ch.lkmc.bangnidraw.engine.core.WatercolorDabPlan
import kotlin.math.roundToInt

/**
 * The five secondary tools' settings — `:app`'s `RmwSettingsSheet`,
 * `FillSettingsSheet` and `EyedropperSettingsSheet`, in the same window the
 * brush settings use.
 *
 * Every control writes back through the params type's own constructor, so an
 * out-of-range value fails here rather than at the next pen-down: each of
 * these classes `require`s its own bounds.
 */
@Composable
internal fun DesktopToolSettings(state: DesktopShellState, modifier: Modifier = Modifier) {
    val tool = state.rail.secondary ?: return
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(secondaryLabel(tool), style = MaterialTheme.typography.titleSmall)
        when (tool) {
            DesktopSecondaryTool.SMUDGE -> Smudge(state.smudgeParams) { state.smudgeParams = it }
            DesktopSecondaryTool.WATER -> Water(state.waterParams) { state.waterParams = it }
            DesktopSecondaryTool.BLUR -> Blur(state.blurParams) { state.blurParams = it }
            DesktopSecondaryTool.FILL -> Fill(state.fillParams) { state.fillParams = it }
            DesktopSecondaryTool.EYEDROPPER ->
                Eyedropper(state.eyedropperParams) { state.eyedropperParams = it }
        }
    }
}

@Composable
private fun Smudge(params: SmudgeParams, onChanged: (SmudgeParams) -> Unit) {
    SettingsGroup(DesktopStrings.get("desktop_dab_group"))
    SettingSlider(DesktopStrings.get("brush_size"), params.size, params.sizeMin..params.sizeMax, px(params.size)) {
        onChanged(params.copy(size = it))
    }
    SettingSlider(DesktopStrings.get("brush_hardness"), params.hardness, UNIT, percent(params.hardness)) {
        onChanged(params.copy(hardness = it))
    }
    SettingSlider(DesktopStrings.get("brush_spacing"), params.spacing, SPACING, percent(params.spacing)) {
        onChanged(params.copy(spacing = it))
    }

    SettingsGroup(DesktopStrings.get("desktop_pickup_group"))
    SettingSlider(DesktopStrings.get("smudge_strength"), params.strength, UNIT, percent(params.strength)) {
        onChanged(params.copy(strength = it))
    }
    // 0 is a clean finger: the pickup buffer keeps the colour it started with
    // instead of absorbing what it drags through.
    SettingSlider(DesktopStrings.get("smudge_pickup"), params.pickupRate, UNIT, percent(params.pickupRate)) {
        onChanged(params.copy(pickupRate = it))
    }
    SettingToggle(DesktopStrings.get("brush_pigment"), params.mixing) {
        onChanged(params.copy(mixing = it))
    }

    SettingsGroup(DesktopStrings.get("brush_group_dynamics"))
    CurveRow(DesktopStrings.get("smudge_pressure_strength"), params.pressureStrength) {
        onChanged(params.copy(pressureStrength = it))
    }
    SettingSlider(DesktopStrings.get("brush_stabilizer"), params.stabilizer, UNIT, percent(params.stabilizer)) {
        onChanged(params.copy(stabilizer = it))
    }
}

@Composable
private fun Water(params: WaterParams, onChanged: (WaterParams) -> Unit) {
    SettingsGroup(DesktopStrings.get("desktop_dab_group"))
    // The maximum is the GLES scratch bound, not the tool's taste: WaterParams
    // refuses a size past it (`WatercolorDabPlan.MAX_DIAMETER_PX`).
    SettingSlider(
        DesktopStrings.get("brush_size"),
        params.size,
        params.sizeMin..minOf(params.sizeMax, WatercolorDabPlan.MAX_DIAMETER_PX.toFloat()),
        px(params.size),
    ) {
        onChanged(params.withSize(it))
    }
    SettingSlider(DesktopStrings.get("brush_hardness"), params.hardness, UNIT, percent(params.hardness)) {
        onChanged(params.copy(hardness = it))
    }
    SettingSlider(DesktopStrings.get("brush_spacing"), params.spacing, SPACING, percent(params.spacing)) {
        onChanged(params.copy(spacing = it))
    }

    SettingsGroup(DesktopStrings.get("desktop_water_group"))
    SettingSlider(DesktopStrings.get("water_amount"), params.waterLoad, UNIT, percent(params.waterLoad)) {
        onChanged(params.withWaterLoad(it))
    }
    SettingSlider(DesktopStrings.get("water_spread"), params.spread, UNIT, percent(params.spread)) {
        onChanged(params.copy(spread = it))
    }
    SettingSlider(DesktopStrings.get("water_granulation"), params.granulation, UNIT, percent(params.granulation)) {
        onChanged(params.copy(granulation = it))
    }
    SettingSlider(DesktopStrings.get("water_edge_darkening"), params.edgeDarkening, UNIT, percent(params.edgeDarkening)) {
        onChanged(params.copy(edgeDarkening = it))
    }

    SettingsGroup(DesktopStrings.get("brush_group_dynamics"))
    CurveRow(DesktopStrings.get("brush_pressure_flow"), params.pressureWater) {
        onChanged(params.copy(pressureWater = it))
    }
    SettingSlider(DesktopStrings.get("brush_stabilizer"), params.stabilizer, UNIT, percent(params.stabilizer)) {
        onChanged(params.copy(stabilizer = it))
    }
}

@Composable
private fun Blur(params: BlurParams, onChanged: (BlurParams) -> Unit) {
    SettingsGroup(DesktopStrings.get("desktop_dab_group"))
    SettingSlider(DesktopStrings.get("brush_size"), params.size, params.sizeMin..params.sizeMax, px(params.size)) {
        onChanged(params.copy(size = it))
    }
    SettingSlider(DesktopStrings.get("brush_spacing"), params.spacing, SPACING, percent(params.spacing)) {
        onChanged(params.copy(spacing = it))
    }

    SettingsGroup(DesktopStrings.get("desktop_kernel_group"))
    SettingSlider(DesktopStrings.get("smudge_strength"), params.strength, UNIT, percent(params.strength)) {
        onChanged(params.copy(strength = it))
    }
    // The kernel radius is size × this, clamped to 1..24 px by the pass.
    SettingSlider(DesktopStrings.get("blur_radius"), params.radiusFraction, UNIT, percent(params.radiusFraction)) {
        onChanged(params.copy(radiusFraction = it))
    }

    SettingsGroup(DesktopStrings.get("brush_group_dynamics"))
    CurveRow(DesktopStrings.get("smudge_pressure_strength"), params.pressureStrength) {
        onChanged(params.copy(pressureStrength = it))
    }
}

@Composable
private fun Fill(params: FillParams, onChanged: (FillParams) -> Unit) {
    SettingsGroup(DesktopStrings.get("desktop_region_group"))
    SettingSlider(DesktopStrings.get("fill_tolerance"), params.tolerance, UNIT, percent(params.tolerance)) {
        onChanged(params.copy(tolerance = it))
    }
    SettingChoice(DesktopStrings.get("fill_reference")) {
        SettingChip(
            DesktopStrings.get("fill_reference_composite"),
            params.reference == FillReference.Composite,
        ) {
            onChanged(params.copy(reference = FillReference.Composite))
        }
        SettingChip(
            DesktopStrings.get("fill_reference_current"),
            params.reference == FillReference.CurrentLayer,
        ) {
            onChanged(params.copy(reference = FillReference.CurrentLayer))
        }
    }
    // Off is a global fill: every matching pixel, not just the region the
    // click landed in.
    SettingToggle(DesktopStrings.get("fill_contiguous"), params.contiguous) {
        onChanged(params.copy(contiguous = it))
    }

    SettingsGroup(DesktopStrings.get("desktop_edges_group"))
    // Grows the region to close the anti-aliased gap around an inked outline.
    SettingSlider(
        DesktopStrings.get("fill_expand"),
        params.expand.toFloat(),
        0f..FillParams.MAX_EXPAND.toFloat(),
        DesktopStrings.get("desktop_fill_expand_value", params.expand),
    ) {
        onChanged(params.copy(expand = it.roundToInt().coerceIn(0, FillParams.MAX_EXPAND)))
    }
    SettingToggle(DesktopStrings.get("fill_antialias"), params.antialias) { onChanged(params.copy(antialias = it)) }
    SettingSlider(DesktopStrings.get("fill_opacity"), params.opacity, UNIT, percent(params.opacity)) {
        onChanged(params.copy(opacity = it))
    }
}

@Composable
private fun Eyedropper(params: EyedropperParams, onChanged: (EyedropperParams) -> Unit) {
    SettingsGroup(DesktopStrings.get("desktop_sample_group"))
    SettingChoice(DesktopStrings.get("eyedropper_sample")) {
        SettingChip(
            DesktopStrings.get("fill_reference_composite"),
            params.source == SampleSource.Composite,
        ) {
            onChanged(params.copy(source = SampleSource.Composite))
        }
        SettingChip(
            DesktopStrings.get("fill_reference_current"),
            params.source == SampleSource.CurrentLayer,
        ) {
            onChanged(params.copy(source = SampleSource.CurrentLayer))
        }
    }
    // 0 is one pixel; 1 averages a 3×3 block, and so on.
    SettingSlider(
        DesktopStrings.get("eyedropper_radius"),
        params.radius.toFloat(),
        0f..EyedropperParams.MAX_RADIUS.toFloat(),
        radiusLabel(params.radius),
    ) {
        onChanged(params.copy(radius = it.roundToInt().coerceIn(0, EyedropperParams.MAX_RADIUS)))
    }
}

private fun radiusLabel(radius: Int): String = if (radius == 0) {
    DesktopStrings.get("desktop_eyedropper_radius_one")
} else {
    DesktopStrings.get("desktop_eyedropper_radius_block", radius * 2 + 1)
}

private val UNIT = 0f..1f
private val SPACING = 0.02f..1f
private val PANEL_PADDING = 16.dp
