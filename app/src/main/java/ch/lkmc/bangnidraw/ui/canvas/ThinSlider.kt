package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
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
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            onValueChangeFinished = onValueChangeFinished,
            modifier = semantics
                .width(length)
                .rotate(-QUARTER_TURN_DEGREES),
        )
    }
}

private val TOUCH_SLAB = 48.dp
private val DEFAULT_LENGTH = 120.dp
private const val QUARTER_TURN_DEGREES = 90f
