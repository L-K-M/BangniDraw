package ch.lkmc.bangnidraw.ui.shared

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.HoverCursorPolicy
import ch.lkmc.bangnidraw.engine.core.HoverRing
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.input.StylusState

/** Size-accurate cursor over the SurfaceView; [revision] is frame-coalesced input. */
@Composable
internal fun HoverCursor(
    stylus: StylusState,
    active: ToolKind,
    eraserPreset: BrushPreset,
    brushColor: Int,
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
            // The ink ring: what the pen is about to lay down, inside the
            // size ring. Erasers skip it — there is no colour to show — and
            // so does a cursor too small to hold it.
            if (spec.ink && radius > INK_INSET_PX + INK_STROKE_PX) {
                drawCircle(
                    // Opaque on purpose: the ring is a colour hint, not the
                    // stroke itself, and a translucent hint can vanish.
                    color = Color(brushColor).copy(alpha = 1f),
                    radius = radius - INK_INSET_PX,
                    center = center,
                    style = Stroke(width = INK_STROKE_PX),
                )
            }
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPipette(center: Offset) {
    // A centered ring: the sample point is exactly the cursor position, and
    // the tip of a diagonal pipette glyph would sit 8 px off it. No
    // crosshair inside — its strokes would cover the very pixel being
    // sampled; the ring's hole keeps it visible.
    drawCircle(
        color = PaperSwatchBlack,
        radius = PIPETTE_RING_PX,
        center = center,
        style = Stroke(width = OUTER_STROKE_PX),
    )
    drawCircle(
        color = PaperSwatchWhite,
        radius = PIPETTE_RING_PX,
        center = center,
        style = Stroke(width = INNER_STROKE_PX),
    )
}

private const val OUTER_STROKE_PX = 3f
private const val INNER_STROKE_PX = 1f
private const val INK_STROKE_PX = 2f
private const val INK_INSET_PX = 5f
private const val CROSSHAIR_PX = 3f
private const val DASH_ON_PX = 6f
private const val DASH_OFF_PX = 4f
private const val PIPETTE_RING_PX = 6f
