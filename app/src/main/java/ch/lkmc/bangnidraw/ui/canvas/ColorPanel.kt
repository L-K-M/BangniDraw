@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ch.lkmc.bangnidraw.ui.canvas

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.ColorText
import ch.lkmc.bangnidraw.engine.core.ColorUiState
import ch.lkmc.bangnidraw.engine.core.Composite
import ch.lkmc.bangnidraw.engine.core.DishWell
import ch.lkmc.bangnidraw.engine.core.HsvColor
import ch.lkmc.bangnidraw.engine.core.HsvChannel
import ch.lkmc.bangnidraw.engine.core.HsvPicker
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.HueMilestone
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.MixingDish
import ch.lkmc.bangnidraw.engine.core.Palette
import ch.lkmc.bangnidraw.engine.core.PaletteCatalog
import ch.lkmc.bangnidraw.engine.core.RgbFieldArrangement
import ch.lkmc.bangnidraw.engine.core.RgbFieldLayoutPolicy
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class TextInputFocus { CLEAR, FOCUSED }

/** Hue/SV picker, persistent palettes, and the active mixer's dish. */
@Composable
internal fun ColorPanel(
    state: ColorUiState,
    mixSteps: (Int, Int) -> IntArray,
    mixColor: (Int, Int, Float) -> Int,
    onColorSelected: (Int) -> Unit,
    onSwapColors: () -> Unit,
    onMixerChanged: (MixerChoice) -> Unit,
    onPaletteSelected: (String) -> Unit,
    onCreatePalette: () -> Unit,
    onAddToPalette: (Int) -> Unit,
    onReplaceSwatch: (Int) -> Unit,
    onDeleteSwatch: (Int) -> Unit,
    onMoveSwatch: (Int, Int) -> Unit,
    onPickSwatch: (Int) -> Unit,
    onDishWellChanged: (DishWell, Int) -> Unit,
    onPickDishWell: (DishWell) -> Unit,
    onTextInputFocus: (TextInputFocus) -> Unit,
    hapticsMode: HapticsMode,
) {
    var hsv by remember(state.current) { mutableStateOf(HsvColor.fromArgb(state.current)) }
    var draft by remember(state.current) { mutableIntStateOf(state.current) }
    val scroll = rememberScrollState()

    fun preview(next: HsvColor) {
        hsv = next
        draft = next.toArgb()
    }

    fun select(argb: Int) {
        draft = argb
        hsv = HsvColor.fromArgb(argb)
        onColorSelected(argb)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PANEL_GAP),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = PANEL_PADDING, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.color_panel), style = MaterialTheme.typography.headlineSmall)
            HsvRingSquare(
                hsv = hsv,
                hapticsMode = hapticsMode,
                onPreview = ::preview,
                onCommit = { select(it.toArgb()) },
            )
            HsvControls(
                hsv = hsv,
                onPreview = ::preview,
                onCommit = { select(it.toArgb()) },
            )
            ColorChips(
                current = draft,
                previous = state.previous,
                onAddCurrent = { onAddToPalette(draft) },
                onSwap = onSwapColors,
            )
            ColorFields(state.current, ::select, onTextInputFocus)

            PaletteSwitcher(
                palettes = state.palettes,
                activeId = state.activePaletteId,
                onSelected = onPaletteSelected,
                onCreate = onCreatePalette,
            )
            PaletteSwatches(
                palette = state.activePalette,
                selected = draft,
                onSelected = ::select,
                onReplace = onReplaceSwatch,
                onDelete = onDeleteSwatch,
                onMove = onMoveSwatch,
                onPick = onPickSwatch,
            )

            Text(stringResource(R.string.mixing_dish), style = MaterialTheme.typography.titleMedium)
            MixerSwitch(state, onMixerChanged)
            MixingDishControls(
                state = state,
                current = draft,
                mixSteps = mixSteps,
                mixColor = mixColor,
                onSelect = ::select,
                onAddToPalette = onAddToPalette,
                onWellChanged = onDishWellChanged,
                onPickWell = onPickDishWell,
            )
        }
    }
}

@Composable
private fun HsvRingSquare(
    hsv: HsvColor,
    hapticsMode: HapticsMode,
    onPreview: (HsvColor) -> Unit,
    onCommit: (HsvColor) -> Unit,
) {
    val markerColor = MaterialTheme.colorScheme.onSurface
    val latestHsv = rememberUpdatedState(hsv)
    val latestPreview = rememberUpdatedState(onPreview)
    val latestCommit = rememberUpdatedState(onCommit)
    val view = LocalView.current
    Canvas(
        modifier = Modifier
            .size(PICKER_SIZE)
            .pointerInput(hapticsMode) {
                detectTapGestures { position ->
                    val next = HsvPicker.select(
                        position.x,
                        position.y,
                        minOf(size.width, size.height).toFloat(),
                        latestHsv.value,
                    )
                    if (
                        hapticsMode == HapticsMode.ENABLED &&
                        HueMilestone.crossed(latestHsv.value.h, next.h)
                    ) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    latestPreview.value(next)
                    latestCommit.value(next)
                }
            }
            .pointerInput(hapticsMode) {
                var gestureHsv = latestHsv.value
                val update: (Offset) -> Unit = { position ->
                    val next = HsvPicker.select(
                        position.x,
                        position.y,
                        minOf(size.width, size.height).toFloat(),
                        gestureHsv,
                    )
                    if (
                        hapticsMode == HapticsMode.ENABLED &&
                        HueMilestone.crossed(gestureHsv.h, next.h)
                    ) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    gestureHsv = next
                    latestPreview.value(next)
                }
                detectDragGestures(
                    onDragStart = {
                        gestureHsv = latestHsv.value
                        update(it)
                    },
                    onDragEnd = { latestCommit.value(gestureHsv) },
                ) { change, _ -> update(change.position) }
            },
    ) {
        val ringWidth = size.minDimension * RING_WIDTH_FRACTION
        drawCircle(
            brush = Brush.sweepGradient(HUE_COLORS),
            radius = size.minDimension / 2f - ringWidth / 2f,
            style = Stroke(ringWidth),
        )

        val half = size.minDimension * HsvPicker.SQUARE_HALF_EDGE
        val topLeft = Offset(center.x - half, center.y - half)
        val squareSize = Size(half * 2f, half * 2f)
        val hue = Color(HsvColor(hsv.h, 1f, 1f).toArgb())
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color.White, hue), topLeft.x, topLeft.x + squareSize.width),
            topLeft = topLeft,
            size = squareSize,
        )
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black),
                topLeft.y,
                topLeft.y + squareSize.height,
            ),
            topLeft = topLeft,
            size = squareSize,
        )

        val radians = Math.toRadians(hsv.h.toDouble())
        val ringRadius = size.minDimension * RING_MARKER_RADIUS
        val hueMarker = Offset(
            center.x + cos(radians).toFloat() * ringRadius,
            center.y + sin(radians).toFloat() * ringRadius,
        )
        val svMarker = Offset(
            topLeft.x + hsv.s * squareSize.width,
            topLeft.y + (1f - hsv.v) * squareSize.height,
        )
        drawCircle(markerColor, MARKER_RADIUS.toPx(), hueMarker, style = Stroke(MARKER_STROKE.toPx()))
        drawCircle(markerColor, MARKER_RADIUS.toPx(), svMarker, style = Stroke(MARKER_STROKE.toPx()))
    }
}

@Composable
private fun HsvControls(
    hsv: HsvColor,
    onPreview: (HsvColor) -> Unit,
    onCommit: (HsvColor) -> Unit,
) {
    val latestHsv = rememberUpdatedState(hsv)
    val latestCommit = rememberUpdatedState(onCommit)
    var pendingHsv by remember { mutableStateOf(hsv) }

    // Finish commits the last preview for touch, keyboard, and accessibility.
    Column(
        verticalArrangement = Arrangement.spacedBy(FIELD_GAP),
        modifier = Modifier.fillMaxWidth(),
    ) {
        HsvChannel.entries.forEach { channel ->
            val label = stringResource(
                when (channel) {
                    HsvChannel.HUE -> R.string.color_hue
                    HsvChannel.SATURATION -> R.string.color_saturation
                    HsvChannel.VALUE -> R.string.color_value
                },
            )
            val value = channel.read(hsv)
            val valueText = when (channel) {
                HsvChannel.HUE -> stringResource(R.string.color_hue_value, value)
                HsvChannel.SATURATION,
                HsvChannel.VALUE,
                -> stringResource(R.string.brush_value_percent, value)
            }

            HsvChannelSlider(
                label = label,
                value = value,
                valueText = valueText,
                range = channel.range,
                steps = channel.steps,
                onChanged = {
                    val next = channel.replace(latestHsv.value, it)
                    pendingHsv = next
                    onPreview(next)
                },
                onFinished = { latestCommit.value(pendingHsv) },
            )
        }
    }
}

@Composable
private fun HsvChannelSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChanged: (Float) -> Unit,
    onFinished: () -> Unit,
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
        onValueChangeFinished = onFinished,
        valueRange = range,
        steps = steps,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorChips(
    current: Int,
    previous: Int,
    onAddCurrent: () -> Unit,
    onSwap: () -> Unit,
) {
    val currentLabel = stringResource(R.string.color_current)
    val previousLabel = stringResource(R.string.color_previous)
    val addLabel = stringResource(R.string.mixing_add_palette)
    Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP), verticalAlignment = Alignment.CenterVertically) {
        ColorCircle(
            current,
            Modifier
                .size(CURRENT_CHIP_SIZE)
                .semantics {
                    contentDescription = currentLabel
                    onLongClick(label = addLabel) {
                        onAddCurrent()
                        true
                    }
                }
                .pointerInput(onAddCurrent) {
                    detectTapGestures(onLongPress = { onAddCurrent() })
                },
        )
        ColorCircle(
            previous,
            Modifier
                .size(PREVIOUS_CHIP_SIZE)
                .semantics { contentDescription = previousLabel }
                .combinedClickable(onClick = onSwap),
        )
        Text(ColorText.hex(current), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ColorFields(
    color: Int,
    onSelected: (Int) -> Unit,
    onTextInputFocus: (TextInputFocus) -> Unit,
) {
    var hex by remember(color) { mutableStateOf(ColorText.hex(color)) }
    var red by remember(color) { mutableStateOf(Composite.red(color).toString()) }
    var green by remember(color) { mutableStateOf(Composite.green(color).toString()) }
    var blue by remember(color) { mutableStateOf(Composite.blue(color).toString()) }

    fun selectRgb() {
        val r = ColorText.parseChannel(red) ?: return
        val g = ColorText.parseChannel(green) ?: return
        val b = ColorText.parseChannel(blue) ?: return
        onSelected(Composite.argb(CHANNEL_MAX, r, g, b))
    }

    OutlinedTextField(
        value = hex,
        onValueChange = {
            hex = it
            ColorText.parseHex(it)?.let(onSelected)
        },
        label = { Text(stringResource(R.string.color_hex)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                onTextInputFocus(
                    if (focus.hasFocus) TextInputFocus.FOCUSED else TextInputFocus.CLEAR,
                )
            },
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = RgbFieldLayoutPolicy.forContentWidth(
            contentWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
        )
        if (!layout.hasUsableWidth) return@BoxWithConstraints

        val fieldModifier = Modifier.width(layout.fieldWidthDp.dp)
        val fields: @Composable () -> Unit = {
            ChannelField(red, R.string.color_red, onTextInputFocus, fieldModifier) {
                red = it
                selectRgb()
            }
            ChannelField(green, R.string.color_green, onTextInputFocus, fieldModifier) {
                green = it
                selectRgb()
            }
            ChannelField(blue, R.string.color_blue, onTextInputFocus, fieldModifier) {
                blue = it
                selectRgb()
            }
        }

        when (layout.arrangement) {
            RgbFieldArrangement.ROW -> Row(
                horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
                modifier = Modifier.fillMaxWidth(),
                content = { fields() },
            )
            RgbFieldArrangement.COLUMN -> Column(
                verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
                modifier = Modifier.fillMaxWidth(),
                content = { fields() },
            )
        }
    }
}

@Composable
private fun ChannelField(
    value: String,
    label: Int,
    onTextInputFocus: (TextInputFocus) -> Unit,
    modifier: Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = modifier
            .heightIn(min = CHANNEL_FIELD_MIN_HEIGHT)
            .onFocusChanged { focus ->
                onTextInputFocus(
                    if (focus.hasFocus) TextInputFocus.FOCUSED else TextInputFocus.CLEAR,
                )
            },
    )
}

@Composable
private fun PaletteSwitcher(
    palettes: List<Palette>,
    activeId: String,
    onSelected: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (palette in palettes) {
            FilterChip(
                selected = palette.id == activeId,
                onClick = { onSelected(palette.id) },
                label = { Text(paletteLabel(palette.name)) },
            )
        }
        Button(onClick = onCreate) { Text(stringResource(R.string.palette_create)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteSwatches(
    palette: Palette,
    selected: Int,
    onSelected: (Int) -> Unit,
    onReplace: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onPick: (Int) -> Unit,
) {
    var menuIndex by remember(palette.id, palette.swatches) { mutableIntStateOf(NO_SWATCH) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        palette.swatches.forEachIndexed { index, color ->
            Box {
                ColorCircle(
                    color,
                    Modifier
                        .size(SWATCH_TARGET)
                        .semantics { contentDescription = ColorText.hex(color) }
                        .combinedClickable(
                            onClick = { onSelected(color) },
                            onLongClick = { if (!palette.builtIn) menuIndex = index },
                        )
                        .padding(SWATCH_PADDING),
                    selected = color == selected,
                )
                DropdownMenu(
                    expanded = menuIndex == index,
                    onDismissRequest = { menuIndex = NO_SWATCH },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.palette_replace)) },
                        onClick = { menuIndex = NO_SWATCH; onReplace(index) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.palette_eyedrop)) },
                        onClick = { menuIndex = NO_SWATCH; onPick(index) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.palette_delete)) },
                        onClick = { menuIndex = NO_SWATCH; onDelete(index) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.palette_move_left)) },
                        enabled = index > 0,
                        onClick = { menuIndex = NO_SWATCH; onMove(index, index - 1) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.palette_move_right)) },
                        enabled = index < palette.swatches.lastIndex,
                        onClick = { menuIndex = NO_SWATCH; onMove(index, index + 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MixerSwitch(state: ColorUiState, onMixerChanged: (MixerChoice) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        FilterChip(
            selected = state.mixerChoice == MixerChoice.PIGMENT,
            enabled = state.pigmentMixerAvailable,
            onClick = { onMixerChanged(MixerChoice.PIGMENT) },
            label = { Text(stringResource(R.string.mixing_pigment)) },
        )
        FilterChip(
            selected = state.mixerChoice == MixerChoice.RGB,
            onClick = { onMixerChanged(MixerChoice.RGB) },
            label = { Text(stringResource(R.string.mixing_rgb)) },
        )
    }
}

@Composable
private fun MixingDishControls(
    state: ColorUiState,
    current: Int,
    mixSteps: (Int, Int) -> IntArray,
    mixColor: (Int, Int, Float) -> Int,
    onSelect: (Int) -> Unit,
    onAddToPalette: (Int) -> Unit,
    onWellChanged: (DishWell, Int) -> Unit,
    onPickWell: (DishWell) -> Unit,
) {
    var t by remember { mutableFloatStateOf(state.dish.t) }
    val gradient = mixSteps(state.dish.a, state.dish.b)
    val result = mixColor(state.dish.a, state.dish.b, t)
    val wellALabel = stringResource(R.string.mixing_well_a)
    val wellBLabel = stringResource(R.string.mixing_well_b)
    Row(horizontalArrangement = Arrangement.spacedBy(FIELD_GAP), verticalAlignment = Alignment.CenterVertically) {
        ColorCircle(
            state.dish.a,
            Modifier
                .size(PREVIOUS_CHIP_SIZE)
                .semantics { contentDescription = wellALabel }
                .combinedClickable(
                    onClick = { onWellChanged(DishWell.A, current) },
                    onLongClick = { onPickWell(DishWell.A) },
                ),
        )
        Slider(value = t, onValueChange = { t = it }, modifier = Modifier.weight(1f))
        ColorCircle(
            state.dish.b,
            Modifier
                .size(PREVIOUS_CHIP_SIZE)
                .semantics { contentDescription = wellBLabel }
                .combinedClickable(
                    onClick = { onWellChanged(DishWell.B, current) },
                    onLongClick = { onPickWell(DishWell.B) },
                ),
        )
    }
    val selectedStep = (t * (MixingDish.STEPS - 1)).roundToInt()
    SwatchStrip(gradient, selectedStep) { index, color ->
        t = index.toFloat() / (MixingDish.STEPS - 1)
        onSelect(color)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
        Button(onClick = { onSelect(result) }) { Text(stringResource(R.string.mixing_use)) }
        Button(onClick = { onAddToPalette(result) }) {
            Text(stringResource(R.string.mixing_add_palette))
        }
    }
    Text(stringResource(R.string.mixing_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SwatchStrip(colors: IntArray, selectedIndex: Int, onSelected: (Int, Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        colors.forEachIndexed { index, color ->
            ColorCircle(
                color,
                Modifier
                    .size(SWATCH_TARGET)
                    .semantics { contentDescription = ColorText.hex(color) }
                    .combinedClickable(onClick = { onSelected(index, color) })
                    .padding(SWATCH_PADDING),
                selected = index == selectedIndex,
            )
        }
    }
}

@Composable
private fun ColorCircle(argb: Int, modifier: Modifier, selected: Boolean = false) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        drawCircle(Color(argb))
        drawCircle(outline, style = Stroke(if (selected) SELECTED_BORDER.toPx() else SWATCH_BORDER.toPx()))
    }
}

@Composable
private fun paletteLabel(name: String): String = when (name) {
    PaletteCatalog.PAINTERS_NAME -> stringResource(R.string.palette_painters)
    PaletteCatalog.BASIC_NAME -> stringResource(R.string.palette_basic)
    PaletteCatalog.RECENT_NAME -> stringResource(R.string.palette_recent)
    PaletteCatalog.MY_PALETTE_NAME -> stringResource(R.string.palette_my)
    else -> name
}

private val HUE_COLORS = listOf(
    Color.Red,
    Color.Yellow,
    Color.Green,
    Color.Cyan,
    Color.Blue,
    Color.Magenta,
    Color.Red,
)
private val PICKER_SIZE = 220.dp
private val CURRENT_CHIP_SIZE = 56.dp
private val PREVIOUS_CHIP_SIZE = 48.dp
private val SWATCH_TARGET = 48.dp
private val SWATCH_PADDING = 8.dp
private val MARKER_RADIUS = 7.dp
private val MARKER_STROKE = 2.dp
private val SWATCH_BORDER = 1.dp
private val SELECTED_BORDER = 3.dp
private val PANEL_PADDING = 20.dp
private val PANEL_GAP = 12.dp
private val FIELD_GAP = 6.dp
private val CHANNEL_FIELD_MIN_HEIGHT = 64.dp
private const val CHANNEL_MAX = 255
private const val NO_SWATCH = -1
private const val RING_WIDTH_FRACTION = 0.12f
private const val RING_MARKER_RADIUS = 0.44f
