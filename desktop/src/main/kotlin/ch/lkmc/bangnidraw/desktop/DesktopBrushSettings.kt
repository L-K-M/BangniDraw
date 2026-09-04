package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.Curve
import ch.lkmc.bangnidraw.engine.core.GrainMode
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.TipOrientation
import ch.lkmc.bangnidraw.engine.core.TipShape
import java.util.Locale

/**
 * The brush settings panel — `:app`'s `BrushSettingsSheet` in a window of its
 * own. Every control edits the *active* preset in place, which is how the
 * rail's own sliders already work: tuning stays with the preset it was made
 * on until the catalogue is reloaded.
 *
 * Android reaches the sheet by tapping the rail slot that is already
 * selected; the desktop rail does the same, which is why the eraser slot's
 * hard/soft choice moved *into* this panel — a second click cannot mean two
 * different things.
 */
@Composable
internal fun DesktopBrushSettings(
    preset: BrushPreset,
    catalogue: List<BrushPreset>,
    presets: List<BrushPreset>,
    mixerChoice: MixerChoice,
    onChanged: (BrushPreset) -> Unit,
    onSelectPreset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(DesktopBrushUi.label(preset), style = MaterialTheme.typography.titleSmall)

        if (preset.eraseMode) {
            // The rail's slot holds one eraser at a time; this is where the
            // other one is chosen, since a second click now opens this panel.
            Group("Eraser")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (eraser in presets.filter(BrushPreset::eraseMode)) {
                    FilterChip(
                        selected = eraser.id == preset.id,
                        onClick = { onSelectPreset(eraser.id) },
                        label = { Text(DesktopBrushUi.label(eraser)) },
                    )
                }
            }
        }

        Group("Stroke")
        Slider(
            "Size",
            preset.size,
            preset.sizeMin..preset.sizeMax,
            px(preset.size),
        ) { onChanged(preset.withSize(it)) }
        if (preset.watercolor == null) {
            Slider("Opacity", preset.opacity, 0f..1f, percent(preset.opacity)) {
                onChanged(preset.copy(opacity = it))
            }
        }
        Slider("Flow", preset.flow, 0f..1f, percent(preset.flow)) {
            onChanged(preset.copy(flow = it))
        }

        Group("Tip")
        Slider("Hardness", preset.hardness, 0f..1f, percent(preset.hardness)) {
            onChanged(preset.copy(hardness = it))
        }
        Slider("Spacing", preset.spacing, SPACING_MIN..SPACING_MAX, percent(preset.spacing)) {
            onChanged(preset.copy(spacing = it))
        }
        Choice("Shape") {
            Chip("Round", preset.tip is TipShape.Round) {
                onChanged(preset.copy(tip = TipShape.Round))
            }
            Chip("Flat", preset.tip is TipShape.Flat) {
                onChanged(preset.copy(tip = TipShape.Flat(DEFAULT_ASPECT)))
            }
        }
        val flat = preset.tip as? TipShape.Flat
        if (flat != null) {
            Slider("Aspect", flat.aspect, TipShape.Flat.MIN_ASPECT..1f, percent(flat.aspect)) {
                onChanged(preset.copy(tip = TipShape.Flat(it)))
            }
        }
        Choice("Orientation") {
            for (orientation in TipOrientation.entries) {
                Chip(orientationLabel(orientation), preset.orientation == orientation) {
                    onChanged(preset.copy(orientation = orientation))
                }
            }
        }

        Group("Dynamics")
        CurveRow("Pressure → size", preset.pressureSize) {
            onChanged(preset.copy(pressureSize = it))
        }
        if (preset.watercolor == null) {
            CurveRow("Pressure → opacity", preset.pressureOpacity) {
                onChanged(preset.copy(pressureOpacity = it))
            }
        }
        CurveRow("Pressure → flow", preset.pressureFlow) {
            onChanged(preset.copy(pressureFlow = it))
        }
        Slider("Tilt → size", preset.tilt.sizeAtFlat, TILT_RANGE, multiple(preset.tilt.sizeAtFlat)) {
            onChanged(preset.copy(tilt = preset.tilt.copy(sizeAtFlat = it)))
        }
        Slider(
            "Tilt → opacity",
            preset.tilt.opacityAtFlat,
            0f..TILT_MAX,
            multiple(preset.tilt.opacityAtFlat),
        ) {
            onChanged(preset.copy(tilt = preset.tilt.copy(opacityAtFlat = it)))
        }
        Toggle("Tilt elongates the dab", preset.tilt.elongate) {
            onChanged(preset.copy(tilt = preset.tilt.copy(elongate = it)))
        }
        Slider(
            "Speed → size",
            preset.velocity.sizeAtFast,
            TILT_RANGE,
            multiple(preset.velocity.sizeAtFast),
        ) {
            onChanged(preset.copy(velocity = preset.velocity.copy(sizeAtFast = it)))
        }
        Slider(
            "Speed → opacity",
            preset.velocity.opacityAtFast,
            0f..TILT_MAX,
            multiple(preset.velocity.opacityAtFast),
        ) {
            onChanged(preset.copy(velocity = preset.velocity.copy(opacityAtFast = it)))
        }
        Slider(
            "Fast is",
            preset.velocity.fastPxPerMs,
            FAST_MIN..FAST_MAX,
            "%.1f px/ms".format(Locale.ROOT, preset.velocity.fastPxPerMs),
        ) {
            onChanged(preset.copy(velocity = preset.velocity.copy(fastPxPerMs = it)))
        }
        Slider("Jitter → size", preset.jitter.size, 0f..1f, percent(preset.jitter.size)) {
            onChanged(preset.copy(jitter = preset.jitter.copy(size = it)))
        }
        Slider(
            "Jitter → position",
            preset.jitter.position,
            0f..1f,
            percent(preset.jitter.position),
        ) {
            onChanged(preset.copy(jitter = preset.jitter.copy(position = it)))
        }
        Slider("Stabilizer", preset.stabilizer, 0f..1f, percent(preset.stabilizer)) {
            onChanged(preset.copy(stabilizer = it))
        }

        Group("Paint")
        Toggle("Paper grain", preset.grainMode != GrainMode.None) { on ->
            onChanged(preset.copy(grain = if (on) PROCEDURAL_GRAIN else null))
        }
        // Pigment mixing needs a pigment mixer to mean anything: without
        // Mixbox the stroke is RGB either way, so the control would lie.
        if (!preset.eraseMode && mixerChoice == MixerChoice.PIGMENT) {
            Toggle("Pigment mixing", preset.mixing) { onChanged(preset.copy(mixing = it)) }
            if (preset.mixing) {
                Slider("Dilution", preset.dilution, 0f..1f, percent(preset.dilution)) {
                    onChanged(preset.copy(dilution = it))
                }
            }
        }
        Choice("Build-up") {
            Chip("Flat", preset.bufferMode == BufferMode.Max) {
                onChanged(preset.copy(bufferMode = BufferMode.Max))
            }
            Chip("Accumulate", preset.bufferMode == BufferMode.Accumulate) {
                onChanged(preset.copy(bufferMode = BufferMode.Accumulate))
            }
        }

        Spacer(Modifier.width(0.dp))
        Button(
            onClick = { catalogue.firstOrNull { it.id == preset.id }?.let(onChanged) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset to shipped settings")
        }
    }
}

@Composable
private fun Group(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Slider(
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
private fun CurveRow(label: String, curve: Curve, onChange: (Curve) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val knots = listOf(curve.p0, curve.p1, curve.p2, curve.p3)
        for ((index, knot) in knots.withIndex()) {
            Box(Modifier.weight(1f)) {
                DesktopThinSlider(
                    value = knot,
                    range = 0f..1f,
                    axis = DesktopSliderAxis.Horizontal,
                    description = "$label knot ${index + 1}",
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
private fun Choice(label: String, content: @Composable () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/**
 * A switch row that owns the toggle action while the [Switch] delegates it —
 * the accessible shape AGENTS.md pins for every switch row in this product.
 */
@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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

private fun orientationLabel(orientation: TipOrientation): String = when (orientation) {
    TipOrientation.Fixed -> "Fixed"
    TipOrientation.Stylus -> "Stylus"
    TipOrientation.StrokeDirection -> "Direction"
}

private fun percent(value: Float): String = "%.0f%%".format(Locale.ROOT, value * PERCENT)

private fun px(value: Float): String = "%.0f px".format(Locale.ROOT, value)

private fun multiple(value: Float): String = "%.2f×".format(Locale.ROOT, value)

private val PANEL_PADDING = 16.dp
private const val PERCENT = 100f
private const val DEFAULT_ASPECT = 0.3f
private const val SPACING_MIN = 0.02f
private const val SPACING_MAX = 1f
private val TILT_RANGE = 0.05f..8f
private const val TILT_MAX = 8f
private const val FAST_MIN = 0.5f
private const val FAST_MAX = 20f

/**
 * The reserved key the v1 grain uses. `04-tools.md` §5 keeps `grain` null
 * until a CC0 texture arrives; AGENTS.md records that this shell, like the
 * app, means procedural shader noise by it in the meantime.
 */
private const val PROCEDURAL_GRAIN = "procedural"
