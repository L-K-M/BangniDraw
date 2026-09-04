package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.Curve

/**
 * The controls every settings panel is built from — the desktop twin of
 * `:app`'s `SettingSlider`, `ChoiceLabel` and switch rows.
 *
 * One copy, because the brush panel and the five tool panels present the same
 * kinds of value and a second set would drift on formatting, on the slider's
 * geometry, and on the switch row's accessibility shape.
 */

@Composable
internal fun SettingsGroup(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
internal fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DesktopThinSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            range = range,
            axis = DesktopSliderAxis.Horizontal,
            description = label,
            onValueChange = onChange,
            fillWidth = true,
        )
    }
}

/**
 * A [Curve] as its four knots. Android drags them on a plotted curve; four
 * labelled sliders are the same four numbers, and a mouse can hit them.
 */
@Composable
internal fun CurveRow(label: String, curve: Curve, onChange: (Curve) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val knots = listOf(curve.p0, curve.p1, curve.p2, curve.p3)
        for ((index, knot) in knots.withIndex()) {
            Box(Modifier.weight(1f)) {
                DesktopThinSlider(
                    value = knot,
                    range = 0f..1f,
                    axis = DesktopSliderAxis.Horizontal,
                    description = DesktopStrings.get("desktop_curve_knot", label, index + 1),
                    onValueChange = { value ->
                        onChange(
                            when (index) {
                                0 -> curve.copy(p0 = value)
                                1 -> curve.copy(p1 = value)
                                2 -> curve.copy(p2 = value)
                                else -> curve.copy(p3 = value)
                            },
                        )
                    },
                    fillWidth = true,
                )
            }
        }
    }
}

@Composable
internal fun SettingChoice(label: String, content: @Composable () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
internal fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/**
 * A switch row that owns the toggle action while the [Switch] delegates it —
 * the accessible shape AGENTS.md pins for every switch row in this product.
 */
@Composable
internal fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            },
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

internal fun percent(value: Float): String =
    DesktopStrings.get("desktop_percent_value", value * PERCENT)

internal fun px(value: Float): String = DesktopStrings.get("desktop_pixel_value", value)

internal fun multiple(value: Float): String = DesktopStrings.get("desktop_multiplier_value", value)

private const val PERCENT = 100f
