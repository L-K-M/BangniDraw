package ch.lkmc.bangnidraw.ui.canvas

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.BrushSizeScale
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.OpacityMilestone
import ch.lkmc.bangnidraw.engine.core.RailMode
import ch.lkmc.bangnidraw.engine.core.ToolKind
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
    onBlurSelected: () -> Unit,
    onFillSelected: () -> Unit,
    onEyedropperSelected: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFillSettingsRequested: () -> Unit,
    onSizeChanged: (Float) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onTuningFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val paints = BrushPresets.railOrder(presets).filterNot(BrushPreset::eraseMode)
    val activeBrush = (selection.kind as? ToolKind.Brush)?.preset
    val currentPaint = paints.firstOrNull { it.id == paintBrushId } ?: paints.firstOrNull()
    val eraser = presets.firstOrNull { it.id == eraserBrushId && it.eraseMode }
        ?: presets.firstOrNull { it.eraseMode }

    val slots = if (layout.railMode == RailMode.FULL) {
        fullSlots(
            paints = paints,
            eraser = eraser,
            selection = selection,
            view = view,
            hapticsMode = hapticsMode,
            onBrushSelected = onBrushSelected,
            onSmudgeSelected = onSmudgeSelected,
            onBlurSelected = onBlurSelected,
            onFillSelected = onFillSelected,
            onEyedropperSelected = onEyedropperSelected,
            onSettingsRequested = onSettingsRequested,
            onFillSettingsRequested = onFillSettingsRequested,
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
            onBlurSelected = onBlurSelected,
            onFillSelected = onFillSelected,
            onEyedropperSelected = onEyedropperSelected,
            onSettingsRequested = onSettingsRequested,
            onFillSettingsRequested = onFillSettingsRequested,
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
                dividersAfter = when (layout.railMode) {
                    RailMode.FULL -> setOf(FULL_PAINT_LAST_INDEX, FULL_ERASER_INDEX)
                    RailMode.GROUPED -> setOf(GROUPED_ERASER_INDEX)
                    RailMode.SHORT, RailMode.DOCK -> emptySet()
                },
                gap = if (layout.railMode == RailMode.SHORT) 0.dp else TOOL_GAP,
            )

            if (activeBrush != null && layout.sliderLengthDp > 0) {
                BrushSliders(
                    preset = activeBrush,
                    length = layout.sliderLengthDp.dp,
                    view = view,
                    hapticsMode = hapticsMode,
                    onSizeChanged = onSizeChanged,
                    onOpacityChanged = onOpacityChanged,
                    onTuningFinished = onTuningFinished,
                )
            }
        }
    }
}

@Composable
private fun ToolColumn(
    slots: List<ToolSlot>,
    slot: Dp,
    dividersAfter: Set<Int>,
    gap: Dp,
) {
    for ((index, item) in slots.withIndex()) {
        ToolButton(item.icon, item.description(), item.state, item.onClick, slot)
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
                ToolButton(item.icon, item.description(), item.state, item.onClick, slot)
            }
        }
    }
}

@Composable
private fun BrushSliders(
    preset: BrushPreset,
    length: Dp,
    view: View,
    hapticsMode: HapticsMode,
    onSizeChanged: (Float) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onTuningFinished: () -> Unit,
) {
    var previousOpacity by remember(preset.id) { mutableFloatStateOf(preset.opacity) }
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
            value = preset.opacity,
            range = 0f..1f,
            axis = SliderAxis.Vertical,
            description = stringResource(R.string.brush_opacity),
            onValueChange = { value ->
                if (OpacityMilestone.crossed(previousOpacity, value).isNotEmpty()) {
                    view.tick(hapticsMode)
                }
                previousOpacity = value
                onOpacityChanged(value)
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
    onBlurSelected: () -> Unit,
    onFillSelected: () -> Unit,
    onEyedropperSelected: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFillSettingsRequested: () -> Unit,
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
        )
    }
    result +=
        secondarySlots(
            selection,
            view,
            hapticsMode,
            onSmudgeSelected,
            onBlurSelected,
            onFillSelected,
            onEyedropperSelected,
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
    onBlurSelected: () -> Unit,
    onFillSelected: () -> Unit,
    onEyedropperSelected: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFillSettingsRequested: () -> Unit,
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
        )
    }
    result +=
        secondarySlots(
            selection,
            view,
            hapticsMode,
            onSmudgeSelected,
            onBlurSelected,
            onFillSelected,
            onEyedropperSelected,
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
): ToolSlot {
    val active = (selection.kind as? ToolKind.Brush)?.preset?.id == preset.id
    return ToolSlot(
        icon = if (preset.eraseMode) Icons.Filled.DeleteSweep else iconFor(preset.id),
        description = {
            if (preset.eraseMode) stringResource(R.string.tool_eraser) else brushPresetName(preset)
        },
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
    )
}

@Composable
private fun secondarySlots(
    selection: ToolSelection,
    view: View,
    hapticsMode: HapticsMode,
    onSmudgeSelected: () -> Unit,
    onBlurSelected: () -> Unit,
    onFillSelected: () -> Unit,
    onEyedropperSelected: () -> Unit,
    onFillSettingsRequested: () -> Unit,
): List<ToolSlot> {
    val smudgeActive = selection.kind is ToolKind.Smudge
    val blurActive = selection.kind is ToolKind.Blur
    val fillActive = selection.kind is ToolKind.Fill
    val eyedropperActive = selection.kind is ToolKind.Eyedropper
    return listOf(
        ToolSlot(
            Icons.Filled.Gesture,
            { stringResource(R.string.tool_smudge) },
            buttonState(
                if (smudgeActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
        ) { if (!smudgeActive) switch(view, hapticsMode, onSmudgeSelected) },
        ToolSlot(
            Icons.Filled.BlurOn,
            { stringResource(R.string.tool_blur) },
            buttonState(
                if (blurActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
        ) { if (!blurActive) switch(view, hapticsMode, onBlurSelected) },
        ToolSlot(
            Icons.Filled.FormatColorFill,
            { stringResource(R.string.tool_fill) },
            buttonState(
                if (fillActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
        ) {
            if (fillActive) onFillSettingsRequested()
            else switch(view, hapticsMode, onFillSelected)
        },
        ToolSlot(
            Icons.Filled.Colorize,
            { stringResource(R.string.tool_eyedropper) },
            buttonState(
                if (eyedropperActive) ButtonActivation.ACTIVE else ButtonActivation.INACTIVE,
                selection,
            ),
        ) { if (!eyedropperActive) switch(view, hapticsMode, onEyedropperSelected) },
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
) {
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
    Box(modifier = Modifier.size(slot), contentAlignment = Alignment.Center) {
        Surface(
            color = buttonColors.container,
            shape = shape,
            modifier = border.size(visual),
        ) {}
        IconButton(
            onClick = onClick,
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

private fun iconFor(id: String): ImageVector = when (id) {
    BrushPresets.PENCIL_ID -> Icons.Filled.Gesture
    BrushPresets.INK_PEN_ID -> Icons.Filled.Create
    BrushPresets.PAINTBRUSH_ID -> Icons.Filled.Brush
    BrushPresets.AIRBRUSH_ID -> Icons.Filled.BlurOn
    BrushPresets.MARKER_ID -> Icons.Filled.Edit
    else -> Icons.Filled.Brush
}

private data class ToolSlot(
    val icon: ImageVector,
    val description: @Composable () -> String,
    val state: ToolButtonState,
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
private const val FULL_PAINT_LAST_INDEX = 4
private const val FULL_ERASER_INDEX = 5
private const val GROUPED_ERASER_INDEX = 1
private const val RAIL_ALPHA = 0.92f
