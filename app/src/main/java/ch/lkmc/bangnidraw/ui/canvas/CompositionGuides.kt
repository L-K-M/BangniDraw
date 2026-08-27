package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.CompositionGuide
import ch.lkmc.bangnidraw.engine.core.CompositionGuideVisibility
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchBlack
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchWhite

/** Draws rule-of-thirds and centre guides through the paper's transform. */
@Composable
internal fun CompositionGuides(
    visibility: CompositionGuideVisibility,
    canvas: CanvasSize,
    screenTransform: ScreenTransform?,
    modifier: Modifier = Modifier,
) {
    if (visibility == CompositionGuideVisibility.HIDDEN) return
    val transform = screenTransform ?: return
    val thirds = remember(canvas) { CompositionGuide.thirds(canvas) }
    val center = remember(canvas) { CompositionGuide.center(canvas) }

    Canvas(modifier) {
        for (segment in thirds) {
            val from = Offset(
                transform.screenX(segment.x0, segment.y0),
                transform.screenY(segment.x0, segment.y0),
            )
            val to = Offset(
                transform.screenX(segment.x1, segment.y1),
                transform.screenY(segment.x1, segment.y1),
            )
            drawGuideLine(from, to)
        }

        // Convert the fixed screen-size tick to canvas px, then transform it
        // so the cross rotates with the paper.
        val tick = CENTER_TICK.toPx() * transform.canvasPerScreen
        drawGuideLine(
            transform.offset(center.x - tick, center.y),
            transform.offset(center.x + tick, center.y),
        )
        drawGuideLine(
            transform.offset(center.x, center.y - tick),
            transform.offset(center.x, center.y + tick),
        )
    }
}

private fun ScreenTransform.offset(x: Float, y: Float): Offset =
    Offset(screenX(x, y), screenY(x, y))

/** Keylined like the hover cursor: dark wide under light thin, legible on any paint. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGuideLine(from: Offset, to: Offset) {
    drawLine(
        PaperSwatchBlack.copy(alpha = GUIDE_ALPHA),
        from,
        to,
        strokeWidth = OUTER_STROKE.toPx(),
    )
    drawLine(
        PaperSwatchWhite.copy(alpha = GUIDE_ALPHA),
        from,
        to,
        strokeWidth = INNER_STROKE.toPx(),
    )
}

private val OUTER_STROKE = 2.dp
private val INNER_STROKE = 1.dp
private val CENTER_TICK = 12.dp
private const val GUIDE_ALPHA = 0.55f
