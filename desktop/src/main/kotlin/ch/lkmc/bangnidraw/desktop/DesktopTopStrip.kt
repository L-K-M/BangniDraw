@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.LayoutSpec

/**
 * The Canvas's persistent actions, laid out as `:app`'s `TopStrip` does:
 * navigation on the rail's side, the colour and the overflow opposite it
 * (`docs/plan/08-ui-and-layout.md` §3.1).
 *
 * Android's Back and Layers buttons are absent: this shell opens straight
 * into one painting with one layer, so neither would do anything.
 */
@Composable
internal fun DesktopTopStrip(
    layout: LayoutSpec,
    canUndo: Boolean,
    canRedo: Boolean,
    brushColor: Int,
    colorPanelOpen: Boolean,
    savedMessage: String?,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onColor: () -> Unit,
    onSave: () -> Unit,
    onAbout: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigation: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.Start) {
            HistoryButton("Undo", Icons.AutoMirrored.Filled.Undo, canUndo, onUndo)
            HistoryButton("Redo", Icons.AutoMirrored.Filled.Redo, canRedo, onRedo)
        }
    }
    val tools: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.End) {
            ColorButton(brushColor, colorPanelOpen, onColor)
            OverflowMenu(onSave, onAbout, onHelp)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth().height(LayoutSpec.TOP_STRIP_DP.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (layout.railSide == Hand.RIGHT) navigation() else tools()
            Spacer(Modifier.width(STRIP_GAP))
            // The save path is the one transient message this shell has, and
            // the strip is the only chrome always on screen to carry it.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (savedMessage != null) {
                    Text(
                        savedMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(STRIP_GAP))
            if (layout.railSide == Hand.RIGHT) tools() else navigation()
        }
    }
}

@Composable
private fun HistoryButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val iconColor = LocalContentColor.current
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(ICON_BUTTON)
                // Android states why a dead tap is dead rather than dropping
                // the node; a disabled IconButton already carries that, so
                // only the reason is added here.
                .semantics { if (!enabled) stateDescription = UNAVAILABLE_STATE },
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) iconColor else iconColor.copy(alpha = DISABLED_ALPHA),
            )
        }
    }
}

@Composable
private fun ColorButton(brushColor: Int, open: Boolean, onClick: () -> Unit) {
    val description = "Colour #%06X".format(brushColor and RGB_MASK)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(ICON_BUTTON).semantics {
                role = Role.Button
                selected = open
                contentDescription = description
            },
        ) {
            Box(
                modifier = Modifier
                    .size(COLOR_SWATCH)
                    .border(
                        width = 1.dp,
                        color = if (open) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(COLOR_RADIUS),
                    ),
            ) {
                Canvas(Modifier.size(COLOR_SWATCH)) { drawRect(Color(brushColor)) }
            }
        }
    }
}

@Composable
private fun OverflowMenu(onSave: () -> Unit, onAbout: () -> Unit, onHelp: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(ICON_BUTTON)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            OverflowItem("Save PNG", onSave) { open = false }
            OverflowItem("About " + DesktopBrand.displayName, onAbout) { open = false }
            OverflowItem("Help", onHelp) { open = false }
        }
    }
}

@Composable
private fun OverflowItem(label: String, action: () -> Unit, dismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            dismiss()
            action()
        },
    )
}

private val STRIP_GAP = 8.dp
private val ICON_BUTTON = 48.dp
private val COLOR_SWATCH = 24.dp
private val COLOR_RADIUS = 6.dp
private const val DISABLED_ALPHA = 0.38f
private const val RGB_MASK = 0xFFFFFF
private const val UNAVAILABLE_STATE = "Unavailable"
