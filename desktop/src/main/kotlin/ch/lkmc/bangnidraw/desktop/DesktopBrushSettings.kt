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
            SettingsGroup(DesktopStrings.get("desktop_eraser_choice"))
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

        SettingsGroup(DesktopStrings.get("brush_group_stroke"))
        SettingSlider(
            DesktopStrings.get("brush_size"),
            preset.size,
            preset.sizeMin..preset.sizeMax,
            px(preset.size),
        ) { onChanged(preset.withSize(it)) }
        if (preset.watercolor == null) {
            SettingSlider(
                DesktopStrings.get("brush_opacity"),
                preset.opacity,
                0f..1f,
                percent(preset.opacity),
            ) {
                onChanged(preset.copy(opacity = it))
            }
        }
        SettingSlider(DesktopStrings.get("brush_flow"), preset.flow, 0f..1f, percent(preset.flow)) {
            onChanged(preset.copy(flow = it))
        }

        SettingsGroup(DesktopStrings.get("brush_group_tip"))
        SettingSlider(
            DesktopStrings.get("brush_hardness"),
            preset.hardness,
            0f..1f,
            percent(preset.hardness),
        ) {
            onChanged(preset.copy(hardness = it))
        }
        SettingSlider(
            DesktopStrings.get("brush_spacing"),
            preset.spacing,
            SPACING_MIN..SPACING_MAX,
            percent(preset.spacing),
        ) {
            onChanged(preset.copy(spacing = it))
        }
        SettingChoice(DesktopStrings.get("brush_tip_shape")) {
            SettingChip(DesktopStrings.get("brush_tip_round"), preset.tip is TipShape.Round) {
                onChanged(preset.copy(tip = TipShape.Round))
            }
            SettingChip(DesktopStrings.get("brush_tip_flat"), preset.tip is TipShape.Flat) {
                onChanged(preset.copy(tip = TipShape.Flat(DEFAULT_ASPECT)))
            }
        }
        val flat = preset.tip as? TipShape.Flat
        if (flat != null) {
            SettingSlider(
                DesktopStrings.get("brush_tip_aspect"),
                flat.aspect,
                TipShape.Flat.MIN_ASPECT..1f,
                percent(flat.aspect),
            ) {
                onChanged(preset.copy(tip = TipShape.Flat(it)))
            }
        }
        SettingChoice(DesktopStrings.get("brush_tip_orientation")) {
            for (orientation in TipOrientation.entries) {
                SettingChip(orientationLabel(orientation), preset.orientation == orientation) {
                    onChanged(preset.copy(orientation = orientation))
                }
            }
        }

        SettingsGroup(DesktopStrings.get("brush_group_dynamics"))
        CurveRow(DesktopStrings.get("brush_pressure_size"), preset.pressureSize) {
            onChanged(preset.copy(pressureSize = it))
        }
        if (preset.watercolor == null) {
            CurveRow(DesktopStrings.get("brush_pressure_opacity"), preset.pressureOpacity) {
                onChanged(preset.copy(pressureOpacity = it))
            }
        }
        CurveRow(DesktopStrings.get("brush_pressure_flow"), preset.pressureFlow) {
            onChanged(preset.copy(pressureFlow = it))
        }
        SettingSlider(
            DesktopStrings.get("brush_tilt_size"),
            preset.tilt.sizeAtFlat,
            TILT_RANGE,
            multiple(preset.tilt.sizeAtFlat),
        ) {
            onChanged(preset.copy(tilt = preset.tilt.copy(sizeAtFlat = it)))
        }
        SettingSlider(
            DesktopStrings.get("brush_tilt_opacity"),
            preset.tilt.opacityAtFlat,
            0f..TILT_MAX,
            multiple(preset.tilt.opacityAtFlat),
        ) {
            onChanged(preset.copy(tilt = preset.tilt.copy(opacityAtFlat = it)))
        }
        SettingToggle(DesktopStrings.get("brush_tilt_elongate"), preset.tilt.elongate) {
            onChanged(preset.copy(tilt = preset.tilt.copy(elongate = it)))
        }
        SettingSlider(
            DesktopStrings.get("desktop_speed_size"),
            preset.velocity.sizeAtFast,
            TILT_RANGE,
            multiple(preset.velocity.sizeAtFast),
        ) {
            onChanged(preset.copy(velocity = preset.velocity.copy(sizeAtFast = it)))
        }
        SettingSlider(
            DesktopStrings.get("desktop_speed_opacity"),
            preset.velocity.opacityAtFast,
            0f..TILT_MAX,
            multiple(preset.velocity.opacityAtFast),
        ) {
            onChanged(preset.copy(velocity = preset.velocity.copy(opacityAtFast = it)))
        }
        SettingSlider(
            DesktopStrings.get("desktop_speed_threshold"),
            preset.velocity.fastPxPerMs,
            FAST_MIN..FAST_MAX,
            DesktopStrings.get("desktop_speed_threshold_value", preset.velocity.fastPxPerMs),
        ) {
            onChanged(preset.copy(velocity = preset.velocity.copy(fastPxPerMs = it)))
        }
        SettingSlider(
            DesktopStrings.get("brush_jitter_size"),
            preset.jitter.size,
            0f..1f,
            percent(preset.jitter.size),
        ) {
            onChanged(preset.copy(jitter = preset.jitter.copy(size = it)))
        }
        SettingSlider(
            DesktopStrings.get("brush_jitter_position"),
            preset.jitter.position,
            0f..1f,
            percent(preset.jitter.position),
        ) {
            onChanged(preset.copy(jitter = preset.jitter.copy(position = it)))
        }
        SettingSlider(
            DesktopStrings.get("brush_stabilizer"),
            preset.stabilizer,
            0f..1f,
            percent(preset.stabilizer),
        ) {
            onChanged(preset.copy(stabilizer = it))
        }

        SettingsGroup(DesktopStrings.get("brush_group_paint"))
        SettingToggle(DesktopStrings.get("desktop_paper_grain"), preset.grainMode != GrainMode.None) { on ->
            onChanged(preset.copy(grain = if (on) PROCEDURAL_GRAIN else null))
        }
        // Pigment mixing needs a pigment mixer to mean anything: without
        // Mixbox the stroke is RGB either way, so the control would lie.
        if (!preset.eraseMode && mixerChoice == MixerChoice.PIGMENT) {
            SettingToggle(DesktopStrings.get("brush_pigment"), preset.mixing) {
                onChanged(preset.copy(mixing = it))
            }
            if (preset.mixing) {
                SettingSlider(
                    DesktopStrings.get("brush_dilution"),
                    preset.dilution,
                    0f..1f,
                    percent(preset.dilution),
                ) {
                    onChanged(preset.copy(dilution = it))
                }
            }
        }
        SettingChoice(DesktopStrings.get("brush_buffer_mode")) {
            SettingChip(DesktopStrings.get("brush_buffer_max"), preset.bufferMode == BufferMode.Max) {
                onChanged(preset.copy(bufferMode = BufferMode.Max))
            }
            SettingChip(
                DesktopStrings.get("brush_buffer_accumulate"),
                preset.bufferMode == BufferMode.Accumulate,
            ) {
                onChanged(preset.copy(bufferMode = BufferMode.Accumulate))
            }
        }

        Spacer(Modifier.width(0.dp))
        Button(
            onClick = { catalogue.firstOrNull { it.id == preset.id }?.let(onChanged) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(DesktopStrings.get("brush_reset"))
        }
    }
}

private fun orientationLabel(orientation: TipOrientation): String = DesktopStrings.get(
    when (orientation) {
        TipOrientation.Fixed -> "brush_tip_fixed"
        TipOrientation.Stylus -> "brush_tip_stylus"
        TipOrientation.StrokeDirection -> "brush_tip_direction"
    },
)

private val PANEL_PADDING = 16.dp
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
