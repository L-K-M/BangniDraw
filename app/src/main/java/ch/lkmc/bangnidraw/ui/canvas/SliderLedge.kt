package ch.lkmc.bangnidraw.ui.canvas

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

/** Horizontal size/opacity controls used when the rail cannot hold sliders. */
@Composable
internal fun SliderLedge(
    layout: LayoutSpec,
    preset: BrushPreset,
    hapticsMode: HapticsMode,
    onSizeChanged: (Float) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onTuningFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (layout.railMode != RailMode.SHORT && layout.railMode != RailMode.DOCK) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = LEDGE_ALPHA),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.height(LEDGE_HEIGHT),
    ) {
        if (layout.railMode == RailMode.DOCK) {
            CompactLedge(
                layout,
                preset,
                hapticsMode,
                onSizeChanged,
                onOpacityChanged,
                onTuningFinished,
            )
            return@Surface
        }

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
                OpacitySlider(
                    layout,
                    preset,
                    hapticsMode,
                    maxWidth,
                    onOpacityChanged,
                    onTuningFinished,
                )
            }
        }
    }
}

@Composable
private fun CompactLedge(
    layout: LayoutSpec,
    preset: BrushPreset,
    hapticsMode: HapticsMode,
    onSizeChanged: (Float) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onTuningFinished: () -> Unit,
) {
    var active by rememberSaveable { mutableStateOf(LedgeControl.SIZE) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LEDGE_PADDING),
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            if (active == LedgeControl.SIZE) {
                SizeSlider(layout, preset, maxWidth, onSizeChanged, onTuningFinished)
            } else {
                OpacitySlider(
                    layout,
                    preset,
                    hapticsMode,
                    maxWidth,
                    onOpacityChanged,
                    onTuningFinished,
                )
            }
        }
        TextButton(
            onClick = {
                active = if (active == LedgeControl.SIZE) LedgeControl.OPACITY else LedgeControl.SIZE
            },
            modifier = Modifier.widthIn(min = TOGGLE_MIN_WIDTH),
        ) {
            Text(
                stringResource(
                    if (active == LedgeControl.SIZE) R.string.brush_size
                    else R.string.brush_opacity,
                ),
                maxLines = 1,
            )
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
private fun OpacitySlider(
    layout: LayoutSpec,
    preset: BrushPreset,
    hapticsMode: HapticsMode,
    length: androidx.compose.ui.unit.Dp,
    onChanged: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val view = LocalView.current
    var previousOpacity by remember(preset.id) { mutableFloatStateOf(preset.opacity) }
    ThinSlider(
        value = preset.opacity,
        range = 0f..1f,
        axis = SliderAxis.Horizontal,
        description = stringResource(R.string.brush_opacity),
        onValueChange = { value ->
            if (
                hapticsMode == HapticsMode.ENABLED &&
                OpacityMilestone.crossed(previousOpacity, value).isNotEmpty()
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            previousOpacity = value
            onChanged(value)
        },
        onValueChangeFinished = onFinished,
        length = length,
        modifier = mirrored(layout.railSide),
    )
}

private fun mirrored(hand: Hand): Modifier {
    if (hand == Hand.RIGHT) return Modifier
    return Modifier.graphicsLayer { scaleX = -1f }
}

private enum class LedgeControl { SIZE, OPACITY }

private val LEDGE_HEIGHT = 48.dp
private val LEDGE_PADDING = 8.dp
private val TOGGLE_MIN_WIDTH = 88.dp
private const val LEDGE_ALPHA = 0.94f
