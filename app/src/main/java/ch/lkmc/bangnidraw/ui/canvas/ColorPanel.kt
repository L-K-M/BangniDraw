@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ch.lkmc.bangnidraw.ui.canvas

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.ColorText
import ch.lkmc.bangnidraw.engine.core.ColorUiState
import ch.lkmc.bangnidraw.engine.core.Composite
import ch.lkmc.bangnidraw.engine.core.DishWell
import ch.lkmc.bangnidraw.engine.core.HsvColor
import ch.lkmc.bangnidraw.engine.core.HsvChannel
import ch.lkmc.bangnidraw.engine.core.HsvSelection
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.MixingDish
import ch.lkmc.bangnidraw.engine.core.Palette
import ch.lkmc.bangnidraw.engine.core.PalettePolicy
import ch.lkmc.bangnidraw.engine.core.PaletteCatalog
import ch.lkmc.bangnidraw.engine.core.RgbFieldArrangement
import ch.lkmc.bangnidraw.engine.core.RgbFieldLayoutPolicy
import ch.lkmc.bangnidraw.ui.shared.ColorCircle
import ch.lkmc.bangnidraw.ui.shared.HsvRingSquare
import kotlin.math.roundToInt

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
    onCreatePalette: (String) -> Unit,
    onAddToPalette: (Int) -> Unit,
    onReplaceSwatch: (Int) -> Unit,
    onDeleteSwatch: (Int) -> Unit,
    onMoveSwatch: (Int, Int) -> Unit,
    onPickSwatch: (Int) -> Unit,
    onDishWellChanged: (DishWell, Int) -> Unit,
    onPickDishWell: (DishWell) -> Unit,
    onDishTChanged: (Float) -> Unit,
    onTextInputFocus: (TextInputFocus) -> Unit,
    onDismiss: () -> Unit,
    hapticsMode: HapticsMode,
) {
    var selection by remember {
        mutableStateOf(HsvSelection.fromArgb(state.current))
    }
    LaunchedEffect(state.current) {
        selection = selection.sync(state.current)
    }
    val hsv = selection.hsv
    val draft = selection.argb
    val scroll = rememberScrollState()
    // The picker's geometry is shared; its haptics are not — only this
    // product has a device to tick.
    val view = LocalView.current
    // Naming on create: two palettes must not share one "My palette" chip
    // (the stored name is what distinguishes them — there is no rename yet).
    // Saveable so a rotation mid-dialog keeps both the dialog and the draft.
    var namingPalette by rememberSaveable { mutableStateOf(false) }
    if (namingPalette) {
        // Compared against display names, so a typed literal also collides
        // with a default-named palette that renders the same way.
        PaletteNameDialog(
            existingNames = state.palettes.map { paletteLabel(it.name) }.toSet(),
            onConfirm = { name ->
                namingPalette = false
                onCreatePalette(name)
            },
            onDismiss = { namingPalette = false },
        )
    }

    fun preview(next: HsvColor) {
        selection = selection.preview(next)
    }

    fun select(argb: Int) {
        selection = selection.commit(argb)
        onColorSelected(argb)
    }

    fun select(next: HsvColor) {
        val committed = selection.commit(next)
        selection = committed
        onColorSelected(committed.argb)
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
            PanelHeader(
                title = stringResource(R.string.color_panel),
                onClose = onDismiss,
                helpBody = R.string.help_color_body,
            )
            HsvRingSquare(
                hsv = hsv,
                onPreview = ::preview,
                onCommit = ::select,
                onHueMilestone = {
                    if (hapticsMode == HapticsMode.ENABLED) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                },
            )
            HsvControls(
                hsv = hsv,
                onPreview = ::preview,
                onCommit = ::select,
            )
            ColorChips(
                current = draft,
                previous = state.previous,
                hapticsMode = hapticsMode,
                onAddCurrent = { onAddToPalette(draft) },
                onSwap = onSwapColors,
            )
            ColorFields(state.current, ::select, onTextInputFocus)

            PaletteSwitcher(
                palettes = state.palettes,
                activeId = state.activePaletteId,
                onSelected = onPaletteSelected,
                onCreate = { namingPalette = true },
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
                onTChanged = onDishTChanged,
            )
        }
    }
}

@Composable
private fun HsvControls(
    hsv: HsvColor,
    onPreview: (HsvColor) -> Unit,
    onCommit: (HsvColor) -> Unit,
) {
    val latestHsv = rememberUpdatedState(hsv)
    val latestPreview = rememberUpdatedState(onPreview)
    val latestCommit = rememberUpdatedState(onCommit)
    var pendingHsv by remember(hsv) { mutableStateOf(hsv) }

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
                -> stringResource(R.string.color_percent_value, value)
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
                    latestPreview.value(next)
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
            .semantics {
                contentDescription = label
                stateDescription = valueText
            },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorChips(
    current: Int,
    previous: Int,
    hapticsMode: HapticsMode,
    onAddCurrent: () -> Unit,
    onSwap: () -> Unit,
) {
    val view = LocalView.current
    val currentLabel = stringResource(R.string.color_current)
    val previousLabel = stringResource(R.string.color_previous)
    val addLabel = stringResource(R.string.mixing_add_palette)

    fun addCurrent() {
        if (hapticsMode == HapticsMode.ENABLED) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        onAddCurrent()
    }

    Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP), verticalAlignment = Alignment.CenterVertically) {
        ColorCircle(
            current,
            Modifier
                .size(CURRENT_CHIP_SIZE)
                .semantics {
                    contentDescription = currentLabel
                    onLongClick(label = addLabel) {
                        addCurrent()
                        true
                    }
                }
                .pointerInput(onAddCurrent, hapticsMode) {
                    detectTapGestures(onLongPress = { addCurrent() })
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
    // Field-local drafts, NOT keyed on `color`: a commit this field itself
    // emitted must not re-key the field, or the cursor jumps while the user
    // is still typing (a 3-char hex like "FFF" parses and commits mid-edit).
    // Only a color change from elsewhere (picker, swatch, eyedropper)
    // re-syncs the drafts.
    var hex by remember { mutableStateOf(ColorText.hex(color)) }
    var red by remember { mutableStateOf(Composite.red(color).toString()) }
    var green by remember { mutableStateOf(Composite.green(color).toString()) }
    var blue by remember { mutableStateOf(Composite.blue(color).toString()) }
    var lastReflected by remember { mutableIntStateOf(color) }

    LaunchedEffect(color) {
        if (color == lastReflected) return@LaunchedEffect
        lastReflected = color
        hex = ColorText.hex(color)
        red = Composite.red(color).toString()
        green = Composite.green(color).toString()
        blue = Composite.blue(color).toString()
    }

    fun emit(argb: Int) {
        // The drafts already show what was typed; mark them as reflecting
        // the committed color so the external-change sync does not fire.
        lastReflected = argb
        onSelected(argb)
    }

    /** Syncs the fields the user is NOT typing in; the typed field keeps its literal text. */
    fun syncSiblings(argb: Int) {
        red = Composite.red(argb).toString()
        green = Composite.green(argb).toString()
        blue = Composite.blue(argb).toString()
    }

    fun selectRgb() {
        val r = ColorText.parseChannel(red) ?: return
        val g = ColorText.parseChannel(green) ?: return
        val b = ColorText.parseChannel(blue) ?: return
        val argb = Composite.argb(CHANNEL_MAX, r, g, b)
        hex = ColorText.hex(argb)
        emit(argb)
    }

    OutlinedTextField(
        value = hex,
        onValueChange = {
            hex = it
            ColorText.parseHex(it)?.let { argb ->
                syncSiblings(argb)
                emit(argb)
            }
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
    onTChanged: (Float) -> Unit,
) {
    val t = state.dish.t
    // The nine-mix gradient and the current mix are remembered: the panel
    // recomposes per frame while the picker ring drags, and the wells did
    // not change — nine Mixbox mixes per drag frame buy nothing.
    val gradient = remember(state.dish.a, state.dish.b) { mixSteps(state.dish.a, state.dish.b) }
    val result = remember(state.dish.a, state.dish.b, t) { mixColor(state.dish.a, state.dish.b, t) }
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
        Slider(value = t, onValueChange = onTChanged, modifier = Modifier.weight(1f))
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
        onTChanged(index.toFloat() / (MixingDish.STEPS - 1))
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

/**
 * Names a palette about to be created. The field starts empty with the
 * localized default as its placeholder: confirming blank stores the display
 * token (which keeps localizing), typing stores a literal.
 */
@Composable
private fun PaletteNameDialog(
    existingNames: Set<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val defaultDisplayName = stringResource(R.string.palette_my)
    val taken = PalettePolicy.isCreatedNameTaken(draft, defaultDisplayName, existingNames)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.palette_create)) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(PalettePolicy.NAME_MAX_LENGTH) },
                    singleLine = true,
                    isError = taken,
                    placeholder = { Text(defaultDisplayName) },
                )
                if (taken) {
                    Text(
                        text = stringResource(R.string.palette_name_taken),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !taken,
                onClick = { onConfirm(draft) },
            ) {
                Text(stringResource(R.string.new_canvas_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.new_canvas_cancel))
            }
        },
    )
}

@Composable
private fun paletteLabel(name: String): String = when (name) {
    PaletteCatalog.PAINTERS_NAME -> stringResource(R.string.palette_painters)
    PaletteCatalog.BASIC_NAME -> stringResource(R.string.palette_basic)
    PaletteCatalog.RECENT_NAME -> stringResource(R.string.palette_recent)
    PaletteCatalog.MY_PALETTE_NAME -> stringResource(R.string.palette_my)
    else -> name
}

private val CURRENT_CHIP_SIZE = 56.dp
private val PREVIOUS_CHIP_SIZE = 48.dp
private val SWATCH_TARGET = 48.dp
private val SWATCH_PADDING = 8.dp
private val PANEL_PADDING = 20.dp
private val PANEL_GAP = 12.dp
private val FIELD_GAP = 6.dp
private val CHANNEL_FIELD_MIN_HEIGHT = 64.dp
private const val CHANNEL_MAX = 255
private const val NO_SWATCH = -1
