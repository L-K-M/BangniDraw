package ch.lkmc.bangnidraw.ui.canvas

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.BrushSizeScale
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ToolSelection

/** Step-5 rail; adaptive dock/rail placement replaces its container in step 8. */
@Composable
internal fun ToolRail(
    presets: List<BrushPreset>,
    selection: ToolSelection,
    brushColor: Int,
    onBrushSelected: (String) -> Unit,
    onSmudgeSelected: () -> Unit,
    onBlurSelected: () -> Unit,
    onFillSelected: () -> Unit,
    onEyedropperSelected: () -> Unit,
    onColorRequested: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFillSettingsRequested: () -> Unit,
    onSizeChanged: (Float) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onTuningFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val colorDescription = stringResource(R.string.color_panel)
    val activeBrush = (selection.kind as? ToolKind.Brush)?.preset
    val paints = BrushPresets.railOrder(presets).filterNot { it.eraseMode }
    val eraser = if (activeBrush?.eraseMode == true) {
        activeBrush
    } else {
        presets.firstOrNull { it.id == BrushPresets.HARD_ERASER_ID }
            ?: presets.firstOrNull { it.eraseMode }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = RAIL_ALPHA),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TOOL_GAP),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(RAIL_PADDING),
        ) {
            for (preset in paints) {
                val active = activeBrush?.id == preset.id
                ToolButton(
                    icon = iconFor(preset.id),
                    description = brushPresetName(preset),
                    state = when {
                        !active -> ToolButtonState.Inactive
                        selection.temporaryReason != null -> ToolButtonState.Temporary
                        else -> ToolButtonState.Active
                    },
                    onClick = {
                        if (active) {
                            onSettingsRequested()
                            return@ToolButton
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onBrushSelected(preset.id)
                    },
                )
            }

            if (eraser != null) {
                val active = activeBrush?.eraseMode == true
                ToolButton(
                    icon = Icons.Filled.DeleteSweep,
                    description = stringResource(R.string.tool_eraser),
                    state = when {
                        !active -> ToolButtonState.Inactive
                        selection.temporaryReason != null -> ToolButtonState.Temporary
                        else -> ToolButtonState.Active
                    },
                    onClick = {
                        if (active) {
                            onSettingsRequested()
                            return@ToolButton
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onBrushSelected(eraser.id)
                    },
                )
            }

            ToolButton(
                icon = Icons.Filled.Gesture,
                description = stringResource(R.string.tool_smudge),
                state = if (selection.kind is ToolKind.Smudge) {
                    ToolButtonState.Active
                } else {
                    ToolButtonState.Inactive
                },
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSmudgeSelected()
                },
            )
            ToolButton(
                icon = Icons.Filled.BlurOn,
                description = stringResource(R.string.tool_blur),
                state = if (selection.kind is ToolKind.Blur) {
                    ToolButtonState.Active
                } else {
                    ToolButtonState.Inactive
                },
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onBlurSelected()
                },
            )
            val fillActive = selection.kind is ToolKind.Fill
            ToolButton(
                icon = Icons.Filled.FormatColorFill,
                description = stringResource(R.string.tool_fill),
                state = if (fillActive) ToolButtonState.Active else ToolButtonState.Inactive,
                onClick = {
                    if (fillActive) {
                        onFillSettingsRequested()
                        return@ToolButton
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onFillSelected()
                },
            )

            val eyedropperActive = selection.kind is ToolKind.Eyedropper
            ToolButton(
                icon = Icons.Filled.Colorize,
                description = stringResource(R.string.tool_eyedropper),
                state = if (eyedropperActive) ToolButtonState.Temporary else ToolButtonState.Inactive,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onEyedropperSelected()
                },
            )

            IconButton(
                onClick = onColorRequested,
                modifier = Modifier
                    .size(TOOL_SLOT)
                    .semantics { contentDescription = colorDescription },
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = COLOR_GAP)
                        .size(COLOR_DOT)
                        .clip(RoundedCornerShape(COLOR_DOT))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(COLOR_DOT)),
                ) {
                    Canvas(Modifier.size(COLOR_DOT)) { drawCircle(Color(brushColor)) }
                }
            }

            if (activeBrush != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(SLIDER_GAP)) {
                    ThinSlider(
                        value = BrushSizeScale.fraction(
                            activeBrush.size,
                            activeBrush.sizeMin,
                            activeBrush.sizeMax,
                        ),
                        range = 0f..1f,
                        axis = SliderAxis.Vertical,
                        description = stringResource(R.string.brush_size),
                        onValueChange = {
                            onSizeChanged(
                                BrushSizeScale.size(
                                    it,
                                    activeBrush.sizeMin,
                                    activeBrush.sizeMax,
                                ),
                            )
                        },
                        onValueChangeFinished = onTuningFinished,
                    )
                    ThinSlider(
                        value = activeBrush.opacity,
                        range = 0f..1f,
                        axis = SliderAxis.Vertical,
                        description = stringResource(R.string.brush_opacity),
                        onValueChange = onOpacityChanged,
                        onValueChangeFinished = onTuningFinished,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    state: ToolButtonState,
    onClick: () -> Unit,
) {
    val active = state != ToolButtonState.Inactive
    val selectedText = stringResource(R.string.cd_selected)
    val shape = MaterialTheme.shapes.medium
    val border = if (active) Modifier.border(ACTIVE_BORDER, MaterialTheme.colorScheme.primary, shape)
    else Modifier
    Box(modifier = Modifier.size(TOOL_SLOT), contentAlignment = Alignment.Center) {
        Surface(
            color = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = shape,
            modifier = border.size(TOOL_VISUAL),
        ) {}
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(TOOL_SLOT)
                .semantics {
                    role = Role.Button
                    selected = active
                    if (active) stateDescription = selectedText
                },
        ) {
            Icon(icon, contentDescription = description)
        }
        if (state == ToolButtonState.Temporary) {
            val color = MaterialTheme.colorScheme.secondary
            Canvas(Modifier.size(TOOL_SLOT)) {
                drawRoundRect(
                    color = color,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = Stroke(
                        width = TEMPORARY_BORDER.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
        }
    }
}

private enum class ToolButtonState {
    Inactive,
    Active,
    Temporary,
}

private fun iconFor(id: String): ImageVector = when (id) {
    BrushPresets.PENCIL_ID -> Icons.Filled.Gesture
    BrushPresets.INK_PEN_ID -> Icons.Filled.Create
    BrushPresets.PAINTBRUSH_ID -> Icons.Filled.Brush
    BrushPresets.AIRBRUSH_ID -> Icons.Filled.BlurOn
    BrushPresets.MARKER_ID -> Icons.Filled.Edit
    else -> Icons.Filled.Brush
}

private val TOOL_SLOT = 48.dp
private val TOOL_VISUAL = 40.dp
private val TOOL_GAP = 4.dp
private val RAIL_PADDING = 4.dp
private val SLIDER_GAP = 0.dp
private val COLOR_GAP = 2.dp
private val COLOR_DOT = 20.dp
private val ACTIVE_BORDER = 2.dp
private val TEMPORARY_BORDER = 2.dp
private const val RAIL_ALPHA = 0.92f
