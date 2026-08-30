package ch.lkmc.bangnidraw.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The 2×2 quadrant checker that stands for "see-through" wherever a swatch is
 * too small for the canvas's fine checkerboard — a solid fill there reads as
 * gray paper, which is exactly the misunderstanding transparent paper keeps
 * causing (ANALYSIS U9). Four large cells stay legible at any swatch size;
 * an 8 px checker at 28 dp reads as noise, which is why the earlier "∅ on
 * surfaceVariant" stand-in existed at all.
 *
 * Callers pass the same two roles the layer thumbnail's checker uses, so
 * every transparency cue in the app shares one visual language.
 */
fun DrawScope.drawQuadrantChecker(colorA: Color, colorB: Color) {
    val cellWidth = size.width / 2f
    val cellHeight = size.height / 2f
    val cell = Size(cellWidth, cellHeight)
    drawRect(colorA)
    drawRect(colorB, topLeft = Offset(cellWidth, 0f), size = cell)
    drawRect(colorB, topLeft = Offset(0f, cellHeight), size = cell)
}
