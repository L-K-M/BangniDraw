package ch.lkmc.bangnidraw.ui.canvas

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

internal enum class PanelVisibility { HIDDEN, VISIBLE }

/** Positions identical panel content as a compact sheet, side sheet, or card. */
@Composable
internal fun BoxScope.PanelHost(
    layout: LayoutSpec,
    windowWidth: Dp,
    visibility: PanelVisibility,
    announcement: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val onRight = layout.panelSide == Hand.RIGHT
    val alignment = when {
        onRight -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    val panelWidth = when (layout.panelMode) {
        PanelMode.FULL_HEIGHT_SHEET -> minOf(PANEL_MAX_WIDTH, windowWidth * COMPACT_WIDTH_FRACTION)
        PanelMode.SIDE_SHEET -> PANEL_SIDE_WIDTH
        PanelMode.FLOATING -> PANEL_MAX_WIDTH
    }
    val railGap = if (layout.panelMode == PanelMode.FLOATING) {
        (layout.railWidthDp + FLOATING_GAP_DP).dp
    } else {
        0.dp
    }
    val sidePadding = if (onRight) {
        Modifier.padding(end = railGap)
    } else {
        Modifier.padding(start = railGap)
    }
    val height = if (layout.panelMode == PanelMode.FLOATING) {
        Modifier.fillMaxHeight(FLOATING_HEIGHT_FRACTION)
    } else {
        Modifier
            .fillMaxHeight()
            .padding(top = TOP_STRIP_HEIGHT)
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
            .then(sidePadding)
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
private val TOP_STRIP_HEIGHT = 48.dp
private const val FLOATING_GAP_DP = 8
private const val PANEL_ANIMATION_MS = 220
private const val COMPACT_WIDTH_FRACTION = 0.85f
private const val FLOATING_HEIGHT_FRACTION = 0.9f
