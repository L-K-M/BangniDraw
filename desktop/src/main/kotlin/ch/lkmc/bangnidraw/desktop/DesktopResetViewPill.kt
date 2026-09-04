package ch.lkmc.bangnidraw.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.ResetViewPolicy
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import kotlin.math.roundToInt

/**
 * The way back to fit — `:app`'s `ResetViewPill`, minus the platform pieces
 * (its animator check and its string resources).
 *
 * It appears only once the view has actually been moved
 * ([ResetViewPolicy.isDisplaced]) and after a beat, so it does not flash on
 * every wheel notch of a zoom the user is still adjusting.
 */
@Composable
internal fun DesktopResetViewPill(
    view: ViewTransform,
    density: Float,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displaced = ResetViewPolicy.isDisplaced(view, density)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(displaced, view) {
        visible = false
        if (!displaced) return@LaunchedEffect

        kotlinx.coroutines.delay(APPEAR_DELAY_MS)
        visible = true
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 3.dp,
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraLarge)
                .clickable(role = Role.Button, onClick = onReset),
        ) {
            Text(
                text = label(view),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The reading the pill carries: what the view is *at*, so the button says
 * what it is undoing rather than only offering to undo it.
 */
private fun label(view: ViewTransform): String {
    val reset = DesktopStrings.get("canvas_reset_view")
    val zoom = (view.scale * PERCENT).roundToInt()
    val degrees = Math.toDegrees(view.rotation.toDouble()).roundToInt()
    return if (degrees == 0) {
        DesktopStrings.get("desktop_reset_view_zoom", reset, zoom)
    } else {
        DesktopStrings.get("desktop_reset_view_zoom_angle", reset, zoom, degrees)
    }
}

private const val PERCENT = 100f
private const val APPEAR_DELAY_MS = 400L
