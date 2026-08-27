package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Repo-owned silhouettes for tools that Material Icons does not name clearly. */
internal object ToolGlyphs {

    private val ICON_SIZE = 24.dp
    private const val ICON_VIEWPORT = 24f

    val Marker: ImageVector = ImageVector.Builder(
        name = "ToolMarker",
        defaultWidth = ICON_SIZE,
        defaultHeight = ICON_SIZE,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    ).apply {
        // Separate cap and broad barrel make this read as a marker, not a pen.
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 2f)
            lineTo(17f, 2f)
            lineTo(17f, 6f)
            lineTo(7f, 6f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 7.5f)
            lineTo(17f, 7.5f)
            lineTo(17f, 13f)
            lineTo(14f, 18f)
            lineTo(10f, 18f)
            lineTo(7f, 13f)
            close()
        }
        // The flat sample stroke identifies the chisel tip at rail size.
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 20f)
            lineTo(20f, 20f)
            lineTo(20f, 23f)
            lineTo(4f, 23f)
            close()
        }
    }.build()

    val Eraser: ImageVector = ImageVector.Builder(
        name = "ToolEraser",
        defaultWidth = ICON_SIZE,
        defaultHeight = ICON_SIZE,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    ).apply {
        // Two separated materials form the seam of a tilted block eraser.
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 10f)
            lineTo(8f, 5f)
            lineTo(17f, 14f)
            lineTo(12f, 19f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(9f, 4f)
            lineTo(11f, 2f)
            lineTo(20f, 11f)
            lineTo(18f, 13f)
            close()
        }
        // A broken baseline shows the local removal action, not delete-all.
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 21f)
            lineTo(7f, 21f)
            lineTo(7f, 23f)
            lineTo(3f, 23f)
            close()
            moveTo(17f, 21f)
            lineTo(21f, 21f)
            lineTo(21f, 23f)
            lineTo(17f, 23f)
            close()
        }
    }.build()
}
