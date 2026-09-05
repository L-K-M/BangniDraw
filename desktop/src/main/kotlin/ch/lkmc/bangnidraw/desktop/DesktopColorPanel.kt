@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.ColorText
import ch.lkmc.bangnidraw.engine.core.Composite
import ch.lkmc.bangnidraw.engine.core.DishWell
import ch.lkmc.bangnidraw.engine.core.HsvChannel
import ch.lkmc.bangnidraw.engine.core.HsvColor
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.MixingDish
import ch.lkmc.bangnidraw.engine.core.Palette
import ch.lkmc.bangnidraw.engine.core.PaletteCatalog
import ch.lkmc.bangnidraw.engine.core.PalettePolicy
import ch.lkmc.bangnidraw.ui.shared.ColorCircle
import ch.lkmc.bangnidraw.ui.shared.HsvRingSquare
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The desktop twin of `:app`'s `ColorPanel`, section for section: the hue
 * ring over its saturation/value square, the three channel readings, the
 * current/previous chips, the hex and RGB fields, the palette switcher and
 * its swatches, and the mixing dish.
 *
 * The picker itself is `:app`'s own, compiled from `ui/shared` — its ring
 * radius and square edge are [HsvRingSquare]'s single copy, so a tap near
 * their boundary cannot mean one thing on a phone and another here.
 *
 * Where Android hides an action behind a long-press, this uses the mouse's
 * own idiom: the secondary button opens the swatch's or the well's menu.
 */
@Composable
internal fun DesktopColorPanel(
    state: DesktopShellState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Panel-local, exactly as `:app`'s is: a ring drag previews continuously
    // and must not persist a colour per frame, and a greyscale commit must
    // not rebuild HSV from ARGB (hue is not in the bytes).
    var selection by remember { mutableStateOf(state.colorSelection) }
    LaunchedEffect(state.colorSelection.argb) {
        selection = selection.sync(state.colorSelection.argb)
    }
    var namingPalette by remember { mutableStateOf(false) }

    val hsv = selection.hsv
    val draft = selection.argb

    fun select(argb: Int) {
        selection = selection.commit(argb)
        state.pickColor(selection)
    }

    fun select(next: HsvColor) {
        selection = selection.commit(next)
        state.pickColor(selection)
    }

    if (namingPalette) {
        PaletteNameDialog(
            existingNames = state.palettes.map { paletteLabel(it.name) }.toSet(),
            onConfirm = {
                namingPalette = false
                state.createPalette(it)
            },
            onDismiss = { namingPalette = false },
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        modifier = modifier
            .width(PANEL_WIDTH)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                paneTitle = DesktopStrings.get("color_panel")
            },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
            modifier = Modifier
                .padding(PANEL_PADDING)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(DesktopStrings.get("color_panel"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = DesktopStrings.get("panel_close"))
                }
            }

            HsvRingSquare(
                hsv = hsv,
                onPreview = { selection = selection.preview(it) },
                onCommit = ::select,
            )

            HsvControls(
                hsv = hsv,
                onPreview = { selection = selection.preview(it) },
                onCommit = ::select,
            )

            ColorChips(
                current = draft,
                previous = state.previousColor,
                onSwap = state::swapColors,
                onAdd = { state.addToPalette(draft) },
            )
            ColorFields(draft, ::select)

            PaletteSwitcher(
                palettes = state.palettes,
                activeId = state.activePaletteId,
                onSelected = state::selectPalette,
                onCreate = { namingPalette = true },
            )
            PaletteSwatches(
                palette = state.activePalette,
                selected = draft,
                onSelected = ::select,
                onReplace = { state.replaceSwatch(it, draft) },
                onPick = { index ->
                    state.beginPickInto(
                        DesktopPickTarget.Swatch(state.activePaletteId, index),
                    )
                },
                onDelete = state::deleteSwatch,
                onMove = state::moveSwatch,
            )

            Text(
                DesktopStrings.get("mixing_dish"),
                style = MaterialTheme.typography.titleSmall,
            )
            MixerSwitch(state)
            MixingDishControls(state, draft, ::select)
        }
    }
}

/**
 * The three channel readings under the ring, with `:app`'s own preview/commit
 * split: a drag previews continuously and only the settled value is committed,
 * so one gesture is one entry rather than one per frame.
 */
@Composable
private fun HsvControls(
    hsv: HsvColor,
    onPreview: (HsvColor) -> Unit,
    onCommit: (HsvColor) -> Unit,
) {
    var pending by remember(hsv) { mutableStateOf(hsv) }
    for (channel in HsvChannel.entries) {
        val label = channelLabel(channel)
        val value = channel.read(hsv)
        Column {
            Text(
                "$label ${channelValue(channel, value)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The same thin track the rail uses: Material Expressive's default
            // slider brings an asymmetric thumb and a terminal marker that read
            // as a different app beside it.
            DesktopThinSlider(
                value = value,
                range = channel.range,
                axis = DesktopSliderAxis.Horizontal,
                description = label,
                onValueChange = {
                    val next = channel.replace(hsv, it)
                    pending = next
                    onPreview(next)
                },
                fillWidth = true,
                // The channel's own stops, as `:app`'s panel passes them: hue
                // to the degree, saturation and value to the percent. Without
                // them the desktop panel drifts off the Android one by design,
                // which is the whole reason HsvChannel carries `steps`.
                steps = channel.steps,
                onValueChangeFinished = { onCommit(pending) },
            )
        }
    }
}

@Composable
private fun ColorChips(
    current: Int,
    previous: Int,
    onSwap: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorCircle(
            current,
            Modifier
                .size(CURRENT_CHIP)
                .semantics { contentDescription = DesktopStrings.get("color_current") },
        )
        // Android swaps on a tap of the previous chip; the same tap here.
        ColorCircle(
            previous,
            Modifier
                .size(PREVIOUS_CHIP)
                .semantics { contentDescription = DesktopStrings.get("color_previous") }
                .clickable(onClick = onSwap),
        )
        // The reading takes the slack rather than the button: a clipped
        // "Add to palette" reads as a bug, a clipped hex code as a hex code.
        Text(
            ColorText.hex(current),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Android hides this behind a long-press on the current chip; a
        // long-press is not a mouse gesture, so it is a button here.
        TextButton(onClick = onAdd) {
            Text(DesktopStrings.get("mixing_add_palette"), maxLines = 1)
        }
    }
}

/**
 * Hex and RGB entry. The drafts are deliberately *not* keyed on [color]: a
 * commit this field itself emitted must not re-key it, or the caret jumps
 * while the user is still typing — `FFF` parses and commits mid-edit.
 */
@Composable
private fun ColorFields(color: Int, onSelected: (Int) -> Unit) {
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
        lastReflected = argb
        onSelected(argb)
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
        onValueChange = { typed ->
            hex = typed
            ColorText.parseHex(typed)?.let { argb ->
                red = Composite.red(argb).toString()
                green = Composite.green(argb).toString()
                blue = Composite.blue(argb).toString()
                emit(argb)
            }
        },
        label = { Text(DesktopStrings.get("color_hex")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        ChannelField(red, "color_red", Modifier.weight(1f)) { red = it; selectRgb() }
        ChannelField(green, "color_green", Modifier.weight(1f)) { green = it; selectRgb() }
        ChannelField(blue, "color_blue", Modifier.weight(1f)) { blue = it; selectRgb() }
    }
}

@Composable
private fun ChannelField(
    value: String,
    label: String,
    modifier: Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(DesktopStrings.get(label), maxLines = 1) },
        singleLine = true,
        modifier = modifier,
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
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (palette in palettes) {
            FilterChip(
                selected = palette.id == activeId,
                onClick = { onSelected(palette.id) },
                label = { Text(paletteLabel(palette.name), maxLines = 1) },
            )
        }
        TextButton(onClick = onCreate) {
            Text(DesktopStrings.get("palette_create"), maxLines = 1)
        }
    }
}

@Composable
private fun PaletteSwatches(
    palette: Palette,
    selected: Int,
    onSelected: (Int) -> Unit,
    onReplace: (Int) -> Unit,
    onPick: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        palette.swatches.forEachIndexed { index, color ->
            val swatch = @Composable {
                ColorCircle(
                    color,
                    Modifier
                        .size(SWATCH_TARGET)
                        .semantics { contentDescription = ColorText.hex(color) }
                        .clickable { onSelected(color) }
                        .padding(SWATCH_PADDING),
                    selected = color == selected,
                )
            }
            // A built-in palette is immutable, so it gets no menu at all
            // rather than one whose every item refuses.
            if (palette.builtIn) {
                swatch()
            } else {
                ContextMenuArea(
                    items = {
                        buildList {
                            add(ContextMenuItem(DesktopStrings.get("palette_replace")) { onReplace(index) })
                            add(ContextMenuItem(DesktopStrings.get("palette_eyedrop")) { onPick(index) })
                            add(ContextMenuItem(DesktopStrings.get("palette_delete")) { onDelete(index) })
                            if (index > 0) {
                                add(
                                    ContextMenuItem(DesktopStrings.get("palette_move_left")) {
                                        onMove(index, index - 1)
                                    },
                                )
                            }
                            if (index < palette.swatches.lastIndex) {
                                add(
                                    ContextMenuItem(DesktopStrings.get("palette_move_right")) {
                                        onMove(index, index + 1)
                                    },
                                )
                            }
                        }
                    },
                    content = swatch,
                )
            }
        }
    }
}

@Composable
private fun MixerSwitch(state: DesktopShellState) {
    Row(horizontalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        FilterChip(
            selected = state.mixerChoice == MixerChoice.PIGMENT,
            enabled = state.pigmentAvailable,
            onClick = { state.chooseMixer(MixerChoice.PIGMENT) },
            label = { Text(DesktopStrings.get("mixing_pigment"), maxLines = 1) },
        )
        FilterChip(
            selected = state.mixerChoice == MixerChoice.RGB,
            onClick = { state.chooseMixer(MixerChoice.RGB) },
            label = { Text(DesktopStrings.get("mixing_rgb"), maxLines = 1) },
        )
    }
}

@Composable
private fun MixingDishControls(
    state: DesktopShellState,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    val dish = state.dish
    val mixer = state.activeMixer
    // Remembered on the wells: the panel recomposes per frame while the ring
    // drags, and nine pigment mixes per frame buy nothing.
    val gradient = remember(dish.a, dish.b, mixer) { MixingDish.gradient(dish.a, dish.b, mixer) }
    val result = remember(dish.a, dish.b, dish.t, mixer) { mixer.mix(dish.a, dish.b, dish.t) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DishWellChip(DishWell.A, dish.a, "mixing_well_a", current, state)
        Slider(
            value = dish.t,
            onValueChange = state::setDishBlend,
            modifier = Modifier.weight(1f),
        )
        DishWellChip(DishWell.B, dish.b, "mixing_well_b", current, state)
    }

    val selectedStep = (dish.t * (MixingDish.STEPS - 1)).roundToInt()
    Row(
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        gradient.forEachIndexed { index, color ->
            ColorCircle(
                color,
                Modifier
                    .size(SWATCH_TARGET)
                    .semantics { contentDescription = ColorText.hex(color) }
                    .clickable {
                        state.setDishBlend(index.toFloat() / (MixingDish.STEPS - 1))
                        onSelect(color)
                    }
                    .padding(SWATCH_PADDING),
                selected = index == selectedStep,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        TextButton(onClick = { onSelect(result) }) {
            Text(DesktopStrings.get("mixing_use"), maxLines = 1)
        }
        TextButton(onClick = { state.addToPalette(result) }) {
            Text(DesktopStrings.get("mixing_add_palette"), maxLines = 1)
        }
    }
    Text(DesktopStrings.get("mixing_hint"), style = MaterialTheme.typography.bodySmall)
}

/**
 * One well. A click fills it from the paint colour, as Android's tap does;
 * the secondary button offers the eyedropper Android hides behind a
 * long-press.
 */
@Composable
private fun DishWellChip(
    well: DishWell,
    color: Int,
    labelKey: String,
    current: Int,
    state: DesktopShellState,
) {
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem(DesktopStrings.get("palette_eyedrop")) {
                    state.beginPickInto(DesktopPickTarget.Well(well))
                },
            )
        },
    ) {
        ColorCircle(
            color,
            Modifier
                .size(PREVIOUS_CHIP)
                .semantics { contentDescription = DesktopStrings.get(labelKey) }
                .clickable { state.setDishWell(well, current) },
        )
    }
}

/**
 * Names a palette about to be created, as `:app`'s dialog does: the field
 * starts empty with the localized default as its placeholder, so confirming
 * blank stores the token that keeps localizing and typing stores a literal.
 */
@Composable
private fun PaletteNameDialog(
    existingNames: Set<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val defaultDisplayName = DesktopStrings.get("palette_my")
    val taken = PalettePolicy.isCreatedNameTaken(draft, defaultDisplayName, existingNames)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DesktopStrings.get("palette_create")) },
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
                        text = DesktopStrings.get("palette_name_taken"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !taken, onClick = { onConfirm(draft) }) {
                Text(DesktopStrings.get("new_canvas_create"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(DesktopStrings.get("new_canvas_cancel"))
            }
        },
    )
}

/** The closed grammar `:app`'s panel resolves; anything else is a literal. */
private fun paletteLabel(name: String): String = when (name) {
    PaletteCatalog.PAINTERS_NAME -> DesktopStrings.get("palette_painters")
    PaletteCatalog.BASIC_NAME -> DesktopStrings.get("palette_basic")
    PaletteCatalog.RECENT_NAME -> DesktopStrings.get("palette_recent")
    PaletteCatalog.MY_PALETTE_NAME -> DesktopStrings.get("palette_my")
    else -> name
}

/**
 * The reading `:app` shows for the same selection: `%.0f` rounds where
 * `toInt` would truncate — a stored saturation of 0.6299 reads 63 there and
 * would read 62 here — and the degree and percent signs are its units.
 */
private fun channelValue(channel: HsvChannel, value: Float): String {
    val rounded = "%.0f".format(Locale.ROOT, value)
    return if (channel == HsvChannel.HUE) "$rounded°" else "$rounded%"
}

private fun channelLabel(channel: HsvChannel): String = DesktopStrings.get(
    when (channel) {
        HsvChannel.HUE -> "color_hue"
        HsvChannel.SATURATION -> "color_saturation"
        HsvChannel.VALUE -> "color_value"
    },
)

private val PANEL_WIDTH = 360.dp
private val PANEL_PADDING = 16.dp
private val SECTION_GAP = 10.dp
private val CHIP_GAP = 8.dp
private val FIELD_GAP = 6.dp
private val CURRENT_CHIP = 44.dp
private val PREVIOUS_CHIP = 36.dp
private val SWATCH_TARGET = 40.dp
private val SWATCH_PADDING = 6.dp
private const val CHANNEL_MAX = 255
