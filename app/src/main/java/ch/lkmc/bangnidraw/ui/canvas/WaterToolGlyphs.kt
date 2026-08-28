package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The watercolor preset combines a brush silhouette with a separate drop. */
internal object WaterToolGlyphs {
    private val ICON_SIZE = 24.dp
    private const val ICON_VIEWPORT = 24f

    val Watercolor: ImageVector = ImageVector.Builder(
        name = "ToolWatercolor",
        defaultWidth = ICON_SIZE,
        defaultHeight = ICON_SIZE,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    ).apply {
        // Handle and ferrule keep the small silhouette readable as a brush.
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 18f)
            lineTo(13f, 8f)
            lineTo(17f, 12f)
            lineTo(7f, 22f)
            lineTo(3f, 22f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(14f, 7f)
            lineTo(17f, 4f)
            lineTo(20f, 7f)
            lineTo(18f, 11f)
            close()
        }
        // The detached drop distinguishes watercolor from the dry brush slot.
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 13f)
            curveTo(17f, 16f, 16f, 18f, 16f, 19f)
            curveTo(16f, 21.2f, 17.4f, 23f, 19f, 23f)
            curveTo(20.6f, 23f, 22f, 21.2f, 22f, 19f)
            curveTo(22f, 18f, 21f, 16f, 19f, 13f)
            close()
        }
    }.build()
}
