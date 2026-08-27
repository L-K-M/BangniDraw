package ch.lkmc.bangnidraw.ui.canvas

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.CanvasPanel
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.LayoutSpec

/** The Canvas's six persistent actions, mirrored as two handed clusters. */
@Composable
internal fun TopStrip(
    layout: LayoutSpec,
    undoAvailability: ActionAvailability,
    redoAvailability: ActionAvailability,
    activeLayer: Int,
    brushColor: Int,
    openPanel: CanvasPanel?,
    hapticsMode: HapticsMode,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onUndoLongPress: () -> Unit,
    onLayers: () -> Unit,
    onColor: () -> Unit,
    onShare: () -> Unit,
    onExportPng: () -> Unit,
    onExportJpeg: () -> Unit,
    onFocus: () -> Unit,
    onRename: () -> Unit,
    onSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val navigation: @Composable () -> Unit = {
        NavigationCluster(
            undoAvailability,
            redoAvailability,
            hapticsMode,
            onBack,
            onUndo,
            onRedo,
            onUndoLongPress,
        )
    }
    val tools: @Composable () -> Unit = {
        ToolCluster(
            activeLayer,
            brushColor,
            openPanel,
            onLayers,
            onColor,
            onShare,
            onExportPng,
            onExportJpeg,
            onFocus,
            onRename,
            onSettings,
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (layout.railSide == Hand.RIGHT) {
                navigation()
                Spacer(Modifier.weight(1f))
                tools()
            } else {
                tools()
                Spacer(Modifier.weight(1f))
                navigation()
            }
        }
    }
}

@Composable
private fun NavigationCluster(
    undoAvailability: ActionAvailability,
    redoAvailability: ActionAvailability,
    hapticsMode: HapticsMode,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onUndoLongPress: () -> Unit,
) {
    val view = LocalView.current
    val undoEnabled = undoAvailability == ActionAvailability.ENABLED
    val iconColor = MaterialTheme.colorScheme.onSurface
    Row(horizontalArrangement = Arrangement.Start) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.canvas_back),
            )
        }
        // Long-press = the §3.1 readout: how deep the undo history is and
        // how close to the cap. combinedClickable keeps tap and long-press
        // mutually exclusive — checking the budget never costs a stroke —
        // and the node stays enabled either way, so the readout also works
        // with nothing to undo (the click itself is gated below).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ICON_BUTTON)
                .combinedClickable(
                    onClick = {
                        if (!undoEnabled) return@combinedClickable
                        if (hapticsMode == HapticsMode.ENABLED) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        onUndo()
                    },
                    onLongClick = {
                        if (hapticsMode == HapticsMode.ENABLED) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        onUndoLongPress()
                    },
                ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.canvas_undo),
                tint = if (undoEnabled) iconColor else iconColor.copy(alpha = DISABLED_ALPHA),
            )
        }
        IconButton(
            enabled = redoAvailability == ActionAvailability.ENABLED,
            onClick = {
                if (hapticsMode == HapticsMode.ENABLED) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                onRedo()
            },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Redo,
                contentDescription = stringResource(R.string.canvas_redo),
            )
        }
    }
}

@Composable
private fun ToolCluster(
    activeLayer: Int,
    brushColor: Int,
    openPanel: CanvasPanel?,
    onLayers: () -> Unit,
    onColor: () -> Unit,
    onShare: () -> Unit,
    onExportPng: () -> Unit,
    onExportJpeg: () -> Unit,
    onFocus: () -> Unit,
    onRename: () -> Unit,
    onSettings: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.End) {
        Box(contentAlignment = Alignment.BottomEnd) {
            IconButton(onClick = onLayers) {
                Icon(
                    Icons.Filled.Layers,
                    contentDescription = stringResource(R.string.layers_title),
                    tint = if (openPanel == CanvasPanel.LAYERS) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(BADGE_RADIUS),
            ) {
                Text(
                    text = activeLayer.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        val colorDescription = stringResource(
            R.string.cd_color,
            String.format("#%06X", brushColor and RGB_MASK),
        )
        IconButton(
            onClick = onColor,
            modifier = Modifier.semantics { contentDescription = colorDescription },
        ) {
            Box(
                modifier = Modifier
                    .size(COLOR_SWATCH)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(COLOR_RADIUS),
                    ),
            ) {
                Canvas(Modifier.size(COLOR_SWATCH)) { drawRect(Color(brushColor)) }
            }
        }
        OverflowMenu(
            onShare,
            onExportPng,
            onExportJpeg,
            onFocus,
            onRename,
            onSettings,
        )
    }
}

@Composable
private fun OverflowMenu(
    onShare: () -> Unit,
    onExportPng: () -> Unit,
    onExportJpeg: () -> Unit,
    onFocus: () -> Unit,
    onRename: () -> Unit,
    onSettings: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.canvas_more),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            OverflowItem(R.string.studio_share, onShare) { open = false }
            OverflowItem(R.string.canvas_export_png, onExportPng) { open = false }
            OverflowItem(R.string.canvas_export_jpeg, onExportJpeg) { open = false }
            OverflowItem(R.string.canvas_focus, onFocus) { open = false }
            OverflowItem(R.string.studio_rename, onRename) { open = false }
            if (onSettings != null) {
                OverflowItem(R.string.canvas_settings, onSettings) { open = false }
            }
        }
    }
}

@Composable
private fun OverflowItem(label: Int, action: () -> Unit, dismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            dismiss()
            action()
        },
    )
}

private val STRIP_HEIGHT = 48.dp
private val ICON_BUTTON = 48.dp
private const val DISABLED_ALPHA = 0.38f
private val COLOR_SWATCH = 24.dp
private val COLOR_RADIUS = 6.dp
private val BADGE_RADIUS = 8.dp
private const val RGB_MASK = 0xFFFFFF

internal enum class ActionAvailability { ENABLED, DISABLED }
