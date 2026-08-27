package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.HoverCursorPolicy
import ch.lkmc.bangnidraw.engine.core.HoverRing
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.input.StylusState
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchBlack
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchWhite

/** Size-accurate cursor over the SurfaceView; [revision] is frame-coalesced input. */
@Composable
internal fun HoverCursor(
    stylus: StylusState,
    active: ToolKind,
    eraserPreset: BrushPreset,
    canvasToScreenScale: Float,
    revision: Int,
    modifier: Modifier = Modifier,
) {
    if (!stylus.isHovering || stylus.isDown) return
    val spec = HoverCursorPolicy.resolve(
        pointer = stylus.tool,
        active = active,
        eraserPreset = eraserPreset,
        canvasToScreenScale = canvasToScreenScale,
    ) ?: return

    // The cursor position is a single-writer struct, not snapshot state. The
    // frame key invalidates this draw node after coalesced hover input.
    key(revision) {
        Canvas(modifier = modifier) {
            val center = Offset(stylus.hoverX, stylus.hoverY)
            if (spec.ring == HoverRing.None) {
                drawPipette(center)
                return@Canvas
            }

            val radius = spec.diameterPx / 2f
            val pathEffect = if (spec.ring == HoverRing.Dashed) {
                PathEffect.dashPathEffect(floatArrayOf(DASH_ON_PX, DASH_OFF_PX))
            } else {
                null
            }
            drawCircle(
                color = PaperSwatchBlack,
                radius = radius,
                center = center,
                style = Stroke(width = OUTER_STROKE_PX, pathEffect = pathEffect),
            )
            drawCircle(
                color = PaperSwatchWhite,
                radius = radius,
                center = center,
                style = Stroke(width = INNER_STROKE_PX, pathEffect = pathEffect),
            )
            if (spec.crosshair) drawCrosshair(center)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrosshair(center: Offset) {
    drawLine(
        PaperSwatchBlack,
        Offset(center.x - CROSSHAIR_PX, center.y),
        Offset(center.x + CROSSHAIR_PX, center.y),
        OUTER_STROKE_PX,
    )
    drawLine(
        PaperSwatchBlack,
        Offset(center.x, center.y - CROSSHAIR_PX),
        Offset(center.x, center.y + CROSSHAIR_PX),
        OUTER_STROKE_PX,
    )
    drawLine(
        PaperSwatchWhite,
        Offset(center.x - CROSSHAIR_PX, center.y),
        Offset(center.x + CROSSHAIR_PX, center.y),
        INNER_STROKE_PX,
    )
    drawLine(
        PaperSwatchWhite,
        Offset(center.x, center.y - CROSSHAIR_PX),
        Offset(center.x, center.y + CROSSHAIR_PX),
        INNER_STROKE_PX,
    )
}

/**
 * The eyedropper's mark, centred on the sample point: the picked colour
 * is the one under the glyph's middle. The earlier pipette drew its tip
 * circle offset from the centre, so the colour shown was a neighbour's.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPipette(center: Offset) {
    val arm = PIPETTE_TIP_PX + CROSSHAIR_PX
    drawLine(
        PaperSwatchBlack,
        Offset(center.x - arm, center.y),
        Offset(center.x + arm, center.y),
        OUTER_STROKE_PX,
    )
    drawLine(
        PaperSwatchBlack,
        Offset(center.x, center.y - arm),
        Offset(center.x, center.y + arm),
        OUTER_STROKE_PX,
    )
    drawCircle(
        color = PaperSwatchBlack,
        radius = PIPETTE_TIP_PX,
        center = center,
        style = Stroke(width = OUTER_STROKE_PX),
    )
    drawLine(
        PaperSwatchWhite,
        Offset(center.x - arm, center.y),
        Offset(center.x + arm, center.y),
        INNER_STROKE_PX,
    )
    drawLine(
        PaperSwatchWhite,
        Offset(center.x, center.y - arm),
        Offset(center.x, center.y + arm),
        INNER_STROKE_PX,
    )
    drawCircle(
        color = PaperSwatchWhite,
        radius = PIPETTE_TIP_PX,
        center = center,
        style = Stroke(width = INNER_STROKE_PX),
    )
}

private const val OUTER_STROKE_PX = 3f
private const val INNER_STROKE_PX = 1f
private const val CROSSHAIR_PX = 3f
private const val DASH_ON_PX = 6f
private const val DASH_OFF_PX = 4f
private const val PIPETTE_TIP_PX = 4f
