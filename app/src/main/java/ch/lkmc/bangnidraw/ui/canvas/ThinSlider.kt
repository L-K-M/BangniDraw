package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class SliderAxis {
    Horizontal,
    Vertical,
}

/** A 4 dp Material track inside the plan's 48 dp touch slab. */
@Composable
internal fun ThinSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    axis: SliderAxis,
    description: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    length: Dp = DEFAULT_LENGTH,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val semantics = Modifier.semantics { contentDescription = description }
    if (axis == SliderAxis.Horizontal) {
        ThinMaterialSlider(
            value = value,
            onValueChange = onValueChange,
            range = range,
            onValueChangeFinished = onValueChangeFinished,
            modifier = modifier
                .then(semantics)
                .height(TOUCH_SLAB)
                .width(length),
        )
        return
    }

    Box(
        modifier = modifier
            .width(TOUCH_SLAB)
            .height(length),
        contentAlignment = Alignment.Center,
    ) {
        ThinMaterialSlider(
            value = value,
            onValueChange = onValueChange,
            range = range,
            onValueChangeFinished = onValueChangeFinished,
            modifier = semantics
                // Measure at full length before the 48 dp parent rotates it.
                .requiredWidth(length)
                .rotate(-QUARTER_TURN_DEGREES),
        )
    }
}

/** Avoids Material Expressive's asymmetric thumb and terminal marker. */
@Composable
private fun ThinMaterialSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (() -> Unit)?,
    modifier: Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = INACTIVE_TRACK_ALPHA)
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        modifier = modifier,
        thumb = {
            Canvas(Modifier.size(THUMB_DIAMETER)) {
                drawCircle(activeColor)
            }
        },
        track = { state ->
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(TRACK_THICKNESS),
            ) {
                val y = size.height / 2f
                val start = Offset(0f, y)
                val end = Offset(size.width, y)
                val activeEnd = Offset(size.width * state.coercedValueAsFraction, y)

                drawLine(inactiveColor, start, end, size.height, StrokeCap.Round)
                drawLine(activeColor, start, activeEnd, size.height, StrokeCap.Round)
            }
        },
    )
}

private val TOUCH_SLAB = 48.dp
private val DEFAULT_LENGTH = 120.dp
private val TRACK_THICKNESS = 4.dp
private val THUMB_DIAMETER = 20.dp
private const val QUARTER_TURN_DEGREES = 90f
private const val INACTIVE_TRACK_ALPHA = 0.38f
