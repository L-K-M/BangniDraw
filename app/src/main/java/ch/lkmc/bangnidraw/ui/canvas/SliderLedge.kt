package ch.lkmc.bangnidraw.ui.canvas

import android.view.HapticFeedbackConstants
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushSizeScale
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.RailMode
import ch.lkmc.bangnidraw.engine.core.OpacityMilestone
import ch.lkmc.bangnidraw.engine.core.ToolSliderSecondary

/** Horizontal size/secondary controls used when the rail cannot hold sliders. */
@Composable
internal fun SliderLedge(
    layout: LayoutSpec,
    preset: BrushPreset,
    secondary: ToolSliderSecondary,
    secondaryValue: Float,
    hapticsMode: HapticsMode,
    onSizeChanged: (Float) -> Unit,
    onSecondaryChanged: (Float) -> Unit,
    onTuningFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (layout.railMode != RailMode.SHORT && layout.railMode != RailMode.DOCK) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = LEDGE_ALPHA),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.height(LEDGE_HEIGHT),
    ) {
        // SHORT and DOCK both lay the size and secondary sliders side by side:
        // on a sideways phone (DOCK) the user must still see brush size and
        // opacity/flow/water together, not pick one behind a tab.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LEDGE_PADDING),
        ) {
            BoxWithConstraints(Modifier.weight(1f)) {
                SizeSlider(
                    layout,
                    preset,
                    maxWidth,
                    onSizeChanged,
                    onTuningFinished,
                )
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                SecondarySlider(
                    layout,
                    preset,
                    secondary,
                    secondaryValue,
                    hapticsMode,
                    maxWidth,
                    onSecondaryChanged,
                    onTuningFinished,
                )
            }
        }
    }
}

@Composable
private fun SizeSlider(
    layout: LayoutSpec,
    preset: BrushPreset,
    length: androidx.compose.ui.unit.Dp,
    onChanged: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    ThinSlider(
        value = BrushSizeScale.fraction(preset.size, preset.sizeMin, preset.sizeMax),
        range = 0f..1f,
        axis = SliderAxis.Horizontal,
        description = stringResource(R.string.brush_size),
        onValueChange = {
            onChanged(BrushSizeScale.size(it, preset.sizeMin, preset.sizeMax))
        },
        onValueChangeFinished = onFinished,
        length = length,
        modifier = mirrored(layout.railSide),
    )
}

@Composable
private fun SecondarySlider(
    layout: LayoutSpec,
    preset: BrushPreset,
    secondary: ToolSliderSecondary,
    secondaryValue: Float,
    hapticsMode: HapticsMode,
    length: androidx.compose.ui.unit.Dp,
    onChanged: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val view = LocalView.current
    var previousSecondary by remember(preset.id, secondary) {
        mutableFloatStateOf(secondaryValue)
    }
    ThinSlider(
        value = secondaryValue,
        range = 0f..1f,
        axis = SliderAxis.Horizontal,
        description = stringResource(secondaryLabel(secondary)),
        onValueChange = { value ->
            if (
                hapticsMode == HapticsMode.ENABLED &&
                OpacityMilestone.crossed(previousSecondary, value).isNotEmpty()
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            previousSecondary = value
            onChanged(value)
        },
        onValueChangeFinished = onFinished,
        length = length,
        modifier = mirrored(layout.railSide),
    )
}

@StringRes
private fun secondaryLabel(secondary: ToolSliderSecondary): Int = when (secondary) {
    ToolSliderSecondary.OPACITY -> R.string.brush_opacity
    ToolSliderSecondary.FLOW -> R.string.brush_flow
    ToolSliderSecondary.WATER -> R.string.water_amount
}

private fun mirrored(hand: Hand): Modifier {
    if (hand == Hand.RIGHT) return Modifier
    return Modifier.graphicsLayer { scaleX = -1f }
}

private val LEDGE_HEIGHT = 48.dp
private val LEDGE_PADDING = 8.dp
private const val LEDGE_ALPHA = 0.94f
