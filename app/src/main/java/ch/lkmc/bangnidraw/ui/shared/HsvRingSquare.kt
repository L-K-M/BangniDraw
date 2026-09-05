package ch.lkmc.bangnidraw.ui.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.HsvColor
import ch.lkmc.bangnidraw.engine.core.HsvPicker
import ch.lkmc.bangnidraw.engine.core.HueMilestone
import kotlin.math.cos
import kotlin.math.sin

/**
 * The hue ring around an independent saturation/value square — the colour
 * panel's primary control on both products.
 *
 * It lives here, beside the composition guides and the hover cursor, for the
 * reason those do: its geometry is what must not drift. The ring's inner
 * radius and the square's half-edge are [HsvPicker]'s, and the pointer math
 * that reads them is the same object; two transcriptions of that pair would
 * be two chances for a tap near the ring's edge to mean different things on
 * a phone and on a laptop.
 *
 * Haptics stay with the caller: [onHueMilestone] fires when a gesture crosses
 * one of [HueMilestone]'s stops, and only Android has anything to do with it.
 */
@Composable
internal fun HsvRingSquare(
    hsv: HsvColor,
    onPreview: (HsvColor) -> Unit,
    onCommit: (HsvColor) -> Unit,
    modifier: Modifier = Modifier,
    onHueMilestone: () -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        HsvRingSquareSized(
            hsv = hsv,
            // Sized to its panel, capped: the fixed 220 dp left tablets'
            // 320 dp floating panel half empty, and a bigger ring is an
            // easier target either way.
            pickerSize = pickerSizeFor(maxWidth, maxHeight),
            onPreview = onPreview,
            onCommit = onCommit,
            onHueMilestone = onHueMilestone,
        )
    }
}

@Composable
private fun HsvRingSquareSized(
    hsv: HsvColor,
    pickerSize: Dp,
    onPreview: (HsvColor) -> Unit,
    onCommit: (HsvColor) -> Unit,
    onHueMilestone: () -> Unit,
) {
    val markerColor = MaterialTheme.colorScheme.onSurface
    val latestHsv = rememberUpdatedState(hsv)
    val latestPreview = rememberUpdatedState(onPreview)
    val latestCommit = rememberUpdatedState(onCommit)
    val latestMilestone = rememberUpdatedState(onHueMilestone)
    // The brushes are remembered: a picker drag redraws per input frame, and
    // a fresh Brush per draw is a fresh native Shader per draw — ShaderBrush
    // caches by size, so a remembered instance pays one shader at worst. The
    // endpoints must be explicit: the shader is not translated to the rect.
    val pickerPx = with(LocalDensity.current) { pickerSize.toPx() }
    val pickerCenter = Offset(pickerPx / 2f, pickerPx / 2f)
    val squareHalf = pickerPx * HsvPicker.SQUARE_HALF_EDGE
    val squareLeft = pickerPx / 2f - squareHalf
    val hueColor = Color(HsvColor(hsv.h, 1f, 1f).toArgb())
    val hueRingBrush = remember { Brush.sweepGradient(HUE_COLORS) }
    val saturationBrush = remember(hueColor, pickerPx) {
        Brush.horizontalGradient(
            listOf(Color.White, hueColor),
            squareLeft,
            squareLeft + squareHalf * 2f,
        )
    }
    val valueBrush = remember(pickerPx) {
        Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black),
            squareLeft,
            squareLeft + squareHalf * 2f,
        )
    }
    Canvas(
        modifier = Modifier
            .size(pickerSize)
            .pointerInput(pickerSize) {
                detectTapGestures { position ->
                    val next = HsvPicker.select(
                        position.x,
                        position.y,
                        minOf(size.width, size.height).toFloat(),
                        latestHsv.value,
                    )
                    if (HueMilestone.crossed(latestHsv.value.h, next.h)) {
                        latestMilestone.value()
                    }
                    latestPreview.value(next)
                    latestCommit.value(next)
                }
            }
            .pointerInput(pickerSize) {
                var gestureHsv = latestHsv.value
                val update: (Offset) -> Unit = { position ->
                    val next = HsvPicker.select(
                        position.x,
                        position.y,
                        minOf(size.width, size.height).toFloat(),
                        gestureHsv,
                    )
                    if (HueMilestone.crossed(gestureHsv.h, next.h)) {
                        latestMilestone.value()
                    }
                    gestureHsv = next
                    latestPreview.value(next)
                }
                detectDragGestures(
                    onDragStart = {
                        gestureHsv = latestHsv.value
                        update(it)
                    },
                    onDragEnd = { latestCommit.value(gestureHsv) },
                ) { change, _ -> update(change.position) }
            },
    ) {
        val ringWidth = pickerPx * RING_WIDTH_FRACTION
        drawCircle(
            brush = hueRingBrush,
            radius = pickerPx / 2f - ringWidth / 2f,
            center = pickerCenter,
            style = Stroke(ringWidth),
        )

        val topLeft = Offset(squareLeft, squareLeft)
        val squareSize = Size(squareHalf * 2f, squareHalf * 2f)
        drawRect(
            brush = saturationBrush,
            topLeft = topLeft,
            size = squareSize,
        )
        drawRect(
            brush = valueBrush,
            topLeft = topLeft,
            size = squareSize,
        )

        val radians = Math.toRadians(hsv.h.toDouble())
        val ringRadius = pickerPx * RING_MARKER_RADIUS
        val hueMarker = Offset(
            pickerCenter.x + cos(radians).toFloat() * ringRadius,
            pickerCenter.y + sin(radians).toFloat() * ringRadius,
        )
        val svMarker = Offset(
            topLeft.x + hsv.s * squareSize.width,
            topLeft.y + (1f - hsv.v) * squareSize.height,
        )
        drawCircle(markerColor, MARKER_RADIUS.toPx(), hueMarker, style = Stroke(MARKER_STROKE.toPx()))
        drawCircle(markerColor, MARKER_RADIUS.toPx(), svMarker, style = Stroke(MARKER_STROKE.toPx()))
    }
}

/**
 * One palette swatch or colour chip. Shared for the same reason the picker
 * is: the selected ring is how both panels say which colour is live, and a
 * second copy of it would be a second border width to keep in step.
 */
@Composable
internal fun ColorCircle(argb: Int, modifier: Modifier, selected: Boolean = false) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        drawCircle(Color(argb))
        drawCircle(outline, style = Stroke(if (selected) SELECTED_BORDER.toPx() else SWATCH_BORDER.toPx()))
    }
}

/** The hue ring's size: its panel's smaller dimension, capped. */
internal fun pickerSizeFor(maxWidth: Dp, maxHeight: Dp): Dp =
    minOf(maxWidth, maxHeight).coerceAtMost(PICKER_MAX)

private val HUE_COLORS = listOf(
    Color.Red,
    Color.Yellow,
    Color.Green,
    Color.Cyan,
    Color.Blue,
    Color.Magenta,
    Color.Red,
)
private val PICKER_MAX = 280.dp
private val MARKER_RADIUS = 7.dp
private val MARKER_STROKE = 2.dp
private val SWATCH_BORDER = 1.dp
private val SELECTED_BORDER = 3.dp
private const val RING_WIDTH_FRACTION = 0.12f
private const val RING_MARKER_RADIUS = 0.44f
