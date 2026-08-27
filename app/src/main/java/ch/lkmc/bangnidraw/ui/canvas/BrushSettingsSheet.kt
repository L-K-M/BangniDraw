package ch.lkmc.bangnidraw.ui.canvas

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.BrushPreview
import ch.lkmc.bangnidraw.engine.core.BrushSizeScale
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.Curve
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.OpacityMilestone
import ch.lkmc.bangnidraw.engine.core.TiltEffect
import ch.lkmc.bangnidraw.engine.core.TipOrientation
import ch.lkmc.bangnidraw.engine.core.TipShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Complete v1 brush editor; every change stays valid under [BrushPreset]'s schema. */
@Composable
internal fun BrushSettingsSheet(
    active: BrushPreset,
    presets: List<BrushPreset>,
    brushColor: Int,
    paperColor: Int,
    hapticsMode: HapticsMode,
    mixerChoice: MixerChoice,
    onPresetSelected: (String) -> Unit,
    onPresetChanged: (BrushPreset) -> Unit,
    onPresetPersisted: () -> Unit,
    onReset: () -> Unit,
) {
    val view = LocalView.current
    val category = BrushPresets.railOrder(presets).filter {
        it.eraseMode == active.eraseMode
    }
    val percent: @Composable (Float) -> String = {
        stringResource(R.string.brush_value_percent, it * PERCENT)
    }
    val number: @Composable (Float) -> String = {
        stringResource(R.string.brush_value_number, it)
    }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var previousOpacity by remember(active.id) { mutableFloatStateOf(active.opacity) }
    LaunchedEffect(active, brushColor, paperColor, previewSize) {
        if (previewSize.width <= 0 || previewSize.height <= 0) return@LaunchedEffect
        delay(PREVIEW_DEBOUNCE_MS)
        preview = withContext(Dispatchers.Default) {
            val pixels = BrushPreview.render(
                active,
                brushColor,
                paperColor,
                previewSize.width,
                previewSize.height,
            )
            Bitmap.createBitmap(
                pixels,
                previewSize.width,
                previewSize.height,
                Bitmap.Config.ARGB_8888,
            ).asImageBitmap()
        }
    }

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
                text = stringResource(R.string.brush_settings),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = brushPresetName(active),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                for (preset in category) {
                    FilterChip(
                        selected = preset.id == active.id,
                        onClick = {
                            if (preset.id == active.id) return@FilterChip
                            if (hapticsMode == HapticsMode.ENABLED) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            onPresetSelected(preset.id)
                        },
                        label = { Text(brushPresetName(preset)) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PREVIEW_HEIGHT)
                    .onSizeChanged { previewSize = it },
            ) {
                val bitmap = preview
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            SettingsGroup(stringResource(R.string.brush_group_stroke))
            SettingSlider(
                label = stringResource(R.string.brush_size),
                value = BrushSizeScale.fraction(active.size, active.sizeMin, active.sizeMax),
                valueText = stringResource(R.string.brush_value_px, active.size),
                range = UNIT_RANGE,
                onValueChange = {
                    onPresetChanged(
                        active.copy(
                            size = BrushSizeScale.size(it, active.sizeMin, active.sizeMax),
                        ),
                    )
                },
                onValueChangeFinished = onPresetPersisted,
            )
            SettingSlider(
                label = stringResource(R.string.brush_opacity),
                value = active.opacity,
                valueText = percent(active.opacity),
                range = UNIT_RANGE,
                onValueChange = { value ->
                    if (
                        hapticsMode == HapticsMode.ENABLED &&
                        OpacityMilestone.crossed(previousOpacity, value).isNotEmpty()
                    ) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    previousOpacity = value
                    onPresetChanged(active.copy(opacity = value))
                },
                onValueChangeFinished = onPresetPersisted,
            )
            SettingSlider(
                label = stringResource(R.string.brush_flow),
                value = active.flow,
                valueText = percent(active.flow),
                range = UNIT_RANGE,
                onValueChange = { onPresetChanged(active.copy(flow = it)) },
                onValueChangeFinished = onPresetPersisted,
            )

            SettingsGroup(stringResource(R.string.brush_group_tip))
            SettingSlider(
                label = stringResource(R.string.brush_hardness),
                value = active.hardness,
                valueText = percent(active.hardness),
                range = UNIT_RANGE,
                onValueChange = { onPresetChanged(active.copy(hardness = it)) },
                onValueChangeFinished = onPresetPersisted,
            )
            SettingSlider(
                label = stringResource(R.string.brush_spacing),
                value = active.spacing / DIAMETER_TO_RADIUS,
                valueText = percent(active.spacing / DIAMETER_TO_RADIUS),
                range = BrushPreset.MIN_SPACING / DIAMETER_TO_RADIUS..
                    BrushPreset.MAX_SPACING / DIAMETER_TO_RADIUS,
                onValueChange = {
                    onPresetChanged(active.copy(spacing = it * DIAMETER_TO_RADIUS))
                },
                onValueChangeFinished = onPresetPersisted,
            )
            ChoiceLabel(stringResource(R.string.brush_tip_shape))
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                FilterChip(
                    selected = active.tip is TipShape.Round,
                    onClick = {
                        onPresetChanged(active.copy(tip = TipShape.Round))
                        onPresetPersisted()
                    },
                    label = { Text(stringResource(R.string.brush_tip_round)) },
                )
                FilterChip(
                    selected = active.tip is TipShape.Flat,
                    onClick = {
                        if (active.tip is TipShape.Flat) return@FilterChip
                        onPresetChanged(active.copy(tip = TipShape.Flat(DEFAULT_FLAT_ASPECT)))
                        onPresetPersisted()
                    },
                    label = { Text(stringResource(R.string.brush_tip_flat)) },
                )
            }
            val flat = active.tip as? TipShape.Flat
            if (flat != null) {
                SettingSlider(
                    label = stringResource(R.string.brush_tip_aspect),
                    value = flat.aspect,
                    valueText = percent(flat.aspect),
                    range = TipShape.Flat.MIN_ASPECT..1f,
                    onValueChange = {
                        onPresetChanged(active.copy(tip = TipShape.Flat(it)))
                    },
                    onValueChangeFinished = onPresetPersisted,
                )
            }
            ChoiceLabel(stringResource(R.string.brush_tip_orientation))
            Row(
                horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                OrientationChip(
                    active,
                    TipOrientation.Fixed,
                    R.string.brush_tip_fixed,
                    onPresetChanged,
                    onPresetPersisted,
                )
                OrientationChip(
                    active,
                    TipOrientation.Stylus,
                    R.string.brush_tip_stylus,
                    onPresetChanged,
                    onPresetPersisted,
                )
                OrientationChip(
                    active,
                    TipOrientation.StrokeDirection,
                    R.string.brush_tip_direction,
                    onPresetChanged,
                    onPresetPersisted,
                )
            }

            SettingsGroup(stringResource(R.string.brush_group_dynamics))
            CurveEditor(
                title = stringResource(R.string.brush_pressure_size),
                curve = active.pressureSize,
                onChanged = { onPresetChanged(active.copy(pressureSize = it)) },
                onFinished = onPresetPersisted,
                valueText = percent,
            )
            CurveEditor(
                title = stringResource(R.string.brush_pressure_opacity),
                curve = active.pressureOpacity,
                onChanged = { onPresetChanged(active.copy(pressureOpacity = it)) },
                onFinished = onPresetPersisted,
                valueText = percent,
            )
            CurveEditor(
                title = stringResource(R.string.brush_pressure_flow),
                curve = active.pressureFlow,
                onChanged = { onPresetChanged(active.copy(pressureFlow = it)) },
                onFinished = onPresetPersisted,
                valueText = percent,
            )
            SettingSlider(
                label = stringResource(R.string.brush_tilt_size),
                value = active.tilt.sizeAtFlat,
                valueText = number(active.tilt.sizeAtFlat),
                range = TiltEffect.MIN_MUL..TiltEffect.MAX_MUL,
                onValueChange = {
                    onPresetChanged(active.copy(tilt = active.tilt.copy(sizeAtFlat = it)))
                },
                onValueChangeFinished = onPresetPersisted,
            )
            SettingSlider(
                label = stringResource(R.string.brush_tilt_opacity),
                value = active.tilt.opacityAtFlat,
                valueText = number(active.tilt.opacityAtFlat),
                range = 0f..TiltEffect.MAX_MUL,
                onValueChange = {
                    onPresetChanged(active.copy(tilt = active.tilt.copy(opacityAtFlat = it)))
                },
                onValueChangeFinished = onPresetPersisted,
            )
            ToggleRow(
                label = stringResource(R.string.brush_tilt_elongate),
                value = if (active.tilt.elongate) ToggleValue.On else ToggleValue.Off,
                onChanged = {
                    onPresetChanged(
                        active.copy(tilt = active.tilt.copy(elongate = it.enabled)),
                    )
                    onPresetPersisted()
                },
            )
            SettingSlider(
                label = stringResource(R.string.brush_velocity_size),
                value = active.velocity.sizeAtFast,
                valueText = number(active.velocity.sizeAtFast),
                range = TiltEffect.MIN_MUL..TiltEffect.MAX_MUL,
                onValueChange = {
                    onPresetChanged(active.copy(velocity = active.velocity.copy(sizeAtFast = it)))
                },
                onValueChangeFinished = onPresetPersisted,
            )
            SettingSlider(
                label = stringResource(R.string.brush_velocity_opacity),
                value = active.velocity.opacityAtFast,
                valueText = number(active.velocity.opacityAtFast),
                range = 0f..TiltEffect.MAX_MUL,
                onValueChange = {
                    onPresetChanged(active.copy(velocity = active.velocity.copy(opacityAtFast = it)))
                },
                onValueChangeFinished = onPresetPersisted,
            )
            SettingSlider(
                label = stringResource(R.string.brush_velocity_threshold),
                value = active.velocity.fastPxPerMs.coerceAtMost(MAX_FAST_PX_PER_MS),
                valueText = number(active.velocity.fastPxPerMs),
                range = MIN_FAST_PX_PER_MS..MAX_FAST_PX_PER_MS,
                onValueChange = {
                    onPresetChanged(active.copy(velocity = active.velocity.copy(fastPxPerMs = it)))
                },
                onValueChangeFinished = onPresetPersisted,
            )
            SettingSlider(
                label = stringResource(R.string.brush_jitter_size),
                value = active.jitter.size,
                valueText = percent(active.jitter.size),
                range = UNIT_RANGE,
                onValueChange = {
                    onPresetChanged(active.copy(jitter = active.jitter.copy(size = it)))
                },
                onValueChangeFinished = onPresetPersisted,
            )
            SettingSlider(
                label = stringResource(R.string.brush_jitter_position),
                value = active.jitter.position,
                valueText = percent(active.jitter.position),
                range = UNIT_RANGE,
                onValueChange = {
                    onPresetChanged(active.copy(jitter = active.jitter.copy(position = it)))
                },
                onValueChangeFinished = onPresetPersisted,
            )
            SettingSlider(
                label = stringResource(R.string.brush_stabilizer),
                value = active.stabilizer,
                valueText = percent(active.stabilizer),
                range = UNIT_RANGE,
                onValueChange = { onPresetChanged(active.copy(stabilizer = it)) },
                onValueChangeFinished = onPresetPersisted,
            )

            SettingsGroup(stringResource(R.string.brush_group_paint))
            ToggleRow(
                label = stringResource(R.string.brush_grain),
                value = if (active.grain == BrushPreset.PROCEDURAL_GRAIN) {
                    ToggleValue.On
                } else {
                    ToggleValue.Off
                },
                onChanged = {
                    val grain = if (it.enabled) BrushPreset.PROCEDURAL_GRAIN else null
                    onPresetChanged(active.copy(grain = grain))
                    onPresetPersisted()
                },
            )
            // RGB ignores these stored values; retaining them restores the
            // brush's pigment tuning when the user switches back.
            if (BrushSettingsPolicy.showsPigmentControls(active, mixerChoice)) {
                ToggleRow(
                    label = stringResource(R.string.brush_pigment),
                    value = if (active.mixing) ToggleValue.On else ToggleValue.Off,
                    onChanged = {
                        onPresetChanged(active.copy(mixing = it.enabled))
                        onPresetPersisted()
                    },
                )
                SettingSlider(
                    label = stringResource(R.string.brush_dilution),
                    value = active.dilution,
                    valueText = percent(active.dilution),
                    range = UNIT_RANGE,
                    onValueChange = { onPresetChanged(active.copy(dilution = it)) },
                    onValueChangeFinished = onPresetPersisted,
                )
            }
            ChoiceLabel(stringResource(R.string.brush_buffer_mode))
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                BufferChip(
                    active,
                    BufferMode.Max,
                    R.string.brush_buffer_max,
                    onPresetChanged,
                    onPresetPersisted,
                )
                BufferChip(
                    active,
                    BufferMode.Accumulate,
                    R.string.brush_buffer_accumulate,
                    onPresetChanged,
                    onPresetPersisted,
                )
            }

            Spacer(Modifier.height(GROUP_GAP))
            Button(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.brush_reset))
            }
            Spacer(Modifier.height(SHEET_BOTTOM_GAP))
        }
    }
}

@Composable
internal fun SettingsGroup(title: String) {
    Spacer(Modifier.height(GROUP_GAP))
    HorizontalDivider()
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = GROUP_TITLE_GAP),
    )
}

@Composable
internal fun ChoiceLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = CONTROL_GAP),
    )
}

@Composable
internal fun SettingSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    steps: Int = 0,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.labelMedium)
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
    )
}

@Composable
internal fun ToggleRow(label: String, value: ToggleValue, onChanged: (ToggleValue) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = value.enabled,
            onCheckedChange = {
                onChanged(if (it) ToggleValue.On else ToggleValue.Off)
            },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
internal fun CurveEditor(
    title: String,
    curve: Curve,
    onChanged: (Curve) -> Unit,
    onFinished: () -> Unit,
    valueText: @Composable (Float) -> String,
) {
    ChoiceLabel(title)
    CurvePlot(curve)
    SettingSlider(
        stringResource(R.string.brush_curve_knot, 1),
        curve.p0,
        valueText(curve.p0),
        UNIT_RANGE,
        { onChanged(curve.copy(p0 = it)) },
        onFinished,
    )
    SettingSlider(
        stringResource(R.string.brush_curve_knot, 2),
        curve.p1,
        valueText(curve.p1),
        UNIT_RANGE,
        { onChanged(curve.copy(p1 = it)) },
        onFinished,
    )
    SettingSlider(
        stringResource(R.string.brush_curve_knot, 3),
        curve.p2,
        valueText(curve.p2),
        UNIT_RANGE,
        { onChanged(curve.copy(p2 = it)) },
        onFinished,
    )
    SettingSlider(
        stringResource(R.string.brush_curve_knot, 4),
        curve.p3,
        valueText(curve.p3),
        UNIT_RANGE,
        { onChanged(curve.copy(p3 = it)) },
        onFinished,
    )
}

/**
 * The curve the four knot sliders below own, drawn so the control is legible:
 * pressure in on x, mapped value on y, the sampled spline and its four knots.
 *
 * Read-only on purpose — the sliders stay the editing surface, this is the
 * shape they add up to. Sampled through [Curve.eval] rather than interpolated
 * between the knots: a polyline would hide the Catmull-Rom overshoot the
 * knots are being tuned for.
 */
@Composable
private fun CurvePlot(curve: Curve) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val knot = MaterialTheme.colorScheme.secondary
    val description = stringResource(R.string.brush_curve_plot)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(PLOT_HEIGHT)
            .padding(vertical = PLOT_VERTICAL_PADDING)
            .clipToBounds()
            .semantics { contentDescription = description },
    ) {
        val gridStroke = GRID_STROKE.toPx()
        val curveStroke = CURVE_STROKE.toPx()
        val knotRadius = KNOT_RADIUS.toPx()

        drawRect(color = grid, style = Stroke(width = gridStroke))
        for (k in 1 until KNOT_X.size - 1) {
            val x = size.width * KNOT_X[k]
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), gridStroke)
        }
        val mid = size.height / 2f
        drawLine(grid, Offset(0f, mid), Offset(size.width, mid), gridStroke)

        val path = Path()
        for (i in 0..PLOT_SAMPLES) {
            val x = i.toFloat() / PLOT_SAMPLES
            val px = x * size.width
            val py = (1f - curve.eval(x)) * size.height
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path,
            color = line,
            style = Stroke(width = curveStroke, cap = StrokeCap.Round),
        )

        val knotY = floatArrayOf(curve.p0, curve.p1, curve.p2, curve.p3)
        for (k in KNOT_X.indices) {
            drawCircle(
                color = knot,
                radius = knotRadius,
                center = Offset(size.width * KNOT_X[k], (1f - knotY[k]) * size.height),
            )
        }
    }
}

@Composable
private fun OrientationChip(
    active: BrushPreset,
    orientation: TipOrientation,
    @StringRes label: Int,
    onPresetChanged: (BrushPreset) -> Unit,
    onPresetPersisted: () -> Unit,
) {
    FilterChip(
        selected = active.orientation == orientation,
        onClick = {
            onPresetChanged(active.copy(orientation = orientation))
            onPresetPersisted()
        },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun BufferChip(
    active: BrushPreset,
    mode: BufferMode,
    @StringRes label: Int,
    onPresetChanged: (BrushPreset) -> Unit,
    onPresetPersisted: () -> Unit,
) {
    FilterChip(
        selected = active.bufferMode == mode,
        onClick = {
            onPresetChanged(active.copy(bufferMode = mode))
            onPresetPersisted()
        },
        label = { Text(stringResource(label)) },
    )
}

internal enum class ToggleValue {
    Off,
    On,
    ;

    val enabled: Boolean get() = this == On
}

private val UNIT_RANGE = 0f..1f
private val SHEET_PADDING = 20.dp
private val CHIP_GAP = 8.dp
private val GROUP_GAP = 20.dp
private val GROUP_TITLE_GAP = 12.dp
private val CONTROL_GAP = 8.dp
private val SHEET_BOTTOM_GAP = 32.dp
private val PREVIEW_HEIGHT = 72.dp
private const val PREVIEW_DEBOUNCE_MS = 50L
private const val PERCENT = 100f
private const val DIAMETER_TO_RADIUS = 2f
private const val DEFAULT_FLAT_ASPECT = 0.5f
private const val MIN_FAST_PX_PER_MS = 0.1f
private const val MAX_FAST_PX_PER_MS = 16f
private val PLOT_HEIGHT = 64.dp
private val PLOT_VERTICAL_PADDING = 4.dp
private const val PLOT_SAMPLES = 64
private val GRID_STROKE = 1.dp
private val CURVE_STROKE = 2.dp
private val KNOT_RADIUS = 3.dp

/** The four knots' fixed x positions — `Curve`'s contract (04 §2). */
private val KNOT_X = floatArrayOf(0f, 1f / 3f, 2f / 3f, 1f)
