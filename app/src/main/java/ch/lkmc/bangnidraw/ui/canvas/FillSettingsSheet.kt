package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.FillReference
import kotlin.math.roundToInt

/** Bucket-fill options; changes apply to the next touch. */
@Composable
internal fun FillSettingsSheet(
    active: FillParams,
    onChanged: (FillParams) -> Unit,
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
            Text(
                text = stringResource(R.string.fill_settings),
                style = MaterialTheme.typography.headlineSmall,
            )
            FillSlider(
                label = stringResource(R.string.fill_tolerance),
                value = active.tolerance,
                valueText = stringResource(R.string.brush_value_percent, active.tolerance * 100f),
                range = UNIT_RANGE,
                onChanged = { onChanged(active.copy(tolerance = it)) },
            )
            FillSlider(
                label = stringResource(R.string.fill_expand),
                value = active.expand.toFloat(),
                valueText = stringResource(R.string.fill_expand_value, active.expand),
                range = 0f..FillParams.MAX_EXPAND.toFloat(),
                steps = FillParams.MAX_EXPAND - 1,
                onChanged = { onChanged(active.copy(expand = it.roundToInt())) },
            )
            FillSlider(
                label = stringResource(R.string.fill_opacity),
                value = active.opacity,
                valueText = stringResource(R.string.brush_value_percent, active.opacity * 100f),
                range = UNIT_RANGE,
                onChanged = { onChanged(active.copy(opacity = it)) },
            )
            FillToggle(
                label = stringResource(R.string.fill_contiguous),
                checked = active.contiguous,
                onChanged = { onChanged(active.copy(contiguous = it)) },
            )
            FillToggle(
                label = stringResource(R.string.fill_antialias),
                checked = active.antialias,
                onChanged = { onChanged(active.copy(antialias = it)) },
            )
            Text(
                text = stringResource(R.string.fill_reference),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                FilterChip(
                    selected = active.reference == FillReference.CurrentLayer,
                    onClick = { onChanged(active.copy(reference = FillReference.CurrentLayer)) },
                    label = { Text(stringResource(R.string.fill_reference_current)) },
                )
                FilterChip(
                    selected = active.reference == FillReference.Composite,
                    onClick = { onChanged(active.copy(reference = FillReference.Composite)) },
                    label = { Text(stringResource(R.string.fill_reference_composite)) },
                )
            }
        }
    }
}

@Composable
private fun FillSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChanged: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(valueText, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = value,
        onValueChange = onChanged,
        valueRange = range,
        steps = steps,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FillToggle(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CONTROL_MIN_HEIGHT),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

private val SHEET_PADDING = 24.dp
private val CHIP_GAP = 8.dp
private val CONTROL_MIN_HEIGHT = 48.dp
private val UNIT_RANGE = 0f..1f
