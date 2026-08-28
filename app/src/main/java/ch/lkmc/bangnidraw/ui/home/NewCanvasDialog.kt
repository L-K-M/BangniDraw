package ch.lkmc.bangnidraw.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.CanvasOrientation
import ch.lkmc.bangnidraw.engine.core.CanvasPreset
import ch.lkmc.bangnidraw.engine.core.CanvasPresetId
import ch.lkmc.bangnidraw.engine.core.CanvasPresets
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.CustomSizeResult
import ch.lkmc.bangnidraw.engine.core.CustomSizeFieldArrangement
import ch.lkmc.bangnidraw.engine.core.HsvChannel
import ch.lkmc.bangnidraw.engine.core.HsvColor
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.NewCanvasDefaultsPolicy
import ch.lkmc.bangnidraw.engine.core.NewCanvasLayoutPolicy
import ch.lkmc.bangnidraw.engine.core.SizeRefusal
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchBlack
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchCustomDefault
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchGray
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchWarm
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchWhite

/**
 * The New Canvas dialog (`docs/plan/08-ui-and-layout.md` §2.1): the fixed
 * presets annotated with what this device holds, a Custom row validated on
 * every keystroke, orientation for non-square sizes, and the paper swatches
 * — the fixed five plus a sixth that opens an HSV picker for any colour
 * (the picker commits through the same `onCreate(size, paperColor)` seam).
 * Every decision it renders comes from the pure canvas and layout policies,
 * so this composable only lays the answers out.
 */
@Composable
fun NewCanvasDialog(
    budget: MemoryBudget.Result,
    /** Captured by the screen because an AlertDialog reports its own window. */
    screenSizePx: IntSize,
    /** The Custom row's pre-fill; null starts from [DEFAULT_CUSTOM_EDGE]². */
    lastCustomSize: CanvasSize?,
    onDismiss: () -> Unit,
    onCreate: (CanvasSize, paperColor: Int) -> Unit,
    /** Reports a Custom-row creation so the next dialog can pre-fill it. */
    onCustomSizeCreated: (CanvasSize) -> Unit,
) {
    val presets = CanvasPresets.forDevice(budget)
    val defaults = NewCanvasDefaultsPolicy.forWindow(
        presets = presets,
        windowWidthPx = screenSizePx.width,
        windowHeightPx = screenSizePx.height,
    )
    var selected by rememberSaveable { mutableIntStateOf(defaults.presetIndex) }
    // The Custom row starts from the last custom size this device created
    // (08 §2.1's "remembered"), falling back to the square default. The
    // remembered size arrives asynchronously, so it syncs only until the
    // user types: a keystroke is intent and always wins over the pre-fill,
    // whether the emission lands before it or after.
    fun prefill(edge: Int?): String = edge?.toString() ?: DEFAULT_CUSTOM_EDGE
    var customW by rememberSaveable { mutableStateOf(prefill(lastCustomSize?.width)) }
    var customH by rememberSaveable { mutableStateOf(prefill(lastCustomSize?.height)) }
    var customEdited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lastCustomSize) {
        if (customEdited) return@LaunchedEffect
        customW = prefill(lastCustomSize?.width)
        customH = prefill(lastCustomSize?.height)
    }
    // Keyed on the selection: an override was chosen FOR a preset, so
    // switching presets starts from that preset's own default again —
    // carrying it across applied a stale Landscape to a portrait row.
    var orientationOverride by rememberSaveable(selected) {
        mutableStateOf<CanvasOrientation?>(null)
    }
    var paper by rememberSaveable { mutableIntStateOf(PaperSwatchWhite.toArgb()) }
    // The sixth swatch's colour: remembered so reopening the dialog keeps the
    // last custom paper, exactly like the Custom row keeps its size.
    var customPaper by rememberSaveable { mutableIntStateOf(PaperSwatchCustomDefault.toArgb()) }
    var paperIsCustom by rememberSaveable { mutableStateOf(false) }
    var showPaperPicker by rememberSaveable { mutableStateOf(false) }
    val orientation = orientationOverride ?: defaults.orientation

    val isCustom = selected == presets.size
    val chosenSize: CanvasSize? = if (isCustom) {
        val w = customW.toIntOrNull()
        val h = customH.toIntOrNull()
        if (w != null && h != null && w > 0 && h > 0) CanvasSize(w, h) else null
    } else {
        presets.getOrNull(selected)?.takeIf { it.enabled }
            ?.let { if (it.isSquare) it.size else it.oriented(orientation) }
    }
    val validation: CustomSizeResult? = chosenSize?.let { CanvasPresets.custom(it, budget) }
    val ok = validation as? CustomSizeResult.Ok

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_canvas_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                presets.forEachIndexed { index, preset ->
                    PresetRow(
                        preset = preset,
                        selected = selected == index,
                        orientation = orientation,
                        onSelect = { if (preset.enabled) selected = index },
                    )
                }

                // Keep the choice and its inputs on separate lines so compact
                // dialogs do not compress two editable values into scraps.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = isCustom, onClick = { selected = presets.size }),
                ) {
                    RadioButton(selected = isCustom, onClick = { selected = presets.size })
                    Text(
                        stringResource(R.string.canvas_preset_custom),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                CustomSizeFields(
                    width = customW,
                    height = customH,
                    enabled = isCustom,
                    onWidthChange = {
                        customEdited = true
                        customW = it.filter(Char::isDigit).take(5)
                    },
                    onHeightChange = {
                        customEdited = true
                        customH = it.filter(Char::isDigit).take(5)
                    },
                )
                val helper = when {
                    !isCustom -> null
                    chosenSize == null -> stringResource(
                        R.string.size_too_small, TileGrid.MIN_EDGE,
                    )
                    else -> when (val v = validation) {
                        is CustomSizeResult.Ok ->
                            pluralStringResource(
                                R.plurals.canvas_preset_fits,
                                v.preset.maxLayers,
                                v.preset.maxLayers,
                            )
                        is CustomSizeResult.Refused -> refusalText(v.reason, budget)
                        null -> null
                    }
                }
                if (helper != null) {
                    Text(
                        helper,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Orientation, hidden for square sizes (08 §2.1).
                val orientable = if (isCustom) false else presets.getOrNull(selected)?.isSquare == false
                if (orientable) {
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = orientation == CanvasOrientation.LANDSCAPE,
                            onClick = { orientationOverride = CanvasOrientation.LANDSCAPE },
                            label = { Text(stringResource(R.string.orientation_landscape)) },
                        )
                        FilterChip(
                            selected = orientation == CanvasOrientation.PORTRAIT,
                            onClick = { orientationOverride = CanvasOrientation.PORTRAIT },
                            label = { Text(stringResource(R.string.orientation_portrait)) },
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.paper_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for ((color, label) in paperSwatches()) {
                        PaperSwatch(
                            color = color,
                            label = label,
                            selected = !paperIsCustom && paper == color.toArgb(),
                            onSelect = {
                                paper = color.toArgb()
                                paperIsCustom = false
                            },
                        )
                    }
                    PaperSwatch(
                        color = Color(customPaper),
                        label = stringResource(R.string.paper_custom),
                        selected = paperIsCustom,
                        onSelect = { showPaperPicker = true },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    ok?.let {
                        if (isCustom) onCustomSizeCreated(it.preset.size)
                        onCreate(it.preset.size, if (paperIsCustom) customPaper else paper)
                    }
                },
                enabled = ok != null,
            ) { Text(stringResource(R.string.new_canvas_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.new_canvas_cancel)) }
        },
    )

    if (showPaperPicker) {
        PaperColorPickerDialog(
            current = customPaper,
            onCancel = { showPaperPicker = false },
            onDone = { argb ->
                customPaper = argb
                paper = argb
                paperIsCustom = true
                showPaperPicker = false
            },
        )
    }
}

/**
 * Any paper colour, as three channel sliders over the shared `HsvChannel`
 * math. A nested `AlertDialog` (its own small window — nothing here needs
 * the parent window's dimensions), committing one ARGB like every preset.
 */
@Composable
private fun PaperColorPickerDialog(
    current: Int,
    onCancel: () -> Unit,
    onDone: (Int) -> Unit,
) {
    var hue by rememberSaveable { mutableFloatStateOf(HsvColor.fromArgb(current).h) }
    var saturation by rememberSaveable { mutableFloatStateOf(HsvColor.fromArgb(current).s) }
    var value by rememberSaveable { mutableFloatStateOf(HsvColor.fromArgb(current).v) }
    val hsv = HsvColor(hue, saturation, value)
    val preview = hsv.toArgb()
    val previewLabel = stringResource(R.string.paper_custom)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.paper_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(NewCanvasLayoutPolicy.PAPER_TARGET_DP.dp)
                        .align(Alignment.CenterHorizontally)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                        .background(Color(preview), CircleShape)
                        .semantics { contentDescription = previewLabel },
                )
                HsvChannel.entries.forEach { channel ->
                    val label = stringResource(
                        when (channel) {
                            HsvChannel.HUE -> R.string.color_hue
                            HsvChannel.SATURATION -> R.string.color_saturation
                            HsvChannel.VALUE -> R.string.color_value
                        },
                    )
                    val channelValue = channel.read(hsv)
                    val valueText = when (channel) {
                        HsvChannel.HUE -> stringResource(R.string.color_hue_value, channelValue)
                        HsvChannel.SATURATION,
                        HsvChannel.VALUE,
                        -> stringResource(R.string.color_percent_value, channelValue)
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = channelValue,
                        onValueChange = { next ->
                            val updated = channel.replace(hsv, next)
                            hue = updated.h
                            saturation = updated.s
                            value = updated.v
                        },
                        valueRange = channel.range,
                        steps = channel.steps,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = label
                                stateDescription = valueText
                            },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(preview) }) {
                Text(stringResource(R.string.reference_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.new_canvas_cancel))
            }
        },
    )
}

@Composable
private fun CustomSizeFields(
    width: String,
    height: String,
    enabled: Boolean,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = NewCanvasLayoutPolicy.customSizeFields(
            contentWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
        )
        if (!layout.hasUsableWidth) return@BoxWithConstraints

        when (layout.arrangement) {
            CustomSizeFieldArrangement.ROW -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
            ) {
                DimensionField(
                    value = width,
                    onValueChange = onWidthChange,
                    label = stringResource(R.string.canvas_width),
                    enabled = enabled,
                    modifier = Modifier.width(layout.fieldWidthDp.dp),
                )
                Text(
                    text = stringResource(R.string.dimension_separator),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(layout.separatorWidthDp.dp),
                )
                DimensionField(
                    value = height,
                    onValueChange = onHeightChange,
                    label = stringResource(R.string.canvas_height),
                    enabled = enabled,
                    modifier = Modifier.width(layout.fieldWidthDp.dp),
                )
            }
            CustomSizeFieldArrangement.COLUMN -> Column(
                verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
            ) {
                DimensionField(
                    value = width,
                    onValueChange = onWidthChange,
                    label = stringResource(R.string.canvas_width),
                    enabled = enabled,
                    modifier = Modifier.width(layout.fieldWidthDp.dp),
                )
                DimensionField(
                    value = height,
                    onValueChange = onHeightChange,
                    label = stringResource(R.string.canvas_height),
                    enabled = enabled,
                    modifier = Modifier.width(layout.fieldWidthDp.dp),
                )
            }
        }
    }
}

@Composable
private fun DimensionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
private fun PresetRow(
    preset: CanvasPreset,
    selected: Boolean,
    orientation: CanvasOrientation,
    onSelect: () -> Unit,
) {
    val displayedSize = preset.oriented(orientation)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = preset.enabled, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = preset.enabled)
        Column(Modifier.weight(1f)) {
            Text(
                presetName(preset.id),
                style = MaterialTheme.typography.bodyMedium,
                color = if (preset.enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.canvas_dimensions,
                    displayedSize.width,
                    displayedSize.height,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (preset.enabled) pluralStringResource(
                R.plurals.canvas_preset_fits,
                preset.maxLayers,
                preset.maxLayers,
            )
            else stringResource(R.string.canvas_preset_too_large),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PaperSwatch(color: Color, label: String, selected: Boolean, onSelect: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(NewCanvasLayoutPolicy.PAPER_TARGET_DP.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(NewCanvasLayoutPolicy.PAPER_VISUAL_DP.dp)
                .border(if (selected) 2.dp else 1.dp, border, CircleShape)
                .background(
                    // The transparent swatch shows the surface variant as its
                    // "checkerboard" stand-in — an actual checker at 28 dp reads
                    // as noise.
                    if (color == Color.Transparent) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        color
                    },
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (color == Color.Transparent) {
                Text(
                    stringResource(R.string.paper_transparent_symbol),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun presetName(id: CanvasPresetId): String = when (id) {
    CanvasPresetId.PHONE_SKETCH -> stringResource(R.string.canvas_preset_phone)
    CanvasPresetId.SQUARE_2048 -> stringResource(R.string.canvas_preset_square)
    CanvasPresetId.TABLET -> stringResource(R.string.canvas_preset_tablet)
    CanvasPresetId.LARGE_4096 -> stringResource(R.string.canvas_preset_large)
    CanvasPresetId.CUSTOM -> stringResource(R.string.canvas_preset_custom)
}

@Composable
private fun refusalText(reason: SizeRefusal, budget: MemoryBudget.Result): String = when (reason) {
    SizeRefusal.TOO_SMALL -> stringResource(R.string.size_too_small, TileGrid.MIN_EDGE)
    SizeRefusal.TOO_LARGE_FOR_FORMAT ->
        stringResource(R.string.size_too_large_format, TileGrid.MAX_EDGE)
    SizeRefusal.TOO_MANY_TILES -> stringResource(R.string.size_too_many_tiles)
    SizeRefusal.TOO_LARGE_FOR_DEVICE ->
        stringResource(R.string.size_too_large_device, budget.maxCanvasEdge)
}

@Composable
private fun paperSwatches(): List<Pair<Color, String>> = listOf(
    PaperSwatchWhite to stringResource(R.string.paper_white),
    PaperSwatchWarm to stringResource(R.string.paper_warm),
    PaperSwatchGray to stringResource(R.string.paper_gray),
    PaperSwatchBlack to stringResource(R.string.paper_black),
    Color.Transparent to stringResource(R.string.paper_transparent),
)

/** The Custom row's starting point when no custom size was created yet. */
private const val DEFAULT_CUSTOM_EDGE = "2048"
