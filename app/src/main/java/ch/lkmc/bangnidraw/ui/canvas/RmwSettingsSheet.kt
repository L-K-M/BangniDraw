package ch.lkmc.bangnidraw.ui.canvas

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.BlurParams
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushSizeScale
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.SampleSource
import ch.lkmc.bangnidraw.engine.core.SmudgeParams
import ch.lkmc.bangnidraw.engine.core.WaterParams
import kotlin.math.roundToInt

/**
 * Settings sheets for the tools that are not brush presets
 * (`docs/plan/08-ui-and-layout.md` §3.5's tool-kind gating): smudge and blur
 * show Stroke (size, no opacity), Tip, Dynamics (the one pressure curve they
 * have), then smudge's Stabilizer and Mixing; the eyedropper shows what it
 * samples. Changes are session state — the next stroke uses them, nothing is
 * journaled, exactly like the fill sheet.
 */
@Composable
internal fun SmudgeSettingsSheet(
    active: SmudgeParams,
    onChanged: (SmudgeParams) -> Unit,
    onDismiss: () -> Unit,
) {
    ToolSheetScaffold(
        title = stringResource(R.string.tool_smudge),
        helpBody = R.string.help_smudge_body,
        onClose = onDismiss,
    ) {
        val percent: @Composable (Float) -> String = {
            stringResource(R.string.brush_value_percent, it * PERCENT)
        }
        SettingsGroup(stringResource(R.string.brush_group_stroke))
        ToolSizeSlider(active.size, active.sizeMin, active.sizeMax) {
            onChanged(active.copy(size = it))
        }

        SettingsGroup(stringResource(R.string.brush_group_tip))
        DeferredSettingSlider(
            label = stringResource(R.string.brush_hardness),
            value = active.hardness,
            valueText = percent,
            range = UNIT_RANGE,
            onCommit = { onChanged(active.copy(hardness = it)) },
        )
        ToolSpacingSlider(active.spacing) {
            onChanged(active.copy(spacing = it))
        }

        SettingsGroup(stringResource(R.string.brush_group_dynamics))
        CurveEditor(
            title = stringResource(R.string.smudge_pressure_strength),
            curve = active.pressureStrength,
            onChanged = { onChanged(active.copy(pressureStrength = it)) },
            onFinished = {},
            valueText = percent,
        )
        // Its own plan §3.5 section; the header was dropped as a duplicate
        // of the slider label, but the divider stays so it reads as one.
        Spacer(Modifier.height(GROUP_GAP))
        HorizontalDivider()
        DeferredSettingSlider(
            label = stringResource(R.string.brush_stabilizer),
            value = active.stabilizer,
            valueText = percent,
            range = UNIT_RANGE,
            onCommit = { onChanged(active.copy(stabilizer = it)) },
        )

        SettingsGroup(stringResource(R.string.brush_group_mixing))
        ToggleRow(
            label = stringResource(R.string.brush_pigment),
            value = if (active.mixing) ToggleValue.On else ToggleValue.Off,
            onChanged = { onChanged(active.copy(mixing = it.enabled)) },
        )
        DeferredSettingSlider(
            label = stringResource(R.string.smudge_strength),
            value = active.strength,
            valueText = percent,
            range = UNIT_RANGE,
            onCommit = { onChanged(active.copy(strength = it)) },
        )
        DeferredSettingSlider(
            label = stringResource(R.string.smudge_pickup),
            value = active.pickupRate,
            valueText = percent,
            range = UNIT_RANGE,
            onCommit = { onChanged(active.copy(pickupRate = it)) },
        )
    }
}

/** The colorless water tool keeps its three expressive controls together. */
@Composable
internal fun WaterSettingsSheet(
    active: WaterParams,
    onChanged: (WaterParams) -> Unit,
    onDismiss: () -> Unit,
) {
    ToolSheetScaffold(
        title = stringResource(R.string.tool_water),
        helpBody = R.string.help_water_body,
        onClose = onDismiss,
    ) {
        val percent: @Composable (Float) -> String = {
            stringResource(R.string.brush_value_percent, it * PERCENT)
        }

        ToolSizeSlider(active.size, active.sizeMin, active.sizeMax) {
            onChanged(active.withSize(it))
        }
        DeferredSettingSlider(
            label = stringResource(R.string.water_amount),
            value = active.waterLoad,
            valueText = percent,
            range = UNIT_RANGE,
            onCommit = { onChanged(active.withWaterLoad(it)) },
        )
        DeferredSettingSlider(
            label = stringResource(R.string.water_spread),
            value = active.spread,
            valueText = percent,
            range = UNIT_RANGE,
            onCommit = { onChanged(active.copy(spread = it)) },
        )
    }
}

@Composable
internal fun BlurSettingsSheet(
    active: BlurParams,
    onChanged: (BlurParams) -> Unit,
    onDismiss: () -> Unit,
) {
    ToolSheetScaffold(
        title = stringResource(R.string.tool_blur),
        helpBody = R.string.help_blur_body,
        onClose = onDismiss,
    ) {
        val percent: @Composable (Float) -> String = {
            stringResource(R.string.brush_value_percent, it * PERCENT)
        }
        SettingsGroup(stringResource(R.string.brush_group_stroke))
        ToolSizeSlider(active.size, active.sizeMin, active.sizeMax) {
            onChanged(active.copy(size = it))
        }
        DeferredSettingSlider(
            label = stringResource(R.string.smudge_strength),
            value = active.strength,
            valueText = percent,
            range = UNIT_RANGE,
            onCommit = { onChanged(active.copy(strength = it)) },
        )
        DeferredSettingSlider(
            label = stringResource(R.string.blur_radius),
            value = active.radiusFraction,
            valueText = percent,
            range = UNIT_RANGE,
            onCommit = { onChanged(active.copy(radiusFraction = it)) },
        )

        SettingsGroup(stringResource(R.string.brush_group_tip))
        ToolSpacingSlider(active.spacing) {
            onChanged(active.copy(spacing = it))
        }

        SettingsGroup(stringResource(R.string.brush_group_dynamics))
        CurveEditor(
            title = stringResource(R.string.smudge_pressure_strength),
            curve = active.pressureStrength,
            onChanged = { onChanged(active.copy(pressureStrength = it)) },
            onFinished = {},
            valueText = percent,
        )
    }
}

@Composable
internal fun EyedropperSettingsSheet(
    active: EyedropperParams,
    onChanged: (EyedropperParams) -> Unit,
    onDismiss: () -> Unit,
) {
    ToolSheetScaffold(
        title = stringResource(R.string.tool_eyedropper),
        helpBody = R.string.help_eyedropper_body,
        onClose = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.eyedropper_sample),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
            FilterChip(
                selected = active.source == SampleSource.Composite,
                onClick = { onChanged(active.copy(source = SampleSource.Composite)) },
                label = { Text(stringResource(R.string.fill_reference_composite)) },
            )
            FilterChip(
                selected = active.source == SampleSource.CurrentLayer,
                onClick = { onChanged(active.copy(source = SampleSource.CurrentLayer)) },
                label = { Text(stringResource(R.string.fill_reference_current)) },
            )
        }
        DeferredSettingSlider(
            label = stringResource(R.string.eyedropper_radius),
            value = active.radius.toFloat(),
            valueText = { it.roundToInt().toString() },
            range = 0f..EyedropperParams.MAX_RADIUS.toFloat(),
            steps = EyedropperParams.MAX_RADIUS - 1,
            onCommit = { onChanged(active.copy(radius = it.roundToInt())) },
        )
    }
}

@Composable
private fun ToolSheetScaffold(
    title: String,
    @StringRes helpBody: Int,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SHEET_PADDING),
        ) {
            PanelHeader(title = title, onClose = onClose, helpBody = helpBody)
            content()
            Spacer(Modifier.height(SHEET_BOTTOM_GAP))
        }
    }
}

@Composable
private fun ToolSizeSlider(
    size: Float,
    sizeMin: Float,
    sizeMax: Float,
    onChanged: (Float) -> Unit,
) {
    DeferredSettingSlider(
        label = stringResource(R.string.brush_size),
        value = BrushSizeScale.fraction(size, sizeMin, sizeMax),
        valueText = {
            stringResource(R.string.brush_value_px, BrushSizeScale.size(it, sizeMin, sizeMax))
        },
        range = UNIT_RANGE,
        onCommit = { onChanged(BrushSizeScale.size(it, sizeMin, sizeMax)) },
    )
}

@Composable
private fun ToolSpacingSlider(spacing: Float, onChanged: (Float) -> Unit) {
    DeferredSettingSlider(
        label = stringResource(R.string.brush_spacing),
        value = spacing / DIAMETER_TO_RADIUS,
        valueText = { stringResource(R.string.brush_value_percent, it * PERCENT) },
        range = BrushPreset.MIN_SPACING / DIAMETER_TO_RADIUS..
            BrushPreset.MAX_SPACING / DIAMETER_TO_RADIUS,
        onCommit = { onChanged(it * DIAMETER_TO_RADIUS) },
    )
}

private val UNIT_RANGE = 0f..1f
private val SHEET_PADDING = 20.dp
private val CHIP_GAP = 8.dp
private val SHEET_BOTTOM_GAP = 32.dp
private val GROUP_GAP = 20.dp
private const val PERCENT = 100f
private const val DIAMETER_TO_RADIUS = 2f
