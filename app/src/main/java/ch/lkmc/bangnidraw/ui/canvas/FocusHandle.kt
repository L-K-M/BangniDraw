package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.Hand

/** A 6 dp visual pill inside the accessibility-safe 48 dp focus target. */
@Composable
internal fun FocusHandle(
    side: Hand,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.canvas_show_controls)
    val threshold = with(LocalDensity.current) { DRAG_THRESHOLD.toPx() }
    var drag = 0f
    var opened = false
    Box(
        contentAlignment = if (side == Hand.RIGHT) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = modifier
            .size(HANDLE_TARGET)
            .semantics { contentDescription = description }
            .pointerInput(side, threshold) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        drag = 0f
                        opened = false
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        drag += amount
                        val inward = if (side == Hand.RIGHT) -drag else drag
                        if (inward < threshold || opened) return@detectHorizontalDragGestures

                        opened = true
                        onClick()
                    },
                )
            }
            .clickable(onClick = onClick),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = HANDLE_ALPHA),
            shape = RoundedCornerShape(HANDLE_RADIUS),
            modifier = Modifier.size(width = HANDLE_WIDTH, height = HANDLE_HEIGHT),
        ) {}
    }
}

private val HANDLE_TARGET = 48.dp
private val HANDLE_WIDTH = 6.dp
private val HANDLE_HEIGHT = 48.dp
private val HANDLE_RADIUS = 3.dp
private val DRAG_THRESHOLD = 24.dp
private const val HANDLE_ALPHA = 0.35f
