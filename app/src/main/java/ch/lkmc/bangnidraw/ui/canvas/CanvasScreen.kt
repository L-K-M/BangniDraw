package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.FitTransform
import ch.lkmc.bangnidraw.engine.core.ViewTransform

/**
 * The Canvas: where one painting is painted (PLAN.md §5).
 *
 * Scaffold (roadmap step 1): no engine yet. What is real already is the
 * navigation — two fingers pan, zoom and rotate a sheet of paper through
 * the same [ViewTransform] the GL renderer will consume in step 2, and
 * the reset-view pill springs it back. The paper is a Compose drawing so
 * the gesture math is exercised on a device before the SurfaceView
 * replaces it.
 */
@Composable
fun CanvasScreen(onBack: () -> Unit) {
    var view by remember { mutableStateOf(ViewTransform()) }
    val paper = Color(0xFFFFFFFF)
    val paperEdge = MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotationDegrees ->
                    view = view.gesture(
                        centroidX = centroid.x,
                        centroidY = centroid.y,
                        panX = pan.x,
                        panY = pan.y,
                        zoom = zoom,
                        rotationDelta = Math.toRadians(rotationDegrees.toDouble()).toFloat(),
                    )
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // A placeholder sheet at 3:4, fitted into the view with margins;
            // the real canvas is fitted the same way (FitTransform).
            val fit = FitTransform(
                viewWidth = size.width,
                viewHeight = size.height,
                imageWidth = 1500f,
                imageHeight = 2000f,
            )
            val margin = 0.85f
            val w = fit.fittedWidth * margin
            val h = fit.fittedHeight * margin
            val left = (size.width - w) / 2f
            val top = (size.height - h) / 2f
            val corners = listOf(
                view.apply(left, top),
                view.apply(left + w, top),
                view.apply(left + w, top + h),
                view.apply(left, top + h),
            )
            val path = Path().apply {
                moveTo(corners[0].first, corners[0].second)
                for (i in 1 until corners.size) lineTo(corners[i].first, corners[i].second)
                close()
            }
            drawPath(path, paper)
            drawPath(path, paperEdge, style = Stroke(width = 2.dp.toPx()))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.canvas_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.canvas_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            )
            if (!view.isIdentity) {
                FilledTonalButton(
                    onClick = { view = ViewTransform() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Text(stringResource(R.string.canvas_reset_view))
                }
            }
        }
    }
}
