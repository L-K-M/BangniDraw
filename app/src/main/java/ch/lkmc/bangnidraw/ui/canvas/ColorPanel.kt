package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.HsvColor
import ch.lkmc.bangnidraw.engine.core.HsvWheel
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.ui.theme.DrawingSwatches
import kotlin.math.cos
import kotlin.math.sin

/** HSV picker, fixed swatches, and the active mixer's nine-step dish. */
@Composable
internal fun ColorPanel(
    color: Int,
    pigmentActive: Boolean,
    pigmentAvailable: Boolean,
    mix: (Int, Int) -> IntArray,
    onColorChanged: (Int) -> Unit,
    onMixerChanged: (MixerChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var hsv by remember(color) { mutableStateOf(HsvColor.fromArgb(color)) }
    var wellA by remember { mutableStateOf(color) }
    var wellB by remember { mutableStateOf(DrawingSwatches[3].toArgb()) }
    val gradient = mix(wellA, wellB)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.color_panel), style = MaterialTheme.typography.headlineSmall)
            HsvWheelPicker(hsv) { next ->
                hsv = next
                onColorChanged(next.toArgb())
            }
            Text(stringResource(R.string.color_value))
            Slider(
                value = hsv.v,
                onValueChange = {
                    hsv = hsv.copy(v = it)
                    onColorChanged(hsv.toArgb())
                },
            )

            SwatchRow(DrawingSwatches.map { it.toArgb() }) {
                hsv = HsvColor.fromArgb(it)
                onColorChanged(it)
            }

            Text(stringResource(R.string.mixing_dish), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = pigmentActive,
                    enabled = pigmentAvailable,
                    onClick = { onMixerChanged(MixerChoice.PIGMENT) },
                    label = { Text(stringResource(R.string.mixing_pigment)) },
                )
                FilterChip(
                    selected = !pigmentActive,
                    onClick = { onMixerChanged(MixerChoice.RGB) },
                    label = { Text(stringResource(R.string.mixing_rgb)) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { wellA = color }) {
                    Text(stringResource(R.string.mixing_well_a))
                }
                Button(onClick = { wellB = color }) {
                    Text(stringResource(R.string.mixing_well_b))
                }
            }
            SwatchRow(gradient.toList(), onColorChanged)
        }
    }
}

@Composable
private fun HsvWheelPicker(hsv: HsvColor, onChanged: (HsvColor) -> Unit) {
    val markerColor = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .size(WHEEL_SIZE)
            .pointerInput(hsv.v) {
                detectTapGestures { position ->
                    onChanged(
                        HsvWheel.select(
                            position.x,
                            position.y,
                            size.width.toFloat(),
                            size.height.toFloat(),
                            hsv.v,
                        ),
                    )
                }
            }
            .pointerInput(hsv.v) {
                val update: (Offset) -> Unit = { position ->
                    onChanged(
                        HsvWheel.select(
                            position.x,
                            position.y,
                            size.width.toFloat(),
                            size.height.toFloat(),
                            hsv.v,
                        ),
                    )
                }
                detectDragGestures(onDragStart = update) { change, _ -> update(change.position) }
            },
    ) {
        drawCircle(
            Brush.sweepGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
            ),
        )
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent)))
        val radians = Math.toRadians(hsv.h.toDouble())
        val wheelRadius = size.minDimension / 2f
        val marker = Offset(
            center.x + cos(radians).toFloat() * wheelRadius * hsv.s,
            center.y + sin(radians).toFloat() * wheelRadius * hsv.s,
        )
        drawCircle(markerColor, MARKER_RADIUS.toPx(), marker, style = Stroke(MARKER_STROKE.toPx()))
    }
}

@Composable
private fun SwatchRow(colors: List<Int>, onSelected: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (argb in colors) {
            val outline = MaterialTheme.colorScheme.outline
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE)
                    .clickable { onSelected(argb) },
            ) {
                Canvas(Modifier.size(SWATCH_SIZE)) {
                    drawCircle(Color(argb))
                    drawCircle(
                        outline,
                        style = Stroke(SWATCH_BORDER.toPx()),
                    )
                }
            }
        }
    }
}

private val WHEEL_SIZE = 220.dp
private val SWATCH_SIZE = 34.dp
private val MARKER_RADIUS = 7.dp
private val MARKER_STROKE = 2.dp
private val SWATCH_BORDER = 1.dp
