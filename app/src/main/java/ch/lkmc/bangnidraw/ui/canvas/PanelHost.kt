package ch.lkmc.bangnidraw.ui.canvas

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.PanelMode
import kotlin.math.roundToInt

internal enum class PanelVisibility { HIDDEN, VISIBLE }

/** Positions identical panel content as a compact sheet, side sheet, or card. */
@Composable
internal fun BoxScope.PanelHost(
    layout: LayoutSpec,
    windowWidth: Dp,
    windowHeight: Dp,
    visibility: PanelVisibility,
    announcement: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val onRight = layout.panelSide == Hand.RIGHT
    val alignment = when {
        onRight -> AbsoluteAlignment.CenterRight
        else -> AbsoluteAlignment.CenterLeft
    }
    val panelWidth = when (layout.panelMode) {
        PanelMode.FULL_HEIGHT_SHEET -> minOf(PANEL_MAX_WIDTH, windowWidth * COMPACT_WIDTH_FRACTION)
        PanelMode.SIDE_SHEET -> PANEL_SIDE_WIDTH
        PanelMode.FLOATING -> PANEL_MAX_WIDTH
    }
    val insets = layout.panelInsets(
        windowWidthDp = windowWidth.value.roundToInt(),
        windowHeightDp = windowHeight.value.roundToInt(),
    )
    val chromePadding = Modifier.absolutePadding(
        left = insets.leftDp.dp,
        top = insets.topDp.dp,
        right = insets.rightDp.dp,
        bottom = insets.bottomDp.dp,
    )
    val height = if (layout.panelMode == PanelMode.FLOATING) {
        Modifier.fillMaxHeight(FLOATING_HEIGHT_FRACTION)
    } else {
        Modifier.fillMaxHeight()
    }
    val direction = if (onRight) 1 else -1
    val animationMs = if (ValueAnimator.areAnimatorsEnabled()) PANEL_ANIMATION_MS else 0

    AnimatedVisibility(
        visible = visibility == PanelVisibility.VISIBLE,
        enter = slideInHorizontally(
            animationSpec = tween(animationMs),
            initialOffsetX = { direction * it },
        ) + fadeIn(tween(animationMs)),
        exit = slideOutHorizontally(
            animationSpec = tween(animationMs),
            targetOffsetX = { direction * it },
        ) + fadeOut(tween(animationMs)),
        modifier = modifier
            .align(alignment)
            .then(chromePadding)
            .then(height)
            .width(panelWidth)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                paneTitle = announcement
            },
    ) {
        content()
    }
}

private val PANEL_SIDE_WIDTH = 300.dp
private val PANEL_MAX_WIDTH = 320.dp
private const val PANEL_ANIMATION_MS = 220
private const val COMPACT_WIDTH_FRACTION = 0.85f
private const val FLOATING_HEIGHT_FRACTION = 0.9f
