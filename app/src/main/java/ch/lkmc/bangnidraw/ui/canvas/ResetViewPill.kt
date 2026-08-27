package ch.lkmc.bangnidraw.ui.canvas

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.ResetViewPolicy
import ch.lkmc.bangnidraw.engine.core.StrokeActivity
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Delayed reset action with the current relative zoom and angle. */
@Composable
internal fun ResetViewPill(
    view: ViewTransform,
    density: Float,
    strokeActivity: StrokeActivity,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displaced = ResetViewPolicy.isDisplaced(view, density)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(displaced, strokeActivity, view) {
        visible = false
        if (!displaced || strokeActivity == StrokeActivity.ACTIVE) return@LaunchedEffect

        delay(APPEAR_DELAY_MS)
        visible = true
    }

    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    AnimatedVisibility(
        visible = visible,
        enter = if (motionEnabled) fadeIn(tween(FADE_MS)) else EnterTransition.None,
        exit = if (motionEnabled) fadeOut(tween(FADE_MS)) else ExitTransition.None,
        modifier = modifier,
    ) {
        FilledTonalButton(onClick = onReset) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.canvas_reset_view))
                Text(
                    stringResource(
                        R.string.canvas_view_readout,
                        (view.scale * PERCENT).roundToInt(),
                        Math.toDegrees(view.rotation.toDouble()).roundToInt(),
                    ),
                )
            }
        }
    }
}

private const val APPEAR_DELAY_MS = 150L
private const val FADE_MS = 150
private const val PERCENT = 100f
