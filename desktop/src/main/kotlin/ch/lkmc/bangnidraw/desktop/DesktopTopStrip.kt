@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Layers
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import java.util.Locale

/**
 * The Canvas's persistent actions, laid out as `:app`'s `TopStrip` does:
 * the colour and overflow cluster on the rail's side, the history cluster
 * opposite it (`docs/plan/08-ui-and-layout.md` §3.1) — so the controls that
 * pair with the rail sit next to it, and Back/Undo stay under the other
 * hand.
 *
 * Android's Back button is absent: this shell has no Studio to go back to.
 * Layers is here, with the same count badge — it opens the layer panel, which
 * on desktop is a window of its own rather than a sheet over the canvas.
 */
@Composable
internal fun DesktopTopStrip(
    layout: LayoutSpec,
    canUndo: Boolean,
    canRedo: Boolean,
    layerCount: Int,
    layerPanelOpen: Boolean,
    brushColor: Int,
    colorPanelOpen: Boolean,
    savedMessage: String?,
    guidesVisible: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onLayers: () -> Unit,
    onColor: () -> Unit,
    onSave: () -> Unit,
    onAbout: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onTracingReference: () -> Unit,
    onToggleGuides: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigation: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.Start) {
            HistoryButton(DesktopStrings.get("canvas_undo"), Icons.AutoMirrored.Filled.Undo, canUndo, onUndo)
            HistoryButton(DesktopStrings.get("canvas_redo"), Icons.AutoMirrored.Filled.Redo, canRedo, onRedo)
        }
    }
    val tools: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.End) {
            LayersButton(layerCount, layerPanelOpen, onLayers)
            ColorButton(brushColor, colorPanelOpen, onColor)
            OverflowMenu(
                guidesVisible,
                onSave,
                onToggleGuides,
                onFocus,
                onSettings,
                onTracingReference,
                onAbout,
                onHelp,
            )
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
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
                .semantics {
                    if (!enabled) stateDescription = DesktopStrings.get("cd_unavailable")
                },
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) iconColor else iconColor.copy(alpha = DISABLED_ALPHA),
            )
        }
    }
}

/**
 * The layer panel's door, with `:app`'s count badge: the active layer's
 * 1-based position, inset from the icon's corner and ringed in the strip's
 * own colour so a two-digit count reads as a badge instead of growing over
 * the glyph.
 */
@Composable
private fun LayersButton(activeLayer: Int, open: Boolean, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(DesktopStrings.get("layers_title")) } },
        state = rememberTooltipState(),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(ICON_BUTTON).semantics {
                    role = Role.Button
                    selected = open
                    contentDescription = DesktopStrings.get("layers_title")
                },
            ) {
                Icon(
                    Icons.Filled.Layers,
                    contentDescription = null,
                    tint = if (open) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(BADGE_RADIUS),
                modifier = Modifier
                    .padding(BADGE_INSET)
                    .border(
                        width = BADGE_RING,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(BADGE_RADIUS),
                    ),
            ) {
                Text(
                    text = activeLayer.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    // The button already names the panel; the badge would
                    // otherwise be read out as a bare number after it.
                    modifier = Modifier
                        .padding(horizontal = BADGE_TEXT_PADDING)
                        .clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun ColorButton(brushColor: Int, open: Boolean, onClick: () -> Unit) {
    // Locale.ROOT for the hex itself: a locale with its own digits would
    // shape the code. The sentence around it is the user's language.
    val description = DesktopStrings.get(
        "cd_color",
        "#%06X".format(Locale.ROOT, brushColor and RGB_MASK),
    )
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
            // The fill is a rounded background rather than a square rect
            // drawn inside the border: modifier draws land under the content,
            // so a square fill covers the rounded outline at each corner.
            Box(
                modifier = Modifier
                    .size(COLOR_SWATCH)
                    .background(Color(brushColor), RoundedCornerShape(COLOR_RADIUS))
                    .border(
                        width = 1.dp,
                        color = if (open) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(COLOR_RADIUS),
                    ),
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    guidesVisible: Boolean,
    onSave: () -> Unit,
    onToggleGuides: () -> Unit,
    onFocus: () -> Unit,
    onSettings: () -> Unit,
    onTracingReference: () -> Unit,
    onAbout: () -> Unit,
    onHelp: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(ICON_BUTTON)) {
            Icon(Icons.Filled.MoreVert, contentDescription = DesktopStrings.get("canvas_more"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            OverflowItem(DesktopStrings.get("desktop_save"), onSave) { open = false }
            OverflowItem(
                DesktopStrings.get(
                    if (guidesVisible) "desktop_hide_guides" else "desktop_show_guides",
                ),
                onToggleGuides,
            ) { open = false }
            OverflowItem(DesktopStrings.get("canvas_focus"), onFocus) { open = false }
            // Where `:app` puts it, and with its rule: no image yet means
            // pick one, an image already placed means open its panel.
            OverflowItem(
                DesktopStrings.get("reference_image"),
                onTracingReference,
            ) { open = false }
            OverflowItem(DesktopStrings.get("canvas_settings"), onSettings) { open = false }
            OverflowItem(
                DesktopStrings.get("desktop_about", DesktopBrand.displayName),
                onAbout,
            ) { open = false }
            OverflowItem(DesktopStrings.get("desktop_menu_help"), onHelp) { open = false }
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
private val BADGE_RADIUS = 6.dp
private val BADGE_INSET = 6.dp
private val BADGE_RING = 1.dp
private val BADGE_TEXT_PADDING = 3.dp
