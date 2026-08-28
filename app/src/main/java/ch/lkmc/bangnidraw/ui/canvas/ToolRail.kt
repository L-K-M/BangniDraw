package ch.lkmc.bangnidraw.ui.canvas

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.BrushSizeScale
import ch.lkmc.bangnidraw.engine.core.BrushToolGlyph
import ch.lkmc.bangnidraw.engine.core.BrushToolGlyphPolicy
import ch.lkmc.bangnidraw.engine.core.EraserTogglePolicy
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.OpacityMilestone
import ch.lkmc.bangnidraw.engine.core.RailMode
import ch.lkmc.bangnidraw.engine.core.RailSlotPolicy
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ToolSliderPreset
import ch.lkmc.bangnidraw.engine.core.ToolSliderSecondary
import ch.lkmc.bangnidraw.engine.core.ToolButtonEmphasis
import ch.lkmc.bangnidraw.engine.core.ToolSelection
import ch.lkmc.bangnidraw.ui.theme.LocalThemeTone
import ch.lkmc.bangnidraw.ui.theme.railButtonColors

/** One adaptive control: full rail, grouped rail, short rail, or bottom dock. */
@Composable
internal fun ToolRail(
    layout: LayoutSpec,
    presets: List<BrushPreset>,
    paintBrushId: String,
    eraserBrushId: String,
    selection: ToolSelection,
    hapticsMode: HapticsMode,
    onBrushSelected: (String) -> Unit,
    onSmudgeSelected: () -> Unit,
    onWaterSelected: () -> Unit,
    onBlurSelected: () -> Unit,
    onFillSelected: () -> Unit,
    onEyedropperSelected: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFillSettingsRequested: () -> Unit,
    onEraserToggle: () -> Unit,
    onSizeChanged: (Float) -> Unit,
    onSecondaryChanged: (Float) -> Unit,
    onTuningFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val paints = BrushPresets.railOrder(presets).filterNot(BrushPreset::eraseMode)
    val sliderPreset = ToolSliderPreset.forKind(selection.kind)
    val sliderSecondaryValue = ToolSliderPreset.secondaryValue(selection.kind)
    val sliderSecondary = ToolSliderPreset.secondaryFor(selection.kind)
    val currentPaint = paints.firstOrNull { it.id == paintBrushId } ?: paints.firstOrNull()
    // The FULL rail shows as many paint slots as the window fits; the rest
    // stay one tap away in the settings sheet's chip row.
    val railPaints = if (layout.railMode == RailMode.FULL) {
        RailSlotPolicy.visible(
            paints,
            activePaintId = currentPaint?.id,
            budget = layout.paintSlotBudget,
        )
    } else {
        paints
    }
    val eraser = presets.firstOrNull { it.id == eraserBrushId && it.eraseMode }
        ?: presets.firstOrNull { it.eraseMode }
    val eraserToggle = if (
        eraser != null && EraserTogglePolicy.next(eraser.id, presets) != null
    ) {
        onEraserToggle
    } else {
        null
    }

    val slots = if (layout.railMode == RailMode.FULL) {
        fullSlots(
            paints = railPaints,
            eraser = eraser,
            selection = selection,
            view = view,
            hapticsMode = hapticsMode,
            onBrushSelected = onBrushSelected,
            onSmudgeSelected = onSmudgeSelected,
            onWaterSelected = onWaterSelected,
            onBlurSelected = onBlurSelected,
            onFillSelected = onFillSelected,
            onEyedropperSelected = onEyedropperSelected,
            onSettingsRequested = onSettingsRequested,
            onFillSettingsRequested = onFillSettingsRequested,
            onEraserToggle = eraserToggle,
        )
    } else {
        groupedSlots(
            paint = currentPaint,
            eraser = eraser,
            selection = selection,
            view = view,
            hapticsMode = hapticsMode,
            onBrushSelected = onBrushSelected,
            onSmudgeSelected = onSmudgeSelected,
            onWaterSelected = onWaterSelected,
            onBlurSelected = onBlurSelected,
            onFillSelected = onFillSelected,
            onEyedropperSelected = onEyedropperSelected,
            onSettingsRequested = onSettingsRequested,
            onFillSettingsRequested = onFillSettingsRequested,
            onEraserToggle = eraserToggle,
        )
    }

    if (layout.railMode == RailMode.DOCK) {
        Dock(slots, layout.toolSlotDp.dp, modifier)
        return
    }

    val verticalPadding = if (layout.railMode == RailMode.SHORT) 0.dp else RAIL_PADDING
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = RAIL_ALPHA),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        modifier = modifier.width(layout.railWidthDp.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = RAIL_HORIZONTAL_PADDING, vertical = verticalPadding),
        ) {
            ToolColumn(
                slots = slots,
                slot = layout.toolSlotDp.dp,
                dividersAfter = dividersAfter(
                    railMode = layout.railMode,
                    paints = railPaints,
                    paint = currentPaint,
                    eraser = eraser,
                ),
                gap = if (layout.railMode == RailMode.SHORT) 0.dp else TOOL_GAP,
            )

            if (sliderPreset != null && sliderSecondaryValue != null && layout.sliderLengthDp > 0) {
                BrushSliders(
                    preset = sliderPreset,
                    secondary = sliderSecondary,
                    secondaryValue = sliderSecondaryValue,
                    length = layout.sliderLengthDp.dp,
                    view = view,
                    hapticsMode = hapticsMode,
                    onSizeChanged = onSizeChanged,
                    onSecondaryChanged = onSecondaryChanged,
                    onTuningFinished = onTuningFinished,
                )
            }
        }
    }
}

/**
 * Where the group dividers fall, derived from the slot lists rather than
 * hardcoded indices — a preset set that is not exactly the built-in seven
 * (corrupt JSON falls back to fewer; user presets append) must not put a
 * divider through the middle of a group.
 */
private fun dividersAfter(
    railMode: RailMode,
    paints: List<BrushPreset>,
    paint: BrushPreset?,
    eraser: BrushPreset?,
): Set<Int> = when (railMode) {
    RailMode.FULL -> buildSet {
        if (paints.isNotEmpty()) {
            add(paints.lastIndex)
            if (eraser != null) add(paints.size)
        } else if (eraser != null) {
            add(0)
        }
    }
    RailMode.GROUPED -> {
        val eraserIndex = if (paint != null) 1 else 0
        if (eraser != null) setOf(eraserIndex) else if (paint != null) setOf(0) else emptySet()
    }
    RailMode.SHORT, RailMode.DOCK -> emptySet()
}

@Composable
private fun ToolColumn(
    slots: List<ToolSlot>,
    slot: Dp,
    dividersAfter: Set<Int>,
    gap: Dp,
) {
    for ((index, item) in slots.withIndex()) {
        ToolButton(
            item.icon,
            item.description(),
            item.state,
            item.onClick,
            slot,
            item.onLongClick,
            item.longClickLabel,
            item.hapticsMode,
            item.menuItems,
        )
        if (index == slots.lastIndex) continue

        if (gap > 0.dp) Spacer(Modifier.height(gap))
        if (index in dividersAfter) RailDivider()
    }
}

@Composable
private fun Dock(slots: List<ToolSlot>, slot: Dp, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = RAIL_ALPHA),
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(DOCK_HEIGHT),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (item in slots) {
                ToolButton(
                    item.icon,
                    item.description(),
                    item.state,
                    item.onClick,
                    slot,
                    item.onLongClick,
                    item.longClickLabel,
                    item.hapticsMode,
                    item.menuItems,
                )
            }
        }
    }
}

@Composable
private fun BrushSliders(
    preset: BrushPreset,
    secondary: ToolSliderSecondary,
    secondaryValue: Float,
    length: Dp,
    view: View,
    hapticsMode: HapticsMode,
    onSizeChanged: (Float) -> Unit,
    onSecondaryChanged: (Float) -> Unit,
    onTuningFinished: () -> Unit,
) {
    var previousSecondary by remember(preset.id, secondary) {
        mutableFloatStateOf(secondaryValue)
    }
    Row {
        ThinSlider(
            value = BrushSizeScale.fraction(preset.size, preset.sizeMin, preset.sizeMax),
            range = 0f..1f,
            axis = SliderAxis.Vertical,
            description = stringResource(R.string.brush_size),
            onValueChange = {
                onSizeChanged(BrushSizeScale.size(it, preset.sizeMin, preset.sizeMax))
            },
            onValueChangeFinished = onTuningFinished,
            length = length,
        )
        ThinSlider(
            value = secondaryValue,
            range = 0f..1f,
            axis = SliderAxis.Vertical,
            description = stringResource(
                when (secondary) {
                    ToolSliderSecondary.OPACITY -> R.string.brush_opacity
                    ToolSliderSecondary.FLOW -> R.string.brush_flow
                    ToolSliderSecondary.WATER -> R.string.water_amount
                },
            ),
            onValueChange = { value ->
                if (OpacityMilestone.crossed(previousSecondary, value).isNotEmpty()) {
                    view.tick(hapticsMode)
                }
                previousSecondary = value
                onSecondaryChanged(value)
            },
            onValueChangeFinished = onTuningFinished,
            length = length,
        )
    }
}

@Composable
private fun fullSlots(
    paints: List<BrushPreset>,
    eraser: BrushPreset?,
    selection: ToolSelection,
    view: View,
    hapticsMode: HapticsMode,
    onBrushSelected: (String) -> Unit,
    onSmudgeSelected: () -> Unit,
    onWaterSelected: () -> Unit,
    onBlurSelected: () -> Unit,
    onFillSelected: () -> Unit,
    onEyedropperSelected: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFillSettingsRequested: () -> Unit,
    onEraserToggle: (() -> Unit)?,
): List<ToolSlot> {
    val result = ArrayList<ToolSlot>()
    for (preset in paints) {
        result += brushSlot(
            preset,
            selection,
            view,
            hapticsMode,
            onBrushSelected,
            onSettingsRequested,
        )
    }
    if (eraser != null) {
        result += brushSlot(
            eraser,
            selection,
            view,
            hapticsMode,
            onBrushSelected,
            onSettingsRequested,
            onEraserToggle = onEraserToggle,
        )
    }
    result +=
        fullSecondarySlots(
            selection,
            view,
            hapticsMode,
            onSmudgeSelected,
            onWaterSelected,
            onBlurSelected,
            onFillSelected,
            onEyedropperSelected,
            onSettingsRequested,
            onFillSettingsRequested,
        )
    return result
}

@Composable
private fun groupedSlots(
    paint: BrushPreset?,
    eraser: BrushPreset?,
    selection: ToolSelection,
    view: View,
    hapticsMode: HapticsMode,
    onBrushSelected: (String) -> Unit,
    onSmudgeSelected: () -> Unit,
    onWaterSelected: () -> Unit,
    onBlurSelected: () -> Unit,
    onFillSelected: () -> Unit,
    onEyedropperSelected: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFillSettingsRequested: () -> Unit,
    onEraserToggle: (() -> Unit)?,
): List<ToolSlot> {
    val result = ArrayList<ToolSlot>()
    if (paint != null) {
        result += brushSlot(
            paint,
            selection,
            view,
            hapticsMode,
            onBrushSelected,
            onSettingsRequested,
        )
    }
    if (eraser != null) {
        result += brushSlot(
            eraser,
            selection,
            view,
            hapticsMode,
            onBrushSelected,
            onSettingsRequested,
            onEraserToggle = onEraserToggle,
        )
    }
    result +=
        groupedSecondarySlots(
            selection,
            view,
            hapticsMode,
            onSmudgeSelected,
            onWaterSelected,
            onBlurSelected,
            onFillSelected,
            onEyedropperSelected,
            onSettingsRequested,
            onFillSettingsRequested,
        )
    return result
}

@Composable
private fun brushSlot(
    preset: BrushPreset,
    selection: ToolSelection,
    view: View,
    hapticsMode: HapticsMode,
    onBrushSelected: (String) -> Unit,
    onSettingsRequested: () -> Unit,
    onEraserToggle: (() -> Unit)? = null,
): ToolSlot {
    val active = (selection.kind as? ToolKind.Brush)?.preset?.id == preset.id
    val toggleLabel = if (onEraserToggle != null) {
        stringResource(R.string.cd_toggle_eraser)
    } else {
        null
    }
    return ToolSlot(
        icon = iconFor(BrushToolGlyphPolicy.forPreset(preset)),
        description = { brushPresetName(preset) },
        state = buttonState(
            if (active) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
            selection,
        ),
        onClick = {
            if (active) {
                onSettingsRequested()
            } else {
                view.tick(hapticsMode)
                onBrushSelected(preset.id)
            }
        },
        hapticsMode = hapticsMode,
        // The LONG_PRESS haptic is the built-in one, gated by the provider
        // above; an explicit performHapticFeedback here would double it.
        onLongClick = onEraserToggle,
        longClickLabel = toggleLabel,
    )
}

@Composable
private fun groupedSecondarySlots(
    selection: ToolSelection,
    view: View,
    hapticsMode: HapticsMode,
    onSmudgeSelected: () -> Unit,
    onWaterSelected: () -> Unit,
    onBlurSelected: () -> Unit,
    onFillSelected: () -> Unit,
    onEyedropperSelected: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFillSettingsRequested: () -> Unit,
): List<ToolSlot> {
    val smudgeActive = selection.kind is ToolKind.Smudge
    val waterActive = selection.kind is ToolKind.Water
    val fillActive = selection.kind is ToolKind.Fill
    val blurActive = selection.kind is ToolKind.Blur
    val eyedropperActive = selection.kind is ToolKind.Eyedropper
    val moreActive = blurActive || eyedropperActive

    return listOf(
        ToolSlot(
            icon = Icons.Filled.Gesture,
            description = { stringResource(R.string.tool_smudge) },
            state = buttonState(
                if (smudgeActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
            onClick = {
                if (smudgeActive) onSettingsRequested()
                else switch(view, hapticsMode, onSmudgeSelected)
            },
        ),
        ToolSlot(
            icon = Icons.Filled.WaterDrop,
            description = { stringResource(R.string.tool_water) },
            state = buttonState(
                if (waterActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
            onClick = {
                if (waterActive) onSettingsRequested()
                else switch(view, hapticsMode, onWaterSelected)
            },
        ),
        ToolSlot(
            icon = Icons.Filled.FormatColorFill,
            description = { stringResource(R.string.tool_fill) },
            state = buttonState(
                if (fillActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
            onClick = {
                if (fillActive) onFillSettingsRequested()
                else switch(view, hapticsMode, onFillSelected)
            },
        ),
        ToolSlot(
            icon = Icons.Filled.MoreHoriz,
            description = { stringResource(R.string.tool_more) },
            state = buttonState(
                if (moreActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
            onClick = {},
            menuItems = listOf(
                ToolMenuItem(
                    icon = Icons.Filled.BlurOn,
                    description = { stringResource(R.string.tool_blur) },
                    active = blurActive,
                    onClick = {
                        if (blurActive) onSettingsRequested()
                        else switch(view, hapticsMode, onBlurSelected)
                    },
                ),
                ToolMenuItem(
                    icon = Icons.Filled.Colorize,
                    description = { stringResource(R.string.tool_eyedropper) },
                    active = eyedropperActive,
                    onClick = {
                        if (eyedropperActive) onSettingsRequested()
                        else switch(view, hapticsMode, onEyedropperSelected)
                    },
                ),
            ),
        ),
    )
}
@Composable
private fun fullSecondarySlots(
    selection: ToolSelection,
    view: View,
    hapticsMode: HapticsMode,
    onSmudgeSelected: () -> Unit,
    onWaterSelected: () -> Unit,
    onBlurSelected: () -> Unit,
    onFillSelected: () -> Unit,
    onEyedropperSelected: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFillSettingsRequested: () -> Unit,
): List<ToolSlot> {
    val smudgeActive = selection.kind is ToolKind.Smudge
    val waterActive = selection.kind is ToolKind.Water
    val blurActive = selection.kind is ToolKind.Blur
    val fillActive = selection.kind is ToolKind.Fill
    val eyedropperActive = selection.kind is ToolKind.Eyedropper
    return listOf(
        ToolSlot(
            icon = Icons.Filled.Gesture,
            description = { stringResource(R.string.tool_smudge) },
            state = buttonState(
                if (smudgeActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
            onClick = {
                if (smudgeActive) onSettingsRequested()
                else switch(view, hapticsMode, onSmudgeSelected)
            },
        ),
        ToolSlot(
            icon = Icons.Filled.WaterDrop,
            description = { stringResource(R.string.tool_water) },
            state = buttonState(
                if (waterActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
            onClick = {
                if (waterActive) onSettingsRequested()
                else switch(view, hapticsMode, onWaterSelected)
            },
        ),
        ToolSlot(
            icon = Icons.Filled.BlurOn,
            description = { stringResource(R.string.tool_blur) },
            state = buttonState(
                if (blurActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
            onClick = {
                if (blurActive) onSettingsRequested()
                else switch(view, hapticsMode, onBlurSelected)
            },
        ),
        ToolSlot(
            icon = Icons.Filled.FormatColorFill,
            description = { stringResource(R.string.tool_fill) },
            state = buttonState(
                if (fillActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
            onClick = {
                if (fillActive) onFillSettingsRequested()
                else switch(view, hapticsMode, onFillSelected)
            },
        ),
        ToolSlot(
            icon = Icons.Filled.Colorize,
            description = { stringResource(R.string.tool_eyedropper) },
            state = buttonState(
                if (eyedropperActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
            onClick = {
                if (eyedropperActive) onSettingsRequested()
                else switch(view, hapticsMode, onEyedropperSelected)
            },
        ),
    )
}

private fun switch(view: View, hapticsMode: HapticsMode, action: () -> Unit) {
    view.tick(hapticsMode)
    action()
}

private fun buttonState(
    activation: ButtonActivation,
    selection: ToolSelection,
): ToolButtonState {
    if (activation == ButtonActivation.INACTIVE) return ToolButtonState.Inactive
    if (selection.temporaryReason != null) return ToolButtonState.Temporary
    return ToolButtonState.Active
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    state: ToolButtonState,
    onClick: () -> Unit,
    slot: Dp,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    hapticsMode: HapticsMode = HapticsMode.DISABLED,
    menuItems: List<ToolMenuItem> = emptyList(),
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val click: () -> Unit = {
        if (menuItems.isEmpty()) onClick() else menuExpanded = true
    }
    val active = state != ToolButtonState.Inactive
    val selectedText = stringResource(R.string.cd_selected)
    val shape = MaterialTheme.shapes.medium
    val temporaryColor = MaterialTheme.colorScheme.secondary
    val emphasis = if (active) ToolButtonEmphasis.ACTIVE else ToolButtonEmphasis.INACTIVE
    val buttonColors = railButtonColors(LocalThemeTone.current, emphasis)
    val visual = minOf(TOOL_VISUAL, slot - TOOL_VISUAL_INSET)
    val border = if (active) {
        Modifier.border(ACTIVE_BORDER, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(slot),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = buttonColors.container,
            shape = shape,
            modifier = border.size(visual),
        ) {}
        // combinedClickable keeps tap and long-press mutually exclusive, and
        // its built-in LongPress haptic knows nothing of HapticsMode — the
        // provider silences it for haptics-off users, exactly as TopStrip's
        // swatch does.
        if (onLongClick == null) {
            IconButton(
                onClick = click,
                colors = IconButtonDefaults.iconButtonColors(contentColor = buttonColors.icon),
                modifier = Modifier
                    .size(slot)
                    .semantics {
                        role = Role.Button
                        selected = active
                        if (active) stateDescription = selectedText
                    },
            ) {
                Icon(icon, contentDescription = description, tint = buttonColors.icon)
            }
        } else {
            CompositionLocalProvider(
                LocalHapticFeedback provides if (hapticsMode == HapticsMode.ENABLED) {
                    LocalHapticFeedback.current
                } else {
                    SilentHapticFeedback
                },
                LocalContentColor provides buttonColors.icon,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .combinedClickable(
                            role = Role.Button,
                            onClick = click,
                            onLongClickLabel = longClickLabel,
                            onLongClick = onLongClick,
                        )
                        .semantics {
                            selected = active
                            if (active) stateDescription = selectedText
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = description, tint = buttonColors.icon)
                }
            }
        }
        if (state == ToolButtonState.Temporary) {
            Canvas(Modifier.size(visual)) {
                drawRoundRect(
                    color = temporaryColor,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = Stroke(
                        width = TEMPORARY_BORDER.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()),
                        ),
                    ),
                )
            }
        }
        if (menuItems.isNotEmpty()) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.semantics { selectableGroup() },
            ) {
                for (item in menuItems) {
                    DropdownMenuItem(
                        text = { Text(item.description()) },
                        onClick = {
                            menuExpanded = false
                            item.onClick()
                        },
                        leadingIcon = {
                            Icon(item.icon, contentDescription = null)
                        },
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            selected = item.active
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RailDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(vertical = DIVIDER_MARGIN),
    )
}

private fun View.tick(mode: HapticsMode) {
    if (mode == HapticsMode.DISABLED) return
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

/** Silences combinedClickable's built-in long-press haptic (HapticsMode.DISABLED). */
private object SilentHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

private fun iconFor(glyph: BrushToolGlyph): ImageVector = when (glyph) {
    // One distinct glyph per tool: the pencil must not share Gesture with the
    // smudge tool, nor the airbrush BlurOn with blur — identical glyphs in one
    // rail defeat the glance-recognition the rail exists for.
    BrushToolGlyph.PENCIL -> Icons.Filled.Draw
    BrushToolGlyph.INK_PEN -> Icons.Filled.Create
    BrushToolGlyph.PAINTBRUSH -> Icons.Filled.Brush
    BrushToolGlyph.WATERCOLOR -> WaterToolGlyphs.Watercolor
    BrushToolGlyph.AIRBRUSH -> Icons.Filled.Air
    BrushToolGlyph.SPRAY_CAN -> ToolGlyphs.SprayCan
    BrushToolGlyph.MARKER -> ToolGlyphs.Marker
    BrushToolGlyph.ERASER -> ToolGlyphs.Eraser
    BrushToolGlyph.CUSTOM -> Icons.Filled.Tune
}

private data class ToolSlot(
    val icon: ImageVector,
    val description: @Composable () -> String,
    val state: ToolButtonState,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val longClickLabel: String? = null,
    val hapticsMode: HapticsMode = HapticsMode.DISABLED,
    val menuItems: List<ToolMenuItem> = emptyList(),
)

private data class ToolMenuItem(
    val icon: ImageVector,
    val description: @Composable () -> String,
    val active: Boolean,
    val onClick: () -> Unit,
)

private enum class ToolButtonState {
    Inactive,
    Active,
    Temporary,
}

private enum class ButtonActivation { ACTIVE, INACTIVE }

private val TOOL_VISUAL = 40.dp
private val TOOL_VISUAL_INSET = 8.dp
private val TOOL_GAP = 4.dp
private val RAIL_PADDING = 12.dp
private val RAIL_HORIZONTAL_PADDING = 4.dp
private val DOCK_HEIGHT = 56.dp
private val ACTIVE_BORDER = 2.dp
private val TEMPORARY_BORDER = 2.dp
private val DIVIDER_MARGIN = 4.dp
private val DASH_ON = 6.dp
private val DASH_OFF = 4.dp
private const val RAIL_ALPHA = 0.92f
