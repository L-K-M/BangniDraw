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
        PanelMode.FULL_HEIGHT_SHEET -> minOf(
            LayoutSpec.PANEL_MAX_WIDTH_DP.dp,
            windowWidth * LayoutSpec.PANEL_COMPACT_WIDTH_FRACTION,
        )
        PanelMode.SIDE_SHEET -> LayoutSpec.PANEL_SIDE_WIDTH_DP.dp
        PanelMode.FLOATING -> LayoutSpec.PANEL_MAX_WIDTH_DP.dp
    }
    val railGap = layout.panelSideInsetDp().dp
    val sidePadding = if (onRight) {
        Modifier.padding(end = railGap)
    } else {
        Modifier.padding(start = railGap)
    }
    val height = if (layout.panelMode == PanelMode.FLOATING) {
        Modifier.fillMaxHeight(LayoutSpec.PANEL_FLOATING_HEIGHT_FRACTION)
    } else {
        Modifier
            .fillMaxHeight()
            .padding(
                top = LayoutSpec.TOP_STRIP_DP.dp,
                bottom = layout.panelBottomInsetDp().dp,
            )
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

private const val PANEL_ANIMATION_MS = 220
