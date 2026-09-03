@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.AppTheme
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushSizeScale
import ch.lkmc.bangnidraw.engine.core.BrushToolGlyphPolicy
import ch.lkmc.bangnidraw.engine.core.EraserTogglePolicy
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.PaintSlotAssignments
import ch.lkmc.bangnidraw.engine.core.RailMode
import ch.lkmc.bangnidraw.engine.core.RailSlotPolicy
import ch.lkmc.bangnidraw.engine.core.ToolButtonEmphasis
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ToolRailColorPolicy
import ch.lkmc.bangnidraw.engine.core.ToolSliderPreset
import ch.lkmc.bangnidraw.engine.core.ToolSliderSecondary
import ch.lkmc.bangnidraw.ui.glyphs.brushGlyphIcon
import kotlin.math.roundToInt

/**
 * The desktop tool rail: the same object `:app`'s `ToolRail` draws — paint
 * slots, a rule, the eraser, and the size/secondary sliders standing in its
 * foot (`docs/plan/08-ui-and-layout.md` §3.2). Assignments, the visible
 * budget and the button emphasis colours come from shared engine-core
 * policies, and the surface/divider colours read the same Material roles
 * `:app`'s rail does, so the two behave alike.
 *
 * The structural difference is what this shell cannot run: Android's five
 * secondary tools (smudge, water, blur, fill, eyedropper) have no desktop
 * engine path, and the settings sheet its overflowing presets hide in does
 * not exist here — so the paints that do not fit go into a menu on the rail
 * itself rather than off it.
 */
@Composable
internal fun DesktopToolRail(
    layout: LayoutSpec,
    presets: List<BrushPreset>,
    paintSlots: PaintSlotAssignments,
    rail: DesktopRailState,
    windowWidth: Dp,
    windowHeight: Dp,
    onPaintSlot: (Int) -> Unit,
    onAssignPaint: (BrushPreset) -> Unit,
    onEraserTap: () -> Unit,
    onSizeChanged: (Float) -> Unit,
    onSecondaryChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val docked = layout.railMode == RailMode.DOCK
    // A dock runs along the window's width; every other rail runs down what
    // the top strip leaves of its height.
    val railExtent = if (docked) windowWidth else windowHeight - LayoutSpec.TOP_STRIP_DP.dp
    val paintsById = DesktopRailPolicy.paints(presets).associateBy(BrushPreset::id)
    val budget = DesktopRailPolicy.paintBudget(
        layout = layout,
        availableDp = railExtent.value.roundToInt(),
        // The rail lays out the assignments, so they are what must fit.
        paintCount = paintSlots.presetIds.size,
    )
    val visible = RailSlotPolicy.visibleIndices(paintSlots, budget)
    val hidden = paintSlots.presetIds
        .filterIndexed { index, _ -> index !in visible }
        .mapNotNull(paintsById::get)
    // The slot the last eraser toggle left in place, never a preset this
    // catalogue does not ship.
    val eraser = presets.firstOrNull { it.id == rail.eraserId && it.eraseMode }
        ?: DesktopRailPolicy.eraserOrNull(presets)
    // Derived, never passed in: the sliders must tune the preset the column
    // is highlighting, and two parameters could disagree about which that is.
    // The ledge derives it from the same function, for the same reason.
    val active = DesktopRailPolicy.activePreset(presets, rail)

    val paintSlotButtons = buildList {
        for (index in visible) {
            val paint = paintsById[paintSlots.presetIds[index]] ?: continue
            add(
                DesktopToolSlot(
                    icon = brushGlyphIcon(BrushToolGlyphPolicy.forPreset(paint)),
                    description = DesktopBrushUi.label(paint),
                    active = rail.selectedId == paint.id,
                    onClick = { onPaintSlot(index) },
                ),
            )
        }
        if (hidden.isNotEmpty()) {
            add(
                DesktopToolSlot(
                    icon = Icons.Filled.MoreHoriz,
                    description = MORE_BRUSHES,
                    active = hidden.any { it.id == rail.selectedId },
                    onClick = {},
                    menuItems = hidden.map { paint ->
                        DesktopToolMenuItem(
                            icon = brushGlyphIcon(BrushToolGlyphPolicy.forPreset(paint)),
                            description = DesktopBrushUi.label(paint),
                            active = rail.selectedId == paint.id,
                            onClick = { onAssignPaint(paint) },
                        )
                    },
                ),
            )
        }
    }
    // Mirrors ToolRail.dividersAfter: the paints and their overflow are one
    // group, the eraser its own. A catalogue missing either must not put a
    // rule through the middle of what is left.
    val dividerAfter = if (paintSlotButtons.isNotEmpty() && eraser != null) {
        paintSlotButtons.lastIndex
    } else {
        -1
    }
    val tools = if (eraser == null) {
        paintSlotButtons
    } else {
        val alternates = EraserTogglePolicy.next(eraser.id, presets) != null
        paintSlotButtons + DesktopToolSlot(
            icon = brushGlyphIcon(BrushToolGlyphPolicy.forPreset(eraser)),
            description = DesktopBrushUi.label(eraser) + if (alternates) ERASER_TOGGLE_HINT else "",
            active = rail.selectedId == eraser.id,
            onClick = onEraserTap,
        )
    }
    val slot = layout.toolSlotDp.dp

    if (docked) {
        // A dock runs along the bottom: a tooltip to a button's left would
        // cover its neighbours, and clip off-screen at the window's edge.
        Dock(tools, dividerAfter, slot, modifier)
        return
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = RAIL_ALPHA),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        modifier = modifier.width(layout.railWidthDp.dp).heightIn(max = railExtent),
    ) {
        val verticalPadding = if (layout.railMode == RailMode.SHORT) 0.dp else RAIL_PADDING
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(
                horizontal = RAIL_HORIZONTAL_PADDING,
                vertical = verticalPadding,
            ),
        ) {
            val gap = if (layout.railMode == RailMode.SHORT) 0.dp else TOOL_GAP
            // The budget above is what keeps the column inside the window;
            // this scroll is the guard for the sizes it cannot foresee (a
            // catalogue grown past the fit, a window shorter than one slot).
            // It wraps the tools alone, so the tuning a stroke needs never
            // scrolls out of reach with them.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .semantics { selectableGroup() },
            ) {
                for ((index, item) in tools.withIndex()) {
                    ToolButton(item, slot)
                    if (index == tools.lastIndex) continue

                    if (gap > 0.dp) Spacer(Modifier.height(gap))
                    if (index == dividerAfter) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = DIVIDER_MARGIN),
                        )
                    }
                }
            }

            if (layout.sliderLengthDp > 0 && active != null) {
                Row {
                    ToolSliders(
                        preset = active,
                        axis = DesktopSliderAxis.Vertical,
                        length = layout.sliderLengthDp.dp,
                        onSizeChanged = onSizeChanged,
                        onSecondaryChanged = onSecondaryChanged,
                    )
                }
            }
        }
    }
}

/** SHORT and DOCK rails cannot hold the sliders; they get a ledge instead. */
@Composable
internal fun DesktopSliderLedge(
    layout: LayoutSpec,
    preset: BrushPreset,
    onSizeChanged: (Float) -> Unit,
    onSecondaryChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (layout.sliderLengthDp > 0) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = LEDGE_ALPHA),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.height(LEDGE_HEIGHT),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LEDGE_PADDING),
            modifier = Modifier.padding(horizontal = LEDGE_PADDING),
        ) {
            ToolSliders(
                preset = preset,
                axis = DesktopSliderAxis.Horizontal,
                length = LEDGE_SLIDER_LENGTH,
                onSizeChanged = onSizeChanged,
                onSecondaryChanged = onSecondaryChanged,
            )
        }
    }
}

/**
 * The size and secondary pair, in the rail's foot or on the ledge. One
 * implementation for both: the mapping between the slider's 0..1 and the
 * preset's own range is the part that must not drift between the two
 * surfaces, and it lives here once.
 */
@Composable
private fun ToolSliders(
    preset: BrushPreset,
    axis: DesktopSliderAxis,
    length: Dp,
    onSizeChanged: (Float) -> Unit,
    onSecondaryChanged: (Float) -> Unit,
) {
    // Identity with the preset's own bounds — `DesktopBrushUiTest` pins that
    // for every shipped preset, so routing both surfaces through it is
    // deduplication, not a change to where the slider's 0..1 lands.
    val range = DesktopBrushUi.sizeRange(preset)
    DesktopThinSlider(
        value = BrushSizeScale.fraction(preset.size, range.start, range.endInclusive),
        range = 0f..1f,
        axis = axis,
        description = sizeDescription(preset),
        onValueChange = {
            onSizeChanged(BrushSizeScale.size(it, range.start, range.endInclusive))
        },
        length = length,
    )
    DesktopThinSlider(
        value = secondaryValue(preset),
        range = 0f..1f,
        axis = axis,
        description = secondaryDescription(preset),
        onValueChange = onSecondaryChanged,
        length = length,
    )
}

@Composable
private fun Dock(
    tools: List<DesktopToolSlot>,
    dividerAfter: Int,
    slot: Dp,
    modifier: Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = RAIL_ALPHA),
        // Top corners only, exactly as the Android dock: rounding just the
        // edge that meets the canvas keeps it flush with the window bottom.
        shape = MaterialTheme.shapes.large.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        ),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth().height(DOCK_HEIGHT),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .semantics { selectableGroup() },
        ) {
            for ((index, item) in tools.withIndex()) {
                ToolButton(item, slot, tooltipAnchor = TooltipAnchorPosition.Above)
                if (index == dividerAfter) {
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .height(slot - DIVIDER_INSET)
                            .padding(horizontal = DIVIDER_MARGIN),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    item: DesktopToolSlot,
    slot: Dp,
    tooltipAnchor: TooltipAnchorPosition = TooltipAnchorPosition.Left,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    val emphasis = if (item.active) ToolButtonEmphasis.ACTIVE else ToolButtonEmphasis.INACTIVE
    val colors = ToolRailColorPolicy.colors(DESKTOP_THEME, emphasis)
    val container = Color(colors.containerArgb)
    val iconColor = Color(colors.iconArgb)
    val visual = minOf(TOOL_VISUAL, slot - TOOL_VISUAL_INSET)
    val border = if (item.active) {
        Modifier.border(ACTIVE_BORDER, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }
    Box(modifier = Modifier.size(slot), contentAlignment = Alignment.Center) {
        Surface(color = container, shape = shape, modifier = border.size(visual)) {}
        // The rail replaced a column of labelled buttons, so every glyph
        // still has to say its own name somewhere a pointer can find it.
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(tooltipAnchor),
            tooltip = { PlainTooltip { Text(item.description) } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                onClick = {
                    if (item.menuItems.isEmpty()) item.onClick() else menuExpanded = true
                },
                colors = IconButtonDefaults.iconButtonColors(contentColor = iconColor),
                modifier = Modifier
                    .size(slot)
                    .semantics {
                        role = Role.Button
                        selected = item.active
                        if (item.active) stateDescription = SELECTED_STATE
                    },
            ) {
                Icon(item.icon, contentDescription = item.description, tint = iconColor)
            }
        }
        if (item.menuItems.isNotEmpty()) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.semantics { selectableGroup() },
            ) {
                for (entry in item.menuItems) {
                    DropdownMenuItem(
                        text = { Text(entry.description) },
                        onClick = {
                            menuExpanded = false
                            entry.onClick()
                        },
                        leadingIcon = { Icon(entry.icon, contentDescription = null) },
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            selected = entry.active
                        },
                    )
                }
            }
        }
    }
}

/**
 * Never actually null for a brush — `ToolSliderPreset.secondaryValue` only
 * returns null for Fill and Eyedropper, which this shell has no path for —
 * but the type is nullable, and opacity is the right reading of the
 * OPACITY secondary this rail's presets all use.
 */
private fun secondaryValue(preset: BrushPreset): Float =
    ToolSliderPreset.secondaryValue(ToolKind.Brush(preset)) ?: preset.opacity

private fun sizeDescription(preset: BrushPreset): String {
    val range = DesktopBrushUi.sizeRange(preset)
    return "Brush size ${preset.size.toInt()} " +
        "(${range.start.toInt()}–${range.endInclusive.toInt()})"
}

private fun secondaryDescription(preset: BrushPreset): String =
    when (ToolSliderPreset.secondaryFor(ToolKind.Brush(preset))) {
        ToolSliderSecondary.FLOW -> "Flow"
        ToolSliderSecondary.WATER -> "Water"
        ToolSliderSecondary.OPACITY -> "Opacity"
    }

private data class DesktopToolSlot(
    val icon: ImageVector,
    val description: String,
    val active: Boolean,
    val onClick: () -> Unit,
    val menuItems: List<DesktopToolMenuItem> = emptyList(),
)

private data class DesktopToolMenuItem(
    val icon: ImageVector,
    val description: String,
    val active: Boolean,
    val onClick: () -> Unit,
)

/** The shell wears the Android app's default palette; see [AppTheme]. */
internal val DESKTOP_THEME = AppTheme.SAFFRON

private const val SELECTED_STATE = "Selected"
private const val MORE_BRUSHES = "More brushes"
private const val ERASER_TOGGLE_HINT = " — click again for the other eraser"
private val TOOL_VISUAL = 40.dp
private val TOOL_VISUAL_INSET = 8.dp
private val TOOL_GAP = DesktopRailGeometry.TOOL_GAP_DP.dp
// Half of the budget's padding allowance, which counts both ends.
private val RAIL_PADDING = (DesktopRailGeometry.RAIL_PADDING_DP / 2).dp
private val RAIL_HORIZONTAL_PADDING = 4.dp
private val DOCK_HEIGHT = LayoutSpec.DOCK_HEIGHT_DP.dp
private val ACTIVE_BORDER = 2.dp
private val DIVIDER_MARGIN = 4.dp
private val DIVIDER_INSET = 16.dp
private val LEDGE_HEIGHT = 48.dp
private val LEDGE_PADDING = 8.dp
private val LEDGE_SLIDER_LENGTH = 140.dp
private const val RAIL_ALPHA = 0.92f
private const val LEDGE_ALPHA = 0.94f
